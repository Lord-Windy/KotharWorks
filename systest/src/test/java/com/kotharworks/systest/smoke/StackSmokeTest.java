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
package com.kotharworks.systest.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.kotharworks.systest.support.Api;
import com.kotharworks.systest.support.Await;
import com.kotharworks.systest.support.Json;
import com.kotharworks.systest.support.Persona;

/** Fast "is the stack alive and wired the way we expect" checks. Run first. */
@Tag("smoke")
@Order(1)
class StackSmokeTest {

    private final Api anon = Api.anonymous();

    @Test
    @DisplayName("meta reports the version triple and a moving logical clock")
    void metaAndClock() {
        Json.Node meta = anon.meta().ok();
        assertEquals(1, meta.get("worldSeed").asLong(), "compose boots seed 1");
        assertTrue(meta.get("rulesVersion").asLong() >= 1);
        assertTrue(meta.get("engineVersion").asLong() >= 1);
        assertTrue(meta.get("protocolVersion").asLong() >= 1);
        long first = meta.get("nowMs").asLong();
        Await.until("logical clock advances", () -> anon.meta().ok().get("nowMs").asLong(),
                now -> now > first);
    }

    @Test
    @DisplayName("public chronicle and static assets render without auth")
    void publicPages() {
        Api.Resp chronicle = anon.get("/chronicle").expect(200);
        assertTrue(chronicle.body().contains("Ironwood Wilds"), chronicle::toString);
        assertTrue(anon.get("/assets/wilds-ui.js").expect(200).body().contains("EventSource"));
        anon.get("/assets/wilds.css").expect(200);
        anon.publicEvents(0).ok();
    }

    @Test
    @DisplayName("anonymous /play is sent to login, and login lands on /play")
    void loginRedirectChain() {
        Api.Resp play = anon.get("/play").expect(303);
        assertEquals("/auth/login", play.header("Location"));
        Api.Resp login = anon.get("/auth/login").expect(303);
        assertEquals("/dev/login", login.header("Location"));
        assertTrue(anon.get("/dev/login").expect(200).body().contains("dev-alice"));

        Api alice = Api.login(Persona.ALICE);
        Json.Node me = alice.me().ok();
        assertEquals("Alice (dev)", me.get("displayName").text());
        alice.get("/play").expect(200);
    }

    @Test
    @DisplayName("rule catalog is self-describing for bots")
    void rules() {
        Json.Node rules = Api.login(Persona.SAM).get("/api/v1/rules").ok();
        assertTrue(rules.get("taskKinds").size() >= 2, rules::toString);
        assertTrue(rules.get("placeKinds").size() >= 1, rules::toString);
    }
}
