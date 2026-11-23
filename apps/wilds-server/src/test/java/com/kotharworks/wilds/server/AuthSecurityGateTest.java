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
package com.kotharworks.wilds.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import com.kotharworks.auth.AuthMode;
import com.kotharworks.auth.dev.DevPersona;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.observe.WorldView;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.replay.JsonCodec.JsonInt;
import com.kotharworks.wilds.replay.JsonCodec.JsonObj;
import com.kotharworks.wilds.replay.JsonCodec.JsonStr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Domain security gate (Ticket 07 checks #7&#8211;#12) driven over a live
 * loopback server: PLAYER quota/kind/token/admin blocks, cross-account reads
 * and writes with zero WAL rows, lateral bot-token escape, bot tokens on human
 * routes, and instant suspension of JWT, cookie, and {@code iw_bot_} access.
 * Sanity positives prove the gates are open to rightful callers.
 */
@Timeout(60)
class AuthSecurityGateTest {

    private WildsServer server;
    private HttpClient http;
    private String base;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    // ------------------------------------------------------------------ rig

    private WildsServer rig() throws IOException {
        stopServer();
        ServerConfig config = new ServerConfig(0, 1L, 1L, 60_000L, AuthMode.DEV_LOCAL,
                "http://127.0.0.1:0", null, null, null, null, null, null, 100L, 200L,
                "127.0.0.1", ServerConfig.StorageMode.MEMORY, null, null, null, 300_000L);
        server = new WildsServer(config);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        http = HttpClient.newHttpClient();
        return server;
    }

    private String jwt(DevPersona persona) {
        return server.devIssuer().mintSignedJwt(persona);
    }

    private HttpResponse<String> get(String path, String bearer) throws IOException,
            InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String jsonBody, String bearer)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long charter(String bearer, String seatName, String kind) throws IOException,
            InterruptedException {
        HttpResponse<String> response = post("/api/v1/seats",
                "{\"seatName\":\"" + seatName + "\",\"controllerKind\":\"" + kind + "\"}", bearer);
        return intField(json(response.body()), "seatId");
    }

    private JsonObj json(String body) {
        return (JsonObj) JsonCodec.parse(body);
    }

    private static long intField(JsonObj obj, String key) {
        return ((JsonInt) obj.getOrNull(key)).value();
    }

    private static String strField(JsonObj obj, String key) {
        return ((JsonStr) obj.getOrNull(key)).value();
    }

    /** The in-world actor owned by a seat (white-box via the published view). */
    private String actorOfSeat(long seatId) {
        WorldView view = server.worldHost().view();
        OwnerId owner = view.findOwnerBySeatKey(seatId).orElseThrow().id();
        return view.actorsOwnedBy(owner).getFirst().id().toWire();
    }

    private int walRowCount() {
        return server.worldStore().loadWalAfter(server.worldHost().worldId(), 0).size();
    }

    private long charterReturningSeat(String bearer, String name) throws IOException,
            InterruptedException {
        return charter(bearer, name, "HUMAN");
    }

    // ------------------------------------------------------------------ #7 PLAYER blocks

    @Test
    void playerCannotCharterSecondSeatBotSeatMintTokensOrHitAdmin() throws Exception {
        rig();
        String alice = jwt(DevPersona.ALICE);

        HttpResponse<String> first = post("/api/v1/seats",
                "{\"seatName\":\"Alice Seat\",\"controllerKind\":\"HUMAN\"}", alice);
        assertEquals(201, first.statusCode(), first.body());

        assertEquals(403, post("/api/v1/seats",
                "{\"seatName\":\"Alice Seat Two\",\"controllerKind\":\"HUMAN\"}", alice)
                .statusCode(), "2nd seat charter must be 403");
        assertEquals(403, post("/api/v1/seats",
                "{\"seatName\":\"Alice Bot\",\"controllerKind\":\"SCRIPT_BOT\"}", alice)
                .statusCode(), "SCRIPT_BOT charter must be 403");
        long seat = intField(json(first.body()), "seatId");
        assertEquals(403, post("/api/v1/seats/" + seat + "/tokens", "{}", alice).statusCode(),
                "PLAYER token minting must be 403");
        assertEquals(403, post("/api/v1/admin/users/" + UUID.randomUUID() + "/suspend", "{}",
                alice).statusCode(), "PLAYER admin route must be 403");
        assertEquals(405, get("/api/v1/admin/users/" + UUID.randomUUID() + "/suspend", alice)
                .statusCode(), "GET variant has no route (405) — still a denial, handler "
                        + "never invoked");
    }

    // ------------------------------------------------------------------ #8 cross-account

    @Test
    void crossAccountReadAndWriteForbiddenWithZeroWalRows() throws Exception {
        rig();
        String alice = jwt(DevPersona.ALICE);
        String sam = jwt(DevPersona.SAM);

        HttpResponse<String> aliceCharter = post("/api/v1/seats",
                "{\"seatName\":\"Alice Seat\",\"controllerKind\":\"HUMAN\"}", alice);
        HttpResponse<String> samCharter = post("/api/v1/seats",
                "{\"seatName\":\"Sam Seat\",\"controllerKind\":\"HUMAN\"}", sam);
        assertEquals(201, aliceCharter.statusCode());
        assertEquals(201, samCharter.statusCode());
        long samSeat = intField(json(samCharter.body()), "seatId");
        String samActor = strField(json(samCharter.body()), "actorId");
        String aliceActor = strField(json(aliceCharter.body()), "actorId");

        int walBefore = walRowCount();

        assertEquals(403, get("/api/v1/observation?seatId=" + samSeat, alice).statusCode(),
                "Alice must not read Sam's observation");
        assertEquals(403, get("/play?seatId=" + samSeat, alice).statusCode(),
                "Alice must not render Sam's seat on /play");
        HttpResponse<String> crossWrite = post("/api/v1/commands", "{\"cmdId\":\""
                + UUID.randomUUID() + "\",\"type\":\"enqueue\",\"targetActor\":\"" + samActor
                + "\",\"tasks\":[{\"type\":\"wait\",\"durationMs\":100,\"batches\":1}]}", alice);
        assertEquals(409, crossWrite.statusCode(), "cross-owner command must be 409");
        assertEquals("FORBIDDEN", strField(json(crossWrite.body()), "errorCode"));
        assertEquals(403, post("/api/v1/commands?seatId=" + samSeat, "{\"cmdId\":\""
                + UUID.randomUUID() + "\",\"type\":\"enqueue\",\"targetActor\":\"" + aliceActor
                + "\",\"tasks\":[{\"type\":\"wait\",\"durationMs\":100,\"batches\":1}]}",
                alice).statusCode(), "explicit foreign ?seatId must be 403");

        assertEquals(walBefore, walRowCount(), "attack calls must write zero WAL rows");

        // Sanity positives: the gates are open to rightful callers.
        assertEquals(200, get("/api/v1/observation", alice).statusCode());
        HttpResponse<String> ownWrite = post("/api/v1/commands", "{\"cmdId\":\""
                + UUID.randomUUID() + "\",\"type\":\"enqueue\",\"targetActor\":\"" + aliceActor
                + "\",\"tasks\":[{\"type\":\"wait\",\"durationMs\":1000,\"batches\":1}]}",
                alice);
        assertEquals(200, ownWrite.statusCode(), ownWrite.body());
    }

    // ------------------------------------------------------------------ #9 lateral bot-token escape

    @Test
    void botTokenCannotEscapeItsScopedSeat() throws Exception {
        rig();
        String sam = jwt(DevPersona.SAM);
        long humanSeat = charterReturningSeat(sam, "Sam Human Seat");
        HttpResponse<String> botSeat = post("/api/v1/seats",
                "{\"seatName\":\"Sam Bot Seat\",\"controllerKind\":\"SCRIPT_BOT\"}", sam);
        assertEquals(201, botSeat.statusCode(), botSeat.body());
        long botSeatId = intField(json(botSeat.body()), "seatId");

        HttpResponse<String> mint = post("/api/v1/seats/" + botSeatId + "/tokens", "{}", sam);
        assertEquals(201, mint.statusCode(), mint.body());
        String token = strField(json(mint.body()), "token");
        assertTrue(token.startsWith("iw_bot_"), token);

        String humanActor = actorOfSeat(humanSeat);
        int walBefore = walRowCount();

        assertEquals(403, get("/api/v1/observation?seatId=" + humanSeat, token).statusCode(),
                "seat #2 token against seat #1 must be 403");
        assertEquals(403, get("/api/v1/events?seatId=" + humanSeat, token).statusCode(),
                "seat #2 token reading seat #1 events must be 403");
        HttpResponse<String> crossCommand = post("/api/v1/commands?seatId=" + humanSeat,
                "{\"cmdId\":\"" + UUID.randomUUID() + "\",\"type\":\"enqueue\",\"targetActor\":\""
                        + humanActor + "\",\"tasks\":[{\"type\":\"wait\",\"durationMs\":100,"
                        + "\"batches\":1}]}", token);
        assertEquals(403, crossCommand.statusCode(), "token on foreign seat must be 403");

        HttpResponse<String> crossActor = post("/api/v1/commands", "{\"cmdId\":\""
                + UUID.randomUUID() + "\",\"type\":\"enqueue\",\"targetActor\":\"" + humanActor
                + "\",\"tasks\":[{\"type\":\"wait\",\"durationMs\":100,\"batches\":1}]}", token);
        assertEquals(409, crossActor.statusCode(), "token targeting seat #1's actor");
        assertEquals("FORBIDDEN", strField(json(crossActor.body()), "errorCode"));

        assertEquals(walBefore, walRowCount(), "escape attempts must write zero WAL rows");

        // Positive: the token still works against its own seat.
        assertEquals(200, get("/api/v1/observation", token).statusCode());
        assertEquals(200, get("/api/v1/observation?seatId=" + botSeatId, token).statusCode());
    }

    // ------------------------------------------------------------------ #10 bot tokens on human routes

    @Test
    void botTokensCannotCharterSeatsOrMintTokens() throws Exception {
        rig();
        String sam = jwt(DevPersona.SAM);
        HttpResponse<String> botSeat = post("/api/v1/seats",
                "{\"seatName\":\"Sam Bot Seat\",\"controllerKind\":\"SCRIPT_BOT\"}", sam);
        assertEquals(201, botSeat.statusCode());
        long seatId = intField(json(botSeat.body()), "seatId");
        String token = strField(json(post("/api/v1/seats/" + seatId + "/tokens", "{}", sam)
                .body()), "token");

        assertEquals(403, post("/api/v1/seats",
                "{\"seatName\":\"Escalated Seat\",\"controllerKind\":\"SCRIPT_BOT\"}",
                token).statusCode(), "bot token chartering must be 403");
        assertEquals(403, post("/api/v1/seats/" + seatId + "/tokens", "{}", token).statusCode(),
                "bot token token-minting must be 403");
        assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/seats/"
                + seatId + "/tokens/1")).header("Authorization", "Bearer " + token)
                .DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode(),
                "bot token revocation must be 403");
        HttpResponse<String> botPlay = get("/play", token);
        assertEquals(403, botPlay.statusCode(), "bot token on human /play console must be 403");
        assertEquals("FORBIDDEN", strField(json(botPlay.body()), "errorCode"));
        assertEquals(403, post("/auth/logout", "{}", token).statusCode(),
                "bot token on /auth/logout must be 403");
    }

    // ------------------------------------------------------------------ #11 instant suspension

    @Test
    void suspensionImmediatelyKillsJwtCookieAndBotTokenAccess() throws Exception {
        rig();
        String alice = jwt(DevPersona.ALICE);
        String sam = jwt(DevPersona.SAM);
        String admin = jwt(DevPersona.ADMIN);

        // Establish a BFF cookie session for Sam before suspension.
        HttpResponse<String> samCookieLogin = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/dev/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("persona=dev-sam"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(303, samCookieLogin.statusCode());
        String samCookie = samCookieLogin.headers().firstValue("Set-Cookie").orElseThrow()
                .split(";")[0];

        HttpResponse<String> samCharter = post("/api/v1/seats",
                "{\"seatName\":\"Sam Seat\",\"controllerKind\":\"HUMAN\"}", sam);
        HttpResponse<String> botSeat = post("/api/v1/seats",
                "{\"seatName\":\"Sam Bot Seat\",\"controllerKind\":\"SCRIPT_BOT\"}", sam);
        assertEquals(201, samCharter.statusCode());
        assertEquals(201, botSeat.statusCode());
        long botSeatId = intField(json(botSeat.body()), "seatId");
        String token = strField(json(post("/api/v1/seats/" + botSeatId + "/tokens", "{}", sam)
                .body()), "token");

        HttpResponse<String> me = get("/api/v1/auth/me", sam);
        assertEquals(200, me.statusCode());
        String samUserId = strField(json(me.body()), "userId");

        HttpResponse<String> suspension = post("/api/v1/admin/users/" + samUserId + "/suspend",
                "{\"reason\":\"gate test\"}", admin);
        assertEquals(200, suspension.statusCode(), suspension.body());

        HttpResponse<String> samJwt = get("/api/v1/auth/me", sam);
        assertEquals(403, samJwt.statusCode(), "suspended JWT must be 403 immediately");
        assertEquals("ACCOUNT_SUSPENDED", strField(json(samJwt.body()), "errorCode"));

        HttpResponse<String> samSession = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/api/v1/auth/me"))
                .header("Cookie", samCookie)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, samSession.statusCode(), "suspended session cookie must be 403 immediately");
        assertEquals("ACCOUNT_SUSPENDED", strField(json(samSession.body()), "errorCode"));

        HttpResponse<String> samRelogin = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/dev/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("persona=dev-sam"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, samRelogin.statusCode(), "suspended account cannot mint new session");
        assertEquals("ACCOUNT_SUSPENDED", strField(json(samRelogin.body()), "errorCode"));

        HttpResponse<String> samToken = get("/api/v1/observation?seatId=" + botSeatId, token);
        assertEquals(403, samToken.statusCode(), "suspended owner's token must be 403");
        assertEquals("ACCOUNT_SUSPENDED", strField(json(samToken.body()), "errorCode"));

        // Sanity: Alice is untouched; admin re-suspending is idempotent.
        assertNotEquals(403, get("/api/v1/auth/me", alice).statusCode());
        assertEquals(200, post("/api/v1/admin/users/" + samUserId + "/suspend",
                "{}", admin).statusCode());
    }

    @Test
    void tokenRevocationIdorBlocked_multiSeatAmbiguity_rateLimit_fogAndEnvelopeGuards()
            throws Exception {
        stopServer();
        ServerConfig tightRateConfig = new ServerConfig(0, 1L, 1L, 60_000L, AuthMode.DEV_LOCAL,
                "http://127.0.0.1:0", null, null, null, null, null, null, 1L, 4L,
                "127.0.0.1", ServerConfig.StorageMode.MEMORY, null, null, null, 300_000L);
        server = new WildsServer(tightRateConfig);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        http = HttpClient.newHttpClient();

        String sam = jwt(DevPersona.SAM);
        String admin = jwt(DevPersona.ADMIN);
        String alice = jwt(DevPersona.ALICE);

        // (a) Cross-user & cross-seat token revocation IDOR is blocked.
        long samBotSeat = charter(sam, "Sam Bot", "SCRIPT_BOT");
        long samOtherSeat = charter(sam, "Sam Bot Two", "SCRIPT_BOT");
        long adminBotSeat = charter(admin, "Admin Bot", "SCRIPT_BOT");
        JsonObj minted = json(post("/api/v1/seats/" + samBotSeat + "/tokens", "{}", sam).body());
        long samTokenId = intField(minted, "tokenId");
        String samBotToken = strField(minted, "token");

        // Admin passing Admin's own seatId with Sam's tokenId must 404 and leave Sam's token alive.
        HttpResponse<String> crossUserRevoke = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/api/v1/seats/" + adminBotSeat + "/tokens/" + samTokenId))
                .header("Authorization", "Bearer " + admin)
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, crossUserRevoke.statusCode());
        // Sam passing Sam's OTHER seatId with samTokenId must also 404.
        HttpResponse<String> crossSeatRevoke = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/api/v1/seats/" + samOtherSeat + "/tokens/" + samTokenId))
                .header("Authorization", "Bearer " + sam)
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, crossSeatRevoke.statusCode());
        assertEquals(200, get("/api/v1/observation", samBotToken).statusCode(),
                "victim bot token must still work after IDOR attempts");

        HttpRequest ownerRevokeRequest = HttpRequest.newBuilder(
                        URI.create(base + "/api/v1/seats/" + samBotSeat + "/tokens/" + samTokenId))
                .header("Authorization", "Bearer " + sam)
                .DELETE().build();
        HttpResponse<String> ownerRevoke = http.send(ownerRevokeRequest,
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ownerRevoke.statusCode(), ownerRevoke.body());
        assertTrue(ownerRevoke.body().contains("\"revoked\":true"), ownerRevoke.body());
        HttpResponse<String> afterRevoke = get("/api/v1/observation", samBotToken);
        assertEquals(401, afterRevoke.statusCode());
        assertEquals("INVALID_TOKEN", strField(json(afterRevoke.body()), "errorCode"));
        assertEquals(200, http.send(ownerRevokeRequest, HttpResponse.BodyHandlers.ofString())
                .statusCode(), "repeat revoke is idempotent");

        // (b) Multi-seat human omitting ?seatId= gets 400 SEAT_REQUIRED; explicit ?seatId= works.
        HttpResponse<String> ambiguous = get("/api/v1/observation", sam);
        assertEquals(400, ambiguous.statusCode());
        assertEquals("SEAT_REQUIRED", strField(json(ambiguous.body()), "errorCode"));
        assertEquals(200, get("/api/v1/observation?seatId=" + samBotSeat, sam).statusCode());

        // (c) Command envelope guards: register_owner (403), client actorOwner (400), huge batches (400).
        long aliceSeat = charter(alice, "Alice Seat", "HUMAN");
        String aliceActor = actorOfSeat(aliceSeat);
        assertEquals(403, post("/api/v1/commands", "{\"cmdId\":\"" + UUID.randomUUID()
                + "\",\"type\":\"register_owner\"}", alice).statusCode());
        assertEquals(400, post("/api/v1/commands", "{\"cmdId\":\"" + UUID.randomUUID()
                + "\",\"type\":\"enqueue\",\"actorOwner\":\"own:0:1\",\"targetActor\":\""
                + aliceActor + "\",\"tasks\":[]}", alice).statusCode());
        HttpResponse<String> hugeBatches = post("/api/v1/commands", "{\"cmdId\":\""
                + UUID.randomUUID() + "\",\"type\":\"enqueue\",\"targetActor\":\"" + aliceActor
                + "\",\"tasks\":[{\"type\":\"wait\",\"durationMs\":1000,\"batches\":5000000000}]}",
                alice);
        assertEquals(400, hugeBatches.statusCode(), hugeBatches.body());
        assertEquals("INVALID_ARGUMENT", strField(json(hugeBatches.body()), "errorCode"));

        // (d) Fog-of-war on /api/v1/examine & public event wire stripping.
        String samActor = actorOfSeat(samBotSeat);
        HttpResponse<String> foreignExamine = get(
                "/api/v1/examine?type=actor&id=" + samActor + "&zoom=examine", alice);
        assertEquals(403, foreignExamine.statusCode());
        assertEquals("FORBIDDEN", strField(json(foreignExamine.body()), "errorCode"));

        HttpResponse<String> publicEvents = get("/api/v1/public/events?since=0", null);
        assertEquals(200, publicEvents.statusCode());
        assertTrue(!publicEvents.body().contains("privateSummary"), publicEvents.body());
        assertTrue(!publicEvents.body().contains("\"payload\""), publicEvents.body());

        // (e) Per-seat rate limiting (burst = 4) rejects excess burst with 429 RATE_LIMITED.
        boolean saw429 = false;
        for (int i = 0; i < 6; i++) {
            HttpResponse<String> r = get("/api/v1/observation?seatId=" + adminBotSeat, admin);
            if (r.statusCode() == 429) {
                assertEquals("RATE_LIMITED", strField(json(r.body()), "errorCode"));
                saw429 = true;
                break;
            }
        }
        assertTrue(saw429, "seat exceeding burst must receive 429 RATE_LIMITED");
    }

    // ------------------------------------------------------------------ #12 sanity: full happy path

    @Test
    void happyPathObservationExamineAndRulesRemainOpen() throws Exception {
        rig();
        String sam = jwt(DevPersona.SAM);
        HttpResponse<String> charter = post("/api/v1/seats",
                "{\"seatName\":\"Sam Seat\",\"controllerKind\":\"HUMAN\"}", sam);
        assertEquals(201, charter.statusCode());
        JsonObj seatBody = json(charter.body());
        String actor = strField(seatBody, "actorId");

        HttpResponse<String> observation = get("/api/v1/observation", sam);
        assertEquals(200, observation.statusCode());
        JsonObj observationBody = json(observation.body());
        JsonObj owner = (JsonObj) observationBody.getOrNull("owner");
        assertEquals(strField(json(charter.body()), "ownerId"), strField(owner, "id"),
                "observation owner matches the chartered owner");

        HttpResponse<String> examine = get("/api/v1/examine?type=actor&id=" + actor
                + "&zoom=look", sam);
        assertEquals(200, examine.statusCode(), examine.body());
        assertTrue(strField(json(examine.body()), "text").contains(actor));

        HttpResponse<String> rules = get("/api/v1/rules", sam);
        assertEquals(200, rules.statusCode());
        assertEquals(1, intField(json(rules.body()), "rulesVersion"));

        HttpResponse<String> meta = get("/api/v1/meta", null);
        assertEquals(200, meta.statusCode(), "meta is public");
        assertEquals(1, intField(json(meta.body()), "worldId"));
    }
}
