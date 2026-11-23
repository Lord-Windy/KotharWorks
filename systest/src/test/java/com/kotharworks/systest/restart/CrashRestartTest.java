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
package com.kotharworks.systest.restart;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.kotharworks.systest.support.Api;
import com.kotharworks.systest.support.Await;
import com.kotharworks.systest.support.Bot;
import com.kotharworks.systest.support.HumanPlayer;
import com.kotharworks.systest.support.Humans;
import com.kotharworks.systest.support.Json;
import com.kotharworks.systest.support.Observation;
import com.kotharworks.systest.support.Persona;
import com.kotharworks.systest.support.RunState;
import com.kotharworks.systest.support.SseStream;
import com.kotharworks.systest.support.World;

/**
 * Crash recovery across a real process boundary. {@code ./systest.sh} runs
 * the {@code before-restart} method in the main phase, then SIGKILLs the
 * server container (no graceful checkpoint), boots it again against the same
 * PostgreSQL, and runs the {@code after-restart} method in a fresh JVM.
 *
 * <p>To add more crash coverage: record facts with {@link RunState#put} in a
 * {@code @Tag("before-restart")} method and assert them in a
 * {@code @Tag("after-restart")} method.
 */
@Tag("restart")
@ExtendWith(Humans.class)
@Order(10000)
class CrashRestartTest {

    private static final String SEAT = "restart-bot";

    @Test
    @Tag("before-restart")
    @DisplayName("record world facts, leaving a long task in flight, just before the crash")
    void recordBeforeCrash() {
        World.BotSeat seat = World.botSeat(SEAT);
        Bot bot = new Bot(seat);
        bot.awaitIdle();
        String place = bot.hopAwayFrom("");
        Json.Node receipt = bot.waitFor(3_600_000, 1).ok().get("receipt");
        Observation inFlight = Await.until("long task active", bot::observe,
                Observation::hasActiveTask);

        Api alice = Api.login(Persona.ALICE);
        World.humanSeat(alice, World.ALICE_SEAT);

        RunState.put("bot.token", seat.token());
        RunState.put("bot.place", place);
        RunState.put("bot.revision", inFlight.revision());
        RunState.put("bot.dueAtMs", inFlight.actor().get("activeTask").get("dueAtMs").asLong());
        RunState.put("bot.lastCursor", bot.api().events(0).ok().get("nextSince").asLong());
        RunState.put("bot.walSeq", receipt.get("walSeq").asLong());
        RunState.put("bot.admittedMs", receipt.get("admittedMs").asLong());
        RunState.put("alice.cookie", alice.sessionCookie().orElseThrow());
        RunState.put("wall.recordedAtMs", System.currentTimeMillis());
        RunState.put("world.timeScale", Api.anonymous().meta().ok().get("timeScale").asLong());
    }

    @Test
    @Tag("after-restart")
    @DisplayName("after kill -9 the world, sessions, tokens and cursors all survive")
    void verifyAfterCrash(Humans humans) {
        Bot bot = new Bot(new World.BotSeat(SEAT, 0, null, null, RunState.get("bot.token"), 0));

        // Token still authenticates; the actor is exactly where it was, task still in flight.
        Observation obs = bot.observe();
        assertEquals(RunState.get("bot.place"), obs.placeId());
        assertTrue(obs.hasActiveTask(), "in-flight task lost in the crash: " + obs);
        assertEquals(RunState.getLong("bot.dueAtMs"),
                obs.actor().get("activeTask").get("dueAtMs").asLong());
        assertEquals(RunState.getLong("bot.revision"), obs.revision());

        // PAUSE_WHILE_DOWN: logical time never runs while the server is down.
        long timeScale = RunState.getLong("world.timeScale");
        long logicalElapsed = obs.nowMs() - RunState.getLong("bot.admittedMs");
        long wallElapsed = System.currentTimeMillis() - RunState.getLong("wall.recordedAtMs");
        long downtime = Long.parseLong(System.getenv().getOrDefault("SYSTEST_DOWNTIME_MS", "0"));
        assertTrue(logicalElapsed >= 0, "logical clock rewound behind a committed command");
        assertTrue(logicalElapsed <= (wallElapsed - downtime) * timeScale + 2_000 * timeScale,
                "logical clock ran while down: logical=" + logicalElapsed + "ms wall="
                        + wallElapsed + "ms downtime=" + downtime + "ms scale=" + timeScale);

        // Cursors and WAL sequence continue strictly forward — no rewind, no reuse.
        long lastCursor = RunState.getLong("bot.lastCursor");
        try (SseStream sse = SseStream.open(bot.api(), lastCursor)) {
            assertEquals(200, sse.awaitHeaders(Duration.ofSeconds(5)));
            Json.Node receipt = bot.act(o -> Api.cancel(o.actorId(), o.revision(), true))
                    .ok().get("receipt");
            assertTrue(receipt.get("walSeq").asLong() > RunState.getLong("bot.walSeq"));
            SseStream.Event cancelled = sse.awaitKind("task_cancelled", lastCursor);
            assertTrue(cancelled.id() > lastCursor);
            assertTrue(sse.events().stream().allMatch(e -> e.id() > lastCursor),
                    "replayed pre-crash events: " + sse.events());
        }
        bot.awaitIdle();

        // Alice's browser session (opaque, Postgres-backed) outlives the process.
        String cookie = RunState.get("alice.cookie");
        Api.resumeSession(cookie).me().expect(200);
        HumanPlayer alice = humans.spawn("alice-after-crash");
        alice.restoreSession(cookie);
        alice.open("/play");
        alice.isOn("/play");
        assertThat(alice.page().locator("#event-log")).isVisible();
    }
}
