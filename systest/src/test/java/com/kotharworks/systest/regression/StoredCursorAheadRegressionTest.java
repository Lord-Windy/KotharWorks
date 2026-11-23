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
package com.kotharworks.systest.regression;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.kotharworks.systest.support.Api;
import com.kotharworks.systest.support.HumanPlayer;
import com.kotharworks.systest.support.Humans;
import com.kotharworks.systest.support.Persona;
import com.kotharworks.systest.support.World;

/**
 * Regression for commit a6a5d1c (T09-1): a localStorage SSE cursor that is
 * ahead of the server's tail (fresh world, DB reset) must be discarded —
 * otherwise the console silently swallows every live event below it.
 */
@Tag("regression")
@Tag("human")
@ExtendWith(Humans.class)
class StoredCursorAheadRegressionTest {

    @Test
    @DisplayName("a6a5d1c: a stale future cursor in localStorage does not mute the live log")
    void futureCursorIsDiscarded(Humans humans) {
        World.humanSeat(Api.login(Persona.ALICE), World.ALICE_SEAT);
        HumanPlayer alice = humans.spawn("alice");
        alice.open("/dev/login").clickText("Alice (dev)");
        alice.isOn("/play");
        assertThat(alice.page().locator("#event-log")).isVisible();

        String key = "wilds:lastCursor:" + alice.bodyData("world-id") + ":" + alice.bodyData("seat-id");
        alice.page().evaluate("k => localStorage.setItem(k, JSON.stringify({cursor: 999999999}))", key);
        alice.page().reload();
        assertThat(alice.page().locator("#event-log")).isVisible();

        var entries = alice.page().locator("#event-log li");
        String lastBefore = entries.count() == 0 ? "" : entries.last().textContent();
        alice.click("button[data-cmd=wait5s]");
        assertThat(entries.last()).not().hasText(lastBefore);
        assertThat(entries.last()).containsText("[task_");
    }
}
