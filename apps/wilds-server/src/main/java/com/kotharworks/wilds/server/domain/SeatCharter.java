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

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * The single source of a seat's {@code RegisterOwner} naming and idempotency
 * key, shared by the charter route ({@code POST /api/v1/seats}) and the
 * {@link WorldHost} seat carry-over after an archive rollover (ticket 11
 * §3.6).
 *
 * <p>Because both paths derive the same {@code cmdId} from
 * {@code (worldId, seatId)}, a {@code /play} "Retry registration" or a
 * re-charter {@code POST} for a seat that the rollover already re-registered
 * dedupes on the committed WAL row instead of registering a second owner.
 *
 * <p>A custom {@code initialActorLabel} supplied at charter time is
 * <em>not</em> persisted ({@code wilds_meta.seats} keeps only the seat name and
 * controller kind), so a rollover re-registers every seat with
 * {@link #defaultActorLabel(String)}.
 */
public final class SeatCharter {

    private SeatCharter() {
    }

    /** The initial actor label a seat gets when none is supplied: {@code "<seatName>'s Actor"}. */
    public static String defaultActorLabel(String seatName) {
        Objects.requireNonNull(seatName, "seatName");
        return seatName + "'s Actor";
    }

    /**
     * The deterministic {@code cmdId} of a seat's {@code RegisterOwner} in a
     * world: a name-based (v3) UUID of {@code "seat-register:<worldId>:<seatId>"}.
     */
    public static UUID registerCmdId(int worldId, long seatId) {
        return UUID.nameUUIDFromBytes(("seat-register:" + worldId + ":" + seatId)
                .getBytes(StandardCharsets.UTF_8));
    }
}
