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
package com.kotharworks.wilds.headless;

import com.kotharworks.wilds.replay.JsonCodec;

import java.util.List;

/**
 * Optional first line of a {@code .jsonl} write-ahead log:
 * {@code {"type":"WAL_HEADER","worldSeed":4805198,"rulesVersion":1,"engineVersion":1}}.
 * A replay rig uses it to fail fast (exit code 2) when the stream was exported by an
 * incompatible engine/rules pair. Absent headers mean "no compatibility claim".
 */
public record WalHeader(long worldSeed, int rulesVersion, int engineVersion) {

    /** The {@code type} discriminator marking a header line. */
    public static final String TYPE = "WAL_HEADER";

    /** True when {@code obj} carries the {@code "type":"WAL_HEADER"} marker. */
    public static boolean isHeader(JsonCodec.JsonObj obj) {
        return obj.getOrNull("type") instanceof JsonCodec.JsonStr type && TYPE.equals(type.value());
    }

    /**
     * Decodes a header JSON object; missing, wrong-typed, or out-of-range fields are
     * rejected with {@link IllegalArgumentException}.
     */
    public static WalHeader decode(JsonCodec.JsonObj obj) {
        if (!isHeader(obj)) {
            throw new IllegalArgumentException("not a " + TYPE + " object");
        }
        return new WalHeader(
                longField(obj, "worldSeed"),
                intField(obj, "rulesVersion"),
                intField(obj, "engineVersion"));
    }

    /** Canonical compact JSON line for this header (fixed key order). */
    public String encode() {
        return JsonCodec.write(new JsonCodec.JsonObj(List.of(
                JsonCodec.JsonObj.entry("type", new JsonCodec.JsonStr(TYPE)),
                JsonCodec.JsonObj.entry("worldSeed", new JsonCodec.JsonInt(worldSeed())),
                JsonCodec.JsonObj.entry("rulesVersion", new JsonCodec.JsonInt(rulesVersion())),
                JsonCodec.JsonObj.entry("engineVersion", new JsonCodec.JsonInt(engineVersion())))));
    }

    private static long longField(JsonCodec.JsonObj obj, String key) {
        if (obj.getOrNull(key) instanceof JsonCodec.JsonInt value) {
            return value.value();
        }
        throw new IllegalArgumentException(
                "WAL header field \"" + key + "\" must be a JSON integer");
    }

    private static int intField(JsonCodec.JsonObj obj, String key) {
        long value = longField(obj, key);
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "WAL header field \"" + key + "\" exceeds int range: " + value);
        }
    }

    /** Compact display form for diagnostics. */
    @Override
    public String toString() {
        return "WAL_HEADER(worldSeed=" + worldSeed() + ", rulesVersion=" + rulesVersion()
                + ", engineVersion=" + engineVersion() + ")";
    }
}
