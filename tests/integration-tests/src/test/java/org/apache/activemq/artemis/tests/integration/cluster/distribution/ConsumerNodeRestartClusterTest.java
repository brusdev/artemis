/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.tests.integration.cluster.distribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.activemq.artemis.api.core.ActiveMQQueueExistsException;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.apache.activemq.artemis.core.config.ScaleDownConfiguration;
import org.apache.activemq.artemis.core.config.ha.PrimaryOnlyPolicyConfiguration;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.cluster.MessageFlowRecord;
import org.apache.activemq.artemis.core.server.cluster.RemoteQueueBinding;
import org.apache.activemq.artemis.core.server.cluster.impl.BridgeImpl;
import org.apache.activemq.artemis.core.server.cluster.impl.ClusterConnectionImpl;
import org.apache.activemq.artemis.core.server.cluster.impl.MessageLoadBalancingType;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.tests.util.Wait;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies message delivery survives consumer-node restarts in a live-only symmetric
 * cluster with ON_DEMAND load-balancing, immediate redistribution, and scale-down.
 * <p>
 * _Composer on behalf of Domenico Francesco Bruscino_
 */
public class ConsumerNodeRestartClusterTest extends ClusterTestBase {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   // Same name required: ANYCAST bindings are indexed by address, while redistribute
   // looks up by queue name when the send is not FQQN.
   private static final String ADDRESS = "testQueue";
   private static final String QUEUE = "testQueue";
   private static final String PRODUCER_NODE_PROP = "producerNode";
   private static final String MSG_ID_PROP = "msgId";

   private static final int DEFAULT_NODES = 9;
   private static final int DEFAULT_RESTARTS = 3;
   /** Cap when there are no restarts (producers stop solely on this limit). */
   private static final long MAX_MESSAGES_NO_RESTART = 5_000;
   /**
    * Safety cap when restarts are enabled. Non-persistent sends are extremely fast; the
    * producer loop also back-pressures on consume lag so this is rarely the stop reason.
    */
   private static final long MAX_MESSAGES_WITH_RESTART = 5_000_000;
   /**
    * Max outstanding (produced - received) while restarts run. Prevents non-persistent
    * floods from exhausting heap and breaking cluster topology/bridges during outages.
    */
   private static final long MAX_OUTSTANDING_DURING_RESTART = 50_000;
   /**
    * Drain after continuous-send restarts can leave a large backlog on the consumer node.
    * Keep this well under the old 2–5 minute waits, but long enough to consume ~250k+ msgs.
    */
   private static final long DRAIN_TIMEOUT_MS = 90_000;
   private static final long TOPOLOGY_TIMEOUT_MS = 30_000;
   /** Bridge/binding sync under continuous send; allow several reconnect/retry cycles. */
   private static final long BRIDGE_TIMEOUT_MS = 90_000;
   private static final long RECEIVE_TIMEOUT_MS = 200;
   private static final int LOG_EVERY = 100;

   @Override
   protected boolean isFileStorage() {
      return true;
   }

   protected boolean isNetty() {
      return true;
   }

   @Test
   public void testPersistentWithRestarts() throws Exception {
      runTest(true, DEFAULT_RESTARTS);
   }

   @Test
   public void testNonPersistentWithRestarts() throws Exception {
      runTest(false, DEFAULT_RESTARTS);
   }

   @Test
   public void testPersistentWithoutRestarts() throws Exception {
      runTest(true, 0);
   }

   @Test
   public void testNonPersistentWithoutRestarts() throws Exception {
      runTest(false, 0);
   }

   private void runTest(boolean persistent, int restarts) throws Exception {
      final int nodes = DEFAULT_NODES;
      assertTrue(nodes >= 2, "nodes must be >= 2");

      final int producerNodes = nodes - 1;
      final int consumerNode = nodes - 1;

      setupCluster(nodes);

      startAllServers(nodes);

      for (int i = 0; i < nodes; i++) {
         waitForTopology(servers[i], nodes);
      }

      for (int i = 0; i < nodes; i++) {
         setupSessionFactory(i, isNetty());
      }

      for (int i = 0; i < nodes; i++) {
         // Keep the shared queue durable even for non-persistent payloads.  Non-durable
         // queues disappear on the consumer-node stop and race cluster binding/consumer
         // notifications when the node rejoins under scale-down; durable queues match the
         // persistent case and are allowed by the test spec for non-persistent messages.
         createQueue(i, ADDRESS, QUEUE, null, true, RoutingType.ANYCAST);
      }

      final AtomicLong produced = new AtomicLong();
      final AtomicLong consumed = new AtomicLong();
      // Unique IDs: reconnect/redelivery after batched acks can deliver a message more than
      // once to the test client; broker correctness is about unique payload survival.
      final Set<Long> consumedIds = ConcurrentHashMap.newKeySet();
      final AtomicBoolean restartsCompleted = new AtomicBoolean(false);
      final AtomicBoolean producersStopped = new AtomicBoolean(false);
      final AtomicBoolean consumerRunning = new AtomicBoolean(true);
      final AtomicReference<Throwable> failure = new AtomicReference<>();

      ExecutorService executor = Executors.newFixedThreadPool(producerNodes + 1);
      runAfter(executor::shutdownNow);

      Future<?> consumerFuture = executor.submit(
         () -> runConsumer(consumerNode, produced, consumed, consumedIds, consumerRunning, failure));

      // Wait until the consumer is attached and visible cluster-wide before producers send.
      waitForBindings(consumerNode, ADDRESS, 1, 1, true);
      for (int i = 0; i < producerNodes; i++) {
         waitForBindings(i, ADDRESS, 1, 0, true);
         waitForBindings(i, ADDRESS, nodes - 1, 1, false);
      }

      final long maxMessages = restarts > 0 ? MAX_MESSAGES_WITH_RESTART : MAX_MESSAGES_NO_RESTART;

      List<Future<?>> producerFutures = new ArrayList<>(producerNodes);
      for (int i = 0; i < producerNodes; i++) {
         final int producerNode = i;
         producerFutures.add(executor.submit(
            () -> runProducer(producerNode, persistent, restarts, maxMessages, restartsCompleted,
               produced, consumed, failure)));
      }

      try {
         if (restarts > 0) {
            Wait.assertTrue(() -> produced.get() > 0, TOPOLOGY_TIMEOUT_MS);
            runRestarts(restarts, consumerNode, nodes, producerNodes, persistent, produced, consumed, failure);
            if (failure.get() != null) {
               fail(failure.get());
            }
            restartsCompleted.set(true);
         }

         for (Future<?> producerFuture : producerFutures) {
            producerFuture.get(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
         }
         producersStopped.set(true);
         logger.debug("producers stopped: produced={}", produced.get());

         if (failure.get() != null) {
            fail(failure.get());
         }

         // First wait until every produced msgId has been observed (ignores client redeliveries),
         // then until broker queues are empty (acked/settled, redistribution finished).
         if (!Wait.waitFor(() -> produced.get() > 0 && consumedIds.size() >= produced.get(), DRAIN_TIMEOUT_MS)) {
            fail("missing messages: produced=" + produced.get() +
               " uniqueConsumed=" + consumedIds.size() +
               " receiveCount=" + consumed.get() +
               " queueTotals=" + describeQueueCounts(nodes) +
               " snfTotals=" + describeSnFCounts(nodes) +
               " remoteConsumers=" + describeRemoteConsumers(producerNodes) +
               " bridges0=" + describeBridges(0));
         }
         if (!Wait.waitFor(() -> totalQueueMessages(nodes) == 0, DRAIN_TIMEOUT_MS)) {
            fail("queues not empty after all msgIds seen: produced=" + produced.get() +
               " uniqueConsumed=" + consumedIds.size() +
               " receiveCount=" + consumed.get() +
               " queueTotals=" + describeQueueCounts(nodes) +
               " snfTotals=" + describeSnFCounts(nodes) +
               " bridges0=" + describeBridges(0));
         }

         consumerRunning.set(false);
         consumerFuture.get(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } finally {
         consumerRunning.set(false);
         restartsCompleted.set(true);
         producersStopped.set(true);
         executor.shutdownNow();
      }

      if (failure.get() != null) {
         fail(failure.get());
      }

      logger.debug("drain complete: produced={} uniqueConsumed={} receiveCount={}",
         produced.get(), consumedIds.size(), consumed.get());
      assertTrue(produced.get() > 0, "expected some messages to be produced");
      assertEquals(produced.get(), consumedIds.size(),
         () -> "each produced msgId must be consumed once; receiveCount=" + consumed.get() +
            " queueTotals=" + describeQueueCounts(nodes) + " snfTotals=" + describeSnFCounts(nodes));
      assertEquals(0L, totalQueueMessages(nodes), () -> "leftover messages: " + describeQueueCounts(nodes));
   }

   private ClusterConnectionImpl clusterConnection(int node) {
      return (ClusterConnectionImpl) servers[node].getClusterManager().getClusterConnections().iterator().next();
   }

   private boolean bridgeConnected(int fromNode, String toNodeId) {
      MessageFlowRecord record = clusterConnection(fromNode).getRecords().get(toNodeId);
      return record != null && record.getBridge() != null && record.getBridge().isConnected();
   }

   private boolean topologyHasPrimary(int fromNode, String nodeId) {
      var member = clusterConnection(fromNode).getTopology().getMember(nodeId);
      return member != null && member.getPrimary() != null;
   }

   private String describeBridges(int fromNode) {
      ClusterConnectionImpl cc = clusterConnection(fromNode);
      StringBuilder sb = new StringBuilder("records={");
      cc.getRecords().forEach((id, record) -> {
         boolean connected = record.getBridge() != null && record.getBridge().isConnected();
         boolean locatorClosed = record.getBridge() instanceof BridgeImpl bridge &&
            bridge.getServerLocator() != null &&
            bridge.getServerLocator().isClosed();
         sb.append(id).append(":[connected=").append(connected)
            .append(", closed=").append(record.isClosed())
            .append(", locatorClosed=").append(locatorClosed)
            .append("] ");
      });
      sb.append("} topologyMembers={");
      for (var member : cc.getTopology().getMembers()) {
         sb.append(member.getNodeId()).append(' ');
      }
      sb.append("} localNodeId=").append(servers[fromNode].getNodeID());
      return sb.toString();
   }

   private int remoteConsumerCount(int node) throws Exception {
      var bindings = servers[node].getPostOffice().getBindingsForAddress(SimpleString.of(ADDRESS));
      int consumers = 0;
      for (var binding : bindings.getBindings()) {
         if (binding instanceof RemoteQueueBinding remote && remote.isConnected()) {
            consumers += remote.consumerCount();
         }
      }
      return consumers;
   }

   private long totalQueueMessages(int nodes) {
      long total = 0;
      for (int i = 0; i < nodes; i++) {
         Queue queue = servers[i].locateQueue(SimpleString.of(QUEUE));
         if (queue != null) {
            total += queue.getMessageCount();
         }
      }
      return total;
   }

   private String describeQueueCounts(int nodes) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < nodes; i++) {
         Queue queue = servers[i].locateQueue(SimpleString.of(QUEUE));
         if (sb.length() > 0) {
            sb.append(", ");
         }
         if (queue == null) {
            sb.append("node").append(i).append("=-1");
         } else {
            sb.append("node").append(i).append('=').append(queue.getMessageCount())
               .append("(del=").append(queue.getDeliveringCount())
               .append(",c=").append(queue.getConsumerCount()).append(')');
         }
      }
      return sb.toString();
   }

   /** Message counts on cluster SnF queues (not visible in testQueue totals). */
   private String describeSnFCounts(int nodes) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < nodes; i++) {
         if (servers[i] == null || !servers[i].isActive()) {
            continue;
         }
         long total = 0;
         try {
            for (MessageFlowRecord record : clusterConnection(i).getRecords().values()) {
               if (record.getBridge() != null && record.getBridge().getQueue() != null) {
                  total += record.getBridge().getQueue().getMessageCount();
               }
            }
         } catch (Exception e) {
            if (sb.length() > 0) {
               sb.append(", ");
            }
            sb.append("node").append(i).append("=err:").append(e.toString());
            continue;
         }
         if (sb.length() > 0) {
            sb.append(", ");
         }
         sb.append("node").append(i).append('=').append(total);
      }
      return sb.toString();
   }

   private void setupCluster(int nodes) throws Exception {
      for (int i = 0; i < nodes; i++) {
         setupPrimaryServer(i, isFileStorage(), isNetty(), true);
         servers[i].getConfiguration().getAddressSettings().put("#", new AddressSettings().setRedistributionDelay(0));
      }

      for (int i = 0; i < nodes; i++) {
         int[] others = otherNodes(i, nodes);
         setupClusterConnection("cluster0", ADDRESS, MessageLoadBalancingType.ON_DEMAND, 1, isNetty(), i, others);

         PrimaryOnlyPolicyConfiguration haPolicy =
            (PrimaryOnlyPolicyConfiguration) servers[i].getConfiguration().getHAPolicyConfiguration();
         ScaleDownConfiguration scaleDownConfiguration = new ScaleDownConfiguration();
         // Use every static cluster connector (any peer) — do not pin a single scale-down target.
         scaleDownConfiguration.getConnectors().addAll(
            servers[i].getConfiguration().getClusterConfigurations().get(0).getStaticConnectors());
         haPolicy.setScaleDownConfiguration(scaleDownConfiguration);
      }
   }

   private void startAllServers(int nodes) throws Exception {
      int[] indexes = new int[nodes];
      for (int i = 0; i < nodes; i++) {
         indexes[i] = i;
      }
      startServers(indexes);
   }

   private static int[] otherNodes(int node, int nodes) {
      int[] others = new int[nodes - 1];
      int idx = 0;
      for (int j = 0; j < nodes; j++) {
         if (j != node) {
            others[idx++] = j;
         }
      }
      return others;
   }

   private void runProducer(int producerNode,
                            boolean persistent,
                            int restarts,
                            long maxMessages,
                            AtomicBoolean restartsCompleted,
                            AtomicLong produced,
                            AtomicLong consumed,
                            AtomicReference<Throwable> failure) {
      ClientSession session = null;
      ClientProducer producer = null;
      try {
         session = addClientSession(sfs[producerNode].createSession(false, true, true));
         producer = addClientProducer(session.createProducer(ADDRESS));

         // Keep sending through consumer-node stop/start; stop only when restarts
         // complete (restarts > 0) or the safety cap is hit.
         while (produced.get() < maxMessages) {
            if (restarts > 0 && restartsCompleted.get()) {
               break;
            }

            // Back-pressure for non-persistent only: unbounded in-memory floods during
            // consumer-node outages collapse cluster topology/bridges. Persistent traffic
            // is naturally slower (sync send + journal) and stays continuous without this.
            if (!persistent && restarts > 0 && !restartsCompleted.get()) {
               while (produced.get() - consumed.get() > MAX_OUTSTANDING_DURING_RESTART &&
                  !restartsCompleted.get()) {
                  // Yield without Thread.sleep (project test convention)
                  Thread.onSpinWait();
               }
               if (restartsCompleted.get()) {
                  break;
               }
            }

            long claimed = produced.getAndIncrement();
            if (claimed >= maxMessages) {
               produced.decrementAndGet();
               break;
            }

            try {
               ClientMessage message = session.createMessage(persistent);
               message.putIntProperty(PRODUCER_NODE_PROP, producerNode);
               message.putLongProperty(MSG_ID_PROP, claimed);
               producer.send(message);

               long total = claimed + 1;
               if (total % LOG_EVERY == 0) {
                  logger.debug("produced producerNode={} totalProduced={}", producerNode, total);
               }
            } catch (Exception e) {
               produced.decrementAndGet();
               throw e;
            }
         }
      } catch (Throwable t) {
         failure.compareAndSet(null, t);
         logger.debug("producer {} failed", producerNode, t);
      } finally {
         closeQuietly(producer);
         closeQuietly(session);
      }
   }

   private void runConsumer(int consumerNode,
                            AtomicLong produced,
                            AtomicLong consumed,
                            Set<Long> consumedIds,
                            AtomicBoolean consumerRunning,
                            AtomicReference<Throwable> failure) {
      ServerLocator locator = null;
      ClientSessionFactory sf = null;
      ClientSession session = null;
      ClientConsumer consumer = null;
      try {
         TransportConfiguration connector =
            createTransportConfiguration(isNetty(), false, generateParams(consumerNode, isNetty()));
         locator = addServerLocator(ActiveMQClient.createServerLocatorWithoutHA(connector)
            .setBlockOnNonDurableSend(true)
            .setBlockOnDurableSend(true)
            // Do not block the receive loop on ack confirmations: with a large window,
            // stalling inside acknowledge() leaves prefetched messages undelivered to the
            // test (broker still shows them in messageCount) and can look like loss.
            .setBlockOnAcknowledge(false)
            .setAckBatchSize(1024)
            .setConsumerWindowSize(1024 * 64)
            .setReconnectAttempts(-1)
            .setRetryInterval(100));

         // Deadline from first connect; keep well above restart + drain windows.
         final long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS * 3;

         while (consumerRunning.get() && System.currentTimeMillis() < deadline) {
            try {
               if (sf == null || sf.isClosed()) {
                  closeQuietly(consumer);
                  closeQuietly(session);
                  closeQuietly(sf);
                  consumer = null;
                  session = null;
                  sf = locator.createSessionFactory();
               }
               if (session == null || session.isClosed()) {
                  closeQuietly(consumer);
                  consumer = null;
                  // autoCommitAcks=true: ack batches; durable settle still reaches the broker
                  session = sf.createSession(false, true, true);
                  session.start();
               }
               if (consumer == null || consumer.isClosed()) {
                  consumer = session.createConsumer(QUEUE);
               }

               ClientMessage message = consumer.receive(RECEIVE_TIMEOUT_MS);
               if (message == null) {
                  // Flush a partial ack batch so broker credits return during idle gaps
                  // (end of drain, or pauses around consumer-node restart).
                  try {
                     session.commit();
                  } catch (Exception ignored) {
                  }
                  continue;
               }

               // Count before ack: acknowledge may flush a batch; never lose the id if that
               // throws or the session drops before the next loop iteration.
               long msgId = message.getLongProperty(MSG_ID_PROP);
               consumedIds.add(msgId);
               long totalConsumed = consumed.incrementAndGet();
               message.acknowledge();
               if (totalConsumed % LOG_EVERY == 0) {
                  int producerNode = message.getIntProperty(PRODUCER_NODE_PROP);
                  logger.debug("consumed producerNode={} receiveCount={} uniqueConsumed={} totalProduced={}",
                     producerNode, totalConsumed, consumedIds.size(), produced.get());
               }
            } catch (Exception e) {
               logger.debug("consumer reconnecting after error: {}", e.toString());
               closeQuietly(consumer);
               closeQuietly(session);
               closeQuietly(sf);
               consumer = null;
               session = null;
               sf = null;
               if (!consumerRunning.get()) {
                  break;
               }
            }
         }
      } catch (Throwable t) {
         failure.compareAndSet(null, t);
         logger.debug("consumer failed", t);
      } finally {
         closeQuietly(consumer);
         closeQuietly(session);
         closeQuietly(sf);
      }
   }

   private void runRestarts(int restarts,
                            int consumerNode,
                            int nodes,
                            int producerNodes,
                            boolean persistent,
                            AtomicLong produced,
                            AtomicLong consumed,
                            AtomicReference<Throwable> failure) {
      try {
         for (int r = 1; r <= restarts; r++) {
            final long consumedBefore = consumed.get();
            logger.debug("restart {}/{}: stopping node={}", r, restarts, consumerNode);
            closeConsumerNodeClients(consumerNode);
            stopServers(consumerNode);

            logger.debug("restart {}/{}: starting node={}", r, restarts, consumerNode);
            startServers(consumerNode);
            Wait.assertTrue(() -> servers[consumerNode].isActive(), TOPOLOGY_TIMEOUT_MS);

            for (int i = 0; i < nodes; i++) {
               waitForTopology(servers[i], nodes, -1, TOPOLOGY_TIMEOUT_MS);
            }

            servers[consumerNode].getAddressSettingsRepository()
               .addMatch("#", new AddressSettings().setRedistributionDelay(0));

            setupSessionFactory(consumerNode, isNetty());
            // Durable queue should reload from journal; recreate only if missing.
            if (servers[consumerNode].locateQueue(SimpleString.of(QUEUE)) == null) {
               try {
                  createQueue(consumerNode, ADDRESS, QUEUE, null, true, RoutingType.ANYCAST);
               } catch (ActiveMQQueueExistsException ignored) {
                  // Consumer reconnect raced and created the queue between the null check and create.
               }
            }

            Wait.assertTrue(() -> servers[consumerNode].locateQueue(SimpleString.of(QUEUE)) != null, BRIDGE_TIMEOUT_MS);

            // Wait until the consumer is locally attached; producer bridges must learn about
            // it via natural reconnect + binding/consumer notification sync.
            if (!Wait.waitFor(() -> {
               Queue q = servers[consumerNode].locateQueue(SimpleString.of(QUEUE));
               return q != null && q.getConsumerCount() >= 1;
            }, BRIDGE_TIMEOUT_MS)) {
               throw new AssertionError("local consumer not attached after restart " + r + "/" + restarts +
                  "; produced=" + produced.get() + " consumed=" + consumed.get() +
                  " queues=[" + describeQueueCounts(nodes) + "]" +
                  " bridges0=" + describeBridges(0));
            }

            final String consumerNodeId = servers[consumerNode].getNodeID().toString();
            // Explicitly wait for the consumer node to be present in each producer's
            // topology before requiring a bridge — scale-down removes the member and
            // rejoin can lag behind local activation / waitForTopology(count).
            for (int i = 0; i < producerNodes; i++) {
               final int producerNode = i;
               if (!Wait.waitFor(() -> topologyHasPrimary(producerNode, consumerNodeId), BRIDGE_TIMEOUT_MS)) {
                  throw new AssertionError("consumer node absent from producer topology after restart " +
                     r + "/" + restarts + " producerNode=" + producerNode +
                     " consumerNodeId=" + consumerNodeId +
                     " bridges=" + describeBridges(producerNode) +
                     " produced=" + produced.get() + " consumed=" + consumed.get() +
                     " queues=[" + describeQueueCounts(nodes) + "]");
               }
            }
            for (int i = 0; i < producerNodes; i++) {
               final int producerNode = i;
               if (!Wait.waitFor(() -> bridgeConnected(producerNode, consumerNodeId) &&
                  remoteConsumerCount(producerNode) >= 1, BRIDGE_TIMEOUT_MS)) {
                  throw new AssertionError("producer bridge/remote-consumer not ready after restart " +
                     r + "/" + restarts + " producerNode=" + producerNode +
                     " consumerNodeId=" + consumerNodeId +
                     " bridgeConnected=" + bridgeConnected(producerNode, consumerNodeId) +
                     " topologyHasConsumer=" + topologyHasPrimary(producerNode, consumerNodeId) +
                     " remoteConsumers=" + describeRemoteConsumers(producerNodes) +
                     " bridges=" + describeBridges(producerNode) +
                     " produced=" + produced.get() + " consumed=" + consumed.get() +
                     " queues=[" + describeQueueCounts(nodes) + "]");
               }
            }

            // Confirm delivery resumed via natural redistribution before the next outage.
            // Producers keep sending throughout the stop/start window.
            boolean deliveryResumed = Wait.waitFor(() -> consumed.get() > consumedBefore, BRIDGE_TIMEOUT_MS);
            if (!deliveryResumed) {
               throw new AssertionError("delivery did not resume after restart " + r + "/" + restarts +
                  "; consumedBefore=" + consumedBefore + " consumed=" + consumed.get() +
                  " produced=" + produced.get() +
                  " queues=[" + describeQueueCounts(nodes) + "]" +
                  " remoteConsumers=" + describeRemoteConsumers(producerNodes) +
                  " bridges0=" + describeBridges(0));
            }
         }
      } catch (Throwable t) {
         failure.compareAndSet(null, t);
         logger.debug("restarter failed", t);
      }
   }

   private String describeRemoteConsumers(int producerNodes) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < producerNodes; i++) {
         if (sb.length() > 0) {
            sb.append(", ");
         }
         try {
            sb.append("node").append(i).append('=').append(remoteConsumerCount(i));
         } catch (Exception e) {
            sb.append("node").append(i).append("=err:").append(e.toString());
         }
      }
      return sb.toString();
   }

   private void closeConsumerNodeClients(int consumerNode) {
      try {
         if (sfs[consumerNode] != null) {
            closeSessionFactory(consumerNode);
         }
      } catch (Exception e) {
         logger.debug("error closing session factory for node {}: {}", consumerNode, e.toString());
         sfs[consumerNode] = null;
      }
      try {
         if (locators[consumerNode] != null) {
            closeServerLocator(locators[consumerNode]);
            locators[consumerNode] = null;
         }
      } catch (Exception e) {
         logger.debug("error closing locator for node {}: {}", consumerNode, e.toString());
         locators[consumerNode] = null;
      }
   }

   private static void closeQuietly(AutoCloseable closeable) {
      if (closeable == null) {
         return;
      }
      try {
         closeable.close();
      } catch (Exception ignored) {
      }
   }
}
