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
package com.kotharworks.auth.jwt;

import java.text.ParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kotharworks.auth.principal.GlobalRole;
import com.nimbusds.jwt.JWTClaimsSet;

/**
 * Extracts {@link GlobalRole}s from Keycloak-style JWT claims: the union of
 * {@code realm_access.roles} and {@code resource_access[clientId].roles}.
 * Every valid human token is granted at least {@link GlobalRole#PLAYER};
 * Keycloak housekeeping roles ({@code offline_access}, {@code uma_authorization},
 * {@code default-roles-*}) are ignored. Unknown role strings are skipped rather
 * than rejected so a provider-side role rename cannot break logins.
 */
public final class KeycloakRoleClaimExtractor {

    private KeycloakRoleClaimExtractor() {
    }

    /**
     * @param claims           the verified JWT claims
     * @param resourceClientId the Keycloak client whose {@code resource_access}
     *                         entry holds client-scoped roles (may be {@code null})
     * @return the union of realm and client roles plus the {@code PLAYER} baseline
     */
    public static Set<GlobalRole> extract(JWTClaimsSet claims, String resourceClientId) {
        Set<GlobalRole> roles = new LinkedHashSet<>();
        roles.add(GlobalRole.PLAYER);
        try {
            Map<String, Object> realmAccess = claims.getJSONObjectClaim("realm_access");
            if (realmAccess != null) {
                addMatchingRoles(realmAccess.get("roles"), roles);
            }
        } catch (ParseException ignored) {
            // realm_access absent or not an object: realm roles simply don't apply.
        }
        try {
            Map<String, Object> resourceAccess = claims.getJSONObjectClaim("resource_access");
            if (resourceAccess != null && resourceClientId != null) {
                Object clientEntry = resourceAccess.get(resourceClientId);
                if (clientEntry instanceof Map<?, ?> clientAccess) {
                    addMatchingRoles(clientAccess.get("roles"), roles);
                }
            }
        } catch (ParseException ignored) {
            // resource_access absent or not an object: client roles simply don't apply.
        }
        return Set.copyOf(roles);
    }

    private static void addMatchingRoles(Object rolesClaim, Set<GlobalRole> into) {
        if (!(rolesClaim instanceof List<?> roleList)) {
            return;
        }
        for (Object entry : roleList) {
            if (!(entry instanceof String roleName)) {
                continue;
            }
            if (isIgnoredRole(roleName)) {
                continue;
            }
            try {
                into.add(GlobalRole.valueOf(roleName));
            } catch (IllegalArgumentException ignored) {
                // Not one of our global roles (e.g. "tenant-manager"): skip.
            }
        }
    }

    private static boolean isIgnoredRole(String roleName) {
        return roleName.equals("offline_access")
                || roleName.equals("uma_authorization")
                || roleName.startsWith("default-roles-");
    }
}
