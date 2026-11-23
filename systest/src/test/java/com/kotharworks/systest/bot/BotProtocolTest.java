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
package com.kotharworks.systest.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.kotharworks.systest.support.Api;
import com.kotharworks.systest.support.Await;
import com.kotharworks.systest.support.Bot;
import com.kotharworks.systest.support.Json;
import com.kotharworks.systest.support.Observation;
import com.kotharworks.systest.support.SseStream;
import com.kotharworks.systest.support.World;

/** The bot contract: observe → command → events, with the protocol's guarantees. */
@Tag("bot")
class BotProtocolTest {

    private static Bot bot;

    @BeforeAll
    static void charter() {
        bot = new Bot(World.botSeat("bot-protocol"));
        bot.awaitIdle();
    }

    @Test
    @DisplayName("a travel command moves the actor and streams its lifecycle over SSE")
    void travelLifecycle() {
        Observation before = bot.awaitIdle();
        String from = before.placeId();
        String to = before.exitsFromHere().getFirst();
        long cursor = bot.api().events(0).ok().get("nextSince").asLong();

        try (SseStream sse = SseStream.open(bot.api(), cursor)) {
            assertEquals(200, sse.awaitHeaders(java.time.Duration.ofSeconds(5)));
            Json.Node receipt = bot.travelTo(to).ok().get("receipt");
            assertTrue(receipt.get("walSeq").asLong() > 0, receipt::toString);

            sse.awaitKind("task_started", cursor);
            bot.awaitAt(to);
            sse.await("travel completion event", e -> e.id() > cursor
                    && (e.kind().equals("task_batch_completed")));

            List<Long> ids = sse.events().stream().map(SseStream.Event::id).toList();
            for (int i = 1; i < ids.size(); i++) {
                assertTrue(ids.get(i) > ids.get(i - 1), "SSE cursors strictly increase: " + ids);
            }
            for (SseStream.Event e : sse.events()) {
                assertEquals(bot.seat().seatId(), e.data().get("seatId").asLong(),
                        "bot only receives its own seat's events: " + e);
            }
        }
        Observation after = bot.observe();
        assertEquals(to, after.placeId());
        assertTrue(after.discoveredPlaces().contains(to), after::toString);
        assertTrue(after.revision() > before.revision());
        // and back again, so the test is re-runnable on a kept stack
        bot.travelTo(from).ok();
        bot.awaitAt(from);
    }

    @Test
    @DisplayName("REST catch-up returns exactly what the stream delivered")
    void restMatchesStream() {
        long cursor = bot.api().events(0).ok().get("nextSince").asLong();
        try (SseStream sse = SseStream.open(bot.api(), cursor)) {
            sse.awaitHeaders(java.time.Duration.ofSeconds(5));
            bot.waitFor(2_000, 2).ok();
            bot.awaitIdle();
            Json.Node page = Await.until("REST page has the two batches",
                    () -> bot.api().events(cursor).ok(),
                    p -> p.get("events").stream()
                            .filter(e -> e.get("kind").text().equals("task_batch_completed"))
                            .count() >= 2);
            List<Long> rest = page.get("events").stream().map(e -> e.get("cursor").asLong()).toList();
            Await.until("stream caught up with REST", sse::lastCursor,
                    last -> last >= rest.getLast());
            List<Long> streamed = sse.events().stream().map(SseStream.Event::id)
                    .filter(id -> id <= rest.getLast()).toList();
            assertEquals(rest, streamed);
        }
    }

    @Test
    @DisplayName("replaying a cmdId is idempotent: same receipt, applied once")
    void idempotency() {
        Observation obs = bot.awaitIdle();
        Map<String, Object> envelope = new java.util.LinkedHashMap<>(
                Api.waitTask(obs.actorId(), obs.revision(), 3_000, 1));
        envelope.put("cmdId", UUID.randomUUID().toString());
        Json.Node first = bot.api().command(envelope).ok().get("receipt");
        Json.Node second = bot.api().command(envelope).ok().get("receipt");
        assertEquals(first.toString(), second.toString());
        Observation now = bot.observe();
        assertTrue(now.pendingCount() + (now.hasActiveTask() ? 1 : 0) <= 1,
                "the duplicate must not enqueue a second task: " + now);
        bot.awaitIdle();
    }

    @Test
    @DisplayName("a stale expectedRevision is rejected with 409 STALE_STATE and changes nothing")
    void staleRevision() {
        Observation obs = bot.awaitIdle();
        Api.Resp stale = bot.api().command(
                Api.waitTask(obs.actorId(), obs.revision() - 1, 1_000, 1));
        assertEquals(409, stale.status(), stale::toString);
        assertEquals("STALE_STATE", stale.errorCode());
        Observation after = bot.observe();
        assertEquals(obs.revision(), after.revision());
        assertTrue(after.idle());
    }

    @Test
    @DisplayName("cancel-active stops a long task and emits task_cancelled")
    void cancelActive() {
        long cursor = bot.api().events(0).ok().get("nextSince").asLong();
        bot.waitFor(600_000, 3).ok();
        Await.until("task active", bot::observe, Observation::hasActiveTask);
        bot.act(o -> Api.cancel(o.actorId(), o.revision(), true)).ok();
        bot.awaitIdle();
        Json.Node page = bot.api().events(cursor).ok();
        assertTrue(page.get("events").stream()
                        .anyMatch(e -> e.get("kind").text().equals("task_cancelled")),
                page::toString);
    }

    @Test
    @DisplayName("failure policy toggles round-trip through observation")
    void failPolicy() {
        bot.act(o -> Api.failPolicy(o.actorId(), o.revision(), "HALT_ON_FAIL")).ok();
        assertEquals("HALT_ON_FAIL", bot.observe().failPolicy());
        bot.act(o -> Api.failPolicy(o.actorId(), o.revision(), "ADVANCE")).ok();
        assertEquals("ADVANCE", bot.observe().failPolicy());
    }

    @Test
    @DisplayName("malformed commands are 4xx with an errorCode — never 5xx, never a mutation")
    void malformedNeverServerError() {
        Observation obs = bot.awaitIdle();
        String actor = obs.actorId();
        List<Object> bodies = List.of(
                "", "{", "[]", "null", "{\"type\":\"enqueue\"}",
                Json.obj("cmdId", "not-a-uuid", "type", "enqueue"),
                Json.obj("type", "teleport", "targetActor", actor),
                Json.obj("type", "enqueue", "targetActor", "act:x:y", "tasks", List.of()),
                Json.obj("type", "enqueue", "targetActor", actor, "tasks", "nope"),
                Json.obj("type", "enqueue", "targetActor", actor,
                        "tasks", List.of(Json.obj("type", "wait", "durationMs", -5, "batches", 1))),
                Json.obj("type", "enqueue", "targetActor", actor,
                        "tasks", List.of(Json.obj("type", "wait", "durationMs", 1000, "batches", 0))),
                Json.obj("type", "enqueue", "targetActor", actor,
                        "tasks", List.of(Json.obj("type", "travel", "destination", "plc:999:1"))),
                Json.obj("type", "set_fail_policy", "targetActor", actor, "policy", "YOLO"));
        List<String> problems = new ArrayList<>();
        for (Object body : bodies) {
            Object envelope = body;
            if (body instanceof Map<?, ?> map && !map.containsKey("cmdId")) {
                Map<String, Object> withId = new java.util.LinkedHashMap<>();
                map.forEach((k, v) -> withId.put((String) k, v));
                withId.put("cmdId", UUID.randomUUID().toString());
                envelope = withId;
            }
            Api.Resp resp = bot.api().postJson("/api/v1/commands", envelope);
            if (resp.status() < 400 || resp.status() >= 500 || resp.errorCode() == null) {
                problems.add(Json.write(body) + " -> " + resp);
            }
        }
        assertTrue(problems.isEmpty(), "bad responses:\n" + String.join("\n", problems));
        Observation after = bot.observe();
        assertEquals(obs.revision(), after.revision(), "no rejected command may mutate");
        assertTrue(after.idle());
    }

    @Test
    @DisplayName("hammering past the burst budget yields 429 RATE_LIMITED, then recovers")
    void rateLimit() {
        Bot spammer = new Bot(World.botSeat("bot-ratelimit"));
        Observation obs = spammer.awaitIdle();
        boolean limited = false;
        for (int i = 0; i < 60 && !limited; i++) {
            Api.Resp resp = spammer.api().command(
                    Api.failPolicy(obs.actorId(), obs.revision() - 1, "ADVANCE"));
            if (resp.status() == 429) {
                limited = true;
                assertEquals("RATE_LIMITED", resp.errorCode());
            } else {
                assertFalse(resp.status() >= 500, resp::toString);
            }
        }
        assertTrue(limited, "expected 429 within 60 rapid commands");
        Await.until("bucket refills", () -> spammer.api().command(
                        Api.failPolicy(obs.actorId(), spammer.observe().revision(), "ADVANCE")),
                r -> r.status() == 200);
    }
}
