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
import java.util.Objects;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthRejectException;
import com.kotharworks.auth.principal.ExternalIdentity;
import com.kotharworks.auth.principal.Principal;
import com.kotharworks.auth.principal.UserId;
import com.kotharworks.auth.spi.IdentityProvisioner;
import com.nimbusds.jwt.JWTClaimsSet;

/**
 * Turns a verified human JWT into a provisioned {@link Principal.HumanPrincipal}:
 * verifies the bearer token, links the external {@code (iss, sub)} identity to
 * a stable {@link UserId} via the host {@link IdentityProvisioner}, and extracts
 * global roles from the Keycloak claims.
 */
public final class JwtHumanAuthenticator {

    private final JwtClaimsVerifier verifier;
    private final String resourceClientId;
    private final IdentityProvisioner provisioner;

    public JwtHumanAuthenticator(JwtClaimsVerifier verifier, String resourceClientId,
            IdentityProvisioner provisioner) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.resourceClientId = resourceClientId;
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
    }

    /**
     * Verifies the presented bearer JWT and returns the provisioned human
     * principal.
     *
     * @throws AuthRejectException on any verification failure
     */
    public Principal.HumanPrincipal authenticate(String bearerToken) {
        Objects.requireNonNull(bearerToken, "bearerToken");
        JWTClaimsSet claims = verifier.verify(bearerToken);
        ExternalIdentity externalId = new ExternalIdentity(claims.getIssuer(), claims.getSubject());
        String displayName = preferredUsername(claims);
        UserId userId = provisioner.provision(externalId, displayName);
        var roles = KeycloakRoleClaimExtractor.extract(claims, resourceClientId);
        return new Principal.HumanPrincipal(userId, externalId, displayName, roles);
    }

    private static String preferredUsername(JWTClaimsSet claims) {
        try {
            String preferred = claims.getStringClaim("preferred_username");
            if (preferred != null && !preferred.isBlank()) {
                return preferred;
            }
            return claims.getSubject();
        } catch (ParseException e) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN,
                    "invalid preferred_username claim: " + e.getMessage());
        }
    }
}
