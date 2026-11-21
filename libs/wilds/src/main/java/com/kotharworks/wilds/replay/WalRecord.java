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
package com.kotharworks.wilds.replay;

import com.kotharworks.wilds.command.WorldCommand;

import java.util.Objects;

/**
 * Closed family of write-ahead-log entries feeding the deterministic replay rig
 * (Ticket 05); exhaustively switchable. {@code walSeq} is the strictly ascending log
 * position, {@code admittedMs} the logical time the entry was admitted, and
 * {@code worldHash} the optional post-entry digest a host may pin for verification.
 */
public sealed interface WalRecord permits WalRecord.ClockAdvance, WalRecord.CommandEntry {

    /** Admitted clock jump: the host advanced the world clock to {@code admittedMs}. */
    record ClockAdvance(long walSeq, long admittedMs, Long worldHash) implements WalRecord {

        public ClockAdvance {
            if (walSeq < 1) {
                throw new IllegalArgumentException("walSeq must be >= 1: " + walSeq);
            }
        }
    }

    /** One admitted {@link WorldCommand} attributed to the seat that submitted it. */
    record CommandEntry(long walSeq, long admittedMs, long seatId, String cmdId,
                        WorldCommand command, Long worldHash) implements WalRecord {

        public CommandEntry {
            if (walSeq < 1) {
                throw new IllegalArgumentException("walSeq must be >= 1: " + walSeq);
            }
            Objects.requireNonNull(cmdId, "cmdId");
            Objects.requireNonNull(command, "command");
        }
    }
}
