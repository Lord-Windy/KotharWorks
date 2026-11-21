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
package com.kotharworks.auth.dev;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.kotharworks.auth.principal.GlobalRole;

/**
 * Built-in dev-local login persona: a test subject with a fixed role set,
 * minted into a self-signed ES256 JWT by the {@link DevLocalJwtIssuer}.
 */
public record DevPersona(String subject, String displayName, Set<GlobalRole> roles) {

    public static final DevPersona ALICE =
            new DevPersona("dev-alice", "Alice (dev)", Set.of(GlobalRole.PLAYER));
    public static final DevPersona SAM =
            new DevPersona("dev-sam", "Sam (dev)", Set.of(GlobalRole.PLAYER, GlobalRole.RESEARCHER));
    public static final DevPersona ADMIN =
            new DevPersona("dev-admin", "Admin (dev)",
                    Set.of(GlobalRole.PLAYER, GlobalRole.RESEARCHER, GlobalRole.ADMIN));

    private static final Set<DevPersona> ALL = Set.of(ALICE, SAM, ADMIN);

    public DevPersona {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(displayName, "displayName");
        roles = Set.copyOf(roles);
    }

    /** All built-in personas. */
    public static Set<DevPersona> all() {
        return ALL;
    }

    /** Resolves a persona by its {@code sub} claim value. */
    public static Optional<DevPersona> fromSubject(String subject) {
        return ALL.stream().filter(p -> p.subject().equals(subject)).findFirst();
    }
}
