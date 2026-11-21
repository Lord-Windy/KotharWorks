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

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import com.kotharworks.auth.http.RequestAuthenticator.Authentication;
import com.kotharworks.auth.principal.Principal;
import com.kotharworks.auth.spi.SessionRecord;
import com.kotharworks.auth.spi.SessionStore;

/**
 * Resolves a BFF session cookie to a cookie-authenticated
 * {@link Principal.HumanPrincipal}. Cookie absence, unknown tokens, and
 * expired sessions all yield {@link Optional#empty()} — the caller treats
 * ambient cookies as best-effort and the router falls back to anonymous
 * (401 on protected routes), never a hard reject.
 */
public final class SessionCookieAuthenticator {

    private final CookieCodec cookies;
    private final SessionStore sessions;
    private final Clock clock;

    public SessionCookieAuthenticator(CookieCodec cookies, SessionStore sessions, Clock clock) {
        this.cookies = Objects.requireNonNull(cookies, "cookies");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Authenticates the session cookie in the given raw {@code Cookie} header.
     *
     * @return the cookie authentication, or empty when absent/unknown/expired
     */
    public Optional<Authentication> authenticate(String cookieHeader) {
        String rawToken = cookies.extractSessionCookie(cookieHeader).orElse(null);
        if (rawToken == null) {
            return Optional.empty();
        }
        SessionRecord record = sessions.findByDigest(CookieCodec.sha256Digest(rawToken)).orElse(null);
        if (record == null || record.expiresAt().isBefore(clock.instant())) {
            return Optional.empty();
        }
        Principal.HumanPrincipal principal = new Principal.HumanPrincipal(
                record.userId(), record.externalId(), record.displayName(), record.grantedRoles());
        return Optional.of(Authentication.cookieSession(principal, record.csrfToken()));
    }
}
