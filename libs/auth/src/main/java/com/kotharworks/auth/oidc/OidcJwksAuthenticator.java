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
package com.kotharworks.auth.oidc;

import java.net.MalformedURLException;
import java.time.Clock;
import java.util.Objects;

import com.kotharworks.auth.jwt.JwtClaimsVerifier;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;

/**
 * OIDC authenticator: exposes a production {@link JwtClaimsVerifier} whose
 * verification keys come from the provider's remote JWKS endpoint through a
 * Nimbus {@link JWKSourceBuilder} cache with bounded connect/read timeouts and
 * response size limits. The {@code issuerUrl} is the exact {@code iss} and the
 * {@code clientId} the expected {@code aud} of every accepted token.
 */
public final class OidcJwksAuthenticator {

    private static final int JWKS_CONNECT_TIMEOUT_MS = 5_000;
    private static final int JWKS_READ_TIMEOUT_MS = 5_000;
    private static final int JWKS_MAX_SIZE_BYTES = 51_200;

    private final String clientId;
    private final JwtClaimsVerifier verifier;

    public OidcJwksAuthenticator(OidcConfig config, Clock clock) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        this.clientId = config.clientId();
        JWKSourceBuilder<SecurityContext> source;
        try {
            var retriever = new DefaultResourceRetriever(
                    JWKS_CONNECT_TIMEOUT_MS, JWKS_READ_TIMEOUT_MS, JWKS_MAX_SIZE_BYTES);
            source = JWKSourceBuilder.create(config.jwksUri().toURL(), retriever);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("jwksUri is not a valid URL: " + config.jwksUri(), e);
        }
        this.verifier = new JwtClaimsVerifier(
                config.issuerUrl(), config.clientId(), source.cache(true).build(), clock);
    }

    /** The configured OIDC {@code client_id} (expected audience / {@code azp}). */
    public String clientId() {
        return clientId;
    }

    /** The verifier backed by the remote JWKS cache. */
    public JwtClaimsVerifier jwtVerifier() {
        return verifier;
    }
}
