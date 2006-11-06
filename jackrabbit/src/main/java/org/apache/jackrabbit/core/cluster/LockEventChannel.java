/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.cluster;

import org.apache.jackrabbit.core.NodeId;

/**
 * Event channel used to transmit lock events.
 */
public interface LockEventChannel {

    /**
     * Called when a node has been locked.
     *
     * @param nodeId node id
     * @param deep flag indicating whether lock is deep
     * @param owner lock owner
     */
    public void locked(NodeId nodeId, boolean deep, String owner);

    /**
     * Called when a node has been unlocked.
     *
     * @param nodeId node id
     */
    public void unlocked(NodeId nodeId);

    /**
     * Set listener that will receive information about incoming, external lock events.
     *
     * @param listener lock event listener
     */
    public void setListener(LockEventListener listener);
}
