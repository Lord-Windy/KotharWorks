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

/**
 * The server's built-in dev-local personas (see {@code DevPersona} in
 * {@code libs/auth}). The live stack is fresh per run but shared by every test
 * in it, so tests must treat persona state as cumulative: use the idempotent
 * helpers in {@link World} instead of assuming a blank account.
 */
public enum Persona {

    /** PLAYER only: one HUMAN seat, cannot charter bots or mint tokens. */
    ALICE("dev-alice", "Alice (dev)"),
    /** PLAYER + RESEARCHER: charters bot seats and mints {@code iw_bot_} tokens. */
    SAM("dev-sam", "Sam (dev)"),
    /** PLAYER + RESEARCHER + ADMIN. Never suspend this persona — later tests need it. */
    ADMIN("dev-admin", "Admin (dev)");

    private final String subject;
    private final String displayName;

    Persona(String subject, String displayName) {
        this.subject = subject;
        this.displayName = displayName;
    }

    public String subject() {
        return subject;
    }

    public String displayName() {
        return displayName;
    }
}
