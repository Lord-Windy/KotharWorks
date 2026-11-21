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
import java.util.regex.Pattern;

/**
 * Slug identifier for a named rule or periodic system pulse, ordered lexicographically.
 * Valid slugs match {@code [a-z][a-z0-9_]{0,63}}.
 */
public record RuleId(String slug) implements Comparable<RuleId> {

    private static final Pattern SLUG_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public RuleId {
        Objects.requireNonNull(slug, "slug");
        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "slug must match [a-z][a-z0-9_]{0,63}: \"" + slug + "\"");
        }
    }

    @Override
    public int compareTo(RuleId other) {
        return slug.compareTo(other.slug);
    }
}
