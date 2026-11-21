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

import java.util.Objects;

/**
 * Single source of truth for the {@code "<prefix>:<index>:<generation>"} wire grammar shared
 * by the concrete {@link EntityId} records.
 */
final class EntityIds {

    private EntityIds() {
    }

    /**
     * Parses the {@code <index>:<generation>} remainder of a wire id.
     *
     * @throws NullPointerException     if {@code wire} is null
     * @throws IllegalArgumentException on wrong prefix, wrong segment count, empty or
     *                                  non-digit or leading-zero segments, values outside the
     *                                  int range, negative index, or generation {@code < 1}
     */
    static int[] parseIndexGeneration(String wire, String expectedPrefix) {
        Objects.requireNonNull(wire, "wire");
        String prefixColon = expectedPrefix + ":";
        if (!wire.startsWith(prefixColon)) {
            throw new IllegalArgumentException(
                    "expected wire id with prefix \"" + expectedPrefix + "\" but got \"" + wire + "\"");
        }
        String[] segments = wire.substring(prefixColon.length()).split(":", -1);
        if (segments.length != 2) {
            throw new IllegalArgumentException(
                    "expected <prefix>:<index>:<generation> but got \"" + wire + "\"");
        }
        int index = parseSegment(wire, segments[0]);
        int generation = parseSegment(wire, segments[1]);
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0 in \"" + wire + "\"");
        }
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be >= 1 in \"" + wire + "\"");
        }
        return new int[] {index, generation};
    }

    /** Digits only, no leading zero unless the single digit {@code "0"}, must fit an int. */
    private static int parseSegment(String wire, String segment) {
        if (segment.isEmpty() || (segment.length() > 1 && segment.charAt(0) == '0')) {
            throw malformed(wire);
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < '0' || c > '9') {
                throw malformed(wire);
            }
        }
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            throw malformed(wire);
        }
    }

    private static IllegalArgumentException malformed(String wire) {
        return new IllegalArgumentException("malformed wire id \"" + wire + "\"");
    }
}
