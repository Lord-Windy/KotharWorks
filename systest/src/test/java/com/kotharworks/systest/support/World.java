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

/**
 * Idempotent fixtures over the shared live world. Seat chartering is
 * idempotent by seat name on the server, so every helper here can be called
 * from any test in any order and always converges on the same seat.
 *
 * <p>Seat-name convention: one distinct name per test class (e.g. {@code
 * "bot-protocol"}), so tests never fight over the same actor. The researcher
 * cap is 25 seats per account; {@link Persona#SAM} owns the bot seats and
 * {@link Persona#ADMIN} is the spare.
 */
public final class World {

    /** Canonical seat name for Alice's single HUMAN seat. */
    public static final String ALICE_SEAT = "Alice of the Wilds";

    /** The bootstrap topology (seed 1): three places in a line. */
    public static final String ORIGIN = "plc:0:1";
    public static final String RIDGE = "plc:1:1";
    public static final String BOUNDARY = "plc:2:1";

    private World() {
    }

    /** A chartered, in-world bot seat plus a freshly minted token for it. */
    public record BotSeat(String name, long seatId, String ownerId, String actorId, String token,
            long tokenId) {
    }

    /** Charters (or re-finds) {@code owner}'s bot seat and mints a new token for it. */
    public static BotSeat botSeat(Persona owner, String name) {
        Api human = Api.login(owner);
        Json.Node seat = human.charterSeat(name, "SCRIPT_BOT").ok();
        long seatId = seat.get("seatId").asLong();
        Json.Node token = human.mintToken(seatId).expect(201).json();
        Api bot = Api.bot(token.get("token").text());
        Observation obs = Observation.of(Await.until("bot seat " + name + " in-world",
                bot::observation, r -> r.status() == 200));
        return new BotSeat(name, seatId, obs.ownerId(), obs.actorId(),
                token.get("token").text(), token.get("tokenId").asLong());
    }

    /** {@link #botSeat(Persona, String)} owned by {@link Persona#SAM}. */
    public static BotSeat botSeat(String name) {
        return botSeat(Persona.SAM, name);
    }

    /** Ensures {@code api}'s human has a HUMAN seat named {@code name}; returns its seatId. */
    public static long humanSeat(Api api, String name) {
        return api.charterSeat(name, "HUMAN").ok().get("seatId").asLong();
    }
}
