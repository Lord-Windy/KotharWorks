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
package com.kotharworks.systest.human;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import com.kotharworks.systest.support.Api;
import com.kotharworks.systest.support.HumanPlayer;
import com.kotharworks.systest.support.Humans;
import com.kotharworks.systest.support.Observation;
import com.kotharworks.systest.support.Persona;
import com.kotharworks.systest.support.World;

/**
 * Human mode: Alice plays through the real browser console — SSR pages plus
 * {@code wilds-ui.js} (EventSource, fetch commands, localStorage cursors).
 * Alice is reserved for browser play; API-side tests only ever re-find her
 * single seat via {@link World#ALICE_SEAT}.
 */
@Tag("human")
@ExtendWith(Humans.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Order(3)
class HumanConsoleTest {

    @Test
    @Order(1)
    @DisplayName("a new player finds the game, signs in, claims a seat and plays a turn")
    void firstSession(Humans humans) {
        boolean unseated = Api.login(Persona.ALICE).me().ok().get("seats").size() == 0;
        HumanPlayer alice = humans.spawn("alice");

        alice.open("/chronicle").sees("Ironwood Wilds — Chronicle");
        alice.clickText("Enter the wilds");
        alice.isOn("/dev/login");
        alice.clickText("Alice (dev)");
        alice.isOn("/play");

        if (unseated) {
            alice.sees("Claim Seat");
            alice.type("#seat-name", World.ALICE_SEAT);
            alice.click("#claim-seat-form input[type=submit]");
        }
        assertThat(alice.page().locator("#event-log")).isVisible();
        alice.sees("#event-log", "[owner_registered]");
        String actorId = alice.bodyData("actor-id");
        assertNotNull(actorId, "console carries the actor id");

        // Wait 5s: watch it start and finish live over SSE.
        alice.click("button[data-cmd=wait5s]");
        alice.sees("#event-log", "[task_started]");
        alice.sees("#event-log", "[task_batch_completed]");

        // Travel along the first path shown, watch the place label change.
        String from = alice.page().locator("#place-label").textContent();
        alice.click("#travel button[data-cmd=travel]");
        assertThat(alice.page().locator("#place-label")).not().hasText(from);

        // Examine the new place in the drawer.
        alice.click("#examine-btn");
        assertThat(alice.page().locator("#drawer")).isVisible();
        alice.sees("#drawer-text", "seed");
        alice.click("#drawer-close");
        assertThat(alice.page().locator("#drawer")).isHidden();

        // Sign out: back to the chronicle, and /play wants a login again.
        alice.click("input[value='Sign Out']");
        alice.isOn("/chronicle");
        alice.open("/play").isOn("/dev/login");
    }

    @Test
    @Order(2)
    @DisplayName("a STALE_STATE from a second tab is recovered without losing anything")
    void staleStateRecovery(Humans humans) {
        HumanPlayer alice = signedIn(humans);
        Api secondTab = alice.api();
        Observation obs = Observation.of(secondTab.observation());
        String revisionBefore = alice.page().locator("#revision").textContent().trim();

        // Second tab bumps the revision with a change that emits no stream event,
        // so the first tab's buttons still carry the old expectedRevision.
        String flipped = obs.failPolicy().equals("ADVANCE") ? "HALT_ON_FAIL" : "ADVANCE";
        secondTab.command(Api.failPolicy(obs.actorId(), obs.revision(), flipped)).ok();

        alice.click("button[data-cmd=wait5s]");
        alice.sees("#notice", "STALE_STATE");
        assertThat(alice.page().locator("#revision")).not().hasText(revisionBefore);
        alice.sees("#fail-policy", flipped);

        // The refreshed panel now works first time.
        alice.click("button[data-cmd=wait5s]");
        alice.sees("#event-log", "[task_started]");
        Observation after = Observation.of(secondTab.observation());
        assertNotEquals(obs.revision(), after.revision());
    }

    @Test
    @Order(3)
    @DisplayName("returning after time away shows what happened while you were gone")
    void whileYouWereAway(Humans humans) {
        HumanPlayer alice = signedIn(humans);
        alice.sees("#event-log", "#"); // console live, localStorage cursor recorded
        alice.open("/chronicle");

        Api secondTab = alice.api();
        Observation obs = Observation.of(secondTab.observation());
        secondTab.command(Api.waitTask(obs.actorId(), obs.revision(), 1_000, 1)).ok();
        com.kotharworks.systest.support.Await.until("away task done",
                () -> Observation.of(secondTab.observation()), Observation::idle);

        alice.open("/play");
        assertThat(alice.page().locator("#away-panel")).isVisible();
        alice.sees("#away-list", "[task_batch_completed]");
    }

    @Test
    @Order(4)
    @DisplayName("the same human on two devices sees one consistent world")
    void twoDevices(Humans humans) {
        HumanPlayer laptop = signedIn(humans);
        HumanPlayer phone = humans.spawn("alice-phone");
        phone.open("/dev/login").clickText("Alice (dev)");
        phone.isOn("/play");
        assertEquals(laptop.bodyData("actor-id"), phone.bodyData("actor-id"));

        laptop.click("button[data-cmd=wait5s]");
        phone.sees("#event-log", "[task_started]"); // pushed to the other device via SSE
        Api api = laptop.api();
        Observation settled = com.kotharworks.systest.support.Await.until("wait finished",
                () -> Observation.of(api.observation()), Observation::idle);
        String expected = String.valueOf(settled.revision());
        laptop.sees("#revision", expected);
        phone.sees("#revision", expected);
    }

    /** Alice, logged in through the persona page, sitting at her console. */
    private static HumanPlayer signedIn(Humans humans) {
        World.humanSeat(Api.login(Persona.ALICE), World.ALICE_SEAT);
        HumanPlayer alice = humans.spawn("alice");
        alice.open("/dev/login").clickText("Alice (dev)");
        alice.isOn("/play");
        assertThat(alice.page().locator("#event-log")).isVisible();
        return alice;
    }
}
