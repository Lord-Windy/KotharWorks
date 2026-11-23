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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.kotharworks.wilds.server.postgres.VersionGateFixtures.postgresConfig;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.headless.HeadlessMain;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.replay.WalHeader;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.server.ServerConfig;
import com.kotharworks.wilds.server.WildsServer;
import com.kotharworks.wilds.server.adapters.postgres.PostgresWorldStore;
import com.kotharworks.wilds.server.domain.SeatCharter;
import com.kotharworks.wilds.server.ports.OnVersionMismatch;

/**
 * Ticket 11 §3.6 / §4 "Postgres IT, archive", end to end over the production
 * composition root: world A with two chartered seats and played commands is
 * archived by a {@code --seed 2 --on-version-mismatch=archive} restart; A's
 * rows are untouched and its export still replays to its final hash; world B
 * begins with one mailbox-committed {@code RegisterOwner} per seat in
 * {@code seat_id} order, written before {@code WildsServer.start()}; both
 * seats observe an owner in B; and B's fingerprinted export replays in
 * {@code wilds-headless} to B's live {@code WorldHash} (Review Focus 6, 7).
 */
@Timeout(240)
class WorldRolloverIntegrationTest {

    private static final long FP = RuleRegistry.minimalDefault().fingerprint();
    private static final List<String> WORLD_TABLES =
            List.of("command_wal", "world_checkpoints", "world_events");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @AfterEach
    void closeHttpClient() {
        http.close();
    }

    @Test
    void archiveRolloverKeepsWorldAReplayableAndCarriesEverySeatIntoWorldB(@TempDir Path tmp)
            throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        PostgresWorldStore store = new PostgresWorldStore(jdbi);

        // ---- world A (seed 1): two seats with owners, some played commands
        ServerConfig configA = postgresConfig(db, 1L, OnVersionMismatch.REFUSE);
        String alice;
        String sam;
        long aliceSeat;
        long samSeat;
        int worldA;
        WildsServer serverA = WildsServer.open(configA);
        try {
            serverA.start();
            String base = base(serverA);
            worldA = serverA.worldHost().worldId();
            alice = login(base, "dev-alice");
            sam = login(base, "dev-sam");
            JsonCodec.JsonObj aliceCharter = charter(base, alice,
                    "{\"seatName\":\"Alice House\",\"controllerKind\":\"HUMAN\","
                            + "\"initialActorLabel\":\"Wanderer\"}");
            JsonCodec.JsonObj samCharter = charter(base, sam,
                    "{\"seatName\":\"Sam Watch\",\"controllerKind\":\"HUMAN\"}");
            aliceSeat = intField(aliceCharter, "seatId");
            samSeat = intField(samCharter, "seatId");
            assertTrue(aliceSeat < samSeat);

            command(base, alice, aliceSeat, strField(aliceCharter, "actorId"),
                    "[{\"type\":\"travel\",\"destination\":\"plc:1:1\"},"
                            + "{\"type\":\"wait\",\"durationMs\":1000,\"batches\":1}]");
            command(base, sam, samSeat, strField(samCharter, "actorId"),
                    "[{\"type\":\"wait\",\"durationMs\":1000,\"batches\":2}]");
            assertTrue(waitUntilIdle(base, alice, aliceSeat), "Alice's queue drained");
            assertTrue(waitUntilIdle(base, sam, samSeat), "Sam's queue drained");
            // A long-running task left in flight: the rollover must not care.
            command(base, alice, aliceSeat, strField(aliceCharter, "actorId"),
                    "[{\"type\":\"wait\",\"durationMs\":600000,\"batches\":1}]");
        } finally {
            serverA.close(); // graceful: shutdown anchor + checkpoint
        }
        long finalHashA = serverA.worldHost().worldHash();
        assertEquals(finalHashA, store.loadLatestCheckpoint(worldA).orElseThrow().worldHash(),
                "A's latest checkpoint pins its final hash");
        Map<String, Long> countsA = worldRowCounts(jdbi, worldA);
        assertTrue(countsA.get("command_wal") > 4, countsA.toString());
        assertTrue(countsA.get("world_events") > 0, countsA.toString());
        assertTrue(countsA.get("world_checkpoints") > 0, countsA.toString());

        // ---- restart with a different seed and --on-version-mismatch=archive
        ServerConfig configB = configA.withWorldSeed(2L)
                .withOnVersionMismatch(OnVersionMismatch.ARCHIVE);
        WildsServer serverB = WildsServer.open(configB);
        try {
            int worldB = serverB.worldHost().worldId();
            assertNotEquals(worldA, worldB);
            assertEquals(worldA, serverB.worldHost().archivedWorld().orElseThrow().worldId());

            // Before start(): the HTTP listener is not accepting yet, and B's WAL already
            // begins with the mailbox-committed seat registrations in seat_id order.
            List<WalRecord> walB = store.loadWalAfter(worldB, 0L);
            assertTrue(walB.size() >= 2, walB.toString());
            long[] seatOrder = {aliceSeat, samSeat};
            String[] seatNames = {"Alice House", "Sam Watch"};
            for (int i = 0; i < 2; i++) {
                WalRecord.CommandEntry entry = assertInstanceOf(WalRecord.CommandEntry.class,
                        walB.get(i));
                assertEquals(i + 1L, entry.walSeq(), "registrations open world B's WAL");
                assertEquals(seatOrder[i], entry.seatId(), "seat_id order");
                assertEquals(SeatCharter.registerCmdId(worldB, seatOrder[i]).toString(),
                        entry.cmdId());
                assertEquals(new WorldCommand.RegisterOwner(seatOrder[i], seatNames[i],
                        SeatCharter.defaultActorLabel(seatNames[i])), entry.command());
            }

            assertEquals(List.of("ARCHIVED", "true"), worldRow(jdbi, worldA),
                    "A is ARCHIVED with ended_at set");
            assertEquals(List.of("ACTIVE", "false", Long.toString(FP)), List.of(
                    worldRow(jdbi, worldB).get(0), worldRow(jdbi, worldB).get(1),
                    jdbi.withHandle(h -> h.select("SELECT rules_fingerprint FROM"
                            + " wilds_meta.worlds WHERE world_id = ?", worldB)
                            .mapTo(String.class).one())),
                    "B is ACTIVE with the running fingerprint");
            assertEquals(countsA, worldRowCounts(jdbi, worldA), "A's rows are untouched");

            serverB.start();
            String base = base(serverB);
            assertSeatOwnsInB(base, alice, aliceSeat, "Alice House");
            assertSeatOwnsInB(base, sam, samSeat, "Sam Watch");

            // A re-charter POST for an already carried-over seat dedupes on the same cmdId.
            HttpResponse<String> recharter = http.send(postJson(base + "/api/v1/seats",
                    "{\"seatName\":\"Sam Watch\",\"controllerKind\":\"HUMAN\"}", sam),
                    ofString());
            assertEquals(200, recharter.statusCode(), recharter.body());
            assertEquals(SeatCharter.registerCmdId(worldB, samSeat).toString(),
                    strField(json(recharter.body()), "cmdId"));

            serverB.close(); // graceful anchor + checkpoint pins B's live hash
            long finalHashB = serverB.worldHost().worldHash();
            assertEquals(finalHashB,
                    store.loadLatestCheckpoint(worldB).orElseThrow().worldHash());
            assertEquals(2L, store.loadWalAfter(worldB, 0L).stream()
                    .filter(r -> r instanceof WalRecord.CommandEntry entry
                            && entry.command() instanceof WorldCommand.RegisterOwner)
                    .count(), "the re-charter did not append a second registration");

            // ---- A's export still replays (seed 1) to A's final hash
            String exportA = export(store, worldA);
            assertEquals(new WalHeader(1L, RuleRegistry.WILDS_RULES_VERSION,
                    WildsEngine.API_VERSION, FP).encode(), exportA.lines().findFirst()
                    .orElseThrow());
            assertReplaysTo(tmp.resolve("world-a.wal.jsonl"), exportA, 1L, finalHashA);

            // ---- B's export carries the fingerprint and replays (seed 2) to B's hash
            String exportB = export(store, worldB);
            assertEquals(new WalHeader(2L, RuleRegistry.WILDS_RULES_VERSION,
                    WildsEngine.API_VERSION, FP).encode(), exportB.lines().findFirst()
                    .orElseThrow(), "B's export header carries the rules fingerprint");
            assertReplaysTo(tmp.resolve("world-b.wal.jsonl"), exportB, 2L, finalHashB);
        } finally {
            serverB.close(); // idempotent
        }
        assertEquals(countsA, worldRowCounts(jdbi, worldA),
                "A's rows stay untouched through B's whole life");
    }

    // ------------------------------------------------------------------ rig

    private void assertSeatOwnsInB(String base, String cookie, long seatId, String seatName)
            throws Exception {
        HttpResponse<String> response = http.send(
                get(base + "/api/v1/observation?seatId=" + seatId, cookie), ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonCodec.JsonObj owner = assertInstanceOf(JsonCodec.JsonObj.class,
                json(response.body()).getOrNull("owner"), response.body());
        assertEquals(seatId, intField(owner, "seatId"));
        assertEquals(seatName, strField(owner, "name"));
        JsonCodec.JsonArr actors = assertInstanceOf(JsonCodec.JsonArr.class,
                json(response.body()).getOrNull("actors"));
        assertFalse(actors.values().isEmpty(), response.body());
        assertEquals(SeatCharter.defaultActorLabel(seatName),
                strField((JsonCodec.JsonObj) actors.values().getFirst(), "label"),
                "a custom charter-time actor label is not persisted; rollover uses the default");
    }

    private static void assertReplaysTo(Path walPath, String export, long seed, long hash)
            throws Exception {
        Files.writeString(walPath, export);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = HeadlessMain.execute(new String[] {"replay", "--seed", Long.toString(seed),
                "--wal", walPath.toString(), "--hash"}, new PrintWriter(out),
                new PrintWriter(err));
        assertEquals(0, exit, "headless replay failed: " + err);
        assertEquals("", err.toString(), "replay must be warning-free");
        String printed = out.toString().lines()
                .filter(line -> line.startsWith("worldHash="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no worldHash line in: " + out));
        assertEquals(String.format("0x%016x", hash),
                printed.substring("worldHash=".length()).trim(),
                "seed " + seed + " export replays bit-for-bit to the live hash");
    }

    private static String export(PostgresWorldStore store, int worldId) throws Exception {
        StringWriter export = new StringWriter();
        store.exportWalJsonl(worldId, export);
        return export.toString();
    }

    private static List<String> worldRow(Jdbi jdbi, int worldId) {
        return jdbi.withHandle(h -> h.select("SELECT status, (ended_at IS NOT NULL)::text"
                        + " FROM wilds_meta.worlds WHERE world_id = ?", worldId)
                .map((rs, ctx) -> List.of(rs.getString(1), rs.getString(2)))
                .one());
    }

    private static Map<String, Long> worldRowCounts(Jdbi jdbi, int worldId) {
        return jdbi.withHandle(h -> {
            Map<String, Long> counts = new TreeMap<>();
            for (String table : WORLD_TABLES) {
                counts.put(table, h.select("SELECT count(*) FROM wilds_world." + table
                        + " WHERE world_id = ?", worldId).mapTo(long.class).one());
            }
            return Map.copyOf(counts);
        });
    }

    private static String base(WildsServer server) {
        return "http://127.0.0.1:" + server.port();
    }

    private static HttpResponse.BodyHandler<String> ofString() {
        return HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8);
    }

    private static HttpRequest get(String url, String cookie) {
        return HttpRequest.newBuilder(URI.create(url)).header("Cookie", cookie).GET().build();
    }

    private static HttpRequest postJson(String url, String json, String cookie) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
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
        String setCookie = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("kw_dev_session="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no kw_dev_session Set-Cookie header"));
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private JsonCodec.JsonObj charter(String base, String cookie, String body) throws Exception {
        HttpResponse<String> response = http.send(postJson(base + "/api/v1/seats", body,
                cookie), ofString());
        assertEquals(201, response.statusCode(), response.body());
        return json(response.body());
    }

    private void command(String base, String cookie, long seatId, String actorId,
            String tasks) throws Exception {
        HttpResponse<String> response = http.send(postJson(
                base + "/api/v1/commands?seatId=" + seatId,
                "{\"cmdId\":\"" + UUID.randomUUID() + "\",\"type\":\"enqueue\","
                        + "\"targetActor\":\"" + actorId + "\",\"expectedRevision\":null,"
                        + "\"tasks\":" + tasks + "}", cookie), ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    private boolean waitUntilIdle(String base, String cookie, long seatId) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadlineNanos) {
            String body = http.send(get(base + "/api/v1/observation?seatId=" + seatId, cookie),
                    ofString()).body();
            if (body.contains("\"activeTask\":null") && body.contains("\"pendingQueue\":[]")) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static JsonCodec.JsonObj json(String body) {
        return (JsonCodec.JsonObj) JsonCodec.parse(body);
    }

    private static long intField(JsonCodec.JsonObj obj, String key) {
        return ((JsonCodec.JsonInt) obj.getOrNull(key)).value();
    }

    private static String strField(JsonCodec.JsonObj obj, String key) {
        return ((JsonCodec.JsonStr) obj.getOrNull(key)).value();
    }
}
