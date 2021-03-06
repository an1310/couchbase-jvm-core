/*
 * Copyright (c) 2016 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.core.message.dcp;

import com.couchbase.client.core.annotations.InterfaceAudience;
import com.couchbase.client.core.annotations.InterfaceStability;

/**
 * Type of DCP connection.
 *
 * It specifies which kind of connection the client have to initiate as a sender.
 * For example, if the sender wants to pull the data from server, it need to
 * choose {@link ConnectionType#CONSUMER}.
 *
 * @author Sergey Avseyev
 * @since 1.1.0
 */
@InterfaceStability.Experimental
@InterfaceAudience.Private
public enum ConnectionType {
    PRODUCER(0),
    CONSUMER(1);

    /* On-wire representation of connection type */
    private final int flags;

    private ConnectionType(int flags) {
        this.flags = flags;
    }

    public int flags() {
        return flags;
    }
}
