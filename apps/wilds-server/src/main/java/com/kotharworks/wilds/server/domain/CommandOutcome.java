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

/**
 * Closed family of {@code WorldHost} submission outcomes; exhaustively
 * switchable. Rate limiting never appears here — it is {@code SeatAuthorizer}'s
 * concern and happens before the command reaches the mailbox.
 */
public sealed interface CommandOutcome permits CommandOutcome.Ok, CommandOutcome.Rejected,
        CommandOutcome.StorageUnavailable {

    /** The command was committed and applied; carries the idempotent receipt. */
    record Ok(CommandReceipt receipt) implements CommandOutcome {

        public Ok {
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    /**
     * Pure Phase-1 rejection: zero WAL rows, zero state mutation. The engine
     * error code names the reason ({@code STALE_STATE}, {@code FORBIDDEN},
     * {@code QUEUE_FULL}, ...).
     */
    record Rejected(String errorCode, long currentRevision, String message)
            implements CommandOutcome {

        public Rejected {
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(message, "message");
        }
    }

    /** The store refused the pre-mutation commit; the RAM world is untouched. */
    record StorageUnavailable(String message) implements CommandOutcome {

        public StorageUnavailable {
            Objects.requireNonNull(message, "message");
        }
    }
}
