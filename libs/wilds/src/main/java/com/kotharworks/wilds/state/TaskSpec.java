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
package com.kotharworks.wilds.state;

import com.kotharworks.wilds.id.PlaceId;

import java.util.Objects;

/**
 * Closed family of neutral task specifications an actor can queue; exhaustively
 * switchable. Deliberately gameplay-neutral — no skills, costs, or requirements.
 */
public sealed interface TaskSpec permits TaskSpec.Wait, TaskSpec.Travel {

    /** Wait in place for {@code durationMs}, repeated up to {@code batches} times. */
    record Wait(long durationMs, int batches) implements TaskSpec {
        public Wait {
            if (durationMs < 0) {
                throw new IllegalArgumentException("durationMs must be >= 0: " + durationMs);
            }
            if (batches < 1) {
                throw new IllegalArgumentException("batches must be >= 1: " + batches);
            }
        }
    }

    /** Travel along the place graph to {@code destination}; duration is edge-driven. */
    record Travel(PlaceId destination) implements TaskSpec {
        public Travel {
            Objects.requireNonNull(destination, "destination");
        }
    }
}
