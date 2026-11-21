/*
 * Copyright 2026 Samuel "Lord-Windy" Brown
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kotharworks.wilds.server.ports;

import java.util.Objects;

/**
 * A persisted {@code SnapshotCodec} checkpoint of the world plus the WAL
 * watermark it covers. Boot verification asserts the restored engine's
 * {@code worldHash()} equals {@code worldHash} before replaying WAL rows
 * {@code > upToWalSeq}.
 *
 * @param checkpointId monotonic checkpoint id
 * @param upToWalSeq   every WAL row with {@code walSeq <= upToWalSeq} is inside
 *                     the snapshot
 * @param logicalMs    world clock at snapshot time
 * @param worldHash    canonical digest the restored state must reproduce
 * @param stateJson    canonical {@code SnapshotCodec} JSON of the world
 */
public record StoredCheckpoint(long checkpointId, long upToWalSeq, long logicalMs, long worldHash,
        String stateJson) {

    public StoredCheckpoint {
        if (upToWalSeq < 0) {
            throw new IllegalArgumentException("upToWalSeq must be >= 0: " + upToWalSeq);
        }
        Objects.requireNonNull(stateJson, "stateJson");
    }
}
