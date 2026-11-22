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
package com.kotharworks.wilds.observe;

import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.replay.WorldHash;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.state.WorldState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Deeply immutable snapshot wrapper of a {@link WorldState}, published on demand after
 * each engine step: clock, rules version, {@link WorldHash}, immutable owner/place/
 * actor row snapshots, and the seat-key index. Every row is itself an immutable record,
 * so a view is safe for lock-free concurrent reads across thousands of virtual threads
 * — clients never touch raw mutable state rows.
 */
public record WorldView(WorldSeed seed, int rulesVersion, long nowMs, long worldHash,
                        List<OwnerRow> owners, List<PlaceRow> places, List<ActorRow> actors,
                        Map<Long, OwnerId> ownersBySeatKey) {

    public WorldView {
        Objects.requireNonNull(seed, "seed");
        if (rulesVersion < 1) {
            throw new IllegalArgumentException("rulesVersion must be >= 1: " + rulesVersion);
        }
        owners = List.copyOf(owners);
        places = List.copyOf(places);
        actors = List.copyOf(actors);
        ownersBySeatKey = Collections.unmodifiableMap(new TreeMap<>(ownersBySeatKey));
    }

    /** Captures an immutable snapshot of {@code state} (rows, counters, world hash). */
    public static WorldView of(WorldState state) {
        Objects.requireNonNull(state, "state");
        return new WorldView(state.seed(), state.rulesVersion(), state.nowMs(),
                WorldHash.hash(state), state.owners().snapshotRows(),
                state.places().snapshotRows(), state.actors().snapshotRows(),
                state.ownersBySeatKey());
    }

    /** Owner registered under {@code seatKey}, or empty. */
    public Optional<OwnerRow> findOwnerBySeatKey(long seatKey) {
        OwnerId ownerId = ownersBySeatKey.get(seatKey);
        return ownerId == null ? Optional.empty() : owner(ownerId);
    }

    /** Live owner row for {@code id}, or empty. */
    public Optional<OwnerRow> owner(OwnerId id) {
        Objects.requireNonNull(id, "id");
        return firstMatch(owners, OwnerRow::id, id);
    }

    /** Live place row for {@code id}, or empty. */
    public Optional<PlaceRow> place(PlaceId id) {
        Objects.requireNonNull(id, "id");
        return firstMatch(places, PlaceRow::id, id);
    }

    /** Live actor row for {@code id}, or empty. */
    public Optional<ActorRow> actor(ActorId id) {
        Objects.requireNonNull(id, "id");
        return firstMatch(actors, ActorRow::id, id);
    }

    /** Every actor owned by {@code ownerId}, in table (slot) order. */
    public List<ActorRow> actorsOwnedBy(OwnerId ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        List<ActorRow> owned = new ArrayList<>(actors.size());
        for (ActorRow actor : actors) {
            if (actor.ownerId().equals(ownerId)) {
                owned.add(actor);
            }
        }
        return List.copyOf(owned);
    }

    private static <R, I> Optional<R> firstMatch(List<R> rows, Function<R, I> idOf, I id) {
        for (R row : rows) {
            if (idOf.apply(row).equals(id)) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }
}
