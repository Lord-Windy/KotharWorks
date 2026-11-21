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
package com.kotharworks.auth.http;

import com.kotharworks.auth.principal.GlobalRole;
import com.kotharworks.auth.principal.Principal;

/**
 * Deny-by-default access policies. Every route registered on the
 * {@link AuthenticatedRouter} must declare one explicitly — there is no
 * implicit policy. {@code PUBLIC} is the only policy that accepts anonymous
 * principals.
 */
public enum AccessPolicy {
    /** Anyone, including anonymous. Login routes (e.g. {@code /dev/login}) live here. */
    PUBLIC,
    /** Any authenticated principal: human or scoped automation token. */
    AUTHENTICATED_ANY,
    /** Human identities only; automation tokens are structurally excluded. */
    HUMAN_ONLY,
    /** Humans with {@link GlobalRole#RESEARCHER} (or {@link GlobalRole#ADMIN}). */
    ROLE_RESEARCHER,
    /** Humans with {@link GlobalRole#ADMIN} only. */
    ROLE_ADMIN;

    /** Whether this policy admits the given principal. */
    public boolean admits(Principal principal) {
        return switch (principal) {
            case Principal.AnonymousPrincipal anonymous -> this == PUBLIC;
            case Principal.ScopedTokenPrincipal scoped -> this == PUBLIC || this == AUTHENTICATED_ANY;
            case Principal.HumanPrincipal human -> switch (this) {
                case PUBLIC, AUTHENTICATED_ANY, HUMAN_ONLY -> true;
                case ROLE_RESEARCHER -> human.hasRole(GlobalRole.RESEARCHER) || human.hasRole(GlobalRole.ADMIN);
                case ROLE_ADMIN -> human.hasRole(GlobalRole.ADMIN);
            };
        };
    }
}
