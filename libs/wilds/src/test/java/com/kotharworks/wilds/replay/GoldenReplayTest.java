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
package com.kotharworks.wilds.replay;

import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.command.ErrorCode;
import com.kotharworks.wilds.command.Phase1Result;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.state.PlaceEdge;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.TaskSpec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden replay harness: a fully scripted multi-owner scenario (chain travel, fail-soft
 * policies, multi-batch waits, active cancellation, integer permille energy penalty)
 * must land on exactly the blessed final {@code WorldHash}. Re-bless with
 * {@code -DblessGolden=true} after an intentional determinism-relevant change.
 */
class GoldenReplayTest {

    private static final Path GOLDEN = Path.of("src", "test", "resources", "golden",
            "minimal-spine.hash");

    @Test
    void scriptedScenarioReproducesBlessedWorldHash() throws IOException {
        long finalHash = runScenario();

        if (Boolean.getBoolean("blessGolden")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, Long.toString(finalHash) + "\n");
        }
        assertTrue(Files.isRegularFile(GOLDEN),
                "golden hash file missing; run with -DblessGolden=true to bless it");
        long blessed = Long.parseLong(Files.readString(GOLDEN).trim());
        assertEquals(blessed, finalHash,
                "final world hash drifted from the blessed golden value");
    }

    private static long runScenario() {
        WildsEngine engine = new WildsEngine(new WorldSeed(0x5EED0004L));

        // (1) + (2): two owners register.
        applyOk(engine, new WorldCommand.RegisterOwner(1L, "Ash", "Ash's Scout"));
        applyOk(engine, new WorldCommand.RegisterOwner(2L, "Bram", "Bram's Walker"));
        OwnerId ash = engine.view().findOwnerBySeatKey(1L).orElseThrow().id();
        OwnerId bram = engine.view().findOwnerBySeatKey(2L).orElseThrow().id();
        ActorId ashActor = engine.view().actorsOwnedBy(ash).get(0).id();
        ActorId bramActor = engine.view().actorsOwnedBy(bram).get(0).id();
        engine.drainEvents();

        // (3): optimistic chain [Travel(1), Travel(2)] walks 0 -> 1 -> 2.
        applyOk(engine, new WorldCommand.EnqueueTasks(ash, ashActor, null,
                List.of(new TaskSpec.Travel(new PlaceId(1, 1)),
                        new TaskSpec.Travel(new PlaceId(2, 1)))));
        runToIdle(engine);
        assertEquals(new PlaceId(2, 1),
                engine.view().actor(ashActor).orElseThrow().placeId());

        // (4): Bram under ADVANCE: broken route then the wait still runs.
        applyOk(engine, new WorldCommand.EnqueueTasks(bram, bramActor, null,
                List.of(new TaskSpec.Travel(new PlaceId(2, 1)),
                        new TaskSpec.Wait(2_000, 1))));
        assertTrue(engine.drainEvents().stream().anyMatch(event -> event
                        instanceof WorldEvent.TaskFailed taskFailed
                        && taskFailed.failureCode() == ErrorCode.NO_ROUTE),
                "expected TaskFailed(NO_ROUTE) for Bram's broken route");
        runToIdle(engine);

        // (5): Bram under HALT_ON_FAIL: broken route clears the queue.
        applyOk(engine, new WorldCommand.SetFailurePolicy(bram, bramActor, null,
                QueueFailurePolicy.HALT_ON_FAIL));
        applyOk(engine, new WorldCommand.EnqueueTasks(bram, bramActor, null,
                List.of(new TaskSpec.Travel(new PlaceId(2, 1)))));
        runToIdle(engine);
        assertTrue(engine.view().actor(bramActor).orElseThrow().pendingQueue().isEmpty());
        engine.drainEvents();

        // (6): Ash waits 3 batches, cancels the active batch, stale entry ignored.
        applyOk(engine, new WorldCommand.EnqueueTasks(ash, ashActor, null,
                List.of(new TaskSpec.Wait(1_000, 3))));
        engine.advanceTo(engine.nowMs() + 1_000L); // batch 1 completes; batch 2 re-queued
        engine.drainEvents();
        applyOk(engine, new WorldCommand.CancelQueue(ash, ashActor, null, true));
        engine.drainEvents();
        engine.advanceTo(engine.nowMs() + 30_000L); // stale batch-2 entry pops and is skipped
        assertTrue(engine.drainEvents().isEmpty(), "stale heap entry must be silent");

        // (7): drain Ash's energy with alternating crossings, then one penalized travel.
        drainEnergy(engine, ash, ashActor);

        return engine.worldHash();
    }

    /** Alternates crossing plc:1:1 <-> plc:2:1 until the actor's energy drops below 15_000. */
    private static void drainEnergy(WildsEngine engine, OwnerId owner, ActorId actor) {
        boolean towardRidge = true;
        int crossings = 0;
        while (engine.view().actor(actor).orElseThrow().energyMilli()
                .valueMilliAt(engine.nowMs()) >= 15_000L) {
            assertTrue(crossings < 2_000, "energy drain loop must terminate");
            PlaceId destination = towardRidge ? new PlaceId(1, 1) : new PlaceId(2, 1);
            towardRidge = !towardRidge;
            applyOk(engine, new WorldCommand.EnqueueTasks(owner, actor, null,
                    List.of(new TaskSpec.Travel(destination))));
            runToIdle(engine);
            crossings++;
        }

        PlaceRow currentPlace = engine.view().place(
                engine.view().actor(actor).orElseThrow().placeId()).orElseThrow();
        PlaceEdge edge = currentPlace.edges().get(0);
        applyOk(engine, new WorldCommand.EnqueueTasks(owner, actor, null,
                List.of(new TaskSpec.Travel(edge.to()))));
        long penalizedDue = engine.peekNextDueMs().orElseThrow() - engine.nowMs();
        assertEquals((edge.travelDurationMs() * 1_350L) / 1_000L, penalizedDue,
                "the low-energy travel must carry the 1_350 permille penalty");
        runToIdle(engine);
    }

    private static void applyOk(WildsEngine engine, WorldCommand command) {
        Phase1Result result = engine.apply(command);
        assertInstanceOf(Phase1Result.Accepted.class, result,
                () -> "command must be accepted: " + command);
    }

    private static void runToIdle(WildsEngine engine) {
        while (engine.peekNextDueMs().isPresent()) {
            engine.advanceToNextEvent();
        }
    }
}
