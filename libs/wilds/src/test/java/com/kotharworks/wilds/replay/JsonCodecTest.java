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

import com.kotharworks.wilds.replay.JsonCodec.JsonArr;
import com.kotharworks.wilds.replay.JsonCodec.JsonBool;
import com.kotharworks.wilds.replay.JsonCodec.JsonInt;
import com.kotharworks.wilds.replay.JsonCodec.JsonNull;
import com.kotharworks.wilds.replay.JsonCodec.JsonObj;
import com.kotharworks.wilds.replay.JsonCodec.JsonStr;
import com.kotharworks.wilds.replay.JsonCodec.JsonValue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Ticket 03 acceptance: escaped strings, nested containers, full long range, rejection
 * of malformed input, and rejection of floating-point numbers.
 */
class JsonCodecTest {

    private static String roundTrip(String json) {
        return JsonCodec.write(JsonCodec.parse(json));
    }

    @Test
    void parsesEscapedStrings() {
        JsonValue parsed = JsonCodec.parse("\"a\\\"b\\\\c\\nd\\u00e9\"");
        assertEquals(new JsonStr("a\"b\\c\nd\u00e9"), parsed);
    }

    @Test
    void escapesOnWriteAndRoundTrips() {
        JsonStr value = new JsonStr("quote\" backslash\\ newline\n tab\t e\u00e9");
        String written = JsonCodec.write(value);
        assertEquals("\"quote\\\" backslash\\\\ newline\\n tab\\t e\u00e9\"", written);
        assertEquals(value, JsonCodec.parse(written));
    }

    @Test
    void rejectsRawControlCharInString() {
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("\"a\nb\""));
    }

    @Test
    void nestedArraysAndObjectsDeepRoundTrip() {
        JsonObj value = new JsonObj(List.of(
                JsonObj.entry("name", new JsonStr("wilds")),
                JsonObj.entry("counts", new JsonArr(List.of(
                        new JsonInt(1), new JsonInt(-2), new JsonInt(Long.MAX_VALUE)))),
                JsonObj.entry("nested", new JsonObj(List.of(
                        JsonObj.entry("flag", new JsonBool(true)),
                        JsonObj.entry("none", JsonNull.INSTANCE),
                        JsonObj.entry("deep", new JsonArr(List.of(
                                new JsonObj(List.of(JsonObj.entry("x", new JsonInt(0))))))))))));
        String once = JsonCodec.write(value);
        assertEquals(value, JsonCodec.parse(once));
        assertEquals(once, roundTrip(once));
    }

    @Test
    void parsesFullLongRange() {
        assertEquals(new JsonInt(Long.MIN_VALUE), JsonCodec.parse("-9223372036854775808"));
        assertEquals(new JsonInt(Long.MAX_VALUE), JsonCodec.parse("9223372036854775807"));
        assertEquals("-9223372036854775808", roundTrip("-9223372036854775808"));
        assertEquals("9223372036854775807", roundTrip("9223372036854775807"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("-9223372036854775809"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("9223372036854775808"));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("[1,]"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("\"unclosed"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("tru"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse(""));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("1 x"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("01"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("[1 2]"));
    }

    @Test
    void rejectsFloatingPointNumbers() {
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("1.5"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("-0.5"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("1e3"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("1E-2"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("1."));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("0.0"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("[1.5]"));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("{\"a\":2.5}"));
    }

    @Test
    void parsesAndWritesCanonicalPrimitives() {
        assertEquals(JsonNull.INSTANCE, JsonCodec.parse("null"));
        assertEquals(new JsonBool(false), JsonCodec.parse("false"));
        assertEquals(new JsonBool(true), JsonCodec.parse("true"));
        assertEquals(new JsonInt(0), JsonCodec.parse("0"));
        assertEquals(new JsonInt(-0L), JsonCodec.parse("-0"));
        assertEquals("null", roundTrip("null"));
        assertEquals("true", roundTrip("true"));
        assertEquals("0", roundTrip("0"));
        assertEquals("[]", roundTrip("[]"));
        assertEquals("{}", roundTrip("{}"));
    }

    @Test
    void emptyContainersAndWhitespaceTolerance() {
        assertEquals(new JsonArr(List.of()), JsonCodec.parse(" [ ] "));
        assertEquals(new JsonObj(List.of()), JsonCodec.parse("{ }"));
        JsonObj value = new JsonObj(List.of(JsonObj.entry("a", new JsonInt(1))));
        assertEquals(value, JsonCodec.parse("  {  \"a\"  :  1  }  "));
        assertEquals("{\"a\":1}", roundTrip("  {  \"a\"  :  1  }  "));
    }
}
