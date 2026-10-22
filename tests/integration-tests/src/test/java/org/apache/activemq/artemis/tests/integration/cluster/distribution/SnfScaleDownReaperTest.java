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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.ScaleDownConfiguration;
import org.apache.activemq.artemis.core.config.ha.PrimaryOnlyPolicyConfiguration;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.cluster.ClusterConnection;
import org.apache.activemq.artemis.core.server.cluster.MessageFlowRecord;
import org.apache.activemq.artemis.core.server.cluster.impl.ClusterConnectionImpl;
import org.apache.activemq.artemis.core.server.cluster.impl.MessageLoadBalancingType;
import org.apache.activemq.artemis.core.server.impl.QueueImpl;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.utils.Wait;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies the lazy SnF reaper: on scale-down teardown the SnF queue toward the departing node is
 * retained (not deleted inline, which would race the deferred addTail of an in-flight route), and a
 * periodic reaper removes it once it is provably idle, relocating any residual to the scale-down
 * target first.
 * <p>
 * See tmp/snf-issues/snf-scaledown-deletequeue-inflight-ref-race.md and
 * snf-scaledown-reaper-implementation.md.
 */
public class SnfScaleDownReaperTest extends ClusterTestBase {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   private static final String ADDRESS = "reaperQueue";

   @Override
   @BeforeEach
   public void setUp() throws Exception {
      super.setUp();

      for (int i = 0; i < 2; i++) {
         setupPrimaryServer(i, isFileStorage(), isNetty(), true);
         servers[i].getConfiguration().getAddressSettings().put("#", new AddressSettings().setRedistributionDelay(0));
      }

      for (int i = 0; i < 2; i++) {
         int other = 1 - i;
         setupClusterConnection("cluster0", ADDRESS, MessageLoadBalancingType.ON_DEMAND, 1, isNetty(), i, other);

         PrimaryOnlyPolicyConfiguration haPolicy =
            (PrimaryOnlyPolicyConfiguration) servers[i].getConfiguration().getHAPolicyConfiguration();
         ScaleDownConfiguration scaleDownConfiguration = new ScaleDownConfiguration();
         scaleDownConfiguration.getConnectors().addAll(
            servers[i].getConfiguration().getClusterConfigurations().get(0).getStaticConnectors());
         haPolicy.setScaleDownConfiguration(scaleDownConfiguration);
      }
   }

   protected boolean isNetty() {
      return true;
   }

   private ClusterConnectionImpl getClusterConnection(int node) {
      for (ClusterConnection cc : servers[node].getClusterManager().getClusterConnections()) {
         if (cc instanceof ClusterConnectionImpl impl) {
            return impl;
         }
      }
      throw new IllegalStateException("No ClusterConnectionImpl on node " + node);
   }

   private SimpleString awaitConnectedSnf(ClusterConnectionImpl cc0, String node1Id) {
      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node1Id);
         return r != null && r.getBridge() != null && r.getBridge().isConnected();
      }, 30_000, 100));
      Queue snf = cc0.getRecords().get(node1Id).getBridge().getQueue();
      assertNotNull(snf);
      assertTrue(snf instanceof QueueImpl);
      return snf.getName();
   }

   /**
    * (a) retain-on-teardown + (b) reap-empty-idle: after scale-down the empty SnF queue is NOT
    * deleted inline (retained past the reaper's initial-delay window), then the reaper removes it.
    */
   @Test
   @Timeout(120)
   public void testRetainThenReapEmpty() throws Exception {
      startServers(0, 1);

      ClusterConnectionImpl cc0 = getClusterConnection(0);
      String node1Id = servers[1].getNodeID().toString();
      SimpleString snfName = awaitConnectedSnf(cc0, node1Id);

      assertTrue(((QueueImpl) servers[0].locateQueue(snfName)).isEmptyForScaleDown());

      // Scale node 1 down to node 0.
      stopServers(1);

      // (a) retained: the SnF queue is not deleted inline on teardown. The reaper's initial delay is
      // the reaper period (5s), so it must still be present shortly after teardown.
      assertFalse(Wait.waitFor(() -> servers[0].locateQueue(snfName) == null, 2_500, 100),
                  "SnF queue must be retained on scale-down teardown, not deleted inline");

      // (b) reaped: once idle across the grace interval the reaper removes the empty SnF queue.
      Wait.assertTrue(() -> servers[0].locateQueue(snfName) == null, 30_000, 200);
      logger.debug("SnF {} retained on teardown then reaped once idle", snfName);
   }

   /**
    * (c) relocate-non-empty: a residual left in the SnF queue at teardown is relocated by the reaper
    * to the recorded scale-down target (not destroyed), then the emptied queue is reaped.
    *
    * TODO: deterministically parking a residual in the SnF queue at teardown (bridge forwarded but
    * unconfirmed, or ingress fenced mid-flight) requires controlling the cluster bridge send/confirm
    * cycle; wire this up with a controllable bridge or a 3-node relocate target. The relocate path
    * itself ({@code QueueImpl.moveReferencesBetweenSnFQueues}) is exercised by the soak test
    * (ScaleDownSoakTest) today.
    */
   @Test
   @Timeout(120)
   @Disabled("TODO: deterministic non-empty-SnF-at-teardown setup; relocate path covered by soak test")
   public void testRelocateNonEmptyResidual() throws Exception {
   }

   /**
    * (d) retain-on-plain-disconnect: a NON-empty SnF queue of a node that dropped without scale-down
    * (no cached target) is retained for reconnection rather than reaped. (Empty SnF queues are reaped
    * regardless, as that is always safe and the queue is recreated on reconnect.)
    *
    * TODO: needs the same deterministic non-empty-SnF setup as (c).
    */
   @Test
   @Timeout(120)
   @Disabled("TODO: deterministic non-empty-SnF setup")
   public void testRetainNonEmptyOnPlainDisconnect() throws Exception {
   }

   /**
    * (e) reuse-race: claim a queue for reaping, then drive createFlowRecord for the same nodeID;
    * assert it does not reuse the reaping queue / cancels the reap, with no refs lost.
    *
    * TODO: drive the reaper claim and a concurrent nodeUP deterministically.
    */
   @Test
   @Timeout(120)
   @Disabled("TODO: deterministic reaper-claim vs createFlowRecord interleaving")
   public void testReuseRaceDoesNotLoseQueue() throws Exception {
   }
}
