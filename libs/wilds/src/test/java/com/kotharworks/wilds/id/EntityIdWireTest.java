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
package com.kotharworks.wilds.id;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityIdWireTest {

    @Test
    void toWireFormatIsCanonical() {
        assertEquals("own:0:1", new OwnerId(0, 1).toWire());
        assertEquals("act:7:3", new ActorId(7, 3).toWire());
        assertEquals("plc:2:1", new PlaceId(2, 1).toWire());
    }

    @Test
    void wireRoundTripParsesBackToEqualIds() {
        int[][] grid = {{0, 1}, {1, 1}, {17, 3}, {2_147_483_647, 99}};
        for (int[] ig : grid) {
            OwnerId owner = new OwnerId(ig[0], ig[1]);
            ActorId actor = new ActorId(ig[0], ig[1]);
            PlaceId place = new PlaceId(ig[0], ig[1]);
            assertEquals(owner, OwnerId.parse(owner.toWire()));
            assertEquals(actor, ActorId.parse(actor.toWire()));
            assertEquals(place, PlaceId.parse(place.toWire()));
        }
    }

    @Test
    void ruleIdAcceptsValidSlugs() {
        List<String> validSlugs = List.of("a", "wrought_iron_pickaxe", "x" + "_".repeat(63));
        for (String slug : validSlugs) {
            assertEquals(slug, new RuleId(slug).slug());
            assertEquals(slug, new RuleId(new RuleId(slug).slug()).slug());
        }
    }

    @Test
    void rejectsWrongPrefixes() {
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("own:0:1"));
        assertThrows(IllegalArgumentException.class, () -> PlaceId.parse("act:0:1"));
        assertThrows(IllegalArgumentException.class, () -> OwnerId.parse("plc:0:1"));
    }

    @Test
    void rejectsNegativeIndexAndZeroGeneration() {
        assertThrows(IllegalArgumentException.class, () -> OwnerId.parse("own:-1:1"));
        assertThrows(IllegalArgumentException.class, () -> OwnerId.parse("own:0:0"));
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("act:-1:1"));
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("act:0:0"));
        assertThrows(IllegalArgumentException.class, () -> PlaceId.parse("plc:-1:1"));
        assertThrows(IllegalArgumentException.class, () -> PlaceId.parse("plc:0:0"));
    }

    @Test
    void rejectsTrailingGarbageAndMissingSegments() {
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("act:0"));
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("act:0:1:extra"));
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("act:0:1:"));
    }

    @Test
    void rejectsWhitespaceAndUppercasePrefix() {
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse(" act:0:1"));
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("act:0:1 "));
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("ACT:0:1"));
    }

    @Test
    void rejectsLeadingZeroAmbiguity() {
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("act:01:1"));
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("act:00:1"));
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("act:0:01"));
    }

    @Test
    void rejectsEmptySegmentsAndEmptyWire() {
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("act::1"));
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("act:0:"));
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse(""));
    }

    @Test
    void rejectsIndexOverflow() {
        assertThrows(IllegalArgumentException.class, () -> ActorId.parse("act:2147483648:1"));
    }

    @Test
    void parseRejectsNull() {
        assertThrows(NullPointerException.class, () -> OwnerId.parse(null));
        assertThrows(NullPointerException.class, () -> ActorId.parse(null));
        assertThrows(NullPointerException.class, () -> PlaceId.parse(null));
    }

    @Test
    void compactConstructorsValidateRanges() {
        assertThrows(IllegalArgumentException.class, () -> new OwnerId(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OwnerId(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ActorId(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ActorId(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlaceId(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new PlaceId(0, 0));
    }

    @Test
    void entityIdsOrderByIndexThenGeneration() {
        List<OwnerId> owners = new ArrayList<>(List.of(
                new OwnerId(0, 2), new OwnerId(2, 5), new OwnerId(0, 1), new OwnerId(1, 1)));
        owners.sort(Comparator.naturalOrder());
        assertEquals(List.of(new OwnerId(0, 1), new OwnerId(0, 2), new OwnerId(1, 1), new OwnerId(2, 5)),
                owners);

        List<ActorId> actors = new ArrayList<>(List.of(
                new ActorId(0, 2), new ActorId(2, 5), new ActorId(0, 1), new ActorId(1, 1)));
        actors.sort(Comparator.naturalOrder());
        assertEquals(List.of(new ActorId(0, 1), new ActorId(0, 2), new ActorId(1, 1), new ActorId(2, 5)),
                actors);

        List<PlaceId> places = new ArrayList<>(List.of(
                new PlaceId(0, 2), new PlaceId(2, 5), new PlaceId(0, 1), new PlaceId(1, 1)));
        places.sort(Comparator.naturalOrder());
        assertEquals(List.of(new PlaceId(0, 1), new PlaceId(0, 2), new PlaceId(1, 1), new PlaceId(2, 5)),
                places);

        assertTrue(new OwnerId(0, 1).compareTo(new OwnerId(0, 2)) < 0);
        assertTrue(new ActorId(3, 1).compareTo(new ActorId(2, 9)) > 0);
        assertTrue(new PlaceId(1, 1).compareTo(new PlaceId(1, 1)) == 0);
    }

    @Test
    void ruleIdOrdersLexicographically() {
        assertTrue(new RuleId("alpha").compareTo(new RuleId("beta")) < 0);
        assertTrue(new RuleId("beta").compareTo(new RuleId("alpha")) > 0);
        assertEquals(0, new RuleId("same").compareTo(new RuleId("same")));
    }

    @Test
    void rejectsInvalidRuleIdSlugs() {
        assertThrows(IllegalArgumentException.class, () -> new RuleId(""));
        assertThrows(IllegalArgumentException.class, () -> new RuleId("A"));
        assertThrows(IllegalArgumentException.class, () -> new RuleId("1abc"));
        assertThrows(IllegalArgumentException.class, () -> new RuleId("has-dash"));
        assertThrows(IllegalArgumentException.class, () -> new RuleId("has space"));
        assertThrows(IllegalArgumentException.class, () -> new RuleId("x" + "_".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new RuleId("\u00fcnicode"));
        assertThrows(NullPointerException.class, () -> new RuleId(null));
    }
}
