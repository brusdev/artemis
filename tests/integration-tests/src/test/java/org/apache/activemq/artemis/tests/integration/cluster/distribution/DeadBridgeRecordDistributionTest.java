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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;

import org.apache.activemq.artemis.api.core.client.TopologyMember;
import org.apache.activemq.artemis.core.server.cluster.Bridge;
import org.apache.activemq.artemis.core.server.cluster.ClusterConnection;
import org.apache.activemq.artemis.core.server.cluster.MessageFlowRecord;
import org.apache.activemq.artemis.core.server.cluster.impl.BridgeImpl;
import org.apache.activemq.artemis.core.server.cluster.impl.ClusterConnectionImpl;
import org.apache.activemq.artemis.core.server.cluster.impl.MessageLoadBalancingType;
import org.apache.activemq.artemis.utils.Wait;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies that {@code nodeUP} replaces a mapped flow record whose bridge locator
 * is closed (AMQ222100-class stuck state), without thrashing a live not-yet-connected
 * replacement bridge.
 * <p>
 * _Composer on behalf of Domenico Francesco Bruscino_
 */
public class DeadBridgeRecordDistributionTest extends ClusterTestBase {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   @Override
   @BeforeEach
   public void setUp() throws Exception {
      super.setUp();
      setupServer(0, isFileStorage(), isNetty());
      setupServer(1, isFileStorage(), isNetty());

      setupClusterConnection("cluster0", "queues", MessageLoadBalancingType.ON_DEMAND, 1, isNetty(), 0, 1);
      setupClusterConnection("cluster1", "queues", MessageLoadBalancingType.ON_DEMAND, 1, isNetty(), 1, 0);
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
   public void testNodeUpReplacesRecordWithClosedLocator() throws Exception {
      startServers(0, 1);

      ClusterConnectionImpl cc0 = getClusterConnection(0);
      String node1Id = getNodeId(1);

      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node1Id);
         return r != null && r.getBridge() != null && r.getBridge().isConnected();
      }, 30_000, 100), "connected bridge required");

      BridgeImpl deadBridge = (BridgeImpl) cc0.getRecords().get(node1Id).getBridge();
      TopologyMember member = cc0.getTopology().getMember(node1Id);
      assertNotNull(member);

      logger.debug("Closing bridge locator to simulate AMQ222100 stuck record");
      deadBridge.getServerLocator().close();
      assertTrue(deadBridge.getServerLocator().isClosed());
      assertTrue(cc0.getRecords().containsKey(node1Id));

      cc0.nodeUP(member, true);

      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node1Id);
         return r != null && r.getBridge() != null && r.getBridge() != deadBridge && r.getBridge().isConnected();
      }, 30_000, 100), "nodeUP must replace dead record and connect a new bridge");
   }

   @Test
   @Timeout(120)
   public void testNodeUpDoesNotReplaceLiveConnectedBridge() throws Exception {
      startServers(0, 1);

      ClusterConnectionImpl cc0 = getClusterConnection(0);
      String node1Id = getNodeId(1);

      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node1Id);
         return r != null && r.getBridge() != null && r.getBridge().isConnected();
      }, 30_000, 100), "connected bridge required");

      TopologyMember member = cc0.getTopology().getMember(node1Id);
      assertNotNull(member);
      Bridge live = cc0.getRecords().get(node1Id).getBridge();
      assertTrue(live instanceof BridgeImpl);
      assertTrue(!((BridgeImpl) live).getServerLocator().isClosed());

      cc0.nodeUP(member, true);
      assertSame(live, cc0.getRecords().get(node1Id).getBridge(),
                 "nodeUP must not replace a live connected bridge");
   }
}
