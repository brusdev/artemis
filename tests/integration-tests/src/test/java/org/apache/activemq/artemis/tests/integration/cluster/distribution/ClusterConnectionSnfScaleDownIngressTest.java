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

import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.core.config.ScaleDownConfiguration;
import org.apache.activemq.artemis.core.config.ha.PrimaryOnlyPolicyConfiguration;
import org.apache.activemq.artemis.core.postoffice.Binding;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.cluster.ClusterConnection;
import org.apache.activemq.artemis.core.server.cluster.MessageFlowRecord;
import org.apache.activemq.artemis.core.server.cluster.RemoteQueueBinding;
import org.apache.activemq.artemis.core.server.cluster.impl.ClusterConnectionImpl;
import org.apache.activemq.artemis.core.server.cluster.impl.MessageLoadBalancingType;
import org.apache.activemq.artemis.core.server.impl.QueueImpl;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.utils.Wait;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies SnF scale-down ingress fencing ({@code RemoteQueueBinding.enabled}),
 * empty-before-delete, and binding reuse re-enable.
 * <p>
 * _Composer on behalf of Domenico Francesco Bruscino_
 */
public class ClusterConnectionSnfScaleDownIngressTest extends ClusterTestBase {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   private static final String ADDRESS = "ingressQueue";
   private static final String QUEUE = "ingressQueue";

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
         if (cc instanceof ClusterConnectionImpl) {
            return (ClusterConnectionImpl) cc;
         }
      }
      throw new IllegalStateException("No ClusterConnectionImpl on node " + node);
   }

   @Test
   @Timeout(120)
   public void testDisabledBindingsAreNotSelectedAndReuseReenables() throws Exception {
      startServers(0, 1);
      setupSessionFactory(0, isNetty());
      setupSessionFactory(1, isNetty());

      createQueue(0, ADDRESS, QUEUE, null, true, RoutingType.ANYCAST);
      createQueue(1, ADDRESS, QUEUE, null, true, RoutingType.ANYCAST);

      waitForBindings(0, ADDRESS, 1, 0, true);
      waitForBindings(1, ADDRESS, 1, 0, true);
      waitForBindings(0, ADDRESS, 1, 0, false);
      waitForBindings(1, ADDRESS, 1, 0, false);

      ClusterConnectionImpl cc0 = getClusterConnection(0);
      String node1Id = servers[1].getNodeID().toString();

      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node1Id);
         return r != null && r.getBridge() != null && r.getBridge().isConnected();
      }, 30_000, 100));

      ClientSession session1 = sfs[1].createSession(false, true, true);
      session1.start();
      ClientConsumer consumer1 = session1.createConsumer(QUEUE);
      waitForBindings(0, ADDRESS, 1, 1, false);

      RemoteQueueBinding remote = null;
      for (Binding binding : servers[0].getPostOffice().getBindingsForAddress(SimpleString.of(ADDRESS)).getBindings()) {
         if (binding instanceof RemoteQueueBinding r) {
            remote = r;
            break;
         }
      }
      assertNotNull(remote);
      assertTrue(remote.isEnabled());

      remote.setEnabled(false);
      assertFalse(remote.isEnabled());

      ClientSession session0 = sfs[0].createSession(false, true, true);
      ClientProducer producer = session0.createProducer(ADDRESS);
      ClientConsumer consumer0 = session0.createConsumer(QUEUE);
      session0.start();
      producer.send(session0.createMessage(true));

      ClientMessage msg = consumer0.receive(5_000);
      assertNotNull(msg, "local consumer should receive when remote binding is disabled");
      msg.acknowledge();

      remote.connect();
      assertTrue(remote.isEnabled(), "connect() must re-enable a reused remote binding");

      consumer0.close();
      consumer1.close();
      session0.close();
      session1.close();
   }

   @Test
   @Timeout(120)
   public void testEmptySnfStillRemovedOnScaleDown() throws Exception {
      startServers(0, 1);

      ClusterConnectionImpl cc0 = getClusterConnection(0);
      String node1Id = servers[1].getNodeID().toString();

      assertTrue(Wait.waitFor(() -> {
         MessageFlowRecord r = cc0.getRecords().get(node1Id);
         return r != null && r.getBridge() != null && r.getBridge().isConnected();
      }, 30_000, 100));

      Queue snf = cc0.getRecords().get(node1Id).getBridge().getQueue();
      assertTrue(snf instanceof QueueImpl);
      assertTrue(((QueueImpl) snf).isEmptyForScaleDown());
      SimpleString snfName = snf.getName();

      stopServers(1);

      Wait.assertTrue(() -> servers[0].locateQueue(snfName) == null, 30_000);
      logger.debug("SnF {} removed after empty scale-down", snfName);
   }
}
