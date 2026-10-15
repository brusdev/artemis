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
package org.apache.activemq.artemis.tests.soak.clusterRedistribution;

import static org.apache.activemq.artemis.utils.TestParameters.testProperty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.activemq.artemis.api.core.management.SimpleManagement;
import org.apache.activemq.artemis.cli.commands.helper.HelperCreate;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.tests.soak.SoakTestBase;
import org.apache.activemq.artemis.util.ServerUtil;
import org.apache.activemq.artemis.utils.RandomUtil;
import org.apache.activemq.artemis.utils.Wait;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterRedistributionSoakTest extends SoakTestBase {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   private static final String TEST_NAME = "CLUSTER_REDISTRIBUTION";
   private static final boolean TEST_ENABLED = Boolean.parseBoolean(testProperty(TEST_NAME, "TEST_ENABLED", "true"));
   private static final int NUMBER_OF_SERVERS = testProperty(TEST_NAME, "NUMBER_OF_SERVERS", 8);
   private static final String MESSAGE_LOAD_BALANCING = testProperty(TEST_NAME, "MESSAGE_LOAD_BALANCING", "ON_DEMAND");
   private static final int TIMEOUT_MINUTES = testProperty(TEST_NAME, "TIMEOUT_MINUTES", 5);
   private static final int CLIENT_RECONNECT_INTERVAL_MS = testProperty(TEST_NAME, "CLIENT_RECONNECT_INTERVAL_MS", 2000);

   private static final String QUEUE_NAME = "TEST_REDIST";
   private static final String SERVER_NAME_BASE = "clusterRedistribution/broker-";
   private static final int SERVER_PORT_BASE = 61616;

   static class ServerRecord {
      String serverName;
      File serverLocation;
      File brokerProperties;
      Process process;
      int serverId;
      private ReadWriteLock readWriteLock = new ReentrantReadWriteLock(true);
      AtomicInteger startNumber = new AtomicInteger();

      ServerRecord(File serverLocation, String serverName, int id) {
         this.serverLocation = serverLocation;
         this.brokerProperties = new File(serverLocation, "broker.properties");
         this.serverName = serverName;
         this.serverId = id;
      }

      public void startServer() throws Exception {
         process = ServerUtil.startServer(serverLocation.getAbsolutePath(), serverName, 0, 0, brokerProperties, null);
         startNumber.incrementAndGet();
      }

      public void readLock() {
         readWriteLock.readLock().lock();
      }

      public void readUnlock() {
         readWriteLock.readLock().unlock();
      }

      public void restartServer(boolean forcibly) throws Exception {
         readWriteLock.writeLock().lock();
         try {
            if (!forcibly) {
               stopServerWithFile(serverLocation.getAbsolutePath(), process, 30, TimeUnit.SECONDS);
            } else {
               process.destroyForcibly();
            }
            process = null;
            startServer();
            ServerUtil.waitForServerToStart(serverId, 10_000);
         } finally {
            readWriteLock.writeLock().unlock();
         }
      }

      public void stopServer() {
         try {
            if (process != null) {
               ServerUtil.killServer(process);
            }
         } catch (Throwable e) {
            logger.warn("Error stopping server {}, message={}", serverName, e.getMessage(), e);
         }
      }

      private String serverURI() {
         return "tcp://localhost:" + (SERVER_PORT_BASE + serverId) + "?ha=false&useTopologyForLoadBalancing=false";
      }

   }


   private static final ServerRecord[] serverRecords = new ServerRecord[NUMBER_OF_SERVERS];

   @BeforeAll
   public static void createServers() throws Exception {
      for (int s = 0; s < NUMBER_OF_SERVERS; s++) {
         String staticClusterURI = buildStaticClusterURI(s);

         String serverName = SERVER_NAME_BASE + s;

         File serverLocation = getFileServerLocation(serverName);
         deleteDirectory(serverLocation);

         HelperCreate cliCreateServer = helperCreate();
         cliCreateServer
            .setRole("amq")
            .setUser("admin")
            .setPassword("admin")
            .setAllowAnonymous(true)
            .setNoWeb(true)
            .setArtemisInstance(serverLocation)
            .setPortOffset(s)
            .setClustered(true);

         cliCreateServer.setMessageLoadBalancing(MESSAGE_LOAD_BALANCING);
         cliCreateServer.setStaticCluster(staticClusterURI);
         cliCreateServer.setArgs("--no-stomp-acceptor", "--no-hornetq-acceptor",
            "--no-mqtt-acceptor", "--no-amqp-acceptor",
            "--max-hops", "1",
            "--cluster-user", "cluster-user",
            "--cluster-password", "cluster-password",
            "--queues", QUEUE_NAME);

         cliCreateServer.createServer();

         Properties brokerProperties = new Properties();
         brokerProperties.put("addressesSettings.#.redistributionDelay", "0");
         brokerProperties.put("HAPolicyConfiguration", "PRIMARY_ONLY");
         brokerProperties.put("HAPolicyConfiguration.scaleDownConfiguration.enabled", "true");
         brokerProperties.put("HAPolicyConfiguration.scaleDownConfiguration.clusterName", "my-cluster");
         saveProperties(brokerProperties, new File(serverLocation, "broker.properties"));
         serverRecords[s] = new ServerRecord(serverLocation, serverName, s);
      }
   }

   private static String buildStaticClusterURI(int excludeIndex) {
      StringBuilder urlBuffer = new StringBuilder();
      boolean first = true;
      for (int i = 0; i < NUMBER_OF_SERVERS; i++) {
         if (i != excludeIndex) {
            if (!first) {
               urlBuffer.append(",");
            }
            first = false;
            urlBuffer.append("tcp://localhost:").append(SERVER_PORT_BASE + i);
         }
      }
      return urlBuffer.toString();
   }

   @BeforeEach
   public void before() throws Exception {
      assumeTrue(TEST_ENABLED);

      for (int i = 0; i < NUMBER_OF_SERVERS; i++) {
         cleanupData(SERVER_NAME_BASE + i);
      }


      for (ServerRecord serverRecord: serverRecords) {
         serverRecord.startServer();
      }

      waitForClusterTopology();
   }

   private void waitForClusterTopology() throws Exception {
      for (int i = 0; i < NUMBER_OF_SERVERS; i++) {
         ServerUtil.waitForServerToStart(i, 10_000);
         SimpleManagement simpleManagement = new SimpleManagement("tcp://localhost:" + (SERVER_PORT_BASE + i), null, null);
         Wait.assertEquals(NUMBER_OF_SERVERS, () -> simpleManagement.listNetworkTopology().size(), 10_000);
      }
   }

   @Test
   public void testRedistribution() throws Throwable {
      final AtomicInteger totalReceived = new AtomicInteger(0);
      final AtomicInteger totalSent = new AtomicInteger(0);
      final AtomicInteger errors = new AtomicInteger(0);
      final AtomicInteger duplicates = new AtomicInteger(0);
      final AtomicBoolean restartsComplete = new AtomicBoolean(false);
      final AtomicBoolean producersComplete = new AtomicBoolean(false);
      final Set<String> receivedMessageIds = ConcurrentHashMap.newKeySet();

      ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_SERVERS * 2);
      runAfter(executorService::shutdownNow);

      CountDownLatch producersDone = new CountDownLatch(NUMBER_OF_SERVERS);
      CountDownLatch consumersDone = new CountDownLatch(NUMBER_OF_SERVERS);

      for (int s = 0; s < NUMBER_OF_SERVERS; s++) {
         final int serverIndex = s;
         executorService.execute(() -> consumeMessages(serverRecords[serverIndex], totalReceived, totalSent, producersComplete, receivedMessageIds, duplicates, consumersDone));
      }

      for (int s = 0; s < NUMBER_OF_SERVERS; s++) {
         final int serverIndex = s;
         executorService.execute(() -> {
            try {
               produceMessages(serverRecords[serverIndex], totalSent, restartsComplete);
            } catch (Exception e) {
               logger.warn("Producer {} failed: {}", serverIndex, e.getMessage(), e);
               errors.incrementAndGet();
            } finally {
               producersDone.countDown();
            }
         });
      }

      /*
      for (int i = 0; i < serverRecords.length * 2; i++) {
         int serverToReStart = i % serverRecords.length;
         logger.info("Restarting server {}", serverToReStart);
         serverRecords[serverToReStart].restartServer(serverToReStart % 2 == 0); // half with kill, half with graceful stop
         logger.info("Restarted server {}", serverToReStart);
      }
       */
      int serverToReStart = 0;
      for (int i = 0; i < 3; i++) {
         logger.info("Restarting server {}", serverToReStart);
         serverRecords[serverToReStart].restartServer(false);
         logger.info("Restarted server {}", serverToReStart);
         Thread.sleep(1000);
      }

      restartsComplete.set(true);
      assertTrue(producersDone.await(TIMEOUT_MINUTES, TimeUnit.MINUTES), "Producers did not complete in time");

      producersComplete.set(true);
      assertTrue(consumersDone.await(TIMEOUT_MINUTES, TimeUnit.MINUTES), "Consumers did not complete in time");

      Wait.assertEquals(totalSent.get(), () -> totalReceived.get(), TimeUnit.MINUTES.toMillis(TIMEOUT_MINUTES));

      logger.info("Test finished: totalSent={}, totalReceived={}, duplicates={}", totalSent.get(), totalReceived.get(), duplicates.get());

      assertEquals(0, errors.get(), "Errors occurred during test");

      assertEquals(0, duplicates.get(), "Duplicate messages detected");
   }

   private void produceMessages(ServerRecord serverRecord, AtomicInteger totalSent, AtomicBoolean restartsComplete) throws Exception {
      int localSent = 0;
      int startNumber = -1;
      while (!restartsComplete.get()) {
         try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(serverRecord.serverURI()); Connection connection = factory.createConnection()) {
            startNumber = serverRecord.startNumber.get();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(QUEUE_NAME));

            long deadline = System.currentTimeMillis() + CLIENT_RECONNECT_INTERVAL_MS;
            while (System.currentTimeMillis() < deadline && !restartsComplete.get()) {
               serverRecord.readLock();
               try {
                  if (serverRecord.startNumber.get() != startNumber) {
                     logger.info("Server {} was restarted after connection was open, need to restart connection for producer", serverRecord.serverId);
                     break;
                  }
                  producer.send(session.createTextMessage("server-" + serverRecord.serverId + "-msg-" + localSent));
                  localSent++;
                  int sent = totalSent.incrementAndGet();
                  if (localSent % 100 == 0) {
                     logger.debug("Producer server={} localSent={} totalSent={}", serverRecord.serverId, localSent, sent);
                  }
               } finally {
                  serverRecord.readUnlock();
               }
            }
            long sleepInterval = RandomUtil.randomInterval(1, CLIENT_RECONNECT_INTERVAL_MS);
            logger.info("Reconnecting producer {}, localSent={}, totalSent={}, sleeping for {}", serverRecord.serverId, localSent, totalSent, sleepInterval);

            // need to make things a little unpredictable
            Thread.sleep(sleepInterval);
         } catch (Exception e) {
            logger.debug("Producer {} reconnecting after: {}", serverRecord.serverId, e.getMessage());
         }
      }
   }

   private void consumeMessages(ServerRecord serverRecord, AtomicInteger totalReceived, AtomicInteger totalSent, AtomicBoolean producersComplete, Set<String> receivedMessageIds, AtomicInteger duplicates, CountDownLatch consumersDone) {
      try {
         int localReceived = 0;
         int startNumber = -1;
         while (!producersComplete.get() || totalReceived.get() < totalSent.get()) {
            try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(serverRecord.serverURI()); Connection connection = factory.createConnection()) {
               startNumber = serverRecord.startNumber.get();
               connection.start();
               Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
               MessageConsumer consumer = session.createConsumer(session.createQueue(QUEUE_NAME));

               long deadline = System.currentTimeMillis() + CLIENT_RECONNECT_INTERVAL_MS;
               while (System.currentTimeMillis() < deadline && (!producersComplete.get() || totalReceived.get() < totalSent.get())) {
                  serverRecord.readLock();
                  try {
                     if (startNumber != serverRecord.startNumber.get()) {
                        logger.info("Server {} was restarted after connection was open, need to restart connection for consumer", serverRecord.serverId);
                        break;
                     }
                     Message msg = consumer.receive(1000);
                     if (msg != null) {
                        localReceived++;
                        String msgId = msg.getJMSMessageID();
                        if (!receivedMessageIds.add(msgId)) {
                           int totalDuplicates = duplicates.incrementAndGet();
                           logger.warn("Duplicate message detected: id={}, server={}, totalReceived={}, duplicates={}", msgId, serverRecord.serverId, totalReceived, totalDuplicates);
                        }
                        if (localReceived % 100 == 0) {
                           logger.debug("Consumer server={} locallyReceived={} totalReceived={} duplicates={}", serverRecord.serverId, localReceived, totalReceived, duplicates);
                        }
                        totalReceived.incrementAndGet();
                     }
                  } finally {
                     serverRecord.readUnlock();
                  }
               }
               long sleepInterval = RandomUtil.randomInterval(1, CLIENT_RECONNECT_INTERVAL_MS);
               logger.info("Reconnecting consumer {}, locallyReceived={}, totalReceived={}, totalSent={}, duplicates={}, sleeping for {}", serverRecord.serverId, localReceived, totalReceived, totalSent, duplicates, sleepInterval);

               // need to make things a little unpredictable
               Thread.sleep(sleepInterval);
            } catch (Exception e) {
               logger.debug("Consumer {} reconnecting after: {}", serverRecord.serverId, e.getMessage());
            }
         }
         logger.debug("Consumer {} finished, received {} messages, duplicates={}", serverRecord.serverId, localReceived, duplicates);
      } finally {
         consumersDone.countDown();
      }
   }

   @Test
   public void testRedistributionSingleConsumer() throws Throwable {
      final AtomicInteger totalReceived = new AtomicInteger(0);
      final AtomicInteger totalSent = new AtomicInteger(0);
      final AtomicInteger errors = new AtomicInteger(0);
      final AtomicInteger duplicates = new AtomicInteger(0);
      final AtomicBoolean restartsComplete = new AtomicBoolean(false);
      final AtomicBoolean producersComplete = new AtomicBoolean(false);
      final Set<String> receivedMessageIds = ConcurrentHashMap.newKeySet();
      final int consumerServerIndex = 0;

      ExecutorService executorService = Executors.newFixedThreadPool(NUMBER_OF_SERVERS + 1);
      runAfter(executorService::shutdownNow);

      CountDownLatch producersDone = new CountDownLatch(NUMBER_OF_SERVERS);
      CountDownLatch consumersDone = new CountDownLatch(1);

      executorService.execute(() -> consumeMessages(serverRecords[consumerServerIndex], totalReceived, totalSent, producersComplete, receivedMessageIds, duplicates, consumersDone));

      for (int s = 0; s < NUMBER_OF_SERVERS; s++) {
         final int serverIndex = s;
         executorService.execute(() -> {
            try {
               produceMessages(serverRecords[serverIndex], totalSent, restartsComplete);
            } catch (Exception e) {
               logger.warn("Producer {} failed: {}", serverIndex, e.getMessage(), e);
               errors.incrementAndGet();
            } finally {
               producersDone.countDown();
            }
         });
      }

      for (int i = 0; i < 3; i++) {
         logger.info("Restarting consumer server {}", consumerServerIndex);
         serverRecords[consumerServerIndex].restartServer(false);
         logger.info("Restarted consumer server {}", consumerServerIndex);
      }

      restartsComplete.set(true);
      assertTrue(producersDone.await(TIMEOUT_MINUTES, TimeUnit.MINUTES), "Producers did not complete in time");

      producersComplete.set(true);
      assertTrue(consumersDone.await(TIMEOUT_MINUTES, TimeUnit.MINUTES), "Consumers did not complete in time");

      Wait.assertEquals(totalSent.get(), () -> totalReceived.get(), TimeUnit.MINUTES.toMillis(TIMEOUT_MINUTES));
      Wait.assertTrue(() -> totalQueueMessages() == 0, TimeUnit.MINUTES.toMillis(TIMEOUT_MINUTES));

      logger.info("Test finished: totalSent={}, totalReceived={}, duplicates={}", totalSent.get(), totalReceived.get(), duplicates.get());

      assertEquals(0, errors.get(), "Errors occurred during test");
      assertEquals(0, duplicates.get(), "Duplicate messages detected");
   }

   private void waitForConsumerServerReady(int consumerServerIndex, AtomicInteger totalReceived, int receivedBeforeRestart) throws Exception {
      for (int i = 0; i < NUMBER_OF_SERVERS; i++) {
         final int serverIndex = i;
         Wait.assertEquals(NUMBER_OF_SERVERS, () -> new SimpleManagement("tcp://localhost:" + (SERVER_PORT_BASE + serverIndex), null, null).listNetworkTopology().size(), 30_000);
      }
      Wait.assertTrue(() -> {
         try {
            return new SimpleManagement("tcp://localhost:" + (SERVER_PORT_BASE + consumerServerIndex), null, null).getNumberOfConsumersOnQueue(QUEUE_NAME) >= 1;
         } catch (Exception e) {
            return false;
         }
      }, 30_000);
      Wait.assertTrue(() -> totalReceived.get() > receivedBeforeRestart, 30_000);
   }

   private long totalQueueMessages() {
      long total = 0;
      for (int i = 0; i < NUMBER_OF_SERVERS; i++) {
         try {
            total += new SimpleManagement("tcp://localhost:" + (SERVER_PORT_BASE + i), null, null).getMessageCountOnQueue(QUEUE_NAME);
         } catch (Exception e) {
            logger.debug("Could not get message count from server {}: {}", i, e.getMessage());
         }
      }
      return total;
   }

   @AfterEach
   public void cleanup() {
      for (int i = 0; i < NUMBER_OF_SERVERS; i++) {
         serverRecords[i].stopServer();
      }
   }
}
