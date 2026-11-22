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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthMode;
import com.kotharworks.auth.AuthRejectException;
import com.sun.net.httpserver.Headers;

/**
 * Multi-layer CSRF guard for cookie-authenticated mutating requests. The
 * router invokes {@link #checkHeaders} before reading any request body and
 * {@link #check} once the bounded body is available on every
 * non-{@link AccessPolicy#PUBLIC} mutating ({@code POST}/{@code PUT}/
 * {@code DELETE}/{@code PATCH}) request that authenticated via session cookie;
 * {@code Authorization: Bearer} requests and public login routes never reach
 * it, so a stale cookie cannot wedge a Bearer or login flow.
 *
 * <p>Three layers, all of which must pass:
 * <ol>
 *   <li>any present {@code Sec-Fetch-Site} header must be {@code same-origin}
 *       or {@code none} (blocking {@code cross-site}, {@code same-site}
 *       cross-port/subdomain requests, and duplicate header smuggling);</li>
 *   <li>any present {@code Origin} must be single-valued and equal the
 *       configured {@code publicOrigin} — or, in {@link AuthMode#DEV_LOCAL},
 *       the bound loopback origins {@code http://127.0.0.1:<port>} /
 *       {@code http://[::1]:<port>} (the port comes from an
 *       {@link IntSupplier} so ephemeral {@code port = 0} test servers work);</li>
 *   <li>the request must carry {@code Content-Type: application/json} or a
 *       per-session CSRF token via {@code X-CSRF-Token} header or
 *       {@code csrf_token} form field (compared in constant time).</li>
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
     * Validates header-only CSRF invariants ({@code Sec-Fetch-Site} and
     * {@code Origin}) before the router buffers any request body.
     */
    public void checkHeaders(String method, Map<String, List<String>> headers) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(headers, "headers");

        for (String fetchSiteValue : headerValues(headers, "Sec-Fetch-Site")) {
            if (fetchSiteValue == null) {
                continue;
            }
            for (String token : fetchSiteValue.split(",")) {
                String site = token.trim();
                if (!site.isEmpty()
                        && !site.equalsIgnoreCase("same-origin")
                        && !site.equalsIgnoreCase("none")) {
                    throw new AuthRejectException(AuthErrorCode.CSRF_REJECTED,
                            "Sec-Fetch-Site rejected: " + site);
                }
            }
        }

        List<String> origins = new ArrayList<>();
        for (String originHeader : headerValues(headers, "Origin")) {
            if (originHeader != null && !originHeader.isBlank()) {
                origins.add(originHeader.trim());
            }
        }
        if (origins.size() > 1) {
            throw new AuthRejectException(AuthErrorCode.CSRF_REJECTED, "multiple Origin headers");
        }
        if (origins.size() == 1 && !originAllowed(origins.getFirst())) {
            throw new AuthRejectException(AuthErrorCode.CSRF_REJECTED,
                    "origin mismatch: " + origins.getFirst());
        }
    }

    /**
     * Validates a cookie-authenticated mutating request across all three CSRF
     * layers.
     *
     * @param method           the HTTP method (already known mutating)
     * @param headers          request headers
     * @param body             buffered request body (read for form-token extraction)
     * @param sessionCsrfToken the per-session CSRF token from the
     *                         {@code SessionRecord}; may be {@code null} when unknown
     * @throws AuthRejectException {@link AuthErrorCode#CSRF_REJECTED} on any layer failure
     */
    public void check(String method, Map<String, List<String>> headers, byte[] body, String sessionCsrfToken) {
        checkHeaders(method, headers);

        String contentType = firstHeader(headers, "Content-Type");
        boolean jsonBody = mediaTypeEquals(contentType, "application/json");
        boolean csrfHeader = tokenEquals(firstHeader(headers, "X-CSRF-Token"), sessionCsrfToken);
        boolean csrfFormField = sessionCsrfToken != null
                && tokenEquals(formFieldToken(contentType, body), sessionCsrfToken);
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

    private static boolean mediaTypeEquals(String contentType, String expectedMediaType) {
        if (contentType == null) {
            return false;
        }
        int semicolon = contentType.indexOf(';');
        String mediaType = (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
                .trim()
                .toLowerCase(Locale.ROOT);
        return mediaType.equals(expectedMediaType);
    }

    private static boolean tokenEquals(String presented, String expected) {
        if (presented == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /** Parses the buffered {@code application/x-www-form-urlencoded} body for {@code csrf_token}. */
    private static String formFieldToken(String contentType, byte[] body) {
        if (!mediaTypeEquals(contentType, "application/x-www-form-urlencoded")
                || body == null || body.length == 0) {
            return null;
        }
        String form = new String(body, StandardCharsets.UTF_8);
        try {
            for (String pair : form.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String name = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (name.equals("csrf_token")) {
                    return value;
                }
            }
        } catch (IllegalArgumentException malformedPercentEncoding) {
            return null;
        }
        return null;
    }

    private static List<String> headerValues(Map<String, List<String>> headers, String name) {
        if (headers instanceof Headers jdkHeaders) {
            List<String> values = jdkHeaders.get(name);
            return values == null ? List.of() : values;
        }
        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null) {
                matched.addAll(entry.getValue());
            }
        }
        return matched;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headerValues(headers, name);
        return values.isEmpty() ? null : values.getFirst();
    }
}
