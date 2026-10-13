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
 * Verifies that a stale cluster bridge {@code fail} cannot {@code notifyNodeDown}
 * after a newer flow record already owns the same target node ID.
 * <p>
 * _Composer on behalf of Domenico Francesco Bruscino_
 */
public class ClusterConnectionStaleBridgeNotifyNodeDownTest extends ClusterTestBase {

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
   public void testStaleFailDoesNotNotifyNodeDown() throws Exception {
      startServers(0, 1, 2);

      ClusterConnectionImpl cc0 = getClusterConnection(0);
      String node2Id = getNodeId(2);

      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node2Id);
         return r != null && r.getBridge() != null && r.getBridge().isConnected();
      }, 30_000, 100), "node0 must have a connected bridge for node2");

      MessageFlowRecord originalRecord = cc0.getRecords().get(node2Id);
      Bridge originalBridge = originalRecord.getBridge();
      assertTrue(originalBridge instanceof BridgeImpl);

      // Recreate the flow record while holding a reference to the old bridge (stale).
      cc0.removeRecord(node2Id);

      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node2Id);
         return r != null && r.getBridge() != null && r.getBridge() != originalBridge;
      }, 30_000, 100), "removeRecord must install a replacement bridge while node2 is in topology");

      Bridge newBridge = cc0.getRecords().get(node2Id).getBridge();
      assertNotNull(cc0.getTopology().getMember(node2Id));

      logger.debug("Invoking stale fail on replaced bridge; topology member must remain");
      BridgeImplAccessor.fail((BridgeImpl) originalBridge, true, true);

      assertNotNull(cc0.getTopology().getMember(node2Id),
                    "stale fail must not notifyNodeDown a recovered topology member");
      assertSame(newBridge, cc0.getRecords().get(node2Id).getBridge(),
                 "stale fail must not replace the current flow-record bridge");
   }

   @Test
   @Timeout(120)
   public void testOwningBridgeStillAuthorizedToNotifyNodeDown() throws Exception {
      startServers(0, 1, 2);

      ClusterConnectionImpl cc0 = getClusterConnection(0);
      String node2Id = getNodeId(2);

      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node2Id);
         return r != null && r.getBridge() != null && r.getBridge().isConnected();
      }, 30_000, 100), "node0 must have a connected bridge for node2");

      BridgeImpl owningBridge = (BridgeImpl) cc0.getRecords().get(node2Id).getBridge();
      assertTrue(BridgeImplAccessor.shouldNotifyNodeDownOnFail(owningBridge),
                 "owning bridge must remain authorized to notifyNodeDown");

      cc0.removeRecord(node2Id);
      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node2Id);
         return r != null && r.getBridge() != null && r.getBridge() != owningBridge;
      }, 30_000, 100), "replacement bridge expected");

      assertFalse(BridgeImplAccessor.shouldNotifyNodeDownOnFail(owningBridge),
                  "replaced bridge must not notifyNodeDown");
      assertTrue(BridgeImplAccessor.shouldNotifyNodeDownOnFail(
                    (BridgeImpl) cc0.getRecords().get(node2Id).getBridge()),
                 "replacement bridge must be authorized to notifyNodeDown");
   }
}
