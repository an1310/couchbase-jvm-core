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
 * A message representing event that removes or expires a document.
 *
 * @author Sergey Avseyev
 * @since 1.1.0
 */
@InterfaceStability.Experimental
@InterfaceAudience.Private
public class RemoveMessage extends AbstractDCPMessage {
    private final long cas;
    private final long bySequenceNumber;
    private final long revisionSequenceNumber;

    public RemoveMessage(int totalBodyLength, short partition, String key, long cas, long bySequenceNumber, long revisionSequenceNumber, String bucket) {
        this(totalBodyLength, partition, key, cas, bySequenceNumber, revisionSequenceNumber, bucket, null);
    }

    public RemoveMessage(int totalBodyLength, short partition, String key, long cas, long bySequenceNumber, long revisionSequenceNumber, String bucket, String password) {
        super(totalBodyLength, partition, key, bucket, password);
        this.cas = cas;
        this.bySequenceNumber = bySequenceNumber;
        this.revisionSequenceNumber = revisionSequenceNumber;
    }

    public long cas() {
        return cas;
    }

    public long bySequenceNumber() {
        return bySequenceNumber;
    }

    public long revisionSequenceNumber() {
        return revisionSequenceNumber;
    }
}
