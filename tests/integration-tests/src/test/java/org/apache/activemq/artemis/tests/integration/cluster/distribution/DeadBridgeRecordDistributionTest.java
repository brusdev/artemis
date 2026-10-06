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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;

import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.apache.activemq.artemis.core.server.cluster.MessageFlowRecord;
import org.apache.activemq.artemis.core.server.cluster.impl.ClusterConnectionImpl;
import org.apache.activemq.artemis.core.server.cluster.impl.MessageLoadBalancingType;
import org.apache.activemq.artemis.tests.util.Wait;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reproduces the "non-null record with dead bridge" bug.
 *
 * <p>Scenario: a {@link MessageFlowRecord} for a remote node can be left in a dead state when
 * {@code createNewRecord()} races with a concurrent shutdown — the bridge is instantiated but
 * its {@code ServerLocator} is closed before the first connection attempt succeeds (AMQ222100).
 * The record is non-null in the map, but the bridge will never reconnect on its own.
 *
 * <p>When the remote node then restarts and node 0 receives a {@code nodeUP}, the existing dead
 * record must be detected and replaced with a fresh one so that the cluster can resume normal
 * message routing and distribution.
 *
 * <p>This test injects the dead-record state directly (by closing the record's
 * {@code ServerLocator}) to make the scenario deterministic, then
 * triggers a {@code nodeUP} by restarting node 1 and verifies that message distribution
 * (load balancing) works end-to-end after the replacement.
 */
public class DeadBridgeRecordDistributionTest extends ClusterTestBase {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   private static final String ADDRESS = "queues.testaddress";
   private static final String QUEUE_NAME = "queue0";
   private static final int MSG_COUNT = 10;

   @Override
   @BeforeEach
   public void setUp() throws Exception {
      super.setUp();
   }

   protected boolean isNetty() {
      return true;
   }

   /**
    * Injects a dead-record state by closing the {@code ServerLocator} of the live record
    * for {@code nodeID} on {@code cc}, then restarts node 1 and verifies that the dead record is
    * detected on {@code nodeUP} and replaced with a live bridge that enables message distribution.
    */
   @Test
   @Timeout(60)
   public void testDistributionAfterDeadBridgeRecord() throws Exception {
      // --- 1. Setup two-node cluster with ON_DEMAND load balancing ---
      setupServer(0, isFileStorage(), isNetty());
      setupServer(1, isFileStorage(), isNetty());

      setupClusterConnection("cluster0", "queues", MessageLoadBalancingType.ON_DEMAND, 1, isNetty(), 0, 1);
      setupClusterConnection("cluster1", "queues", MessageLoadBalancingType.ON_DEMAND, 1, isNetty(), 1, 0);

      startServers(0, 1);

      setupSessionFactory(0, isNetty());
      setupSessionFactory(1, isNetty());

      createQueue(0, ADDRESS, QUEUE_NAME, null, true);
      createQueue(1, ADDRESS, QUEUE_NAME, null, true);

      // --- 2. Let the cluster settle: both bridges connected, both sides see each other ---
      waitForTopology(servers[0], 2);
      waitForTopology(servers[1], 2);

      ClusterConnectionImpl cc0 = getClusterConnectionImpl(0);
      String node1Id = servers[1].getNodeID().toString();

      Wait.assertTrue("node 0 must have a record for node 1",
                      () -> cc0.getRecords().containsKey(node1Id), 5000, 100);
      Wait.assertTrue("bridge from node 0 to node 1 must be connected",
                      () -> {
                         MessageFlowRecord r = cc0.getRecords().get(node1Id);
                         return r != null && r.getBridge() != null && r.getBridge().isConnected();
                      }, 5000, 100);

      // --- 3. Inject the dead-record state ---
      // Close the live record's ServerLocator to simulate what happens when
      // createNewRecord() races with a concurrent shutdown (AMQ222100).  After this:
      //   - record is non-null in cc0.getRecords()
      //   - record.isClosed() == false  (MessageFlowRecordImpl.isClosed not set)
      //   - record.getBridge().isConnected() == false  (session gone)
      //   - targetLocator.isClosed() == true           (the dead-record signal)
      MessageFlowRecord liveRecord = cc0.getRecords().get(node1Id);
      assertNotNull(liveRecord, "live record must exist before injection");

      ServerLocator targetLocator = liveRecord.getTargetLocator();
      assertNotNull(targetLocator, "targetLocator must be accessible from the record");

      // Close the locator so isClosed() returns true — this is the dead-record condition
      // the fix must detect.  The bridge will immediately lose its session.
      targetLocator.close();

      Wait.assertTrue("bridge must become disconnected after locator close",
                      () -> {
                         MessageFlowRecord r = cc0.getRecords().get(node1Id);
                         return r == null || r.getBridge() == null || !r.getBridge().isConnected();
                      }, 5000, 100);

      // Confirm the record is still in the map (dead but non-null)
      assertNotNull(cc0.getRecords().get(node1Id),
                    "dead record must still be present in the map before restart");

      // --- 4. Stop and restart node 1 to trigger nodeUP on node 0 ---
      stopServers(1);
      startServers(1);

      waitForTopology(servers[0], 2);
      waitForTopology(servers[1], 2);

      // --- 5. Attach a fresh consumer on node 1 ---
      resetSessionFactory(1);
      setupSessionFactory(1, isNetty());

      ClientSession consumerSession = sfs[1].createSession(false, true, true);
      ClientConsumer consumer1 = consumerSession.createConsumer(QUEUE_NAME);
      consumerSession.start();

      // --- 6. Assert the fix: the dead record was detected and replaced with a live bridge ---
      Wait.assertTrue("bridge for node 1 must become connected after dead-record replacement",
                      () -> {
                         MessageFlowRecord r = cc0.getRecords().get(node1Id);
                         return r != null && r.getBridge() != null && r.getBridge().isConnected();
                      }, 10_000, 200);

      MessageFlowRecord recordAfter = cc0.getRecords().get(node1Id);
      assertNotNull(recordAfter, "record for node 1 must exist after restart");
      assertTrue(recordAfter.getBridge().isConnected(),
                 "bridge must be connected after dead-record replacement");

      // Wait until node 0 sees node 1's consumer via the remote binding before sending.
      waitForBindings(0, ADDRESS, 1, 1, false);

      // --- 7. Verify message distribution works: send to node 0, routed & consumed on node 1 ---
      ClientSession producerSession = sfs[0].createSession(false, true, true);
      producerSession.start();
      ClientProducer producer = producerSession.createProducer(ADDRESS);

      for (int i = 0; i < MSG_COUNT; i++) {
         ClientMessage msg = producerSession.createMessage(true);
         msg.putIntProperty("index", i);
         producer.send(msg);
      }

      // All messages should be distributed from node 0 to node 1 and consumed there
      int received = 0;
      for (int i = 0; i < MSG_COUNT; i++) {
         ClientMessage msg = consumer1.receive(5000);
         assertNotNull(msg, "expected message " + i + " to be distributed to node 1");
         msg.acknowledge();
         received++;
      }
      assertEquals(MSG_COUNT, received, "all messages must be distributed to node 1");
      consumer1.close();
      consumerSession.close();

      producer.close();
      producerSession.close();
   }

   private ClusterConnectionImpl getClusterConnectionImpl(int node) {
      return servers[node].getClusterManager()
                          .getClusterConnections()
                          .stream()
                          .filter(cc -> cc instanceof ClusterConnectionImpl)
                          .map(cc -> (ClusterConnectionImpl) cc)
                          .findFirst()
                          .orElseThrow(() -> new IllegalStateException("No ClusterConnectionImpl on node " + node));
   }

   private void resetSessionFactory(int node) {
      if (sfs[node] != null) {
         try {
            closeSessionFactory(node);
         } catch (Exception ignored) {
            sfs[node] = null;
         }
      }
   }
}
