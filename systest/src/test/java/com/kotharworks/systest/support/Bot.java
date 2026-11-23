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
package com.kotharworks.systest.support;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Bot mode: a scripted agent driving one SCRIPT_BOT seat exclusively through
 * its scoped {@code iw_bot_} token — the same contract a third-party bot would
 * use ({@code /api/v1/observation}, {@code POST /api/v1/commands}, SSE).
 * Commands carry {@code expectedRevision}; {@code STALE_STATE} is retried
 * against a fresh observation, exactly as a well-behaved bot should.
 */
public final class Bot {

    private static final int MAX_STALE_RETRIES = 5;
    /** Seat budget is 10 req/s incl. reads; poll at 4/s to leave room for commands. */
    private static final java.time.Duration POLL = java.time.Duration.ofMillis(250);

    private final World.BotSeat seat;
    private final Api api;

    public Bot(World.BotSeat seat) {
        this.seat = seat;
        this.api = Api.bot(seat.token());
    }

    public World.BotSeat seat() {
        return seat;
    }

    public Api api() {
        return api;
    }

    public Observation observe() {
        return Observation.of(api.observation());
    }

    /**
     * Builds a command from a fresh observation and submits it, retrying on
     * {@code 409 STALE_STATE} and pacing on {@code 429 RATE_LIMITED}. Returns
     * the final response (2xx, or the first non-retryable error).
     */
    public Api.Resp act(Function<Observation, Map<String, Object>> command) {
        Api.Resp last = null;
        for (int attempt = 0; attempt <= MAX_STALE_RETRIES; attempt++) {
            Observation obs = observe();
            last = api.command(command.apply(obs));
            if (last.status() == 409 && "STALE_STATE".equals(last.errorCode())) {
                continue;
            }
            if (last.status() == 429) {
                Await.sleep(java.time.Duration.ofMillis(250));
                continue;
            }
            return last;
        }
        return last;
    }

    public Api.Resp travelTo(String destination) {
        return act(obs -> Api.travel(obs.actorId(), obs.revision(), destination));
    }

    public Api.Resp waitFor(long durationMs, int batches) {
        return act(obs -> Api.waitTask(obs.actorId(), obs.revision(), durationMs, batches));
    }

    /** Blocks until the actor has no active task and an empty queue. */
    public Observation awaitIdle() {
        return Await.until(seat.name() + " idle", SysEnv.eventually(), POLL, this::observe,
                Observation::idle);
    }

    /** Blocks until the actor stands at {@code placeId} with nothing queued. */
    public Observation awaitAt(String placeId) {
        return Await.until(seat.name() + " at " + placeId, SysEnv.eventually(), POLL,
                this::observe, obs -> obs.idle() && obs.placeId().equals(placeId));
    }

    /** Travels one hop to the first exit that is not {@code avoid} (or any exit). */
    public String hopAwayFrom(String avoid) {
        Observation obs = awaitIdle();
        List<String> exits = obs.exitsFromHere();
        String next = exits.stream().filter(e -> !e.equals(avoid)).findFirst()
                .orElse(exits.getFirst());
        travelTo(next).ok();
        awaitAt(next);
        return next;
    }
}
