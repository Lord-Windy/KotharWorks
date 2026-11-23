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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Tiny self-contained JSON reader/writer so the suite stays a pure black-box
 * client (no dependency on the engine's own codec). Objects parse to
 * insertion-ordered maps, integers to {@code Long}, other numbers to
 * {@code BigDecimal}.
 */
public final class Json {

    private Json() {
    }

    /** Parses {@code text} into a navigable {@link Node}. */
    public static Node parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWs();
        Object value = parser.value();
        parser.skipWs();
        if (parser.pos != text.length()) {
            throw new IllegalArgumentException("trailing JSON at " + parser.pos + ": " + text);
        }
        return new Node(value);
    }

    /** Builds an ordered object from alternating key/value arguments. */
    public static Map<String, Object> obj(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("obj() needs key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** Serializes maps, lists, strings, numbers, booleans and null. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> quote(s, out);
            case Number n -> out.append(n);
            case Boolean b -> out.append(b);
            case Node n -> write(n.raw(), out);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(String.valueOf(e.getKey()), out);
                    out.append(':');
                    write(e.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(list.get(i), out);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("cannot write " + value.getClass());
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
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

    /** Null-safe navigable view over a parsed JSON value. */
    public record Node(Object raw) {

        public Node get(String key) {
            if (raw instanceof Map<?, ?> map) {
                return new Node(map.get(key));
            }
            return new Node(null);
        }

        public Node at(int index) {
            if (raw instanceof List<?> list && index >= 0 && index < list.size()) {
                return new Node(list.get(index));
            }
            return new Node(null);
        }

        public boolean has(String key) {
            return raw instanceof Map<?, ?> map && map.containsKey(key);
        }

        public boolean isNull() {
            return raw == null;
        }

        public String text() {
            return raw == null ? null : raw.toString();
        }

        public long asLong() {
            if (raw instanceof Number n) {
                return n.longValue();
            }
            throw new IllegalStateException("not a number: " + raw);
        }

        public boolean asBool() {
            if (raw instanceof Boolean b) {
                return b;
            }
            throw new IllegalStateException("not a boolean: " + raw);
        }

        public int size() {
            return switch (raw) {
                case List<?> list -> list.size();
                case Map<?, ?> map -> map.size();
                case null, default -> 0;
            };
        }

        public Stream<Node> stream() {
            if (raw instanceof List<?> list) {
                return list.stream().map(Node::new);
            }
            return Stream.empty();
        }

        public List<Node> list() {
            return stream().toList();
        }

        @Override
        public String toString() {
            return write(raw);
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object value() {
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (peek() == '}') {
                pos++;
                return Collections.unmodifiableMap(map);
            }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                map.put(key, value());
                skipWs();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                expect('}');
                return Collections.unmodifiableMap(map);
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWs();
            if (peek() == ']') {
                pos++;
                return Collections.unmodifiableList(list);
            }
            while (true) {
                skipWs();
                list.add(value());
                skipWs();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                expect(']');
                return Collections.unmodifiableList(list);
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char esc = s.charAt(pos++);
                switch (esc) {
                    case '"', '\\', '/' -> out.append(esc);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("bad escape \\" + esc);
                }
            }
        }

        private Object number() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            String token = s.substring(start, pos);
            if (token.isEmpty()) {
                throw error("unexpected character '" + s.charAt(start) + "'");
            }
            if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                return Long.parseLong(token);
            }
            return new BigDecimal(token);
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
            return value;
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void expect(char c) {
            if (peek() != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON " + message + " at " + pos + " in: "
                    + (s.length() > 300 ? s.substring(0, 300) + "..." : s));
        }
    }
}
