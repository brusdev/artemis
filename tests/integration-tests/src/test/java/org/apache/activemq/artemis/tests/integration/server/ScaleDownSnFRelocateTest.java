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
package org.apache.activemq.artemis.tests.integration.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.core.postoffice.impl.LocalQueueBinding;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.cluster.impl.MessageLoadBalancingType;
import org.apache.activemq.artemis.core.server.impl.QueueImpl;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.tests.integration.cluster.distribution.ClusterTestBase;
import org.apache.activemq.artemis.utils.Wait;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage of the store-and-forward (SnF) scale-down relocate fallback in
 * {@link QueueImpl#moveReferencesBetweenSnFQueues(SimpleString)}: when the scale-down target binding is missing, an
 * anycast message is re-routed as if newly received.
 * <p>
 * This exercises the parts that cannot be covered with mocks: the SnF message really carries the application address
 * (so the re-route reaches the real queue), and duplicate detection is disabled on the re-route (the message was
 * already routed through this node when produced, so its duplicate id is cached and would otherwise reject the
 * relocation, losing the message).
 */
public class ScaleDownSnFRelocateTest extends ClusterTestBase {

   private static final String CLUSTER_NAME = "cluster0";
   private static final String ADDRESS = "testAddress";
   private static final String QUEUE = "testQueue";

   @Override
   @BeforeEach
   public void setUp() throws Exception {
      super.setUp();
      setupPrimaryServer(0, isFileStorage(), isNetty(), true);
      setupPrimaryServer(1, isFileStorage(), isNetty(), true);
      setupClusterConnection(CLUSTER_NAME, ADDRESS, MessageLoadBalancingType.ON_DEMAND, 1, isNetty(), 0, 1);
      setupClusterConnection(CLUSTER_NAME, ADDRESS, MessageLoadBalancingType.ON_DEMAND, 1, isNetty(), 1, 0);
      servers[0].getConfiguration().getAddressSettings().put("#", new AddressSettings().setRedistributionDelay(0));
      servers[1].getConfiguration().getAddressSettings().put("#", new AddressSettings().setRedistributionDelay(0));
      startServers(0, 1);
      setupSessionFactory(0, isNetty());
      setupSessionFactory(1, isNetty());
   }

   protected boolean isNetty() {
      return true;
   }

   @Test
   public void testAnycastRelocateMissReRoutesToApplicationAddressWithDuplicateDetection() throws Exception {
      final int messageCount = 10;

      createQueue(0, ADDRESS, QUEUE, null, true, RoutingType.ANYCAST);
      createQueue(1, ADDRESS, QUEUE, null, true, RoutingType.ANYCAST);

      // a consumer only on node 1 so ON_DEMAND routes node 0 sends into the SnF queue toward node 1
      addConsumer(0, 1, QUEUE, null);
      waitForBindings(0, ADDRESS, 1, 1, false);

      // pause the SnF queue so the bridge cannot forward: messages accumulate there instead of reaching node 1
      Queue snf = getSnFQueue(servers[0], servers[1]);
      snf.pause();

      // send with duplicate detection ids: this populates node 0's duplicate id cache at produce time
      AtomicInteger duplicateDetectionSeq = new AtomicInteger(0);
      send(0, ADDRESS, messageCount, true, null, RoutingType.ANYCAST, duplicateDetectionSeq);
      Wait.assertEquals(messageCount, () -> getMessageCount(snf), 5000);

      // fence the departing node's binding exactly as the scale-down drain does before moving the references
      servers[0].getClusterManager().getClusterConnection(CLUSTER_NAME).disableRecord(servers[1].getNodeID().toString());

      // give node 0's local queue demand so the anycast re-route has somewhere to land
      addConsumer(1, 0, QUEUE, null);
      waitForBindings(0, ADDRESS, 1, 1, true);

      // relocate toward a node that has no binding -> every reference misses -> anycast re-route fallback
      ((QueueImpl) snf).moveReferencesBetweenSnFQueues(SimpleString.of("non-existent-target"));

      // all messages must be delivered to node 0's local queue: proves the re-route used the application address and
      // that duplicate detection did not reject the relocation despite the ids cached at produce time
      for (int i = 0; i < messageCount; i++) {
         ClientMessage message = consumers[1].getConsumer().receive(5000);
         assertNotNull(message, "message " + i + " should be re-routed to the local queue on node 0");
         message.acknowledge();
      }

      Wait.assertEquals(0, () -> getMessageCount(snf), 5000);
   }

   private Queue getSnFQueue(ActiveMQServer server, ActiveMQServer targetServer) {
      String snfPrefix = server.getInternalNamingPrefix() + "sf.";
      String targetNodeID = targetServer.getNodeID().toString();
      return server.getPostOffice().getAllBindings()
         .filter(LocalQueueBinding.class::isInstance)
         .map(LocalQueueBinding.class::cast)
         .map(LocalQueueBinding::getQueue)
         .filter(queue -> queue.getAddress().toString().startsWith(snfPrefix) && queue.getAddress().toString().endsWith(targetNodeID))
         .findFirst()
         .orElseThrow(() -> new IllegalStateException("No SnF queue toward " + targetNodeID));
   }
}
