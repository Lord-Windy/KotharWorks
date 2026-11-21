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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthMode;
import com.kotharworks.auth.AuthRejectException;

/**
 * Double-defense CSRF guard for cookie-authenticated mutating requests. The
 * router invokes {@link #check} on every non-{@link AccessPolicy#PUBLIC}
 * mutating ({@code POST}/{@code PUT}/{@code DELETE}/{@code PATCH}) request
 * that authenticated via session cookie; {@code Authorization: Bearer}
 * requests and public login routes never reach it, so a stale cookie cannot
 * wedge a Bearer login flow.
 *
 * <p>Three layers, all of which must pass:
 * <ol>
 *   <li>{@code Sec-Fetch-Site: cross-site} is rejected outright;</li>
 *   <li>any present {@code Origin} must equal the configured
 *       {@code publicOrigin} — or, in {@link AuthMode#DEV_LOCAL}, the bound
 *       loopback origins {@code http://127.0.0.1:<port>} /
 *       {@code http://[::1]:<port>} (the port comes from an
 *       {@link IntSupplier} so ephemeral {@code port = 0} test servers work);</li>
 *   <li>the request must carry {@code Content-Type: application/json} or a
 *       per-session CSRF token via {@code X-CSRF-Token} header or
 *       {@code csrf_token} form field.</li>
 * </ol>
 * Any failure throws {@link AuthRejectException} with
 * {@link AuthErrorCode#CSRF_REJECTED} (HTTP 403) before the route handler runs.
 */
public final class CsrfGuard {

    private final String publicOrigin;
    private final AuthMode authMode;
    private final IntSupplier boundPort;

    public CsrfGuard(String publicOrigin, AuthMode authMode, IntSupplier boundPort) {
        this.publicOrigin = Objects.requireNonNull(publicOrigin, "publicOrigin");
        this.authMode = Objects.requireNonNull(authMode, "authMode");
        this.boundPort = Objects.requireNonNull(boundPort, "boundPort");
    }

    /**
     * Validates a cookie-authenticated mutating request.
     *
     * @param method           the HTTP method (already known mutating)
     * @param headers          request headers
     * @param body             buffered request body (read for form-token extraction)
     * @param sessionCsrfToken the per-session CSRF token from the
     *                         {@code SessionRecord}; may be {@code null} when unknown
     * @throws AuthRejectException {@link AuthErrorCode#CSRF_REJECTED} on any layer failure
     */
    public void check(String method, Map<String, List<String>> headers, byte[] body, String sessionCsrfToken) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(headers, "headers");

        String fetchSite = firstHeader(headers, "Sec-Fetch-Site");
        if (fetchSite != null && fetchSite.trim().equalsIgnoreCase("cross-site")) {
            throw new AuthRejectException(AuthErrorCode.CSRF_REJECTED, "Sec-Fetch-Site: cross-site");
        }

        String origin = firstHeader(headers, "Origin");
        if (origin != null && !origin.isBlank() && !originAllowed(origin)) {
            throw new AuthRejectException(AuthErrorCode.CSRF_REJECTED, "origin mismatch: " + origin);
        }

        String contentType = firstHeader(headers, "Content-Type");
        boolean jsonBody = contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/json");
        boolean csrfHeader = tokenEquals(firstHeader(headers, "X-CSRF-Token"), sessionCsrfToken);
        boolean csrfFormField = sessionCsrfToken != null
                && tokenEquals(formFieldToken(headers, body), sessionCsrfToken);
        if (!jsonBody && !csrfHeader && !csrfFormField) {
            throw new AuthRejectException(AuthErrorCode.CSRF_REJECTED, "missing application/json or csrf token");
        }
    }

    private boolean originAllowed(String origin) {
        String normalized = normalizeOrigin(origin);
        if (normalized.equals(normalizeOrigin(publicOrigin))) {
            return true;
        }
        if (authMode == AuthMode.DEV_LOCAL) {
            int port = boundPort.getAsInt();
            if (normalized.equalsIgnoreCase("http://127.0.0.1:" + port)
                    || normalized.equalsIgnoreCase("http://[::1]:" + port)) {
                return true;
            }
        }
        return false;
    }

    /** Lowercases scheme+host and strips a trailing slash so variants match. */
    private static String normalizeOrigin(String origin) {
        String trimmed = origin.trim().toLowerCase(Locale.ROOT);
        if (trimmed.endsWith("/") && !trimmed.endsWith("://")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean tokenEquals(String presented, String expected) {
        return presented != null && expected != null && !expected.isBlank() && presented.equals(expected);
    }

    /** Parses the buffered {@code application/x-www-form-urlencoded} body for {@code csrf_token}. */
    private static String formFieldToken(Map<String, List<String>> headers, byte[] body) {
        String contentType = firstHeader(headers, "Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")
                || body == null || body.length == 0) {
            return null;
        }
        String form = new String(body, StandardCharsets.UTF_8);
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String name = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            if (name.equals("csrf_token")) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            // HttpServer canonicalizes header keys to lower case.
            values = headers.get(name.toLowerCase(Locale.ROOT));
        }
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
