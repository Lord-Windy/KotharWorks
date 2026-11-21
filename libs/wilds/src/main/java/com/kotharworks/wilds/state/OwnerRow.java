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
package com.kotharworks.wilds.state;

import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Neutral owner row: the pure-simulation projection of an external account/seat.
 * Zero UUID or auth concepts — the only link to the host server is the deterministic
 * {@code externalSeatKey} long.
 */
public record OwnerRow(
        OwnerId id,
        long externalSeatKey,
        String name,
        long revision,
        PlaceId homePlaceId,
        List<PlaceId> discoveredPlaces) {

    public OwnerRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(homePlaceId, "homePlaceId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0: " + revision);
        }
        // Canonical form: sorted ascending by (index, generation), deduplicated, immutable.
        TreeSet<PlaceId> canonical = new TreeSet<>(discoveredPlaces);
        discoveredPlaces = List.copyOf(canonical);
    }
}
