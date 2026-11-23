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
package com.kotharworks.systest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.kotharworks.systest.support.Api;
import com.kotharworks.systest.support.Json;
import com.kotharworks.systest.support.Persona;
import com.kotharworks.systest.support.World;

/**
 * Auth boundaries over the deployed stack (complements the in-process
 * {@code AuthSecurityGateTest}): what a stranger, a player, a bot and a
 * forged cross-site request can and cannot do.
 */
@Tag("security")
class AccessControlTest {

    private static World.BotSeat bot;

    @BeforeAll
    static void seats() {
        bot = World.botSeat("security-bot");
    }

    @Test
    @DisplayName("anonymous callers are refused by every protected endpoint")
    void anonymousRefused() {
        Api anon = Api.anonymous();
        for (String path : List.of("/api/v1/auth/me", "/api/v1/observation", "/api/v1/events",
                "/api/v1/rules", "/api/v1/stream?since=0")) {
            assertEquals(401, anon.get(path).status(), path);
        }
        assertEquals(401, anon.postJson("/api/v1/commands", Json.obj("type", "enqueue")).status());
        assertEquals(401, anon.charterSeat("Nobody", "HUMAN").status());
    }

    @Test
    @DisplayName("garbage and malformed bearer tokens are 401, never 500")
    void badBearer() {
        for (String token : List.of("nonsense", "iw_bot_", "iw_bot_" + "A".repeat(43),
                "eyJhbGciOiJub25lIn0.e30.")) {
            assertEquals(401, Api.bot(token).observation().status(), token);
        }
    }

    @Test
    @DisplayName("a PLAYER cannot charter bot seats or a second human seat")
    void playerQuota() {
        Api alice = Api.login(Persona.ALICE);
        World.humanSeat(alice, World.ALICE_SEAT);
        Api.Resp bot = alice.charterSeat("Alice Bot", "SCRIPT_BOT");
        assertEquals(403, bot.status(), bot::toString);
        Api.Resp second = alice.charterSeat("Alice Two", "HUMAN");
        assertEquals(403, second.status(), second::toString);
    }

    @Test
    @DisplayName("tokens are only minted for non-HUMAN seats the caller owns")
    void tokenMinting() {
        Api alice = Api.login(Persona.ALICE);
        long aliceSeat = World.humanSeat(alice, World.ALICE_SEAT);
        assertEquals(403, alice.mintToken(aliceSeat).status(), "PLAYER role cannot mint");

        Api sam = Api.login(Persona.SAM);
        assertEquals(403, sam.mintToken(aliceSeat).status(), "not sam's seat");
        long samHuman = World.humanSeat(sam, "Sam Walks");
        assertEquals(403, sam.mintToken(samHuman).status(), "HUMAN seats get no tokens");
    }

    @Test
    @DisplayName("a bot token is scoped to its own seat and to the REST API")
    void botTokenScope() {
        Api botApi = Api.bot(bot.token());
        botApi.observation().expect(200);
        long aliceSeat = World.humanSeat(Api.login(Persona.ALICE), World.ALICE_SEAT);
        assertEquals(403, botApi.observation(aliceSeat).status(), "lateral seat escape");
        assertEquals(403, botApi.get("/play").status(), "browser console is human-only");
        Api.Resp me = botApi.me();
        assertTrue(me.status() == 401 || me.status() == 403, "bots have no human identity: " + me);
        Api.Resp charter = botApi.charterSeat("Bot Spawn", "SCRIPT_BOT");
        assertTrue(charter.status() == 401 || charter.status() == 403, charter::toString);
    }

    @Test
    @DisplayName("a bot cannot command another seat's actor")
    void crossOwnerCommand() {
        World.BotSeat victim = World.botSeat("security-victim");
        Api botApi = Api.bot(bot.token());
        long victimRevision = Api.bot(victim.token()).observation().ok()
                .get("actors").at(0).get("revision").asLong();
        Api.Resp hijack = botApi.command(Api.waitTask(victim.actorId(), victimRevision, 1000, 1));
        assertTrue(hijack.status() == 403 || hijack.status() == 409, hijack::toString);
        assertEquals("FORBIDDEN", hijack.errorCode(), hijack::toString);
        assertTrue(Api.bot(victim.token()).observation().ok().get("actors").at(0)
                .get("pendingQueue").size() == 0, "victim queue untouched");
    }

    @Test
    @DisplayName("raw register_owner and client-supplied owners are rejected")
    void noRegistrationBackdoor() {
        Api botApi = Api.bot(bot.token());
        Api.Resp raw = botApi.command(Json.obj("type", "register_owner", "seatName", "x"));
        assertEquals(403, raw.status(), raw::toString);
        Map<String, Object> spoof = new java.util.LinkedHashMap<>(
                Api.waitTask(bot.actorId(), 0, 1000, 1));
        spoof.put("actorOwner", "own:0:1");
        assertEquals(400, botApi.command(spoof).status());
    }

    @Test
    @DisplayName("cookie-authenticated mutations need JSON or a CSRF token, and a same origin")
    void csrf() {
        Api sam = Api.login(Persona.SAM);
        Api.Resp form = sam.postForm("/api/v1/seats",
                Map.of("seatName", "Csrf Victim", "controllerKind", "SCRIPT_BOT"));
        assertEquals(403, form.status(), form::toString);
        assertEquals("CSRF_REJECTED", form.errorCode());

        Api.Resp crossOrigin = sam.postJson("/api/v1/seats",
                Json.obj("seatName", "Csrf Victim", "controllerKind", "SCRIPT_BOT"),
                "Origin", "https://evil.example");
        assertEquals(403, crossOrigin.status(), crossOrigin::toString);

        Api.Resp crossSite = sam.postJson("/api/v1/seats",
                Json.obj("seatName", "Csrf Victim", "controllerKind", "SCRIPT_BOT"),
                "Sec-Fetch-Site", "cross-site");
        assertEquals(403, crossSite.status(), crossSite::toString);
    }

    @Test
    @DisplayName("revoked tokens stop working immediately")
    void revocation() {
        World.BotSeat temp = World.botSeat("security-revoke");
        Api botApi = Api.bot(temp.token());
        botApi.observation().expect(200);
        Api.login(Persona.SAM).revokeToken(temp.seatId(), temp.tokenId()).expect(200);
        assertEquals(401, botApi.observation().status());
    }

    @Test
    @DisplayName("logout kills the session server-side")
    void logout() {
        Api sam = Api.login(Persona.SAM);
        String cookie = sam.sessionCookie().orElseThrow();
        sam.postJson("/auth/logout", Json.obj()).expect(200);
        assertEquals(401, Api.resumeSession(cookie).me().status());
    }
}
