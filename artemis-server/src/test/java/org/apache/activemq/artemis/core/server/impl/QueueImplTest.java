/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.core.server.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.io.IOCallback;
import org.apache.activemq.artemis.core.message.impl.CoreMessage;
import org.apache.activemq.artemis.core.paging.cursor.PageIterator;
import org.apache.activemq.artemis.core.paging.cursor.PageSubscription;
import org.apache.activemq.artemis.core.paging.cursor.PagedReferenceImpl;
import org.apache.activemq.artemis.core.paging.impl.PagedMessageImpl;
import org.apache.activemq.artemis.core.persistence.StorageManager;
import org.apache.activemq.artemis.core.persistence.impl.nullpm.NullStorageManager;
import org.apache.activemq.artemis.core.postoffice.Binding;
import org.apache.activemq.artemis.core.postoffice.PostOffice;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.QueueFactory;
import org.apache.activemq.artemis.core.server.RoutingContext;
import org.apache.activemq.artemis.core.server.cluster.RemoteQueueBinding;
import org.apache.activemq.artemis.core.transaction.Transaction;
import org.apache.activemq.artemis.utils.ExecutorFactory;
import org.apache.activemq.artemis.utils.actors.ArtemisExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class QueueImplTest {

   private static final SimpleString SNF_MESSAGE_ADDRESS = SimpleString.of("myAddress");

   private static final SimpleString SNF_QUEUE_ADDRESS = SimpleString.of("sf.myCluster.target");

   private ExecutorService snfExecutor;

   private NullStorageManager snfStorageManager;

   @AfterEach
   public void cleanupSnFResources() throws Exception {
      if (snfExecutor != null) {
         snfExecutor.shutdownNow();
      }
      if (snfStorageManager != null) {
         snfStorageManager.stop();
      }
   }

   /**
    * When a store-and-forward reference cannot be remapped to the scale-down target (no matching remote queue binding),
    * an anycast message must be re-routed as if newly received and the source reference acknowledged.
    */
   @Test
   public void moveReferencesBetweenSnFQueuesReRoutesAnycastMiss() throws Exception {
      PostOffice postOffice = Mockito.mock(PostOffice.class);
      // no remote queue bindings exist, so every route ID misses its scale-down target
      Mockito.when(postOffice.getAllBindings()).thenAnswer(invocation -> Stream.<Binding>empty());

      QueueImpl snf = createSnFQueue(postOffice);
      snf.addTail(createSnFReference(snf, 1, RoutingType.ANYCAST));

      snf.moveReferencesBetweenSnFQueues(SimpleString.of("non-existent-target"));

      // re-routed as a fresh send with duplicate detection disabled (the original was already routed here) ...
      Mockito.verify(postOffice).route(Mockito.any(Message.class), Mockito.any(Transaction.class), Mockito.eq(false), Mockito.eq(false));
      // ... and the source reference is acknowledged and removed
      assertEquals(0, snf.getMessageCount());
   }

   /**
    * The re-routed copy must carry the application address of the message, not the (internal) SnF queue address,
    * otherwise the fallback would route against the {@code sf.*} address and never reach the real queues.
    */
   @Test
   public void moveReferencesBetweenSnFQueuesReRoutesUsingApplicationAddress() throws Exception {
      PostOffice postOffice = Mockito.mock(PostOffice.class);
      Mockito.when(postOffice.getAllBindings()).thenAnswer(invocation -> Stream.<Binding>empty());

      // the SnF queue address (sf.myCluster.target) is deliberately different from the message application address
      QueueImpl snf = createSnFQueue(postOffice);
      snf.addTail(createSnFReference(snf, 1, RoutingType.ANYCAST));

      snf.moveReferencesBetweenSnFQueues(SimpleString.of("non-existent-target"));

      ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
      Mockito.verify(postOffice).route(captor.capture(), Mockito.any(Transaction.class), Mockito.eq(false), Mockito.eq(false));
      assertEquals(SNF_MESSAGE_ADDRESS, captor.getValue().getAddressSimpleString(),
                   "the re-routed copy must carry the application address, not the SnF queue address");
   }

   /**
    * {@code remoteQueueID} is node-local, so the same id can belong to remote queue bindings toward different nodes.
    * {@code locateTargetBinding} must only consider the binding that belongs to the SnF queue being drained; otherwise a
    * colliding id from another node's binding would resolve the wrong routing name and misroute the message.
    */
   @Test
   public void moveReferencesBetweenSnFQueuesIgnoresCollidingRemoteQueueIdFromAnotherQueue() throws Exception {
      PostOffice postOffice = Mockito.mock(PostOffice.class);
      QueueImpl snf = createSnFQueue(postOffice);

      // the binding that actually belongs to the SnF queue being drained
      RemoteQueueBinding sourceBinding = Mockito.mock(RemoteQueueBinding.class);
      Mockito.when(sourceBinding.getQueue()).thenReturn(snf);
      Mockito.when(sourceBinding.getRemoteQueueID()).thenReturn(42L);
      Mockito.when(sourceBinding.getRoutingName()).thenReturn(SimpleString.of("correct"));

      // a different queue's binding that happens to share the same node-local remote queue id
      Queue otherQueue = Mockito.mock(Queue.class);
      Mockito.when(otherQueue.getName()).thenReturn(SimpleString.of("sf.myCluster.other"));
      RemoteQueueBinding collidingBinding = Mockito.mock(RemoteQueueBinding.class);
      Mockito.when(collidingBinding.getQueue()).thenReturn(otherQueue);
      Mockito.when(collidingBinding.getRemoteQueueID()).thenReturn(42L);
      Mockito.when(collidingBinding.getRoutingName()).thenReturn(SimpleString.of("wrong"));

      // a target on the scale-down node, but only for the colliding binding's (wrong) routing name
      Queue targetQueue = Mockito.mock(Queue.class);
      Mockito.when(targetQueue.getName()).thenReturn(SimpleString.of("sf.myCluster.targetNode"));
      RemoteQueueBinding wrongTarget = Mockito.mock(RemoteQueueBinding.class);
      Mockito.when(wrongTarget.getQueue()).thenReturn(targetQueue);
      Mockito.when(wrongTarget.getRoutingName()).thenReturn(SimpleString.of("wrong"));

      Mockito.when(postOffice.getAllBindings()).thenAnswer(invocation -> Stream.of(sourceBinding, collidingBinding, wrongTarget));
      Mockito.when(postOffice.getAddressInfo(SNF_MESSAGE_ADDRESS)).thenReturn(new AddressInfo(SNF_MESSAGE_ADDRESS, EnumSet.of(RoutingType.ANYCAST)));

      snf.addTail(createSnFReference(snf, 42, RoutingType.ANYCAST));

      snf.moveReferencesBetweenSnFQueues(SimpleString.of("targetNode"));

      // the colliding binding ("wrong") must not resolve a target; the message's real routing name ("correct") has no
      // target, so this is a clean miss handled by the anycast re-route, never a directed route to the wrong target
      Mockito.verify(wrongTarget, Mockito.never()).route(Mockito.any(Message.class), Mockito.any(RoutingContext.class));
      Mockito.verify(postOffice).route(Mockito.any(Message.class), Mockito.any(Transaction.class), Mockito.eq(false), Mockito.eq(false));
   }

   /**
    * A multicast store-and-forward message is only the slice already chosen for the departing node's subscriptions, so
    * on a remap miss it must not be re-routed on the whole address (duplicate risk) and must be left on the source SnF
    * rather than acknowledging a message that was not moved.
    */
   @Test
   public void moveReferencesBetweenSnFQueuesLeavesMulticastMiss() throws Exception {
      PostOffice postOffice = Mockito.mock(PostOffice.class);
      Mockito.when(postOffice.getAllBindings()).thenAnswer(invocation -> Stream.<Binding>empty());

      QueueImpl snf = createSnFQueue(postOffice);
      snf.addTail(createSnFReference(snf, 1, RoutingType.MULTICAST));

      snf.moveReferencesBetweenSnFQueues(SimpleString.of("non-existent-target"));

      Mockito.verify(postOffice, Mockito.never()).route(Mockito.any(Message.class), Mockito.any(Transaction.class), Mockito.anyBoolean(), Mockito.anyBoolean());
      assertEquals(1, snf.getMessageCount());
   }

   /**
    * When the message has no routing type (e.g. an AMQP message sent to a multicast address), the routing type must be
    * resolved from the address: a multicast-capable address must still take the multicast path, leaving the message on
    * the source SnF rather than acknowledging it.
    */
   @Test
   public void moveReferencesBetweenSnFQueuesLeavesMulticastMissWhenRoutingTypeUnset() throws Exception {
      PostOffice postOffice = Mockito.mock(PostOffice.class);
      Mockito.when(postOffice.getAllBindings()).thenAnswer(invocation -> Stream.<Binding>empty());
      Mockito.when(postOffice.getAddressInfo(SNF_MESSAGE_ADDRESS)).thenReturn(new AddressInfo(SNF_MESSAGE_ADDRESS, EnumSet.of(RoutingType.MULTICAST)));

      QueueImpl snf = createSnFQueue(postOffice);
      snf.addTail(createSnFReference(snf, 1, null));

      snf.moveReferencesBetweenSnFQueues(SimpleString.of("non-existent-target"));

      Mockito.verify(postOffice, Mockito.never()).route(Mockito.any(Message.class), Mockito.any(Transaction.class), Mockito.anyBoolean(), Mockito.anyBoolean());
      assertEquals(1, snf.getMessageCount());
   }

   /**
    * When the message has no routing type and the address is anycast-only, the anycast path is used: the message is
    * re-routed and the source reference acknowledged.
    */
   @Test
   public void moveReferencesBetweenSnFQueuesReRoutesAnycastMissWhenRoutingTypeUnset() throws Exception {
      PostOffice postOffice = Mockito.mock(PostOffice.class);
      Mockito.when(postOffice.getAllBindings()).thenAnswer(invocation -> Stream.<Binding>empty());
      Mockito.when(postOffice.getAddressInfo(SNF_MESSAGE_ADDRESS)).thenReturn(new AddressInfo(SNF_MESSAGE_ADDRESS, EnumSet.of(RoutingType.ANYCAST)));

      QueueImpl snf = createSnFQueue(postOffice);
      snf.addTail(createSnFReference(snf, 1, null));

      snf.moveReferencesBetweenSnFQueues(SimpleString.of("non-existent-target"));

      Mockito.verify(postOffice).route(Mockito.any(Message.class), Mockito.any(Transaction.class), Mockito.eq(false), Mockito.eq(false));
      assertEquals(0, snf.getMessageCount());
   }

   /**
    * When the message has no routing type and the address is unknown (no {@code AddressInfo}), the anycast path is used
    * by default: there is nothing left to route to, so re-routing is harmless and the source reference is acknowledged.
    */
   @Test
   public void moveReferencesBetweenSnFQueuesReRoutesWhenRoutingTypeAndAddressUnset() throws Exception {
      PostOffice postOffice = Mockito.mock(PostOffice.class);
      Mockito.when(postOffice.getAllBindings()).thenAnswer(invocation -> Stream.<Binding>empty());
      // getAddressInfo is left returning null (the mock default): the address is unknown

      QueueImpl snf = createSnFQueue(postOffice);
      snf.addTail(createSnFReference(snf, 1, null));

      snf.moveReferencesBetweenSnFQueues(SimpleString.of("non-existent-target"));

      Mockito.verify(postOffice).route(Mockito.any(Message.class), Mockito.any(Transaction.class), Mockito.eq(false), Mockito.eq(false));
      assertEquals(0, snf.getMessageCount());
   }

   private QueueImpl createSnFQueue(PostOffice postOffice) throws Exception {
      snfStorageManager = new NullStorageManager();
      snfStorageManager.start();
      snfExecutor = Executors.newSingleThreadExecutor();
      ArtemisExecutor artemisExecutor = ArtemisExecutor.delegate(snfExecutor);

      ExecutorFactory executorFactory = Mockito.mock(ExecutorFactory.class);
      Mockito.when(executorFactory.getExecutor()).thenReturn(artemisExecutor);
      ActiveMQServer server = Mockito.mock(ActiveMQServer.class);
      Mockito.when(server.getExecutorFactory()).thenReturn(executorFactory);

      return new QueueImpl(QueueConfiguration.of(SNF_QUEUE_ADDRESS).setAddress(SNF_QUEUE_ADDRESS).setRoutingType(RoutingType.MULTICAST).setId(1L).setDurable(false),
                           null, null, null, Mockito.mock(ScheduledExecutorService.class), postOffice, snfStorageManager, null,
                           artemisExecutor, server, Mockito.mock(QueueFactory.class));
   }

   private MessageReference createSnFReference(QueueImpl snf, long id, RoutingType routingType) {
      CoreMessage message = new CoreMessage(id, 512);
      message.setAddress(SNF_MESSAGE_ADDRESS);
      message.setRoutingType(routingType);
      // mirror the cluster bridge: an SnF message carries the remote queue IDs it was routed for
      message.putExtraBytesProperty(Message.HDR_ROUTE_TO_IDS.concat("bridge"), ByteBuffer.allocate(Long.BYTES).putLong(id).array());
      return MessageReference.Factory.createReference(message, snf);
   }

   @Test
   public void deleteAllPagedReferencesTest() throws Exception {
      SimpleString address = SimpleString.of("test");
      AtomicInteger pageIteratorIndex = new AtomicInteger(0);
      PageIterator pageIterator = Mockito.mock(PageIterator.class);
      PageSubscription pageSubscription = Mockito.mock(PageSubscription.class);
      ExecutorService executorService = Executors.newSingleThreadExecutor();
      StorageManager storageManager = Mockito.mock(StorageManager.class);
      ActiveMQServer server = Mockito.mock(ActiveMQServer.class);
      ExecutorFactory executorFactory = Mockito.mock(ExecutorFactory.class);

      final int flushLimit = 100;
      final int pagedReferences = 5 * flushLimit;
      Semaphore availableTransactions = new Semaphore(3);

      //Mock pageIterator.
      Mockito.doAnswer(invocationOnMock -> pageIteratorIndex.get() < pagedReferences).
         when(pageIterator).hasNext();
      Mockito.doAnswer(invocationOnMock -> {
         pageIteratorIndex.incrementAndGet();
         return new PagedReferenceImpl(new PagedMessageImpl(
            Mockito.mock(Message.class), new long[]{0}), pageSubscription);
      }).when(pageIterator).next();
      Mockito.doReturn(pageIterator).when(pageSubscription).iterator();

      //Mock storageManager.
      Mockito.doAnswer(invocationOnMock -> {
         assertTrue(availableTransactions.tryAcquire(3000, TimeUnit.MILLISECONDS),
                           "Too transactions locked on afterCommit.");
         executorService.execute(() -> {
            ((IOCallback) invocationOnMock.getArgument(0)).done();
            availableTransactions.release();
         });
         return null;
      }).when(storageManager).afterCompleteOperations(Mockito.any(IOCallback.class));

      // Mock server
      Mockito.doReturn(executorFactory).when(server).getExecutorFactory();

      QueueImpl queue = new QueueImpl(QueueConfiguration.of(address).setId(0L).setDurable(false), null, null,
                                      pageSubscription, Mockito.mock(ScheduledExecutorService.class), Mockito.mock(PostOffice.class),
                                      storageManager, null, Mockito.mock(ArtemisExecutor.class),
                                      server, Mockito.mock(QueueFactory.class));

      Mockito.doReturn(queue).when(pageSubscription).getQueue();

      assertEquals(pagedReferences, queue.deleteAllReferences(flushLimit));
   }
}