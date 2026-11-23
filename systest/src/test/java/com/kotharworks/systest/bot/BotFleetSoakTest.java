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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.kotharworks.systest.support.Api;
import com.kotharworks.systest.support.Await;
import com.kotharworks.systest.support.Bot;
import com.kotharworks.systest.support.Json;
import com.kotharworks.systest.support.Observation;
import com.kotharworks.systest.support.SseStream;
import com.kotharworks.systest.support.SysEnv;
import com.kotharworks.systest.support.World;

/**
 * A fleet of scripted bots wandering the world concurrently, each with a live
 * SSE subscription, for {@code SYSTEST_SOAK_SECONDS} (default 20). Catches
 * concurrency, ordering and leak regressions no single-request test will.
 */
@Tag("bot")
@Tag("soak")
class BotFleetSoakTest {

    private static final int FLEET = 6;

    @Test
    @DisplayName("a concurrent bot fleet keeps every protocol invariant under load")
    void fleet() throws Exception {
        List<Bot> bots = new ArrayList<>();
        for (int i = 0; i < FLEET; i++) {
            bots.add(new Bot(World.botSeat("fleet-" + i)));
        }
        ConcurrentLinkedQueue<String> serverErrors = new ConcurrentLinkedQueue<>();
        AtomicInteger accepted = new AtomicInteger();
        long deadline = System.nanoTime() + SysEnv.soakDuration().toNanos();
        long publicStart = Api.anonymous().publicEvents(0).ok().get("nextSince").asLong();

        List<SseStream> streams = new ArrayList<>();
        List<Integer> hops = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < FLEET; i++) {
                Bot bot = bots.get(i);
                long cursor = bot.api().events(0).ok().get("nextSince").asLong();
                SseStream sse = SseStream.open(bot.api(), cursor);
                streams.add(sse);
                Random rng = new Random(0xB07L + i);
                results.add(pool.submit(() -> wander(bot, rng, deadline, serverErrors, accepted)));
            }
            for (Future<Integer> result : results) {
                hops.add(result.get());
            }
        }

        try {
            assertTrue(serverErrors.isEmpty(), "5xx during soak:\n" + String.join("\n", serverErrors));
            for (int i = 0; i < FLEET; i++) {
                assertTrue(hops.get(i) >= 1, "bot fleet-" + i + " never completed a hop");
            }
            for (int i = 0; i < FLEET; i++) {
                Bot bot = bots.get(i);
                Observation obs = bot.awaitIdle();
                SseStream sse = streams.get(i);
                long restLast = bot.api().events(0).ok().get("nextSince").asLong();
                Await.until("fleet-" + i + " stream caught up", sse::lastCursor, c -> c >= restLast);
                List<SseStream.Event> events = sse.events();
                for (int j = 1; j < events.size(); j++) {
                    assertTrue(events.get(j).id() > events.get(j - 1).id(),
                            "fleet-" + i + " SSE order broken at " + j);
                }
                for (SseStream.Event e : events) {
                    assertEquals(bot.seat().seatId(), e.data().get("seatId").asLong(),
                            "fog leak into fleet-" + i + ": " + e);
                }
                assertTrue(obs.visiblePlaceIds().contains(obs.placeId()), obs::toString);
            }
            // Phase 0 task events are seat-private (no publicSummary), so the fleet's
            // movement must NOT surface on the public feed; whatever is there must be
            // public-safe.
            Json.Node publicPage = Api.anonymous().publicEvents(publicStart).ok();
            for (Json.Node e : publicPage.get("events").list()) {
                assertTrue(!e.has("privateSummary") && !e.has("payload") && !e.has("seatId"),
                        "public feed leaked private detail: " + e);
                assertTrue(e.get("publicSummary").text() != null
                        && !e.get("publicSummary").text().isBlank(), "non-public event: " + e);
            }
            System.out.printf("[soak] %d bots, %d accepted commands, hops=%s%n",
                    FLEET, accepted.get(), hops);
        } finally {
            streams.forEach(SseStream::close);
        }
    }

    /** One bot's life: pick a random exit (or a short wait), go, repeat. */
    private static int wander(Bot bot, Random rng, long deadline,
            ConcurrentLinkedQueue<String> serverErrors, AtomicInteger accepted) {
        int hops = 0;
        while (System.nanoTime() < deadline) {
            Observation obs = bot.awaitIdle();
            Api.Resp resp;
            String target = null;
            if (rng.nextInt(4) == 0) {
                resp = bot.waitFor(500 + rng.nextInt(2_000), 1 + rng.nextInt(2));
            } else {
                List<String> exits = obs.exitsFromHere();
                target = exits.get(rng.nextInt(exits.size()));
                String destination = target;
                resp = bot.travelTo(destination);
            }
            if (resp.status() >= 500) {
                serverErrors.add(bot.seat().name() + ": " + resp);
                Await.sleep(Duration.ofMillis(200));
                continue;
            }
            if (resp.status() == 200) {
                accepted.incrementAndGet();
                if (target != null) {
                    bot.awaitAt(target);
                    hops++;
                }
            }
        }
        return hops;
    }
}
