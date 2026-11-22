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

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable OIDC provider configuration for {@code --auth-mode=oidc}
 * (e.g. Keycloak). Endpoint URIs usually come from the provider's
 * {@code /.well-known/openid-configuration} discovery document and must use
 * {@code http} or {@code https}.
 */
public record OidcConfig(String issuerUrl, String clientId, String clientSecret, String publicOrigin,
        URI authorizationEndpoint, URI tokenEndpoint, URI jwksUri) {

    public OidcConfig {
        Objects.requireNonNull(issuerUrl, "issuerUrl");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientSecret, "clientSecret");
        Objects.requireNonNull(publicOrigin, "publicOrigin");
        Objects.requireNonNull(authorizationEndpoint, "authorizationEndpoint");
        Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
        Objects.requireNonNull(jwksUri, "jwksUri");
        if (issuerUrl.isBlank() || clientId.isBlank() || publicOrigin.isBlank()) {
            throw new IllegalArgumentException("issuerUrl, clientId, and publicOrigin must not be blank");
        }
        requireHttpUri(authorizationEndpoint, "authorizationEndpoint");
        requireHttpUri(tokenEndpoint, "tokenEndpoint");
        requireHttpUri(jwksUri, "jwksUri");
    }

    private static void requireHttpUri(URI uri, String name) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException(name + " must have an http/https scheme: " + uri);
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        if (!lower.equals("http") && !lower.equals("https")) {
            throw new IllegalArgumentException(name + " scheme must be http or https, got: " + uri);
        }
    }
}
