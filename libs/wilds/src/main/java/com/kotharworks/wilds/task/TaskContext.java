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
package com.kotharworks.wilds.task;

import com.kotharworks.wilds.event.EventOutbox;
import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.state.WorldState;

import java.util.Objects;

/**
 * Per-step execution context handed to task mechanics: the mutable world they may
 * mutate (single writer), the structural rules in force, and the event outbox to
 * append to. Value object — cheap to create per engine step, never stored in state.
 */
public record TaskContext(WorldState state, RuleRegistry rules, EventOutbox outbox) {

    public TaskContext {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(outbox, "outbox");
    }

    /** Appends {@code event} to the outbox (cursor allocation is the caller's job). */
    public void emit(WorldEvent event) {
        outbox.add(event);
    }
}
