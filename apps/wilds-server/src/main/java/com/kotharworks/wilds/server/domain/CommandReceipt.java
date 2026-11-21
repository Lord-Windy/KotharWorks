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
package com.kotharworks.wilds.server.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The idempotent commit record of one admitted command: identical duplicates
 * of the same {@code cmdId} get the identical receipt back without
 * re-execution.
 *
 * @param cmdId      client-supplied idempotency key
 * @param admittedMs logical time the command was admitted at
 * @param walSeq     WAL row the command was committed to; {@code 0} on the
 *                   documented pre-restart reconstruction edge (the WAL row
 *                   exists but its seq is not carried in the receipt cache)
 * @param revision   target actor revision after the command applied (0 for
 *                   owner-level commands)
 */
public record CommandReceipt(UUID cmdId, long admittedMs, long walSeq, long revision) {

    public CommandReceipt {
        Objects.requireNonNull(cmdId, "cmdId");
        if (walSeq < 0) {
            throw new IllegalArgumentException("walSeq must be >= 0: " + walSeq);
        }
    }
}
