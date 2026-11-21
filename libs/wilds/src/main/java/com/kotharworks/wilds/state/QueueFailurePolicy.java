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

/**
 * What an actor's task queue does when the active task fails.
 */
public enum QueueFailurePolicy {
    /** Drop the failed task and advance to the next queued task. */
    ADVANCE,
    /** Stop consuming the queue when a task fails; leave the remaining tasks queued. */
    HALT_ON_FAIL
}
