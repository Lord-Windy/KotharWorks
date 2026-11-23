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
package com.kotharworks.systest.support;

import java.util.List;

/**
 * Typed read-only view over a {@code GET /api/v1/observation} body for the
 * seat's first (Phase 0: only) actor.
 */
public record Observation(Json.Node raw) {

    public static Observation of(Api.Resp resp) {
        return new Observation(resp.ok());
    }

    public long nowMs() {
        return raw.get("nowMs").asLong();
    }

    public String ownerId() {
        return raw.get("owner").get("id").text();
    }

    public Json.Node actor() {
        return raw.get("actors").at(0);
    }

    public String actorId() {
        return actor().get("id").text();
    }

    public long revision() {
        return actor().get("revision").asLong();
    }

    public String placeId() {
        return actor().get("placeId").text();
    }

    public boolean hasActiveTask() {
        return !actor().get("activeTask").isNull();
    }

    public int pendingCount() {
        return actor().get("pendingQueue").size();
    }

    /** No active task and an empty pending queue. */
    public boolean idle() {
        return !hasActiveTask() && pendingCount() == 0;
    }

    public String failPolicy() {
        return actor().get("failPolicy").text();
    }

    /** Wire ids of every place this owner can currently see. */
    public List<String> visiblePlaceIds() {
        return raw.get("places").stream().map(p -> p.get("id").text()).toList();
    }

    /** Destinations reachable by one edge from the actor's current place. */
    public List<String> exitsFromHere() {
        String here = placeId();
        return raw.get("places").stream()
                .filter(p -> here.equals(p.get("id").text()))
                .flatMap(p -> p.get("edges").stream())
                .map(e -> e.get("to").text())
                .toList();
    }

    public List<String> discoveredPlaces() {
        return raw.get("owner").get("discoveredPlaces").stream().map(Json.Node::text).toList();
    }

    @Override
    public String toString() {
        return raw.toString();
    }
}
