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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.kotharworks.auth.AuthMode;

/**
 * Codec for the BFF session cookie pair: {@code __Host-kw_session} for HTTPS
 * origins ({@code HttpOnly; Secure; Path=/; SameSite=Lax}, no Domain, satisfying
 * the {@code __Host-} prefix rules) and {@code kw_dev_session} for loopback HTTP
 * ({@code HttpOnly; Path=/; SameSite=Lax}, no Secure). Raw cookie tokens are
 * hashed with SHA-256 before any store lookup.
 */
public final class CookieCodec {

    /** Secure-context cookie name (HTTPS origins). */
    public static final String HOST_SESSION_COOKIE = "__Host-kw_session";
    /** Loopback-development cookie name (plain HTTP on 127.0.0.1/::1). */
    public static final String DEV_SESSION_COOKIE = "kw_dev_session";

    private static final String SESSION_ATTRIBUTES = "Path=/; HttpOnly; SameSite=Lax";
    private static final String HOST_SESSION_ATTRIBUTES = SESSION_ATTRIBUTES + "; Secure";

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final String cookieName;
    private final String setCookieAttributes;

    private CookieCodec(String cookieName, String setCookieAttributes) {
        this.cookieName = cookieName;
        this.setCookieAttributes = setCookieAttributes;
    }

    /** Production codec for HTTPS origins ({@code __Host-kw_session}, Secure). */
    public static CookieCodec production() {
        return new CookieCodec(HOST_SESSION_COOKIE, HOST_SESSION_ATTRIBUTES);
    }

    /** Development codec for loopback HTTP ({@code kw_dev_session}, no Secure). */
    public static CookieCodec devLoopback() {
        return new CookieCodec(DEV_SESSION_COOKIE, SESSION_ATTRIBUTES);
    }

    /**
     * Selects {@link #production()} for HTTPS {@link AuthMode#OIDC} origins and
     * {@link #devLoopback()} for plain-HTTP loopback development/test origins.
     */
    public static CookieCodec forOrigin(String publicOrigin, AuthMode authMode) {
        Objects.requireNonNull(publicOrigin, "publicOrigin");
        Objects.requireNonNull(authMode, "authMode");
        if (authMode == AuthMode.OIDC
                && publicOrigin.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
            return production();
        }
        return devLoopback();
    }

    /** The cookie name this codec manages. */
    public String sessionCookieName() {
        return cookieName;
    }

    /**
     * The {@code Set-Cookie} header value establishing the session with the
     * given raw token. The token is never logged and stored only as a digest.
     */
    public String setCookieHeaderValue(String rawToken) {
        Objects.requireNonNull(rawToken, "rawToken");
        if (rawToken.isBlank()) {
            throw new IllegalArgumentException("rawToken must not be blank");
        }
        return cookieName + "=" + rawToken + "; " + setCookieAttributes;
    }

    /**
     * The {@code Set-Cookie} header value that clears this codec's cookie in
     * the browser, preserving {@code Secure; Path=/} on {@code __Host-} cookies
     * as required by RFC 6265bis.
     */
    public String clearCookieHeaderValue() {
        return cookieName + "=; " + setCookieAttributes + "; Max-Age=0";
    }

    /** Extracts this codec's cookie value from a raw {@code Cookie} header. */
    public Optional<String> extractSessionCookie(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return Optional.empty();
        }
        String prefix = cookieName + "=";
        for (String part : cookieHeader.split(";")) {
            String candidate = part.trim();
            if (candidate.startsWith(prefix)) {
                String value = candidate.substring(prefix.length()).trim();
                if (!value.isEmpty()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Extracts this codec's cookie value across all {@code Cookie} header lines
     * (supporting multi-header HTTP/2 forwarding per RFC 9113 §8.2.3).
     */
    public Optional<String> extractSessionCookie(List<String> cookieHeaders) {
        if (cookieHeaders == null || cookieHeaders.isEmpty()) {
            return Optional.empty();
        }
        for (String header : cookieHeaders) {
            Optional<String> found = extractSessionCookie(header);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** SHA-256 digest of a raw cookie token — the form stores persist. */
    public static byte[] sha256Digest(String rawToken) {
        Objects.requireNonNull(rawToken, "rawToken");
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK is missing SHA-256", e);
        }
    }

    /** Mints a fresh 43-char base64url random token from 32 SecureRandom bytes. */
    public static String newRandomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }
}
