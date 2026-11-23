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
package com.kotharworks.systest.mixed;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.kotharworks.systest.support.Api;
import com.kotharworks.systest.support.Bot;
import com.kotharworks.systest.support.HumanPlayer;
import com.kotharworks.systest.support.Humans;
import com.kotharworks.systest.support.Observation;
import com.kotharworks.systest.support.Persona;
import com.kotharworks.systest.support.SseStream;
import com.kotharworks.systest.support.World;

/**
 * Humans in browsers and bots over REST sharing one live world at the same
 * time — the shape of the real game. Asserts isolation (fog) in both
 * directions and that the public chronicle reflects everyone.
 */
@Tag("mixed")
@Tag("human")
@Tag("bot")
@ExtendWith(Humans.class)
class HumanAndBotCoexistenceTest {

    @Test
    @DisplayName("a human and a bot play simultaneously without seeing each other's private world")
    void humanAndBotShareTheWorld(Humans humans) throws Exception {
        World.humanSeat(Api.login(Persona.ALICE), World.ALICE_SEAT);
        Bot scout = new Bot(World.botSeat("mixed-scout"));
        scout.awaitIdle();
        long botCursor = scout.api().events(0).ok().get("nextSince").asLong();

        HumanPlayer alice = humans.spawn("alice");
        alice.open("/dev/login").clickText("Alice (dev)");
        alice.isOn("/play");
        assertThat(alice.page().locator("#event-log")).isVisible();
        String aliceActor = alice.bodyData("actor-id");

        try (SseStream botStream = SseStream.open(scout.api(), botCursor);
                ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            botStream.awaitHeaders(Duration.ofSeconds(5));
            // The bot wanders two hops out and back while Alice plays in her browser.
            Future<?> botRun = pool.submit(() -> {
                String start = scout.awaitIdle().placeId();
                String mid = scout.hopAwayFrom(start);
                scout.hopAwayFrom(mid);
                scout.travelTo(mid).ok();
                scout.awaitAt(mid);
                return null;
            });
            alice.click("button[data-cmd=wait5s]");
            alice.sees("#event-log", "[task_batch_completed]");
            alice.click("#travel button[data-cmd=travel]");
            alice.sees("#event-log", "[task_started]");
            botRun.get();

            // Bot side: never sees Alice's actor or seat events.
            Observation botView = scout.observe();
            assertFalse(botView.raw().toString().contains(aliceActor),
                    "bot observation leaked the human's actor: " + botView);
            long aliceSeat = World.humanSeat(alice.api(), World.ALICE_SEAT);
            for (SseStream.Event e : botStream.events()) {
                assertNotEquals(aliceSeat, e.data().get("seatId").asLong(), "leak: " + e);
            }

            // Human side: none of the bot's private event cursors appear in Alice's log.
            Set<String> botCursors = scout.api().events(botCursor).ok().get("events").stream()
                    .map(e -> "#" + e.get("cursor").asLong() + " ")
                    .collect(Collectors.toSet());
            assertFalse(botCursors.isEmpty(), "the bot produced events");
            List<String> aliceLog = alice.page().locator("#event-log li").allTextContents();
            for (String line : aliceLog) {
                assertTrue(botCursors.stream().noneMatch(line::startsWith),
                        "bot event in the human's log: " + line);
            }
        }
    }

    @Test
    @DisplayName("two humans in two browsers each control only their own actor")
    void twoHumans(Humans humans) {
        World.humanSeat(Api.login(Persona.ALICE), World.ALICE_SEAT);
        World.humanSeat(Api.login(Persona.SAM), "Sam Walks");
        HumanPlayer alice = humans.spawn("alice");
        HumanPlayer sam = humans.spawn("sam");
        alice.open("/dev/login").clickText("Alice (dev)");
        sam.open("/dev/login").clickText("Sam (dev)");
        alice.isOn("/play");
        sam.isOn("/play");
        assertThat(alice.page().locator("#event-log")).isVisible();
        assertThat(sam.page().locator("#event-log")).isVisible();

        assertNotEquals(alice.bodyData("actor-id"), sam.bodyData("actor-id"));
        assertNotEquals(alice.bodyData("seat-id"), sam.bodyData("seat-id"));

        // Sam's API session (on his own human seat) cannot drive Alice's actor even
        // with a correct revision.
        Observation aliceObs = Observation.of(alice.api().observation());
        Api.Resp hijack = sam.api().command(Long.parseLong(sam.bodyData("seat-id")),
                Api.waitTask(aliceObs.actorId(), aliceObs.revision(), 1_000, 1));
        assertEquals("FORBIDDEN", hijack.errorCode(), hijack::toString);
    }

    @Test
    @DisplayName("an anonymous spectator sees humans and bots on the public chronicle")
    void spectator(Humans humans) {
        World.humanSeat(Api.login(Persona.ALICE), World.ALICE_SEAT);
        World.botSeat("mixed-scout");
        HumanPlayer spectator = humans.spawn("spectator");
        spectator.open("/chronicle");
        spectator.sees("Seats");
        String seats = spectator.page().locator("body").textContent();
        assertFalse(seats.contains("HUMAN: 0"), seats);
        assertFalse(seats.contains("SCRIPT_BOT: 0"), seats);
        assertThat(spectator.page().locator("#public-feed li").first()).isVisible();
        assertFalse(spectator.page().content().contains("privateSummary"));
    }
}
