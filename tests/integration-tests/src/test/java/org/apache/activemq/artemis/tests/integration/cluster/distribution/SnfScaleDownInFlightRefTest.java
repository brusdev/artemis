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
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.impl.QueueImpl;
import org.apache.activemq.artemis.tests.unit.core.server.impl.fakes.FakeConsumer;
import org.apache.activemq.artemis.utils.Wait;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Focused check that a message which is <em>in flight</em> on an SnF (store-and-forward) bridge
 * when the target node drops is not stranded during scale-down.
 * <p>
 * An in-flight reference is one the bridge started forwarding (so it was moved out of the queue's
 * deliverable references and is held, un-acked, by the bridge consumer) but never got confirmation
 * for before the connection failed. It is still counted by {@link Queue#getMessageCount()} but is
 * invisible to {@link QueueImpl#moveReferencesBetweenSnFQueues(SimpleString)} (which iterates only
 * deliverable references). Before the fix, {@code BridgeImpl.scaleDown()} drained <em>before</em>
 * returning its in-flight references to the queue, so it could never move them, gave up, and left
 * the message stranded on a queue targeting the departing node.
 * <p>
 * This test reproduces the invisibility invariant with a {@link FakeConsumer} standing in for the
 * bridge, and then verifies that once the in-flight reference is cancelled back to the queue (which
 * is what {@code BridgeImpl.scaleDown()} now does via {@code cancelRefs()} <em>before</em> draining)
 * the relocate drains it and the message is delivered to the local application queue.
 * <p>
 * _Claude Opus 4.8 on behalf of Domenico Francesco Bruscino_
 */
public class SnfScaleDownInFlightRefTest extends ClusterTestBase {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   @Test
   @Timeout(60)
   public void testInFlightReferenceIsRelocatedOnScaleDown() throws Exception {
      setupServer(0, true, false);
      startServers(0);
      setupSessionFactory(0, false);

      final SimpleString address = SimpleString.of("inflightAddr");
      final SimpleString appQueueName = SimpleString.of("inflightQ");
      final SimpleString snfName = SimpleString.of("$.artemis.internal.sf.cluster.departingNode");

      servers[0].createQueue(QueueConfiguration.of(appQueueName)
         .setAddress(address)
         .setRoutingType(RoutingType.ANYCAST)
         .setDurable(true));
      assertNotNull(servers[0].locateQueue(appQueueName));

      Queue snf = servers[0].createQueue(QueueConfiguration.of(snfName)
         .setAddress(snfName)
         .setRoutingType(RoutingType.MULTICAST)
         .setAutoCreateAddress(true)
         .setMaxConsumers(-1)
         .setPurgeOnNoConsumers(false)
         .setInternal(true));
      snf.setInternalQueue(true);
      final QueueImpl snfImpl = (QueueImpl) snf;

      // Build an SnF message whose directed target binding is missing, so relocate falls back to
      // postOffice.route and delivers to the local application queue (same address).
      MessageReference ref = generateReference(snf, 1);
      Message serverMsg = ref.getMessage();
      serverMsg.setAddress(address);
      serverMsg.setDurable(true);
      serverMsg.putStringProperty("payload", "inflight");
      serverMsg.putByteProperty(Message.HDR_ORIG_ROUTING_TYPE, RoutingType.ANYCAST.getType());
      byte[] routeIds = new byte[Long.BYTES];
      ByteBuffer.wrap(routeIds).putLong(0, 9_999_999_999L);
      serverMsg.putBytesProperty(Message.HDR_ROUTE_TO_IDS.toString(), routeIds);

      snf.addTail(ref);
      Wait.assertTrue(() -> snf.getMessageCount() >= 1, 5_000);

      // Put the reference "in flight": a FakeConsumer (standing in for the bridge) receives it and
      // holds it without acking, exactly like a bridge that forwarded but never got confirmation.
      FakeConsumer bridgeStandIn = new FakeConsumer();
      snf.addConsumer(bridgeStandIn);
      snf.deliverAsync();
      Wait.assertTrue(() -> bridgeStandIn.getReferences().size() == 1, 5_000);
      Wait.assertEquals(1, snf::getDeliveringCount, 5_000);

      // Reproduce the root cause: the in-flight reference is counted but invisible to the drain.
      assertFalse(snfImpl.isEmptyForScaleDown(),
         "in-flight reference must still count toward the queue (scale-down must not treat it as empty)");
      assertEquals(0, snfImpl.moveReferencesBetweenSnFQueues(SimpleString.of("otherNode")),
         "the drain cannot see an in-flight reference held by the bridge");
      assertFalse(snfImpl.isEmptyForScaleDown(),
         "the in-flight reference is still present after a drain that could not move it");

      // Apply what the fixed BridgeImpl.scaleDown() does BEFORE draining: return in-flight
      // references to the queue (cancelRefs -> queue.cancel), then drain.
      snf.removeConsumer(bridgeStandIn);
      for (MessageReference held : bridgeStandIn.getReferences()) {
         snf.cancel(held, System.currentTimeMillis());
      }
      bridgeStandIn.clearReferences();

      int moved = snfImpl.moveReferencesBetweenSnFQueues(SimpleString.of("otherNode"));
      assertEquals(1, moved, "after cancelRefs the in-flight reference becomes relocatable");
      Wait.assertTrue(snfImpl::isEmptyForScaleDown, 5_000);
      Wait.assertEquals(0L, snf::getMessageCount, 5_000);

      // The relocated message must be delivered, not lost.
      ClientSession session = sfs[0].createSession(false, true, true);
      session.start();
      ClientConsumer consumer = session.createConsumer(appQueueName);
      ClientMessage received = consumer.receive(5_000);
      assertNotNull(received, "the previously in-flight message must be relocated to the local queue, not lost");
      assertEquals("inflight", received.getStringProperty("payload"));
      received.acknowledge();
      logger.debug("in-flight SnF reference relocated and delivered after scale-down drain");
      session.close();
   }
}
