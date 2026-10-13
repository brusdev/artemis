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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.cluster.Bridge;
import org.apache.activemq.artemis.core.server.cluster.ClusterConnection;
import org.apache.activemq.artemis.core.server.cluster.MessageFlowRecord;
import org.apache.activemq.artemis.core.server.cluster.impl.BridgeImpl;
import org.apache.activemq.artemis.core.server.cluster.impl.BridgeImplAccessor;
import org.apache.activemq.artemis.core.server.cluster.impl.ClusterConnectionImpl;
import org.apache.activemq.artemis.core.server.cluster.impl.MessageLoadBalancingType;
import org.apache.activemq.artemis.utils.Wait;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies ownership gates on scale-down SnF delete / removeRecord so a stale
 * bridge cannot destroy a queue adopted by a newer flow record.
 * <p>
 * _Composer on behalf of Domenico Francesco Bruscino_
 */
public class ClusterConnectionSnfScaleDownRaceTest extends ClusterTestBase {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   @Override
   @BeforeEach
   public void setUp() throws Exception {
      super.setUp();
      setupServer(0, isFileStorage(), isNetty());
      setupServer(1, isFileStorage(), isNetty());
      setupServer(2, isFileStorage(), isNetty());

      setupClusterConnection("cluster0", "queues", MessageLoadBalancingType.ON_DEMAND, 1, isNetty(), 0, 1, 2);
      setupClusterConnection("cluster1", "queues", MessageLoadBalancingType.ON_DEMAND, 1, isNetty(), 1, 0, 2);
      setupClusterConnection("cluster2", "queues", MessageLoadBalancingType.ON_DEMAND, 1, isNetty(), 2, 0, 1);
   }

   protected boolean isNetty() {
      return true;
   }

   private ClusterConnectionImpl getClusterConnection(int node) {
      for (ClusterConnection cc : servers[node].getClusterManager().getClusterConnections()) {
         if (cc instanceof ClusterConnectionImpl) {
            return (ClusterConnectionImpl) cc;
         }
      }
      throw new IllegalStateException("No ClusterConnectionImpl on node " + node);
   }

   private String getNodeId(int node) {
      return servers[node].getNodeID().toString();
   }

   @Test
   @Timeout(120)
   public void testStaleScaleDownFailDoesNotRemoveNewerRecordOrSnf() throws Exception {
      startServers(0, 1, 2);

      ClusterConnectionImpl cc0 = getClusterConnection(0);
      String node2Id = getNodeId(2);

      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node2Id);
         return r != null && r.getBridge() != null && r.getBridge().isConnected();
      }, 30_000, 100), "connected bridge required");

      BridgeImpl staleBridge = (BridgeImpl) cc0.getRecords().get(node2Id).getBridge();
      Queue snfQueue = staleBridge.getQueue();
      assertNotNull(snfQueue);
      SimpleString snfName = snfQueue.getName();

      // Install a newer record (6153 recreate) while retaining the stale bridge reference.
      assertTrue(cc0.removeRecord(node2Id, staleBridge, false));
      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node2Id);
         return r != null && r.getBridge() != null && r.getBridge() != staleBridge;
      }, 30_000, 100), "replacement record expected");

      Bridge newBridge = cc0.getRecords().get(node2Id).getBridge();
      logger.debug("Invoking stale scale-down fail");
      BridgeImplAccessor.fail(staleBridge, true, true);

      assertSame(newBridge, cc0.getRecords().get(node2Id).getBridge(),
                 "stale scale-down fail must not remove the current record");
      assertTrue(Wait.waitFor(() -> servers[0].locateQueue(snfName) != null, 10_000, 50),
                 "SnF queue must remain when owned by the newer record");
   }

   @Test
   @Timeout(120)
   public void testScaleDownRemoveRecordDoesNotRecreate() throws Exception {
      startServers(0, 1, 2);

      ClusterConnectionImpl cc0 = getClusterConnection(0);
      String node2Id = getNodeId(2);

      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node2Id);
         return r != null && r.getBridge() != null && r.getBridge().isConnected();
      }, 30_000, 100), "connected bridge required");

      Bridge owning = cc0.getRecords().get(node2Id).getBridge();
      assertTrue(cc0.getTopology().getMember(node2Id) != null);

      synchronized (org.apache.activemq.artemis.core.server.cluster.impl.ClusterConnectionAccessor.getRecordsGuard(cc0)) {
         assertTrue(cc0.removeRecord(node2Id, owning, true));
         assertFalse(cc0.getRecords().containsKey(node2Id),
                     "scale-down removeRecord must not recreate while member is present");
      }
   }

   @Test
   @Timeout(120)
   public void testNonScaleDownRemoveRecordStillRecreates() throws Exception {
      startServers(0, 1, 2);

      ClusterConnectionImpl cc0 = getClusterConnection(0);
      String node2Id = getNodeId(2);

      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node2Id);
         return r != null && r.getBridge() != null && r.getBridge().isConnected();
      }, 30_000, 100), "connected bridge required");

      Bridge owning = cc0.getRecords().get(node2Id).getBridge();
      synchronized (org.apache.activemq.artemis.core.server.cluster.impl.ClusterConnectionAccessor.getRecordsGuard(cc0)) {
         assertTrue(cc0.removeRecord(node2Id, owning, false));
         assertTrue(cc0.getRecords().containsKey(node2Id),
                    "non-scale-down removeRecord must recreate when member is present");
      }
   }
}
