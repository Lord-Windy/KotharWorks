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
package com.kotharworks.wilds.replay;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Optional first line of a {@code .jsonl} write-ahead log:
 * {@code {"type":"WAL_HEADER","worldSeed":4805198,"rulesVersion":1,"engineVersion":1,"rulesFingerprint":2767350039571331759}}.
 * Shared by {@code wilds-headless} and {@code wilds-server} so exported WAL streams
 * carry a canonical seed + version-triple compatibility header with zero codec drift.
 *
 * <p>{@code rulesFingerprint} (the writing binary's {@code RuleRegistry.fingerprint()}) is
 * optional: it is encoded as the last key, as a signed 64-bit JSON integer, only when
 * present, and headers written before it existed decode with a {@code null} fingerprint.
 * A present field must be a JSON integer; explicit JSON {@code null} is rejected.
 *
 * @param rulesFingerprint the writer's rules fingerprint, or {@code null} when absent
 */
public record WalHeader(long worldSeed, int rulesVersion, int engineVersion,
                        Long rulesFingerprint) {

    /** Legacy three-field header with no {@code rulesFingerprint}. */
    public WalHeader(long worldSeed, int rulesVersion, int engineVersion) {
        this(worldSeed, rulesVersion, engineVersion, null);
    }

    /** The {@code type} discriminator marking a header line. */
    public static final String TYPE = "WAL_HEADER";

    /** True when {@code obj} carries the {@code "type":"WAL_HEADER"} marker. */
    public static boolean isHeader(JsonCodec.JsonObj obj) {
        Objects.requireNonNull(obj, "obj");
        return obj.getOrNull("type") instanceof JsonCodec.JsonStr type && TYPE.equals(type.value());
    }

    /**
     * Decodes a header JSON line; non-object JSON or missing/wrong-typed/out-of-range
     * fields are rejected with {@link IllegalArgumentException}.
     */
    public static WalHeader decode(String json) {
        JsonCodec.JsonValue parsed = JsonCodec.parse(json);
        if (!(parsed instanceof JsonCodec.JsonObj obj)) {
            throw new IllegalArgumentException("WAL header must be a JSON object");
        }
        return decode(obj);
    }

    /**
     * Decodes a header JSON object; missing, wrong-typed, or out-of-range fields are
     * rejected with {@link IllegalArgumentException}. An absent {@code rulesFingerprint}
     * decodes to {@code null}; a present one must be a JSON integer.
     */
    public static WalHeader decode(JsonCodec.JsonObj obj) {
        if (!isHeader(obj)) {
            throw new IllegalArgumentException("not a " + TYPE + " object");
        }
        return new WalHeader(
                longField(obj, "worldSeed"),
                intField(obj, "rulesVersion"),
                intField(obj, "engineVersion"),
                obj.getOrNull("rulesFingerprint") == null
                        ? null : longField(obj, "rulesFingerprint"));
    }

    /**
     * Canonical compact JSON line for this header (fixed key order); {@code rulesFingerprint}
     * is appended as the last key only when present, so a header without it is
     * byte-identical to the legacy three-field form.
     */
    public String encode() {
        List<JsonCodec.JsonObj.Entry> entries = new ArrayList<>(List.of(
                JsonCodec.JsonObj.entry("type", new JsonCodec.JsonStr(TYPE)),
                JsonCodec.JsonObj.entry("worldSeed", new JsonCodec.JsonInt(worldSeed())),
                JsonCodec.JsonObj.entry("rulesVersion", new JsonCodec.JsonInt(rulesVersion())),
                JsonCodec.JsonObj.entry("engineVersion", new JsonCodec.JsonInt(engineVersion()))));
        if (rulesFingerprint() != null) {
            entries.add(JsonCodec.JsonObj.entry("rulesFingerprint",
                    new JsonCodec.JsonInt(rulesFingerprint())));
        }
        return JsonCodec.write(new JsonCodec.JsonObj(entries));
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

    /**
     * Compact display form for diagnostics; a present fingerprint renders as {@code 0x} +
     * 16 lowercase hex digits (the {@code RulesFingerprint.toHex} form, formatted here
     * directly to keep {@code replay} free of a dependency on {@code rules}).
     */
    @Override
    public String toString() {
        return "WAL_HEADER(worldSeed=" + worldSeed() + ", rulesVersion=" + rulesVersion()
                + ", engineVersion=" + engineVersion()
                + (rulesFingerprint() == null ? ""
                        : ", rulesFingerprint=0x" + HexFormat.of().toHexDigits(rulesFingerprint()))
                + ")";
    }
}
