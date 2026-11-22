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

import java.util.Objects;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthRejectException;
import com.kotharworks.auth.jwt.JwtHumanAuthenticator;
import com.kotharworks.auth.principal.Principal;
import com.kotharworks.auth.token.ApiTokenCodec;
import com.kotharworks.auth.token.ScopedApiTokenAuthenticator;
import com.sun.net.httpserver.HttpExchange;

/**
 * Strategy seam that turns an {@link HttpExchange} into an authenticated
 * {@link Authentication} (or throws {@link AuthRejectException}).
 *
 * <p>{@link #bearerThenCookie} is the standard composition: an
 * {@code Authorization: Bearer} header routes to the human-JWT authenticator
 * (JWTs) or scoped-token authenticator ({@code iw_bot_} tokens) and — when
 * present but invalid — hard-rejects, never silently falling through to the
 * ambient cookie. Without an Authorization header, the session cookie is
 * consulted; absence simply yields anonymous.
 */
@FunctionalInterface
public interface RequestAuthenticator {

    /** The outcome of authenticating one request. */
    record Authentication(Principal principal, boolean viaSessionCookie, String sessionCsrfToken) {

        public Authentication {
            Objects.requireNonNull(principal, "principal");
        }

        /** Bearer-token authentication (JWT or scoped API token); never CSRF-checked. */
        public static Authentication bearer(Principal principal) {
            return new Authentication(principal, false, null);
        }

        /** Session-cookie authentication; carries the per-session CSRF token. */
        public static Authentication cookieSession(Principal.HumanPrincipal principal, String csrfToken) {
            return new Authentication(principal, true, csrfToken);
        }

        /** No credentials. */
        public static Authentication anonymous() {
            return new Authentication(Principal.AnonymousPrincipal.INSTANCE, false, null);
        }
    }

    /**
     * Authenticates the exchange.
     *
     * @throws AuthRejectException when credentials are present but invalid
     */
    Authentication authenticate(HttpExchange exchange) throws AuthRejectException;

    /** Standard composition over API tokens, human JWTs, and BFF session cookies. */
    static RequestAuthenticator bearerThenCookie(ScopedApiTokenAuthenticator apiTokens,
            JwtHumanAuthenticator humans, SessionCookieAuthenticator sessions) {
        Objects.requireNonNull(apiTokens, "apiTokens");
        Objects.requireNonNull(humans, "humans");
        Objects.requireNonNull(sessions, "sessions");
        return exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null && !authorization.isBlank()) {
                String trimmed = authorization.trim();
                if (!trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
                    throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN,
                            "malformed Authorization header");
                }
                String credential = trimmed.substring("Bearer ".length()).trim();
                if (credential.isEmpty()) {
                    throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN,
                            "empty Bearer credential");
                }
                if (ApiTokenCodec.isApiToken(credential)) {
                    return Authentication.bearer(apiTokens.authenticate(credential));
                }
                return Authentication.bearer(humans.authenticate(credential));
            }
            return sessions.authenticate(exchange.getRequestHeaders().get("Cookie"))
                    .orElseGet(Authentication::anonymous);
        };
    }
}
