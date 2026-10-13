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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

import org.apache.activemq.artemis.core.client.impl.TopologyMemberImpl;
import org.apache.activemq.artemis.core.server.cluster.Bridge;
import org.apache.activemq.artemis.core.server.cluster.MessageFlowRecord;
import org.apache.activemq.artemis.core.server.cluster.impl.BridgeImpl;
import org.apache.activemq.artemis.core.server.cluster.impl.ClusterConnectionBridge;
import org.apache.activemq.artemis.core.server.cluster.impl.ClusterConnectionImpl;
import org.apache.activemq.artemis.core.server.cluster.impl.MessageLoadBalancingType;
import org.apache.activemq.artemis.tests.util.Wait;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A delayed permanent fail() from a replaced cluster bridge must not wipe the live
 * replacement bridge / topology member for the same node ID.
 *
 * <p>_Composer on behalf of Domenico Francesco Bruscino_
 */
public class StaleScaleDownBridgeFailTest extends ClusterTestBase {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   protected boolean isNetty() {
      return true;
   }

   @Test
   @Timeout(60)
   public void testStaleFailDoesNotWipeReplacementBridge() throws Exception {
      setupServer(0, isFileStorage(), isNetty());
      setupServer(1, isFileStorage(), isNetty());

      setupClusterConnection("cluster0", "queues", MessageLoadBalancingType.ON_DEMAND, 1, isNetty(), 0, 1);
      setupClusterConnection("cluster0", "queues", MessageLoadBalancingType.ON_DEMAND, 1, isNetty(), 1, 0);

      startServers(0, 1);
      waitForTopology(servers[0], 2);
      waitForTopology(servers[1], 2);

      String node1Id = servers[1].getNodeID().toString();
      ClusterConnectionImpl cc0 = clusterConnection(0);

      Wait.assertTrue(() -> bridgeConnected(cc0, node1Id), 30_000);

      MessageFlowRecord oldRecord = cc0.getRecords().get(node1Id);
      assertNotNull(oldRecord);
      Bridge oldBridge = oldRecord.getBridge();
      assertNotNull(oldBridge);
      assertTrue(oldBridge instanceof ClusterConnectionBridge);

      // Replace the live record the same way a rejoin does after a dead/closed locator.
      ((BridgeImpl) oldBridge).getServerLocator().close();
      TopologyMemberImpl member = (TopologyMemberImpl) cc0.getTopology().getMember(node1Id);
      assertNotNull(member);
      member.setUniqueEventID(System.currentTimeMillis() + 1_000);
      cc0.nodeUP(member, true);

      Wait.assertTrue(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node1Id);
         return r != null && r.getBridge() != null && r.getBridge() != oldBridge && r.getBridge().isConnected();
      }, 30_000);

      MessageFlowRecord newRecord = cc0.getRecords().get(node1Id);
      Bridge newBridge = newRecord.getBridge();
      assertNotSame(oldBridge, newBridge);
      assertFalse(cc0.isCurrentRecord(node1Id, oldBridge));
      assertTrue(cc0.isCurrentRecord(node1Id, newBridge));

      Method fail = ClusterConnectionBridge.class.getDeclaredMethod("fail", boolean.class, boolean.class);
      fail.setAccessible(true);
      fail.invoke(oldBridge, true, true);

      Wait.assertTrue(() -> "stale fail must not wipe live bridge/topology; " + describe(cc0), () -> {
         MessageFlowRecord r = cc0.getRecords().get(node1Id);
         return r != null && r.getBridge() == newBridge && bridgeConnected(cc0, node1Id)
            && cc0.getTopology().getMember(node1Id) != null;
      }, 10_000, 50);

      logger.debug("stale fail ignored successfully: {}", describe(cc0));
   }

   private ClusterConnectionImpl clusterConnection(int node) {
      return (ClusterConnectionImpl) servers[node].getClusterManager().getClusterConnections().iterator().next();
   }

   private boolean bridgeConnected(ClusterConnectionImpl cc, String toNodeId) {
      MessageFlowRecord record = cc.getRecords().get(toNodeId);
      return record != null && record.getBridge() != null && record.getBridge().isConnected();
   }

   private String describe(ClusterConnectionImpl cc) {
      StringBuilder sb = new StringBuilder("records={");
      cc.getRecords().forEach((id, record) -> {
         boolean connected = record.getBridge() != null && record.getBridge().isConnected();
         sb.append(id).append(":[connected=").append(connected)
            .append(", bridge=").append(System.identityHashCode(record.getBridge())).append("] ");
      });
      sb.append("} topology={");
      cc.getTopology().getMembers().forEach(m -> sb.append(m.getNodeId()).append(' '));
      sb.append('}');
      return sb.toString();
   }
}
