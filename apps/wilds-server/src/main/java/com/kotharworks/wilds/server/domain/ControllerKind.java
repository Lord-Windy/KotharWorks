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
package com.kotharworks.wilds.server.domain;

/**
 * Who pilots a chartered seat. The charter quota policy (see
 * {@code SeatAuthorizer#canCharter}) keys off this kind: plain {@code PLAYER}
 * accounts may hold a single {@link #HUMAN} seat; every non-HUMAN kind (or a
 * player's further seats, up to the researcher cap) requires
 * {@code RESEARCHER} or {@code ADMIN}.
 */
public enum ControllerKind {

    /** A human being playing through the browser console. */
    HUMAN,

    /** A scripted automation bot driven by a scoped {@code iw_bot_} token. */
    SCRIPT_BOT,

    /** A genetic-algorithm search agent driven by a scoped {@code iw_bot_} token. */
    GENETIC_AGENT
}
