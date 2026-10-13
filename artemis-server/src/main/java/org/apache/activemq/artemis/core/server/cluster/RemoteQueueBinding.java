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
package org.apache.activemq.artemis.core.server.cluster;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.filter.Filter;
import org.apache.activemq.artemis.core.postoffice.QueueBinding;
import org.apache.activemq.artemis.core.server.cluster.impl.MessageLoadBalancingType;

public interface RemoteQueueBinding extends QueueBinding {

   void addConsumer(SimpleString filterString) throws Exception;

   void removeConsumer(SimpleString filterString) throws Exception;

   void reset();

   void disconnect();

   void connect();

   /**
    * Whether this remote binding may be selected for address routing. Disabled bindings remain
    * in the post office for relocate lookup ({@code locateTargetBinding}) but are never chosen
    * for new sends (used to fence ingress into a SnF queue being drained on scale-down).
    */
   boolean isEnabled();

   void setEnabled(boolean enabled);

   long getRemoteQueueID();

   void setFilter(Filter filter);

   MessageLoadBalancingType getMessageLoadBalancingType();
}
