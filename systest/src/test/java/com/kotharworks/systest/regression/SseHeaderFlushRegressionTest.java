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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.kotharworks.systest.support.Api;
import com.kotharworks.systest.support.Await;
import com.kotharworks.systest.support.SseStream;
import com.kotharworks.systest.support.World;

/**
 * Regression for commit 078e5f8 (Pass 09 review): SSE response headers must be
 * flushed immediately even when there is nothing to replay, otherwise browsers
 * sit in "connecting" until the first event.
 */
@Tag("regression")
class SseHeaderFlushRegressionTest {

    @Test
    @DisplayName("078e5f8: an idle stream delivers headers at once, not at the first event")
    void headersFlushImmediately() {
        World.BotSeat seat = World.botSeat("regression-sse");
        Api bot = Api.bot(seat.token());
        long head = bot.events(0).ok().get("nextSince").asLong();
        try (SseStream sse = SseStream.open(bot, head)) {
            assertEquals(200, sse.awaitHeaders(Duration.ofSeconds(2)),
                    "SSE headers must not wait for the first event");
            Await.until("connected comment", sse::comments, c -> c.contains("connected"));
        }
    }
}
