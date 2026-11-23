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

/** Validation of trimmed printable labels shared by ports and adapters. */
public final class PrintableText {

    private PrintableText() {
    }

    /**
     * Validates a trimmed printable label ({@code minLen..maxLen} Unicode code
     * points, no ISO control chars, no unpaired UTF-16 surrogates).
     */
    public static String validate(String raw, String field, int minLen, int maxLen) {
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

    /** Validates a seat name: trimmed printable text of 3..40 code points. */
    public static String validateSeatName(String raw) {
        return validate(raw, "seatName", 3, 40);
    }
}
