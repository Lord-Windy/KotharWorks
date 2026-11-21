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
package com.kotharworks.wilds.command;

import java.util.Objects;

/**
 * Closed result family of the side-effect-free Phase-1 command validation;
 * exhaustively switchable.
 */
public sealed interface Phase1Result permits Phase1Result.Accepted, Phase1Result.Rejected {

    /** The command passed every Phase-1 check and may be executed. */
    record Accepted() implements Phase1Result {
    }

    /**
     * The command was rejected; the world is guaranteed untouched. {@code currentRevision}
     * carries the actor's live revision (0 for owner-level rejections) so clients can
     * retry with a fresh {@code expectedRevision}.
     */
    record Rejected(ErrorCode errorCode, long currentRevision, String detail)
            implements Phase1Result {

        public Rejected {
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
