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

import com.kotharworks.auth.principal.UserId;
import com.kotharworks.wilds.server.domain.ControllerKind;

/**
 * A permanent chartered seat: the stable bridge between the auth world
 * ({@code UserId}, {@code iw_bot_} token {@code scopedResourceId}) and the
 * simulation world (the seat key the engine registers an {@code OwnerRow}
 * under). Seat ids are engine-external, so they never collide with generational
 * world ids even across checkpoint restores.
 *
 * @param seatId         permanent seat key (also the engine's externalSeatKey)
 * @param ownerUserId    owning account
 * @param seatName       unique trimmed printable name (3..40 chars)
 * @param controllerKind who pilots this seat
 */
public record SeatRecord(long seatId, UserId ownerUserId, String seatName,
        ControllerKind controllerKind) {

    public SeatRecord {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(seatName, "seatName");
        Objects.requireNonNull(controllerKind, "controllerKind");
    }
}
