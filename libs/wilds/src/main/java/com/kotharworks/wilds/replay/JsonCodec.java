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
import java.util.List;
import java.util.Objects;

/**
 * Zero-dependency, deterministic RFC 8259 JSON codec over the closed
 * {@link JsonValue} family. Integers only: any floating-point token ({@code 1.5},
 * {@code 1e3}) is rejected at parse time so floats can never sneak into simulation
 * state through JSON. {@link JsonObj} preserves insertion key order via an immutable
 * entry list (never a {@code HashMap}); duplicate keys are rejected.
 *
 * <p>Shared by {@link SnapshotCodec}, the Ticket-04 {@code CommandCodec}, and the
 * server/host applications. The writer is canonical: compact, no whitespace, fixed
 * escaping, so equal values always write byte-identically.
 */
public final class JsonCodec {

    private JsonCodec() {
    }

    /** Closed JSON value family; exhaustively switchable. */
    public sealed interface JsonValue permits JsonNull, JsonBool, JsonInt, JsonStr, JsonArr, JsonObj {
    }

    /** JSON {@code null}. */
    public record JsonNull() implements JsonValue {
        public static final JsonNull INSTANCE = new JsonNull();
    }

    /** JSON boolean. */
    public record JsonBool(boolean value) implements JsonValue {
    }

    /** JSON integer; floating-point tokens never reach this type. */
    public record JsonInt(long value) implements JsonValue {
    }

    /** JSON string (decoded). */
    public record JsonStr(String value) implements JsonValue {
        public JsonStr {
            Objects.requireNonNull(value, "value");
        }
    }

    /** JSON array; element order preserved, immutable. */
    public record JsonArr(List<JsonValue> values) implements JsonValue {
        public JsonArr {
            values = List.copyOf(values);
        }
    }

    /** JSON object: insertion-ordered immutable entry list with unique keys. */
    public record JsonObj(List<Entry> entries) implements JsonValue {

        public JsonObj {
            entries = List.copyOf(entries);
            for (int i = 0; i < entries.size(); i++) {
                for (int j = i + 1; j < entries.size(); j++) {
                    if (entries.get(i).key().equals(entries.get(j).key())) {
                        throw new IllegalArgumentException("duplicate object key: " + entries.get(i).key());
                    }
                }
            }
        }

        /** One key/value binding. */
        public record Entry(String key, JsonValue value) {
            public Entry {
                Objects.requireNonNull(key, "key");
                Objects.requireNonNull(value, "value");
            }
        }

        /** Convenience factory for {@link Entry}. */
        public static Entry entry(String key, JsonValue value) {
            return new Entry(key, value);
        }

        /** Value bound to {@code key}, or null when absent. */
        public JsonValue getOrNull(String key) {
            for (Entry candidate : entries) {
                if (candidate.key().equals(key)) {
                    return candidate.value();
                }
            }
            return null;
        }
    }

    /** Parses {@code json} (a complete RFC 8259 document, integers only). */
    public static JsonValue parse(String json) {
        Objects.requireNonNull(json, "json");
        Parser parser = new Parser(json);
        parser.skipWhitespace();
        JsonValue value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("trailing garbage after JSON value");
        }
        return value;
    }

    /** Writes the canonical compact form of {@code value}. */
    public static String write(JsonValue value) {
        Objects.requireNonNull(value, "value");
        StringBuilder out = new StringBuilder();
        writeValue(value, out);
        return out.toString();
    }

    private static void writeValue(JsonValue value, StringBuilder out) {
        switch (value) {
            case JsonNull ignored -> out.append("null");
            case JsonBool(boolean flag) -> out.append(flag ? "true" : "false");
            case JsonInt(long number) -> out.append(number);
            case JsonStr(String text) -> writeString(text, out);
            case JsonArr(List<JsonValue> values) -> {
                out.append('[');
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    writeValue(values.get(i), out);
                }
                out.append(']');
            }
            case JsonObj(List<JsonObj.Entry> entries) -> {
                out.append('{');
                for (int i = 0; i < entries.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    writeString(entries.get(i).key(), out);
                    out.append(':');
                    writeValue(entries.get(i).value(), out);
                }
                out.append('}');
            }
        }
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Single-pass recursive-descent parser over a {@code String} with an index cursor. */
    private static final class Parser {

        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        char peek() {
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            return src.charAt(pos);
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = src.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        JsonValue parseValue() {
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> new JsonStr(parseString());
                case 't' -> parseLiteral("true", new JsonBool(true));
                case 'f' -> parseLiteral("false", new JsonBool(false));
                case 'n' -> parseLiteral("null", JsonNull.INSTANCE);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private JsonValue parseObject() {
            pos++; // consume '{'
            List<JsonObj.Entry> entries = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && peek() == '}') {
                pos++;
                return new JsonObj(entries);
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || peek() != '"') {
                    throw error("expected object key string");
                }
                String key = parseString();
                skipWhitespace();
                if (atEnd() || peek() != ':') {
                    throw error("expected ':' after object key");
                }
                pos++;
                skipWhitespace();
                entries.add(JsonObj.entry(key, parseValue()));
                skipWhitespace();
                if (atEnd()) {
                    throw error("unterminated object");
                }
                char c = src.charAt(pos++);
                if (c == '}') {
                    return new JsonObj(entries);
                }
                if (c != ',') {
                    throw error("expected ',' or '}' in object");
                }
            }
        }

        private JsonValue parseArray() {
            pos++; // consume '['
            List<JsonValue> values = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && peek() == ']') {
                pos++;
                return new JsonArr(values);
            }
            while (true) {
                skipWhitespace();
                values.add(parseValue());
                skipWhitespace();
                if (atEnd()) {
                    throw error("unterminated array");
                }
                char c = src.charAt(pos++);
                if (c == ']') {
                    return new JsonArr(values);
                }
                if (c != ',') {
                    throw error("expected ',' or ']' in array");
                }
            }
        }

        private String parseString() {
            pos++; // consume opening '"'
            StringBuilder text = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return text.toString();
                }
                if (c < 0x20) {
                    throw error("raw control character inside string");
                }
                if (c != '\\') {
                    text.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("unterminated escape sequence");
                }
                char escape = src.charAt(pos++);
                switch (escape) {
                    case '"' -> text.append('"');
                    case '\\' -> text.append('\\');
                    case '/' -> text.append('/');
                    case 'b' -> text.append('\b');
                    case 'f' -> text.append('\f');
                    case 'n' -> text.append('\n');
                    case 'r' -> text.append('\r');
                    case 't' -> text.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw error("truncated unicode escape");
                        }
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(src.charAt(pos++), 16);
                            if (digit < 0) {
                                throw error("invalid unicode escape digit");
                            }
                            code = (code << 4) | digit;
                        }
                        text.append((char) code);
                    }
                    default -> throw error("invalid escape sequence \\" + escape);
                }
            }
        }

        /** Grammar {@code -?(0|[1-9][0-9]*)} only: '.', 'e', 'E' mean rejection. */
        private JsonValue parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (atEnd() || !isDigit(peek())) {
                throw error("malformed number");
            }
            if (peek() == '0') {
                pos++;
                if (!atEnd() && isDigit(peek())) {
                    throw error("leading zero in number");
                }
            } else {
                while (!atEnd() && isDigit(peek())) {
                    pos++;
                }
            }
            if (!atEnd() && (peek() == '.' || peek() == 'e' || peek() == 'E')) {
                throw error("floating-point JSON numbers are rejected");
            }
            String token = src.substring(start, pos);
            try {
                return new JsonInt(Long.parseLong(token));
            } catch (NumberFormatException overflow) {
                throw error("integer does not fit a long: \"" + token + "\"");
            }
        }

        private JsonValue parseLiteral(String literal, JsonValue value) {
            if (!src.startsWith(literal, pos)) {
                throw error("malformed literal, expected \"" + literal + "\"");
            }
            pos += literal.length();
            return value;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("invalid JSON at index " + pos + ": " + message);
        }
    }
}
