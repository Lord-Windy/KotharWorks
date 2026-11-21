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

/**
 * Closed family of deterministic rejection reasons for {@link WorldCommand} validation
 * (Phase 1) and task mechanics (Phase 2). Exhaustively switchable.
 *
 * <p>{@code NO_ROUTE} is Phase-2 only: Phase 1 deliberately accepts Travel commands to
 * any live place without adjacency checks so optimistic task chains can be queued.
 */
public enum ErrorCode {
    /** Actor or place is owned by another owner. */
    FORBIDDEN,
    /** Referenced owner, actor, or place does not exist in the world. */
    UNKNOWN_TARGET,
    /** {@code externalSeatKey} is already registered to an owner. */
    DUPLICATE_SEAT,
    /** {@code expectedRevision} optimistic-concurrency check failed. */
    STALE_STATE,
    /** Task list exceeds the pending-queue capacity. */
    QUEUE_FULL,
    /** Task list is empty where at least one task is required. */
    EMPTY_TASKS,
    /** The task kind is not registered in the world's {@code RuleRegistry}. */
    UNKNOWN_RULE,
    /** A command argument is malformed or outside its structural bounds. */
    INVALID_ARGUMENT,
    /** Phase 2: no place edge exists from the actor's current place to the destination. */
    NO_ROUTE
}
