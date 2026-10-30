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
package org.apache.activemq.artemis.tests.soak.scaledown;

import static org.apache.activemq.artemis.utils.TestParameters.testProperty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.apache.activemq.artemis.api.core.management.SimpleManagement;
import org.apache.activemq.artemis.cli.commands.helper.HelperCreate;
import org.apache.activemq.artemis.json.JsonArray;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.tests.soak.SoakTestBase;
import org.apache.activemq.artemis.util.ServerUtil;
import org.apache.activemq.artemis.utils.Wait;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soak test verifying that scale-down (message migration to surviving nodes on graceful shutdown)
 * preserves message integrity under concurrent producers and consumers across various cluster
 * topologies and restart patterns.
 *
 * <p>Each broker is configured with {@code scaleDownConfiguration.enabled=true} so that when a
 * node shuts down gracefully it drains its queues to a peer rather than losing messages.
 * Redistribution delay is set to zero to keep messages moving immediately between nodes.
 *
 * <p>Test parameters can be overridden at runtime via system properties prefixed with
 * {@code SCALE_DOWN_} (e.g. {@code -DSCALE_DOWN_NUMBER_OF_SERVERS=3}).
 */
public class ScaleDownSoakTest extends SoakTestBase {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   private static final String TEST_NAME = "SCALE_DOWN";
   private static final String TEST_MSG_ID = "TEST_MSG_ID";
   private static final String TEST_QUEUE_NAME = "TEST_SD";
   private static final String SERVER_NAME_BASE = "scaledown/broker-";
   private static final int SERVER_PORT_BASE = 61616;
   private static final String CLUSTER_CONNECTION_NAME = "my-cluster";

   private static final int NUMBER_OF_SERVERS = testProperty(TEST_NAME, "NUMBER_OF_SERVERS", 4);
   private static final int NUMBER_OF_RESTARTS = testProperty(TEST_NAME, "NUMBER_OF_RESTARTS", NUMBER_OF_SERVERS);
   private static final int RECEIVE_TIMEOUT = testProperty(TEST_NAME, "RECEIVE_TIMEOUT", 1000);
   private static final int SERVER_UP_TIME = testProperty(TEST_NAME, "SERVER_UP_TIME", 3000);
   private static final int CLIENT_STOP_TIMEOUT_MS = testProperty(TEST_NAME, "CLIENT_STOP_TIMEOUT_MS", 30_000);
   private static final int SERVER_START_TIMEOUT_MS = testProperty(TEST_NAME, "SERVER_START_TIMEOUT_MS", 30_000);
   private static final int SERVER_STOP_TIMEOUT_MS = testProperty(TEST_NAME, "SERVER_STOP_TIMEOUT_MS", 180_000);
   private static final int CLUSTER_TIMEOUT_MS = testProperty(TEST_NAME, "CLUSTER_TIMEOUT_MS", 120_000);
   private static final boolean DEBUG_LOGGING =
      Boolean.parseBoolean(testProperty(TEST_NAME, "DEBUG_LOGGING", "false"));

   private static final int[] ALL_SERVERS = IntStream.range(0, NUMBER_OF_SERVERS).toArray();

   static class ServerRecord {
      String serverName;
      File serverLocation;
      File brokerProperties;
      Process process;
      int serverId;
      boolean running;

      ServerRecord(int id, String serverName, File serverLocation) {
         this.serverLocation = serverLocation;
         this.brokerProperties = new File(serverLocation, "broker.properties");
         this.serverName = serverName;
         this.serverId = id;
      }

      public void startServer() throws Exception {
         running = true;
         process = ServerUtil.startServer(serverLocation.getAbsolutePath(), serverName, serverId, SERVER_START_TIMEOUT_MS, brokerProperties, null);
         logger.info("Starting server {} with pid: {}", serverId, process.pid());
      }

      public SimpleManagement getManagement() {
         return new SimpleManagement("tcp://localhost:" + (SERVER_PORT_BASE + serverId), null, null);
      }

      public void stopServer() throws IOException {
         running = false;
         logger.info("Stopping server {} with pid {}, process {}", serverId, process.pid(), process);
         if (process.isAlive()) {
            stopServerWithFile(serverLocation.getAbsolutePath());
         }
      }

      public static int[] getStartedNodeIds() {
         return Arrays.stream(serverRecords).filter(serverRecord -> serverRecord.running).mapToInt(value -> value.serverId).toArray();
      }

      public static int[] getStoppedNodeIds() {
         return Arrays.stream(serverRecords).filter(serverRecord -> !serverRecord.running).mapToInt(value -> value.serverId).toArray();
      }

      public void waitForServer() throws InterruptedException {
         assertTrue(process.waitFor(SERVER_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            "Server " + serverName + " did not stop within " + SERVER_STOP_TIMEOUT_MS + "s");
         assertEquals(0, process.exitValue(), "Server " + serverName + " exited with non-zero code");
         logger.info("Stopped server {} with pid {}, exitValue {}, process {}", serverId, process.pid(), process.exitValue(), process);
      }

      private String serverURI() {
         return "tcp://localhost:" + (SERVER_PORT_BASE + serverId) + "?ha=false&useTopologyForLoadBalancing=false";
      }
   }

   private static final ServerRecord[] serverRecords = new ServerRecord[NUMBER_OF_SERVERS];

   Set<Integer> runningProducers;
   Set<Integer> connectedProducers;
   AtomicBoolean stoppingProducers;

   Set<Integer> runningConsumers;
   Set<Integer> connectedConsumers;
   AtomicBoolean stoppingConsumers;

   Map<String, MessageRecord> messageRecords;
   AtomicInteger sentMessages;
   AtomicInteger receivedMessages;

   record ConsumerRecord(int id, int serverId, String jmsMessageId, Instant timestamp) {
   }

   static final class MessageRecord {
      final String id;
      final int producerId;
      final int producerServerId;
      final Instant sendingTimestamp;
      Instant sentTimestamp;
      final List<ConsumerRecord> consumerRecords = Collections.synchronizedList(new ArrayList<>());

      MessageRecord(String id, int producerId, int producerServerId, Instant sendingTimestamp) {
         this.id = id;
         this.producerId = producerId;
         this.producerServerId = producerServerId;
         this.sendingTimestamp = sendingTimestamp;
      }

      boolean isSent() {
         return sentTimestamp != null;
      }

      boolean isConsumed() {
         return !consumerRecords.isEmpty();
      }

      boolean hasDuplicates() {
         return consumerRecords.size() > 1;
      }

      int duration() {
         if (consumerRecords.isEmpty()) {
            throw new IllegalStateException("Message not consumed");
         }

         return Math.toIntExact(Duration.between(sendingTimestamp,
            consumerRecords.get(0).timestamp).toMillis());
      }
   }


   /**
    * Skips the remaining tests of this class (including their {@code @BeforeEach}/{@code @AfterEach})
    * once one of them has failed, since each test is very long and a failure usually makes the
    * following ones meaningless.
    */
   private static class SkipAfterFirstFailure implements TestWatcher, ExecutionCondition {

      private volatile String failedTest;

      void reset() {
         failedTest = null;
      }

      @Override
      public void testFailed(ExtensionContext context, Throwable cause) {
         if (failedTest == null) {
            failedTest = context.getDisplayName();
         }
      }

      @Override
      public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
         if (failedTest != null && context.getTestMethod().isPresent()) {
            return ConditionEvaluationResult.disabled("Skipped because " + failedTest + " failed");
         }
         return ConditionEvaluationResult.enabled("No previous failure");
      }
   }

   @RegisterExtension
   static final SkipAfterFirstFailure skipAfterFirstFailure = new SkipAfterFirstFailure();

   @BeforeAll
   public static void createServers() throws Exception {
      skipAfterFirstFailure.reset();

      for (int serverId = 0; serverId < NUMBER_OF_SERVERS; serverId++) {
         String serverName = SERVER_NAME_BASE + serverId;

         File serverLocation = getFileServerLocation(serverName);
         deleteDirectory(serverLocation);

         HelperCreate cliCreateServer = helperCreate();
         cliCreateServer
            .setRole("amq")
            .setUser("admin")
            .setPassword("admin")
            .setAllowAnonymous(true)
            .setNoWeb(true)
            .setArtemisInstance(serverLocation)
            .setPortOffset(serverId)
            .setClustered(true);

         cliCreateServer.setMessageLoadBalancing("ON_DEMAND");
         cliCreateServer.setStaticCluster(buildStaticClusterURI(serverId));
         cliCreateServer.setArgs("--no-stomp-acceptor", "--no-hornetq-acceptor",
            "--no-mqtt-acceptor", "--no-amqp-acceptor",
            "--max-hops", "1",
            "--cluster-user", "cluster-user",
            "--cluster-password", "cluster-password",
            //"--java-options", "-Xms512M -Xmx1G",
            "--queues", TEST_QUEUE_NAME);

         cliCreateServer.createServer();

         if (DEBUG_LOGGING) {
            enableClusterDebugLogging(serverLocation);
         }

         Properties brokerProperties = new Properties();
         brokerProperties.put("addressesSettings.#.redistributionDelay", "0");
         // 0 disables compaction so history stays in the journal for duplicate analysis
         brokerProperties.put("journalCompactMinFiles", "0");
         brokerProperties.put("HAPolicyConfiguration", "PRIMARY_ONLY");
         brokerProperties.put("HAPolicyConfiguration.scaleDownConfiguration.enabled", "true");
         brokerProperties.put("HAPolicyConfiguration.scaleDownConfiguration.connectors", buildScaleDownConnectors());
         saveProperties(brokerProperties, new File(serverLocation, "broker.properties"));
         serverRecords[serverId] = new ServerRecord(serverId, serverName, serverLocation);
      }
   }

   /**
    * Append DEBUG logger config for the cluster/scale-down classes to a broker instance's
    * {@code etc/log4j2.properties}. This surfaces the SnF relocate drain iterations
    * ({@code BridgeImpl}), the flow-record / SnF-delete lifecycle ({@code ClusterConnectionBridge},
    * {@code ClusterConnectionImpl}) and the node self scale-down ({@code ScaleDownHandler}).
    */
   private static void enableClusterDebugLogging(File serverLocation) throws Exception {
      String debugLoggers = String.join(System.lineSeparator(),
         "",
         "# Enabled by ScaleDownSoakTest (-DTEST_SCALE_DOWN_DEBUG_LOGGING=true) for scale-down reproduction",
         "logger.scaledown_cluster.name=org.apache.activemq.artemis.core.server.cluster",
         "logger.scaledown_cluster.level=DEBUG",
         "logger.scaledown_handler.name=org.apache.activemq.artemis.core.server.impl.ScaleDownHandler",
         "logger.scaledown_handler.level=DEBUG",
         "");
      File log4j = new File(serverLocation, "etc/log4j2.properties");
      Files.writeString(log4j.toPath(), debugLoggers, StandardOpenOption.APPEND);
   }

   private static String joinServers(int excludeIndex, IntFunction<String> token) {
      return IntStream.range(0, NUMBER_OF_SERVERS)
         .filter(i -> i != excludeIndex)
         .mapToObj(token::apply)
         .collect(Collectors.joining(","));
   }

   private static String buildStaticClusterURI(int excludeIndex) {
      return joinServers(excludeIndex, i -> "tcp://localhost:" + (SERVER_PORT_BASE + i));
   }

   private static String buildScaleDownConnectors() {
      // The static-cluster helper names the N-1 peer connectors positionally as
      // node0..node(N-2) on every broker (independent of the broker's own id), so the
      // scale-down list must reference those actual connector names. Using id-based
      // names here (e.g. node8) references a connector that does not exist, which makes
      // scale-down fail with an NPE on every shutdown and silently loses any messages
      // that still need migrating once consumers have stopped.
      return IntStream.range(0, NUMBER_OF_SERVERS - 1)
         .mapToObj(i -> "node" + i)
         .collect(Collectors.joining(","));
   }

   @BeforeEach
   public void beforeEach() throws Exception {

      runningProducers = ConcurrentHashMap.newKeySet();
      connectedProducers = ConcurrentHashMap.newKeySet();
      stoppingProducers = new AtomicBoolean(false);

      runningConsumers = ConcurrentHashMap.newKeySet();
      connectedConsumers = ConcurrentHashMap.newKeySet();
      stoppingConsumers = new AtomicBoolean(false);

      messageRecords = new ConcurrentHashMap<>();
      sentMessages = new AtomicInteger(0);
      receivedMessages = new AtomicInteger(0);



      for (int i = 0; i < NUMBER_OF_SERVERS; i++) {
         cleanupData(SERVER_NAME_BASE + i);
      }

      for (ServerRecord serverRecord : serverRecords) {
         serverRecord.startServer();
      }

      waitForCluster(IntStream.range(0, NUMBER_OF_SERVERS).toArray(), new int[0]);
   }

   @AfterEach
   public void afterEach() throws Exception {
      stopProducers();

      stopConsumers();

      stopServers();
   }

   /**
    * All nodes produce and consume from the same queue. Node 0 is restarted repeatedly to verify
    * that scale-down migrates its messages to the remaining nodes and that those messages are
    * eventually consumed without loss.
    */
   @Test
   public void testSymmetricNodesWithSingleNodeRestarts() throws Exception {
      runRestartScenario(ALL_SERVERS, ALL_SERVERS, i -> 0);
   }

   /**
    * All nodes produce and consume from the same queue. Nodes are restarted in round-robin order
    * to stress scale-down across every broker in turn, ensuring no node is a special case for
    * message migration.
    */
   @Test
   public void testSymmetricNodesWithSequentialNodeRestarts() throws Exception {
      runRestartScenario(ALL_SERVERS, ALL_SERVERS, i -> i % serverRecords.length);
   }

   /**
    * All nodes produce, but only node 0 consumes. Node 0 is restarted repeatedly, forcing
    * scale-down to migrate its in-flight messages to a peer on each shutdown and verifying that
    * the consumer picks them up again after restart without duplication or loss.
    */
   @Test
   public void testSingleConsumerNodeWithConsumerNodeRestarts() throws Exception {
      runRestartScenario(ALL_SERVERS, new int[]{0}, i -> 0);
   }

   /**
    * All nodes produce, but only node 0 consumes. Nodes are restarted in round-robin order
    * to stress scale-down across every broker in turn, ensuring no node is a special case for
    * message migration.
    */
   @Test
   public void testSingleConsumerNodeWithSequentialNodeRestarts() throws Exception {
      runRestartScenario(ALL_SERVERS, new int[]{0}, i -> i % serverRecords.length);
   }

   /**
    * Nodes have different roles: produce only, consume only, both producer and consumer,
    * and pure routing nodes with no local producers or consumers. Node 0 is restarted repeatedly
    * order to verify that scale-down correctly migrates messages regardless of whether the
    * restarted node was a producer, consumer, or pure relay.
    */
   @Test
   public void testAsymmetricNodesWithSingleNodeRestarts() throws Exception {
      int[] producerNodes = IntStream.range(0, NUMBER_OF_SERVERS).filter(i -> i % 2 == 0).toArray();
      int[] consumerNodes = IntStream.range(0, NUMBER_OF_SERVERS).filter(i -> i % 4 < 2).toArray();
      runRestartScenario(producerNodes, consumerNodes, i -> 0);
   }

   /**
    * Nodes have different roles: produce only, consume only, both producer and consumer,
    * and pure routing nodes with no local producers or consumers. Nodes are restarted in round-robin
    * order to verify that scale-down correctly migrates messages regardless of whether the
    * restarted node was a producer, consumer, or pure relay.
    */
   @Test
   public void testAsymmetricNodesWithSequentialNodeRestarts() throws Exception {
      int[] producerNodes = IntStream.range(0, NUMBER_OF_SERVERS).filter(i -> i % 2 == 0).toArray();
      int[] consumerNodes = IntStream.range(0, NUMBER_OF_SERVERS).filter(i -> i % 4 < 2).toArray();
      runRestartScenario(producerNodes, consumerNodes, i -> i % serverRecords.length);
   }

   /**
    * Shared scenario driver: wire up producers/consumers, then repeatedly let the cluster run for
    * {@code UP_TIME} and restart a node chosen by {@code nodeToRestart} (given the iteration index).
    * Finally stop producers, drain, stop consumers/servers and assert integrity.
    */
   private void runRestartScenario(int[] producerNodes, int[] consumerNodes, IntUnaryOperator nodeToRestart) throws Exception {
      setupClients(producerNodes, consumerNodes);

      for (int i = 0; i < NUMBER_OF_RESTARTS; i++) {
         final int restartingNode = nodeToRestart.applyAsInt(i);

         //waitForConsumedMessages(totalSent.get());

         Thread.sleep(SERVER_UP_TIME);

         stopServer(restartingNode);
         startServer(restartingNode);
      }

      stopProducers();
      waitForMessages();
      stopConsumers();
      stopServers();
   }

   private void setupClients(int[] producerNodes, int[] consumerNodes) throws Exception {
      ExecutorService executorService = Executors.newFixedThreadPool(producerNodes.length + consumerNodes.length);
      runAfter(executorService::shutdownNow);


      int consumerId = 0;
      for (int nodeId : consumerNodes) {
         final int idx = nodeId;
         final int id = consumerId++;
         executorService.execute(() -> consumeMessages(id, serverRecords[idx]));
      }

      int producerId = 0;
      for (int nodeId : producerNodes) {
         final int idx = nodeId;
         final int id = producerId++;
         executorService.execute(() -> produceMessages(id, serverRecords[idx]));
      }
   }

   private void startServer(int serverId) throws Exception {
      logger.info("Starting server {}", serverId);
      serverRecords[serverId].startServer();
      logger.info("Started server {}", serverId);
   }

   private void stopServer(int serverId) throws Exception {
      logger.info("Stopping server {}", serverId);
      waitForCluster(ServerRecord.getStartedNodeIds(), ServerRecord.getStoppedNodeIds());
      serverRecords[serverId].stopServer();
      serverRecords[serverId].waitForServer();
      waitForCluster(ServerRecord.getStartedNodeIds(), ServerRecord.getStoppedNodeIds());
      logger.info("Stopped server {}", serverId);
   }

   private void waitForConsumedMessages(int messages) {
      Wait.assertTrue("Consumers did not consumed " + messages + " messages in time: ",
         () -> sentMessages.get() - receivedMessages.get() < 1000, 30_000, 100);
   }

   private void waitForConnectedConsumer(int consumerId) {
      Wait.assertTrue("Consumers did not complete in time: " + runningConsumers,
         () -> connectedConsumers.contains(consumerId), CLIENT_STOP_TIMEOUT_MS, 100);
   }

   private void produceMessages(int producerId, ServerRecord serverRecord) {
      Thread.currentThread().setName("Producer " + producerId + " server=" + serverRecord.serverId);

      int count = 0;
      runningProducers.add(producerId);

      try {
         int messageIndex = 0;
         int connectionFailures = 0;
         while (!stoppingProducers.get()) {
            try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(serverRecord.serverURI());
                 Connection connection = factory.createConnection()) {
               Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
               MessageProducer producer = session.createProducer(session.createQueue(TEST_QUEUE_NAME));
               connectedProducers.add(producerId);

               while (!stoppingProducers.get()) {
                  String messageId = "prd-" + producerId + "-srv-" + serverRecord.serverId + "-msg-" + messageIndex++;
                  MessageRecord messageRecord = new MessageRecord(messageId, producerId, serverRecord.serverId, Instant.now());
                  TextMessage message = session.createTextMessage(String.valueOf(count));
                  message.setStringProperty(TEST_MSG_ID, messageId);
                  messageRecords.put(messageId, messageRecord);
                  producer.send(message);
                  messageRecord.sentTimestamp = Instant.now();
                  sentMessages.incrementAndGet();
                  count++;
                  if (count % 100 == 0) {
                     logger.info("Producer {} server={} count={} {}/{}", producerId,
                        serverRecord.serverId, count, receivedMessages.get(), sentMessages.get());
                  }
               }
            } catch (Exception e) {
               connectedProducers.remove(producerId);
               if (connectionFailures++ % 30 == 0) {
                  logger.info("Producer {} server={} count={} {}/{}: connectionFailures={}", producerId,
                     serverRecord.serverId, count, receivedMessages.get(), sentMessages.get(), connectionFailures, e);
               }
               Thread.sleep(100);
            }
         }
         logger.info("Producer {} server={} count={} {}/{}: finished", producerId,
            serverRecord.serverId, count, receivedMessages.get(), sentMessages.get());
      } catch (InterruptedException e) {
         logger.info("Producer {} server={} count={} {}/{}: interrupted", producerId,
            serverRecord.serverId, count, receivedMessages.get(), sentMessages.get());
      } finally {
         runningProducers.remove(producerId);
      }
   }

   private void consumeMessages(int consumerId, ServerRecord serverRecord) {
      Thread.currentThread().setName("Consumer " + consumerId + " server=" + serverRecord.serverId);

      int count = 0;
      runningConsumers.add(consumerId);

      try {
         int connectionFailures = 0;
         while (!stoppingConsumers.get()) {
            try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(serverRecord.serverURI());
                 Connection connection = factory.createConnection()) {
               connection.start();
               Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
               MessageConsumer consumer = session.createConsumer(session.createQueue(TEST_QUEUE_NAME));
               connectedConsumers.add(consumerId);

               int consecutiveNullMessages = 0;
               while (!stoppingConsumers.get()) {
                  Message message = consumer.receive(RECEIVE_TIMEOUT);
                  if (message != null) {
                     String messageId = message.getStringProperty(TEST_MSG_ID);
                     MessageRecord record = messageRecords.get(messageId);
                     record.consumerRecords.add(new ConsumerRecord(consumerId,
                        serverRecord.serverId, message.getJMSMessageID(), Instant.now()));
                     receivedMessages.incrementAndGet();
                     count++;
                     if (count % 100 == 0) {
                        logger.info("Consumer {} server={} count={} {}/{}", consumerId,
                           serverRecord.serverId, count, receivedMessages.get(), sentMessages.get());
                     }
                  } else {
                     consecutiveNullMessages++;
                     logger.info("Consumer {} server={} count={} {}/{}: consecutiveNullMessages={}", consumerId,
                        serverRecord.serverId, count, receivedMessages.get(), sentMessages.get(), consecutiveNullMessages);
                 }
               }
            } catch (Exception e) {
               connectedConsumers.remove(consumerId);
               if (connectionFailures++ % 30 == 0) {
                  logger.info("Consumer {} server={} count={} {}/{}: connectionFailures={}", consumerId,
                     serverRecord.serverId, count, receivedMessages.get(), sentMessages.get(), connectionFailures, e);
               }
               Thread.sleep(100);
            }
         }
         logger.info("Consumer {} server={} count={} {}/{}: finished", consumerId,
            serverRecord.serverId, count, receivedMessages.get(), sentMessages.get());
      } catch (InterruptedException e) {
         logger.info("Consumer {} server={} count={} {}/{}: interrupted", consumerId,
            serverRecord.serverId, count, receivedMessages.get(), sentMessages.get());
      } finally {
         runningConsumers.remove(consumerId);
      }
   }

   private void stopProducers() throws Exception {
      if (!runningProducers.isEmpty()) {
         stoppingProducers.set(true);

         Wait.assertTrue("Producers did not complete in time: " + runningProducers,
            () -> runningProducers.isEmpty(), CLIENT_STOP_TIMEOUT_MS, 100);

         logger.info("Test producers stopped");

         checkMessages();
      }
   }

   private void waitForMessages() {
      Wait.assertTrue(this::checkMessages, CLUSTER_TIMEOUT_MS, 1_000);
   }

   private boolean checkMessages() {
      long lostMessages = countLostMessages();
      long ghostMessages = countGhostMessages();
      long duplicates = countDuplicateMessages();
      long queueMessages = countQueueMessages();
      long snfMessages = countSnFMessages();

      logger.info("Test status: sent={}, received={}, lost={}, ghost={}, duplicates={}, queueMessages={}, snfMessages={}",
         sentMessages.get(), receivedMessages.get(), lostMessages, ghostMessages, duplicates, queueMessages, snfMessages);

      return lostMessages == 0 && queueMessages == 0 && snfMessages == 0;
   }

   private void stopConsumers() throws Exception {
      if (!runningConsumers.isEmpty()) {
         stoppingConsumers.set(true);

         Wait.assertTrue("Consumers did not complete in time: " + runningConsumers,
            () -> runningConsumers.isEmpty(), CLIENT_STOP_TIMEOUT_MS, 100);

         logger.info("Test consumers stopped");

         checkMessages();
      }
   }

   private void stopServers() throws Exception {
      for (int i = 0; i < NUMBER_OF_SERVERS; i++) {
         serverRecords[i].stopServer();
      }
      for (int i = 0; i < NUMBER_OF_SERVERS; i++) {
         serverRecords[i].waitForServer();
      }
   }

   private long countLostMessages() {
      Set<String> lostIds = messageRecords.values().stream()
         .filter(messageRecord -> messageRecord.isSent() && !messageRecord.isConsumed())
         .map(r -> r.id)
         .collect(Collectors.toCollection(HashSet::new));
      if (!lostIds.isEmpty()) {
         logger.info("Test lost messages: {}", lostIds);
      }
      return lostIds.size();
   }

   private long countGhostMessages() {
      Set<String> ghostIds = messageRecords.values().stream()
         .filter(messageRecord -> !messageRecord.isSent())
         .map(r -> r.id)
         .collect(Collectors.toCollection(HashSet::new));

      if (!ghostIds.isEmpty()) {
         logger.info("Test ghost messages: {}", ghostIds);
      }
      return ghostIds.size();
   }

   private long countDuplicateMessages() {
      Set<String> duplicateIds = messageRecords.values().stream()
         .filter(MessageRecord::hasDuplicates)
         .map(r -> r.id)
         .collect(Collectors.toCollection(HashSet::new));

      if (!duplicateIds.isEmpty()) {
         logger.info("Test duplicated messages: {}", duplicateIds);
      }
      return messageRecords.values().stream().filter(MessageRecord::hasDuplicates).mapToInt(value -> value.consumerRecords.size() - 1).sum();
   }

   private long countQueueMessages() {
      long total = 0;
      for (int i = 0; i < NUMBER_OF_SERVERS; i++) {
         try {
            long count = serverRecords[i].getManagement().getMessageCountOnQueue(TEST_QUEUE_NAME);
            if (count > 0) {
               logger.info("Test server {} queueMessageCount: {}", i, count);
            }
            total += count;
         } catch (Exception e) {
            logger.debug("Could not get message count from server {}: {}", i, e.getMessage());
         }
      }
      return total;
   }

   private long countSnFMessages() {
      long total = 0;
      for (int i = 0; i < NUMBER_OF_SERVERS; i++) {
         try {
            for (Map.Entry<String, Long> entry : serverRecords[i].getManagement().getQueueCounts(1000).entrySet()) {
               if (entry.getKey().startsWith("sf.") && entry.getValue() > 0) {
                  logger.info("Test server {} SnF queue {} count: {}", i, entry.getKey(), entry.getValue());
                  total += entry.getValue();
               }
            }
         } catch (Exception e) {
            logger.debug("Could not get SnF counts from server {}: {}", i, e.getMessage());
         }
      }
      return total;
   }

   private void waitForCluster(int[] startedNodes, int[] stoppedNodes) throws Exception {
      for (int nodeId : startedNodes) {
         ServerUtil.waitForServerToStart(nodeId, SERVER_START_TIMEOUT_MS);
      }
      for (int nodeId : startedNodes) {
         final int serverIndex = nodeId;

         Wait.assertTrue(() -> {
            try {
               JsonArray topology = serverRecords[serverIndex].getManagement().listNetworkTopology();
               for (int s : startedNodes) {
                  String expectedConnector = "localhost:" + (SERVER_PORT_BASE + s);
                  boolean found = false;
                  for (int j = 0; j < topology.size(); j++) {
                     if (topology.getJsonObject(j).getString("primary", "").contains(expectedConnector)) {
                        found = true;
                        break;
                     }
                  }
                  if (!found) {
                     logger.info("Server {} listNetworkTopology missing started connector {}", serverIndex, expectedConnector);
                     return false;
                  }
               }
               for (int s : stoppedNodes) {
                  String stoppedConnector = "localhost:" + (SERVER_PORT_BASE + s);
                  for (int j = 0; j < topology.size(); j++) {
                     if (topology.getJsonObject(j).getString("primary", "").contains(stoppedConnector)) {
                        logger.info("Server {} listNetworkTopology still contains stopped server {}", serverIndex, s);
                        return false;
                     }
                  }
               }
               return true;
            } catch (Exception e) {
               logger.info("Could not get listNetworkTopology from server {}: {}", serverIndex, e.getMessage());
               return false;
            }
         }, CLUSTER_TIMEOUT_MS, 1000);

         Wait.assertTrue(() -> {
            try {
               Map<String, String> nodes = serverRecords[serverIndex].getManagement()
                  .simpleManagementMap(ResourceNames.CORE_CLUSTER_CONNECTION + CLUSTER_CONNECTION_NAME, "getNodes");
               for (int s : startedNodes) {
                  if (s == serverIndex) {
                     continue;
                  }
                  String expectedPort = ":" + (SERVER_PORT_BASE + s);
                  boolean found = false;
                  for (String connector : nodes.values()) {
                     if (connector.contains(expectedPort)) {
                        found = true;
                        break;
                     }
                  }
                  if (!found) {
                     logger.info("Server {} getNodes missing started connector for port {}", serverIndex, expectedPort);
                     return false;
                  }
               }
               for (int s : stoppedNodes) {
                  String stoppedPort = ":" + (SERVER_PORT_BASE + s);
                  for (String connector : nodes.values()) {
                     if (connector.contains(stoppedPort)) {
                        logger.info("Server {} getNodes still contains stopped server {}", serverIndex, s);
                        return false;
                     }
                  }
               }
               return true;
            } catch (Exception e) {
               logger.info("Could not get getNodes from server {}: {}", serverIndex, e.getMessage());
               return false;
            }
         }, CLUSTER_TIMEOUT_MS, 1000);
      }
   }
}
