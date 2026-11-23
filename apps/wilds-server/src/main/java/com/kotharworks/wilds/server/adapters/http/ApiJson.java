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
package com.kotharworks.wilds.server.adapters.http;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.kotharworks.auth.http.AuthenticatedRouter.RouteContext;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.replay.JsonCodec.JsonInt;
import com.kotharworks.wilds.replay.JsonCodec.JsonObj;
import com.kotharworks.wilds.replay.JsonCodec.JsonStr;

/**
 * Wire-layer JSON helpers over {@link JsonCodec}: canonical escaping (a seat
 * name containing {@code <>&"} round-trips safely), uniform error bodies, and
 * strict field readers for request parsing.
 */
public final class ApiJson {

    private ApiJson() {
    }

    /** A JSON string value. */
    public static JsonStr str(String value) {
        return new JsonStr(value);
    }

    /** A JSON integer value. */
    public static JsonInt num(long value) {
        return new JsonInt(value);
    }

    /** Renders {@code {"errorCode":..,"message":..}} and closes the exchange. */
    public static void error(RouteContext ctx, int status, String errorCode, String message)
            throws IOException {
        ctx.respondJson(status, JsonCodec.write(new JsonObj(List.of(
                JsonObj.entry("errorCode", str(errorCode)),
                JsonObj.entry("message", str(message))))));
    }

    /** Adds {@code currentRevision} to the standard error body (conflict responses). */
    public static void errorConflict(RouteContext ctx, String errorCode, String message,
            long currentRevision) throws IOException {
        ctx.respondJson(409, JsonCodec.write(new JsonObj(List.of(
                JsonObj.entry("errorCode", str(errorCode)),
                JsonObj.entry("message", str(message)),
                JsonObj.entry("currentRevision", num(currentRevision))))));
    }

    /** Parses a request body as a JSON object ({@link IllegalArgumentException} on junk). */
    public static JsonObj parseObject(byte[] body) {
        String json = new String(Objects.requireNonNull(body, "body"), StandardCharsets.UTF_8)
                .strip();
        if (json.isEmpty()) {
            throw new IllegalArgumentException("request body must be a JSON object");
        }
        return expectObj(JsonCodec.parse(json), "request body");
    }

    /** String field value, or {@code null} when absent/null; wrong types reject. */
    public static String optString(JsonObj obj, String key) {
        JsonCodec.JsonValue value = obj.getOrNull(key);
        if (value == null || value instanceof JsonCodec.JsonNull) {
            return null;
        }
        if (value instanceof JsonStr str) {
            return str.value();
        }
        throw new IllegalArgumentException("field \"" + key + "\" must be a JSON string");
    }

    /**
     * Validates a trimmed printable label ({@code minLen..maxLen} Unicode code
     * points, no ISO control chars, no unpaired UTF-16 surrogates).
     */
    public static String validatePrintable(String raw, String field, int minLen, int maxLen) {
        String trimmed = raw == null ? "" : raw.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException(
                        "field \"" + field + "\" must not contain ISO control characters");
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= trimmed.length() || !Character.isLowSurrogate(trimmed.charAt(i + 1))) {
                    throw new IllegalArgumentException(
                            "field \"" + field + "\" contains an unpaired UTF-16 surrogate");
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(
                        "field \"" + field + "\" contains an unpaired UTF-16 surrogate");
            }
        }
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints < minLen || codePoints > maxLen) {
            throw new IllegalArgumentException("field \"" + field + "\" must be " + minLen + ".."
                    + maxLen + " characters after trim");
        }
        return trimmed;
    }

    /** Required string field ({@link IllegalArgumentException} when absent/wrong type). */
    public static String requireString(JsonObj obj, String key) {
        JsonCodec.JsonValue value = obj.getOrNull(key);
        if (value instanceof JsonStr str) {
            return str.value();
        }
        throw new IllegalArgumentException("field \"" + key + "\" must be a JSON string");
    }

    /** Required long field ({@link IllegalArgumentException} when absent/wrong type). */
    public static long requireLong(JsonObj obj, String key) {
        JsonCodec.JsonValue value = obj.getOrNull(key);
        if (value instanceof JsonInt number) {
            return number.value();
        }
        throw new IllegalArgumentException("field \"" + key + "\" must be a JSON integer");
    }

    /** Long field or {@code null} when absent; wrong types reject. */
    public static Long optLong(JsonObj obj, String key) {
        JsonCodec.JsonValue value = obj.getOrNull(key);
        if (value == null || value instanceof JsonCodec.JsonNull) {
            return null;
        }
        if (value instanceof JsonInt number) {
            return number.value();
        }
        throw new IllegalArgumentException("field \"" + key + "\" must be a JSON integer");
    }

    /** Boolean field or the default when absent; wrong types reject. */
    public static boolean optBool(JsonObj obj, String key, boolean fallback) {
        JsonCodec.JsonValue value = obj.getOrNull(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof JsonCodec.JsonBool bool) {
            return bool.value();
        }
        throw new IllegalArgumentException("field \"" + key + "\" must be a JSON boolean");
    }

    /** Parses a required UUID field ({@link IllegalArgumentException} on junk). */
    public static UUID requireUuid(JsonObj obj, String key) {
        String raw = requireString(obj, key);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "field \"" + key + "\" must be a UUID, got: \"" + raw + "\"");
        }
    }

    /** Parses a path parameter as a long ({@link IllegalArgumentException} on junk). */
    public static long pathLong(Map<String, String> pathParams, String name) {
        String raw = pathParams.get(name);
        if (raw == null) {
            throw new IllegalArgumentException("missing path parameter: " + name);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "path parameter \"" + name + "\" must be an integer, got: " + raw);
        }
    }

    /** Decodes the exchange's query string into a small ordered map (no repeats). */
    public static Map<String, String> query(RouteContext ctx) {
        String rawQuery = ctx.exchange().getRequestURI().getRawQuery();
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                continue;
            }
            params.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    /** Long query parameter, or {@code fallback} when absent; junk rejects. */
    public static long queryLong(Map<String, String> params, String name, long fallback) {
        String raw = params.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "query parameter \"" + name + "\" must be an integer, got: " + raw);
        }
    }

    private static JsonObj expectObj(JsonCodec.JsonValue value, String path) {
        if (value instanceof JsonObj obj) {
            return obj;
        }
        throw new IllegalArgumentException(path + " must be a JSON object");
    }
}
