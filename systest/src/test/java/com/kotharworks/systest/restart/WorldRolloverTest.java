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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.kotharworks.systest.support.Api;
import com.kotharworks.systest.support.Await;
import com.kotharworks.systest.support.Bot;
import com.kotharworks.systest.support.Json;
import com.kotharworks.systest.support.Observation;
import com.kotharworks.systest.support.RunState;
import com.kotharworks.systest.support.World;

/**
 * Rules-version gate and world rollover across real server boots. {@code ./systest.sh}
 * runs {@code before-rollover} in the main phase, then boots the same database with a
 * different world seed: the default policy must refuse (exit 3), and a second boot with
 * {@code --on-version-mismatch=archive} must roll over to a fresh world and re-register
 * every pre-existing seat. {@code after-rollover} runs in a fresh JVM against that boot.
 */
@Tag("restart")
@Order(10001)
class WorldRolloverTest {

    private static final String SEAT = "rollover-bot";
    private static final Path REFUSE_LOG = Path.of("/reports/live/rollover-refuse.log");

    @Test
    @Tag("before-rollover")
    @DisplayName("record the bot seat and world facts just before the rollover")
    void recordBeforeRollover() {
        World.BotSeat seat = World.botSeat(SEAT);
        Bot bot = new Bot(seat);
        bot.awaitIdle();
        Json.Node meta = Api.anonymous().meta().ok();

        RunState.put("rollover.token", seat.token());
        RunState.put("rollover.seatId", seat.seatId());
        RunState.put("rollover.worldId", meta.get("worldId").asLong());
    }

    @Test
    @Tag("after-rollover")
    @DisplayName("a mismatched seed is refused (exit 3); archive rolls over and keeps every seat")
    void verifyAfterRollover() throws IOException {
        assertEquals("3", System.getenv("SYSTEST_REFUSE_EXIT_CODE"),
                "the default policy must refuse a mismatched world with exit code 3");
        assertRefuseDiagnostic();

        // The archive boot serves the new seed in a strictly newer world.
        Json.Node meta = Api.anonymous().meta().ok();
        long expectedSeed = Long.parseLong(System.getenv("SYSTEST_ROLLOVER_SEED"));
        assertEquals(expectedSeed, meta.get("worldSeed").asLong(), "rolled-over world seed");
        assertTrue(meta.get("worldId").asLong() > RunState.getLong("rollover.worldId"),
                "rollover must create a new world, got " + meta);

        // The pre-existing seat's token still authenticates, and the seat has an owner
        // in the new world without any further charter call.
        Bot bot = new Bot(new World.BotSeat(SEAT, RunState.getLong("rollover.seatId"), null,
                null, RunState.get("rollover.token"), 0));
        Api.Resp observed = Await.until("pre-existing seat has an owner in the new world",
                bot.api()::observation, r -> r.status() == 200);
        Observation obs = Observation.of(observed);
        assertFalse(obs.ownerId().isEmpty(), "seat has no owner after rollover: " + obs);

        // The actor is idle and usable in the new world.
        bot.awaitIdle();
        Api.Resp accepted = bot.waitFor(1_000, 1);
        assertTrue(accepted.status() >= 200 && accepted.status() < 300,
                "command rejected after rollover: " + accepted);
        bot.awaitIdle();
    }

    /** {@code systest.sh} dumps the refused boot's logs into the runner's {@code /reports} mount. */
    private static void assertRefuseDiagnostic() throws IOException {
        String log = Files.readString(REFUSE_LOG);
        assertTrue(log.contains("on-version-mismatch"),
                "refusal diagnostic should name --on-version-mismatch:\n" + log);
        assertFalse(log.contains("\tat "), "refusal must not print a stack trace:\n" + log);
    }
}
