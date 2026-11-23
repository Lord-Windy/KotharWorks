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
package com.kotharworks.wilds.server.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import com.kotharworks.auth.AuthMode;
import com.kotharworks.auth.principal.UserId;
import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.headless.HeadlessMain;
import com.kotharworks.wilds.replay.CommandCodec;
import com.kotharworks.wilds.replay.WalHeader;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.server.ServerConfig;
import com.kotharworks.wilds.server.WildsServer;
import com.kotharworks.wilds.server.adapters.postgres.PostgresWorldStore;
import com.kotharworks.wilds.server.domain.ControllerKind;
import com.kotharworks.wilds.server.ports.OnVersionMismatch;

/**
 * Ticket 09: the full Phase-0 structural spine, end to end over real
 * Testcontainers PostgreSQL and real loopback HTTP/SSE sockets — auth + CSRF +
 * escape-by-default SSR, a two-user gameplay-neutral loop with live SSE and
 * fog isolation, and the mid-flight crash / {@code PAUSE_WHILE_DOWN} restart /
 * cold {@code wilds-headless replay} gate with bit-for-bit {@code WorldHash}
 * parity.
 *
 * <p>Server instances boot through the production constructor path (own
 * Hikari pool + {@code SqlMigrator}) with a deterministic pump: {@code 50ms}
 * ticks at {@code timeScale 20} advance {@code 1_000} logical ms per tick, and
 * periodic checkpoints are disabled ({@code Long.MAX_VALUE}) so nothing races
 * the crash window.
 */
@Timeout(240)
class FullSpineIntegrationTest {

    private static final long CHECKPOINTS_DISABLED = Long.MAX_VALUE;
    /** 31 chars — valid under the 3..40 printable {@code seat_name} rule. */
    private static final String PAYLOAD_SEAT = "House <script>alert(1)</script>";
    private static final String ESCAPED_SEAT = "House &lt;script&gt;alert(1)&lt;/script&gt;";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @AfterEach
    void closeHttpClient() {
        http.close();
    }

    // ------------------------------------------------------------------ (1) auth, XSS, CSRF

    @Test
    void authXssEscapingAndCsrfEndToEnd() throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        WildsServer server = boot(db);
        server.start();
        try {
            String base = base(server);
            // (a) static assets serve with nosniff; unknown asset paths return 404.
            HttpResponse<String> js = http.send(get(base + "/assets/wilds-ui.js", null),
                    ofString());
            assertEquals(200, js.statusCode());
            assertTrue(contentType(js).startsWith("text/javascript"), contentType(js));
            assertEquals("nosniff",
                    js.headers().firstValue("X-Content-Type-Options").orElse(""));
            assertTrue(js.body().contains("wireButtons"), js.body());
            HttpResponse<String> css = http.send(get(base + "/assets/wilds.css", null),
                    ofString());
            assertEquals(200, css.statusCode());
            assertTrue(contentType(css).startsWith("text/css"), contentType(css));
            assertEquals(404,
                    http.send(get(base + "/assets/nonexistent.js", null), ofString()).statusCode());

            // (b) unauthenticated browsers are redirected, never 401-rejected.
            HttpResponse<String> play = http.send(get(base + "/play", null), ofString());
            assertEquals(303, play.statusCode(), play.body());
            assertEquals("/auth/login", play.headers().firstValue("Location").orElse(""));
            HttpResponse<String> login = http.send(get(base + "/auth/login", null), ofString());
            assertEquals(303, login.statusCode());
            assertEquals("/dev/login", login.headers().firstValue("Location").orElse(""));

            // (c) dev-local sign in sets the HttpOnly session cookie; unseated /play has
            //     no-store caching, notice area, POST claim form, and CSRF-guarded logout.
            String firstSession = login(base, "dev-alice");
            HttpResponse<String> unseated = http.send(get(base + "/play", firstSession),
                    ofString());
            assertEquals(200, unseated.statusCode(), unseated.body());
            assertEquals("no-store",
                    unseated.headers().firstValue("Cache-Control").orElse(""));
            assertTrue(unseated.body().contains("id=\"claim-seat-form\" method=\"post\""),
                    unseated.body());
            assertTrue(unseated.body().contains("id=\"notice\""), unseated.body());
            assertTrue(unseated.body().contains("action=\"/auth/logout\""), unseated.body());
            String csrfToken = extractCsrfMeta(unseated.body());

            // Form logout without valid csrf_token is blocked (403); valid csrf_token
            // revokes the session, clears the cookie, and redirects browsers to /chronicle.
            HttpResponse<String> badLogout = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/auth/logout"))
                            .header("Cookie", firstSession)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("csrf_token=forged",
                                    StandardCharsets.UTF_8))
                            .build(),
                    ofString());
            assertEquals(403, badLogout.statusCode(), badLogout.body());
            assertTrue(badLogout.body().contains("CSRF_REJECTED"), badLogout.body());

            HttpResponse<String> goodLogout = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/auth/logout"))
                            .header("Cookie", firstSession)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "csrf_token=" + java.net.URLEncoder.encode(csrfToken,
                                            StandardCharsets.UTF_8),
                                    StandardCharsets.UTF_8))
                            .build(),
                    ofString());
            assertEquals(303, goodLogout.statusCode(), goodLogout.body());
            assertEquals("/chronicle",
                    goodLogout.headers().firstValue("Location").orElse(""));
            assertTrue(goodLogout.headers().firstValue("Set-Cookie").orElse("")
                    .contains("Max-Age=0"), "logout must clear the session cookie");
            assertEquals(303,
                    http.send(get(base + "/play", firstSession), ofString()).statusCode(),
                    "revoked session cookie must no longer authenticate /play");

            String alice = login(base, "dev-alice");

            // (d) cross-origin seat charter is blocked by CsrfGuard.
            HttpResponse<String> crossSite = http.send(postJson(base + "/api/v1/seats",
                    "{\"seatName\":\"Blocked House\",\"controllerKind\":\"HUMAN\"}", alice,
                    "https://evil.example"), ofString());
            assertEquals(403, crossSite.statusCode(), crossSite.body());
            assertTrue(crossSite.body().contains("CSRF_REJECTED"), crossSite.body());

            // (e) the HTML-rich seat name charters fine under the printable rule.
            HttpResponse<String> seat = http.send(postJson(base + "/api/v1/seats",
                    "{\"seatName\":\"" + PAYLOAD_SEAT + "\",\"controllerKind\":\"HUMAN\"}",
                    alice, null), ofString());
            assertEquals(201, seat.statusCode(), seat.body());

            // (f) /play renders the stored payload escaped, never raw, with timeScale & drawer.
            HttpResponse<String> console = http.send(get(base + "/play", alice), ofString());
            assertEquals(200, console.statusCode(), console.body());
            assertTrue(contentType(console).startsWith("text/html"), contentType(console));
            assertEquals("no-store",
                    console.headers().firstValue("Cache-Control").orElse(""));
            assertTrue(console.body().contains("data-time-scale=\"20\""), console.body());
            assertTrue(console.body().contains("id=\"drawer-text\""), console.body());
            assertTrue(console.body().contains(ESCAPED_SEAT),
                    "escaped seat name expected in /play:\n" + console.body());
            assertFalse(console.body().contains(PAYLOAD_SEAT),
                    "the raw payload must not survive /play");

            // (g) /chronicle (public, no cookie) escapes the public feed too.
            HttpResponse<String> chronicle = http.send(get(base + "/chronicle", null),
                    ofString());
            assertEquals(200, chronicle.statusCode(), chronicle.body());
            assertTrue(contentType(chronicle).startsWith("text/html"), contentType(chronicle));
            assertTrue(chronicle.body().contains(ESCAPED_SEAT),
                    "escaped seat name expected in /chronicle:\n" + chronicle.body());
            assertFalse(chronicle.body().contains(PAYLOAD_SEAT),
                    "the raw payload must not survive /chronicle");
            assertTrue(chronicle.body().contains("HUMAN: 1"),
                    "seat roster must break down by controller kind:\n" + chronicle.body());
        } finally {
            server.close();
        }
    }

    @Test
    void orphanSeatWithoutOwnerOffersRetryAndRecoversThroughIdempotentCharter() throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        WildsServer server = boot(db);
        server.start();
        try {
            String base = base(server);
            String alice = login(base, "dev-alice");
            assertEquals(200, http.send(get(base + "/play", alice), ofString()).statusCode());
            Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
            UUID userId = jdbi.withHandle(h -> h.createQuery("SELECT user_id FROM auth.users")
                    .mapTo(UUID.class).one());
            new PostgresWorldStore(jdbi).createSeat(new UserId(userId), "Orphan Seat",
                    ControllerKind.HUMAN);

            HttpResponse<String> stuck = http.send(get(base + "/play", alice), ofString());
            assertEquals(200, stuck.statusCode(), stuck.body());
            assertTrue(stuck.body().contains("id=\"claim-seat-form\""), stuck.body());
            assertTrue(stuck.body().contains("name=\"seatName\" value=\"Orphan Seat\""),
                    stuck.body());
            assertTrue(stuck.body().contains("value=\"HUMAN\""), stuck.body());
            assertTrue(stuck.body().contains(
                    "http-equiv=\"refresh\" content=\"1; url=/play?registeringAttempt=1\""),
                    stuck.body());

            for (String junk : new String[] {"banana", "-5", "99999999999999999999"}) {
                HttpResponse<String> garbage = http.send(
                        get(base + "/play?registeringAttempt=" + junk, alice), ofString());
                assertTrue(garbage.body().contains("url=/play?registeringAttempt=1\""),
                        junk + ": " + garbage.body());
            }
            HttpResponse<String> exhausted = http.send(
                    get(base + "/play?registeringAttempt=10", alice), ofString());
            assertFalse(exhausted.body().contains("http-equiv=\"refresh\""), exhausted.body());
            assertTrue(exhausted.body().contains("Registration is taking longer than expected"),
                    exhausted.body());
            assertTrue(exhausted.body().contains("id=\"claim-seat-form\""), exhausted.body());

            HttpResponse<String> retried = http.send(postJson(base + "/api/v1/seats",
                    "{\"seatName\":\"Orphan Seat\",\"controllerKind\":\"HUMAN\"}", alice,
                    null), ofString());
            assertEquals(200, retried.statusCode(), retried.body());
            HttpResponse<String> console = http.send(get(base + "/play", alice), ofString());
            assertTrue(console.body().contains("id=\"event-log\""), console.body());
        } finally {
            server.close();
        }
    }

    // ------------------------------------------------- (2) two users, SSE, fog, catch-up

    @Test
    void multiUserSseFogIsolationAndCatchUp() throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        WildsServer server = boot(db);
        server.start();
        SseTail aliceTail = null;
        try {
            String base = base(server);
            String alice = login(base, "dev-alice");
            String aliceSeatBody = charter(base, alice, "Alice House");
            long aliceSeatId = jsonLong(aliceSeatBody, "seatId");
            String aliceActor = jsonField(aliceSeatBody, "actorId");

            // Live SSE tail opened before the work is submitted (replay covers the gap).
            aliceTail = new SseTail(http, base + "/api/v1/stream?since=0", alice, null);
            HttpResponse<String> submitted = http.send(postJson(base + "/api/v1/commands",
                    "{\"cmdId\":\"" + UUID.randomUUID() + "\",\"type\":\"enqueue\","
                            + "\"targetActor\":\"" + aliceActor + "\",\"expectedRevision\":null,"
                            + "\"tasks\":[{\"type\":\"travel\",\"destination\":\"plc:1:1\"},"
                            + "{\"type\":\"travel\",\"destination\":\"plc:2:1\"},"
                            + "{\"type\":\"wait\",\"durationMs\":1000,\"batches\":2}]}",
                    alice, null), ofString());
            assertEquals(200, submitted.statusCode(), submitted.body());

            // Ordered subsequence with strictly monotonic cursors (the runner also
            // emits a task_started frame per queued task, so this is not adjacency).
            aliceTail.awaitSequence("travel + wait SSE chain", List.of(
                    frame -> "task_started".equals(frame.kind()),
                    frame -> "task_batch_completed".equals(frame.kind())
                            && frame.data().contains("plc:1:1"),
                    frame -> "place_discovered".equals(frame.kind())
                            && frame.data().contains("plc:2:1"),
                    frame -> "task_batch_completed".equals(frame.kind())
                            && frame.data().contains("plc:2:1"),
                    frame -> "task_batch_completed".equals(frame.kind())
                            && frame.data().contains("\"batchesLeftAfter\":1"),
                    frame -> "task_batch_completed".equals(frame.kind())
                            && frame.data().contains("\"batchesLeftAfter\":0")));

            // Second user (RESEARCHER) charters a bot seat first, then a HUMAN seat:
            // /play defaults to Sam's HUMAN seat, renders the seat switcher, and stays fogged.
            String sam = login(base, "dev-sam");
            HttpResponse<String> samBotCharter = http.send(postJson(base + "/api/v1/seats",
                    "{\"seatName\":\"Sam Bot\",\"controllerKind\":\"SCRIPT_BOT\"}", sam, null),
                    ofString());
            assertEquals(201, samBotCharter.statusCode(), samBotCharter.body());
            long samBotSeatId = jsonLong(samBotCharter.body(), "seatId");
            String samHumanBody = charter(base, sam, "Sam Watch");
            long samHumanSeatId = jsonLong(samHumanBody, "seatId");

            HttpResponse<String> samObs = http.send(
                    get(base + "/api/v1/observation?seatId=" + samHumanSeatId, sam),
                    ofString());
            assertEquals(200, samObs.statusCode(), samObs.body());
            assertTrue(samObs.body().contains("plc:0:1"), samObs.body());
            assertFalse(samObs.body().contains("plc:2:1"),
                    "fog must hide plc:2:1 from Sam's observation:\n" + samObs.body());
            assertFalse(samObs.body().contains(aliceActor),
                    "Sam's observation must not include Alice's actor:\n" + samObs.body());
            HttpResponse<String> samConsole = http.send(get(base + "/play", sam), ofString());
            assertEquals(200, samConsole.statusCode(), samConsole.body());
            assertTrue(samConsole.body().contains("data-seat-id=\"" + samHumanSeatId + "\""),
                    "/play must default to the first HUMAN seat even when a bot seat came first");
            assertTrue(samConsole.body().contains("/play?seatId=" + samBotSeatId),
                    "multi-seat /play must render the seat switcher");
            assertFalse(samConsole.body().contains("plc:2:1"),
                    "fog must hide plc:2:1 from Sam's console:\n" + samConsole.body());
            assertFalse(samConsole.body().contains(aliceActor),
                    "Sam's console must not expose Alice's actor");
            assertEquals(200, http.send(get(base + "/play?seatId=" + samBotSeatId, sam),
                    ofString()).statusCode());
            assertEquals(403, http.send(get(base + "/play?seatId=" + aliceSeatId, sam),
                    ofString()).statusCode(), "foreign ?seatId on /play must be 403");

            // Sam cannot examine or command Alice's actor; public chronicle leaks zero fog.
            HttpResponse<String> samExamineAlice = http.send(get(
                    base + "/api/v1/examine?type=actor&id=" + aliceActor + "&seatId="
                            + samHumanSeatId, sam), ofString());
            assertEquals(403, samExamineAlice.statusCode(), samExamineAlice.body());
            HttpResponse<String> samCommand = http.send(postJson(
                    base + "/api/v1/commands?seatId=" + samHumanSeatId,
                    "{\"cmdId\":\"" + UUID.randomUUID() + "\",\"type\":\"enqueue\","
                            + "\"targetActor\":\"" + aliceActor + "\",\"expectedRevision\":null,"
                            + "\"tasks\":[{\"type\":\"wait\",\"durationMs\":1000,\"batches\":1}]}",
                    sam, null), ofString());
            assertEquals(409, samCommand.statusCode(), samCommand.body());
            assertTrue(samCommand.body().contains("FORBIDDEN"), samCommand.body());
            HttpResponse<String> publicChronicle = http.send(get(base + "/chronicle", null),
                    ofString());
            assertEquals(200, publicChronicle.statusCode());
            assertFalse(publicChronicle.body().contains("plc:2:1"), publicChronicle.body());
            assertFalse(publicChronicle.body().contains("arrived at"), publicChronicle.body());

            // Catch-up: the full travel/wait history replays from cursor 1; Last-Event-ID
            // resumes SSE past already-seen cursors with immediate header flush.
            HttpResponse<String> events = http.send(get(base + "/api/v1/events?since=1", alice),
                    ofString());
            assertEquals(200, events.statusCode(), events.body());
            assertTrue(events.body().contains("arrived at plc:1:1"), events.body());
            assertTrue(events.body().contains("arrived at plc:2:1"), events.body());
            assertTrue(events.body().contains("completed a wait batch"), events.body());
            assertAscendingCursors(events.body());
            long latestAliceCursor = jsonLong(events.body(), "nextSince");
            assertTrue(latestAliceCursor > 1, events.body());
            try (SseTail resumed = new SseTail(http, base + "/api/v1/stream?since=0", alice,
                    String.valueOf(latestAliceCursor - 1))) {
                resumed.awaitSequence("Last-Event-ID resume", List.of(
                        frame -> frame.cursor() == latestAliceCursor));
                assertEquals(latestAliceCursor, resumed.firstCursor(),
                        "Last-Event-ID must advance past ?since=0");
            }
            HttpResponse<String> catchUp = http.send(get(base + "/play?since=1", alice),
                    ofString());
            assertEquals(200, catchUp.statusCode(), catchUp.body());
            assertTrue(catchUp.body().contains("While You Were Away"), catchUp.body());
            assertTrue(catchUp.body().contains("arrived at plc:1:1"), catchUp.body());
            assertTrue(catchUp.body().contains("arrived at plc:2:1"), catchUp.body());
        } finally {
            if (aliceTail != null) {
                aliceTail.close();
            }
            server.close();
        }
    }

    // --------------------------------------- (3) crash, PAUSE_WHILE_DOWN, headless parity

    @Test
    void midFlightCrashPauseWhileDownAndHeadlessHashParity(@TempDir Path tmp) throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        WildsServer server1 = boot(db);
        server1.start();
        Jdbi testJdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        boolean server1Alive = true;
        try {
            String base1 = base(server1);
            String alice = login(base1, "dev-alice");
            String actor = jsonField(charter(base1, alice, "Crash House"), "actorId");
            int worldId = server1.worldHost().worldId();

            HttpResponse<String> firstPage = http.send(
                    get(base1 + "/api/v1/events?since=0", alice), ofString());
            assertEquals(200, firstPage.statusCode(), firstPage.body());
            long replayFrom = jsonLong(firstPage.body(), "nextSince");
            SseTail tail = new SseTail(http, base1 + "/api/v1/stream?since=" + replayFrom,
                    alice, null);
            try {
                // Warm the logical clock up to wall time first: an idle world parks its
                // clock, so an un-warmed first advance would fast-forward through BOTH
                // 5s wait batches in a single step and erase the crash window between
                // them. One throwaway wait drags the clock even with wall*timeScale.
                HttpResponse<String> warmUp = http.send(postJson(base1 + "/api/v1/commands",
                        "{\"cmdId\":\"" + UUID.randomUUID() + "\",\"type\":\"enqueue\","
                                + "\"targetActor\":\"" + actor + "\",\"expectedRevision\":null,"
                                + "\"tasks\":[{\"type\":\"wait\",\"durationMs\":1000,"
                                + "\"batches\":1}]}", alice, null), ofString());
                assertEquals(200, warmUp.statusCode(), warmUp.body());
                tail.awaitSequence("clock warm-up", List.of(
                        frame -> "task_batch_completed".equals(frame.kind())
                                && frame.data().contains("\"durationMs\":1000")));

                HttpResponse<String> submitted = http.send(postJson(base1 + "/api/v1/commands",
                        "{\"cmdId\":\"" + UUID.randomUUID() + "\",\"type\":\"enqueue\","
                                + "\"targetActor\":\"" + actor + "\",\"expectedRevision\":null,"
                                + "\"tasks\":[{\"type\":\"wait\",\"durationMs\":5000,"
                                + "\"batches\":2}]}", alice, null), ofString());
                assertEquals(200, submitted.statusCode(), submitted.body());

                // Batch #1 lands at +5_000 logical ms (~250ms wall); batch #2 is due at
                // +10_000 — abort server1 IMMEDIATELY before joining the SSE reader so
                // nothing races the ~250ms crash window!
                tail.awaitSequence("batch #1", List.of(
                        frame -> "task_batch_completed".equals(frame.kind())
                                && frame.data().contains("\"batchesLeftAfter\":1")));
                server1.closeWithoutCheckpoint(); // abrupt kill -9 stand-in
                server1Alive = false;
            } finally {
                tail.close();
            }
            long hashBeforeCrash = server1.worldHost().worldHash();
            long lastCommittedMs = scalar(testJdbi,
                    "SELECT max(admitted_ms) FROM wilds_world.command_wal"
                            + " WHERE world_id = ?", worldId);
            long cursorBefore = scalar(testJdbi,
                    "SELECT max(event_cursor) FROM wilds_world.world_events"
                            + " WHERE world_id = ?", worldId);
            List<Long> cursorsBefore = eventCursors(testJdbi, worldId);

            // 300ms wall = +6_000 logical at 20x — far past batch #2 if the clock
            // had kept running while down. PAUSE_WHILE_DOWN must hold it.
            Thread.sleep(300);

            WildsServer server2 = boot(db);
            boolean server2Alive = true;
            try {
                assertEquals(lastCommittedMs, server2.worldHost().nowMs(),
                        "PAUSE_WHILE_DOWN: recovery resumes at the exact last committed ms");
                assertEquals(hashBeforeCrash, server2.worldHost().worldHash(),
                        "recovered world hash identical");
                assertEquals(cursorsBefore, eventCursors(testJdbi, worldId),
                        "zero cursor rewind and zero duplicates across the crash");

                server2.start();
                String base2 = base(server2);
                HttpResponse<String> recovered = http.send(
                        get(base2 + "/api/v1/events?since=0", alice), ofString());
                assertEquals(200, recovered.statusCode(), recovered.body());
                assertAscendingCursors(recovered.body());
                assertEquals(cursorBefore, jsonLong(recovered.body(), "nextSince"),
                        "the recovered event stream ends exactly at the pre-crash cursor");

                SseTail tail2 = new SseTail(http,
                        base2 + "/api/v1/stream?since=" + cursorBefore, alice, null);
                try {
                    tail2.awaitSequence("batch #2 after recovery", List.of(
                            frame -> "task_batch_completed".equals(frame.kind())
                                    && frame.data().contains("\"batchesLeftAfter\":0")));
                    assertTrue(tail2.firstCursor() > cursorBefore,
                            "the resumed stream must deliver only post-crash cursors");
                } finally {
                    tail2.close();
                }
                assertTrue(waitUntilIdle(base2, alice),
                        "batch #2 completed and the queue drained once the pump resumed");

                long msBeforeClose = server2.worldHost().nowMs();
                server2.close(); // graceful final currentLogicalMs anchor + checkpoint
                server2Alive = false;
                long finalHash = server2.worldHost().worldHash();
                assertTrue(server2.worldHost().nowMs() >= msBeforeClose,
                        "graceful close anchors up to currentLogicalMs");
                assertEquals(finalHash,
                        new PostgresWorldStore(testJdbi).loadLatestCheckpoint(worldId)
                                .orElseThrow().worldHash(),
                        "shutdown checkpoint pins the post-anchor worldHash");

                // Export with the TEST's own store: server2's pool is now shut.
                StringWriter export = new StringWriter();
                new PostgresWorldStore(testJdbi).exportWalJsonl(worldId, export);
                Path walPath = tmp.resolve("spine-e2e.wal.jsonl");
                Files.writeString(walPath, export.toString());
                List<String> lines = export.toString().lines().toList();
                assertEquals(new WalHeader(1L, RuleRegistry.WILDS_RULES_VERSION,
                        WildsEngine.API_VERSION, RuleRegistry.minimalDefault().fingerprint())
                        .encode(), lines.get(0),
                        "export starts with the canonical WAL header");
                long expectedSeq = 1L;
                for (int i = 1; i < lines.size(); i++) {
                    WalRecord record = CommandCodec.decodeWal(lines.get(i));
                    long seq = switch (record) {
                        case WalRecord.ClockAdvance advance -> advance.walSeq();
                        case WalRecord.CommandEntry entry -> entry.walSeq();
                    };
                    assertEquals(expectedSeq++, seq,
                            "exported WAL stays dense for headless replay");
                }

                // FINAL GATE: cold headless replay lands on the same world hash.
                StringWriter headlessOut = new StringWriter();
                StringWriter headlessErr = new StringWriter();
                int exit = HeadlessMain.execute(new String[] {"replay", "--seed", "1",
                        "--wal", walPath.toString(), "--hash"},
                        new PrintWriter(headlessOut), new PrintWriter(headlessErr));
                assertEquals(0, exit, "headless replay failed: " + headlessErr);
                assertEquals("", headlessErr.toString(), "replay must be warning-free");
                String printedHash = headlessOut.toString().lines()
                        .filter(line -> line.startsWith("worldHash="))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "no worldHash line in replay output: " + headlessOut));
                assertEquals(String.format("0x%016x", finalHash),
                        printedHash.substring("worldHash=".length()).trim(),
                        "bit-for-bit WorldHash parity between the live server and the cold"
                                + " headless replay");
            } finally {
                if (server2Alive) {
                    server2.close();
                }
            }
        } finally {
            if (server1Alive) {
                server1.close();
            }
        }
    }

    // ------------------------------------------------------------------ rig

    /** Production constructor path: own pool + migrations + Postgres adapters. */
    private static WildsServer boot(SharedPostgresContainer.FreshDatabase db) {
        return WildsServer.open(new ServerConfig(0, 1L, 20L, 50L, AuthMode.DEV_LOCAL,
                "http://127.0.0.1:0", null, null, null, null, null, null, 100L, 200L,
                "127.0.0.1", ServerConfig.StorageMode.POSTGRES, db.jdbcUrl(), db.username(),
                db.password(), CHECKPOINTS_DISABLED, OnVersionMismatch.REFUSE));
    }

    private static String base(WildsServer server) {
        return "http://127.0.0.1:" + server.port();
    }

    private static HttpResponse.BodyHandler<String> ofString() {
        return HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8);
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("").trim();
    }

    private static HttpRequest get(String url, String cookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return builder.build();
    }

    private static HttpRequest postJson(String url, String json, String cookie, String origin) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return builder.build();
    }

    /** {@code POST /dev/login} persona switch; returns the Cookie header value. */
    private String login(String base, String persona) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base + "/dev/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("persona=" + persona,
                                StandardCharsets.UTF_8))
                        .build(),
                ofString());
        assertEquals(303, response.statusCode(), response.body());
        assertEquals("/play", response.headers().firstValue("Location").orElse(""),
                "dev login lands on the console");
        String setCookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("kw_dev_session="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no kw_dev_session Set-Cookie header"));
        assertTrue(setCookie.contains("HttpOnly"), "session cookie must be HttpOnly");
        return "kw_dev_session=" + setCookie.substring("kw_dev_session=".length(),
                setCookie.indexOf(';'));
    }

    /** Charters a fresh HUMAN seat; returns the 201 body. */
    private String charter(String base, String cookie, String seatName) throws Exception {
        HttpResponse<String> response = http.send(postJson(base + "/api/v1/seats",
                "{\"seatName\":\"" + seatName + "\",\"controllerKind\":\"HUMAN\"}", cookie,
                null), ofString());
        assertEquals(201, response.statusCode(), response.body());
        return response.body();
    }

    private boolean waitUntilIdle(String base, String cookie) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadlineNanos) {
            HttpResponse<String> observation = http.send(get(base + "/api/v1/observation",
                    cookie), ofString());
            if (observation.body().contains("\"activeTask\":null")
                    && observation.body().contains("\"pendingQueue\":[]")) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static long scalar(Jdbi jdbi, String sql, int worldId) {
        try (Handle handle = jdbi.open()) {
            return handle.select(sql, worldId).mapTo(long.class).one();
        }
    }

    private static List<Long> eventCursors(Jdbi jdbi, int worldId) {
        try (Handle handle = jdbi.open()) {
            return handle.select("SELECT event_cursor FROM wilds_world.world_events"
                            + " WHERE world_id = ? ORDER BY event_cursor", worldId)
                    .mapTo(long.class).list();
        }
    }

    /** Extracts the {@code <meta name="csrf-token" content="...">} value from SSR HTML. */
    private static String extractCsrfMeta(String html) {
        String marker = "<meta name=\"csrf-token\" content=\"";
        int start = html.indexOf(marker);
        assertTrue(start >= 0, "csrf-token meta tag missing in:\n" + html);
        int valueStart = start + marker.length();
        int valueEnd = html.indexOf('"', valueStart);
        return html.substring(valueStart, valueEnd);
    }

    /** Crude top-level string field extraction (charter body). */
    private static String jsonField(String body, String field) {
        int key = body.indexOf("\"" + field + "\":\"");
        int start = key + field.length() + 4; // skip past "\"field\":\""
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    /** Crude top-level numeric field extraction (compact canonical JSON). */
    private static long jsonLong(String body, String field) {
        String needle = "\"" + field + "\":";
        int key = body.indexOf(needle);
        if (key < 0) {
            fail("field \"" + field + "\" not found in: " + body);
        }
        int start = key + needle.length();
        int end = start;
        while (end < body.length() && (end == start && body.charAt(end) == '-'
                || Character.isDigit(body.charAt(end)))) {
            end++;
        }
        return Long.parseLong(body.substring(start, end));
    }

    /** Every cursor occurrence in an events page must strictly ascend (no duplicates). */
    private static void assertAscendingCursors(String body) {
        long previous = 0;
        int index = 0;
        String needle = "\"cursor\":";
        while ((index = body.indexOf(needle, index)) >= 0) {
            long cursor = jsonLong(body.substring(index), "cursor");
            assertTrue(cursor > previous, "cursors must strictly ascend: " + cursor
                    + " after " + previous);
            previous = cursor;
            index += needle.length();
        }
    }

    /**
     * Background reader of one SSE stream: parses {@code id:/event:/data:} frames
     * into a queue, enforces strictly monotonic cursors, and treats the abrupt
     * server-side close as the normal end of stream.
     */
    private static final class SseTail implements AutoCloseable {

        record Frame(long cursor, String kind, String data) {
        }

        private final HttpResponse<InputStream> response;
        private final Thread reader;
        private final LinkedBlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
        private final AtomicLong lastCursor = new AtomicLong(0);
        private final AtomicLong firstCursor = new AtomicLong(0);
        private volatile String failure;

        SseTail(HttpClient client, String url, String cookie, String lastEventId)
                throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
            if (cookie != null) {
                builder.header("Cookie", cookie);
            }
            if (lastEventId != null) {
                builder.header("Last-Event-ID", lastEventId);
            }
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            assertEquals("text/event-stream; charset=utf-8", contentType(response));
            reader = Thread.ofVirtual().name("wilds-sse-tail").start(this::drain);
        }

        private void drain() {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                long cursor = 0;
                String kind = null;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("id: ")) {
                        cursor = Long.parseLong(line.substring(4).trim());
                    } else if (line.startsWith("event: ")) {
                        kind = line.substring(7).trim();
                    } else if (line.startsWith("data: ")) {
                        Frame frame = new Frame(cursor, kind, line.substring(6));
                        long previous = lastCursor.getAndAccumulate(frame.cursor(), Math::max);
                        if (frame.cursor() <= previous) {
                            failure = "non-monotonic cursor " + frame.cursor()
                                    + " after " + previous;
                        }
                        firstCursor.compareAndSet(0, frame.cursor());
                        frames.put(frame);
                    }
                }
            } catch (IOException closed) {
                // the server-side close (poison marker / abort) ends the stream
            } catch (InterruptedException dropped) {
                Thread.currentThread().interrupt();
            }
        }

        /** Awaits the given ordered frame subsequence (monotonic cursors enforced). */
        void awaitSequence(String description, List<Predicate<Frame>> steps)
                throws InterruptedException {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            int matched = 0;
            while (matched < steps.size()) {
                if (System.nanoTime() > deadlineNanos) {
                    fail(description + ": matched " + matched + " of " + steps.size()
                            + " steps; stream failure=" + failure + "; readerAlive="
                            + reader.isAlive() + "; framesSeen=" + frames.size());
                }
                Frame frame = frames.poll(200, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                if (steps.get(matched).test(frame)) {
                    matched++;
                }
            }
            assertNull(failure, "stream stayed healthy across " + description);
        }

        /** The cursor of the first delivered frame ({@code 0} before any frame). */
        long firstCursor() {
            return firstCursor.get();
        }

        @Override
        public void close() {
            try {
                response.body().close(); // wakes a blocked drain with the normal IOException
            } catch (IOException ignored) {
                // already closed by the server
            }
            try {
                reader.join(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
