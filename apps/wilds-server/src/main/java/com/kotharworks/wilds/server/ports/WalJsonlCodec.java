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
package com.kotharworks.wilds.server.ports;

import com.kotharworks.wilds.replay.JsonCodec;

/**
 * Shared emitter for the first line of a {@link WorldStorePort#exportWalJsonl}
 * stream: the canonical {@code WAL_HEADER} object in the exact fixed key order
 * {@code wilds-headless} replay parses. The header type lives in the headless
 * app (test scope here), so the canonical form is duplicated in this one spot
 * and asserted byte-identical against {@code WalHeader.encode()} by the
 * Postgres store test.
 */
public final class WalJsonlCodec {

    /** The {@code type} discriminator of a header line ({@code WalHeader.TYPE}). */
    public static final String HEADER_TYPE = "WAL_HEADER";

    private WalJsonlCodec() {
    }

    /** Canonical compact header line for the given world creation parameters. */
    public static String headerLine(long worldSeed, int rulesVersion, int engineVersion) {
        return JsonCodec.write(new JsonCodec.JsonObj(java.util.List.of(
                JsonCodec.JsonObj.entry("type", new JsonCodec.JsonStr(HEADER_TYPE)),
                JsonCodec.JsonObj.entry("worldSeed", new JsonCodec.JsonInt(worldSeed)),
                JsonCodec.JsonObj.entry("rulesVersion", new JsonCodec.JsonInt(rulesVersion)),
                JsonCodec.JsonObj.entry("engineVersion",
                        new JsonCodec.JsonInt(engineVersion)))));
    }
}
