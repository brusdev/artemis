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
import org.apache.activemq.artemis.utils.Wait;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Focused check that anycast SnF relocate falls back to {@code postOffice.route}
 * when the directed target binding is missing (instead of ack-into-void).
 * <p>
 * _Composer on behalf of Domenico Francesco Bruscino_
 */
public class SnfScaleDownRelocateFallbackTest extends ClusterTestBase {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   @Test
   @Timeout(60)
   public void testAnycastFallbackRoutesWhenTargetBindingMissing() throws Exception {
      setupServer(0, true, false);
      startServers(0);
      setupSessionFactory(0, false);

      final SimpleString address = SimpleString.of("fallbackAddr");
      final SimpleString appQueueName = SimpleString.of("fallbackQ");
      final SimpleString snfName = SimpleString.of("$.artemis.internal.sf.cluster.fakeNode");

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

      MessageReference ref = generateReference(snf, 1);
      Message serverMsg = ref.getMessage();
      serverMsg.setAddress(address);
      serverMsg.setDurable(true);
      serverMsg.putStringProperty("payload", "relocated");
      serverMsg.putByteProperty(Message.HDR_ORIG_ROUTING_TYPE, RoutingType.ANYCAST.getType());
      byte[] routeIds = new byte[Long.BYTES];
      ByteBuffer.wrap(routeIds).putLong(0, 9_999_999_999L);
      serverMsg.putBytesProperty(Message.HDR_ROUTE_TO_IDS.toString(), routeIds);

      snf.addTail(ref);
      Wait.assertTrue(() -> snf.getMessageCount() >= 1, 5_000);

      int moved = ((QueueImpl) snf).moveReferencesBetweenSnFQueues(SimpleString.of("otherNode"));
      assertEquals(1, moved, "iterator should visit the SnF message");
      Wait.assertTrue(() -> snf.getMessageCount() == 0, 5_000);

      ClientSession session = sfs[0].createSession(false, true, true);
      session.start();
      ClientConsumer consumer = session.createConsumer(appQueueName);
      ClientMessage received = consumer.receive(5_000);
      assertNotNull(received, "anycast fallback must route to the local application queue");
      assertEquals("relocated", received.getStringProperty("payload"));
      received.acknowledge();
      logger.debug("fallback relocate delivered payload to local queue");
      session.close();
   }
}
