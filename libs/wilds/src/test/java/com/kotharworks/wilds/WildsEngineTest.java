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
package com.kotharworks.wilds;

import com.kotharworks.wilds.seed.WorldSeed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WildsEngineTest {

    @Test
    void pingAdvancesLogicalClockAndProducesDeterministicGreeting() {
        var engine = new WildsEngine(new WorldSeed(1001L));
        String reply = engine.apply(new WildsEngine.Command.Ping(1_500L));

        assertEquals(1_500L, engine.nowMs());
        assertTrue(reply.startsWith("kotharworks/wilds scaffold (API v1, t=1500ms, origin="));
    }
}
