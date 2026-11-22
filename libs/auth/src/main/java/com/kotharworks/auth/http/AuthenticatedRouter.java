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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthRejectException;
import com.kotharworks.auth.principal.Principal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Deny-by-default HTTP router over {@link HttpServer} with a virtual-thread
 * executor. A single root {@code HttpContext("/")} dispatcher matches
 * {@code (method, pathPattern)} pairs with {@code {param}} extraction —
 * unknown or non-canonical paths return {@code 404 NOT_FOUND}, known paths with
 * the wrong method return {@code 405 METHOD_NOT_ALLOWED} (with an {@code Allow}
 * header), and oversized request bodies return {@code 413 PAYLOAD_TOO_LARGE}.
 *
 * <p>Every registered route declares an explicit {@link AccessPolicy} (there
 * is no route without one); the pipeline per request is: route &amp; method
 * match → authentication → anonymous gate → header-level CSRF → bounded body
 * read → body/token CSRF → policy → handler. {@link #registeredRoutes()}
 * exposes the live route table for automated security audits.
 */
public final class AuthenticatedRouter {

    /** Default maximum buffered request body size (256 KiB). */
    public static final int DEFAULT_MAX_BODY_BYTES = 256 * 1024;

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private static final Consumer<Throwable> DEFAULT_ERROR_REPORTER = cause ->
            System.err.println("[AuthenticatedRouter] unhandled request exception: " + cause);

    private final HttpServer server;
    private final RequestAuthenticator authenticator;
    private final CsrfGuard csrfGuard;
    private final int maxBodyBytes;
    private final Consumer<Throwable> errorReporter;
    private final List<Route> routes = new ArrayList<>();
    private volatile List<Route> routeSnapshot = List.of();

    private volatile Executor executor;

    /** Immutable audit entry describing one registered route. */
    public record RegisteredRoute(String method, String pattern, AccessPolicy policy) {
    }

    /** Per-request handler context. */
    public record RouteContext(HttpExchange exchange, Principal principal, Map<String, String> pathParams,
            byte[] requestBody) {

        /** Writes a full response body and closes the exchange. */
        public void respond(int statusCode, String contentType, byte[] body) throws IOException {
            HttpExchange x = exchange;
            x.getResponseHeaders().set("Content-Type", contentType);
            x.sendResponseHeaders(statusCode, body.length);
            try (var out = x.getResponseBody()) {
                out.write(body);
            }
        }

        /** Writes a UTF-8 text/HTML response. */
        public void respondText(int statusCode, String contentType, String text) throws IOException {
            respond(statusCode, contentType, text.getBytes(StandardCharsets.UTF_8));
        }

        /** Writes a {@code 303 See Other} redirect with the given {@code Location}. */
        public void respondRedirect(String location) throws IOException {
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
        }

        /** Writes a JSON body. */
        public void respondJson(int statusCode, String json) throws IOException {
            respond(statusCode, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Route handler; runs after auth, CSRF, and policy checks all pass. */
    @FunctionalInterface
    public interface RouteHandler {

        /** Handles the request and writes the response. */
        void handle(RouteContext ctx) throws IOException;
    }

    private record Route(String method, String pattern, List<String> segments, int literalCount,
            AccessPolicy policy, RouteHandler handler) {
    }

    public AuthenticatedRouter(HttpServer server, RequestAuthenticator authenticator, CsrfGuard csrfGuard) {
        this(server, authenticator, csrfGuard, DEFAULT_MAX_BODY_BYTES, DEFAULT_ERROR_REPORTER);
    }

    public AuthenticatedRouter(HttpServer server, RequestAuthenticator authenticator, CsrfGuard csrfGuard,
            int maxBodyBytes) {
        this(server, authenticator, csrfGuard, maxBodyBytes, DEFAULT_ERROR_REPORTER);
    }

    public AuthenticatedRouter(HttpServer server, RequestAuthenticator authenticator, CsrfGuard csrfGuard,
            int maxBodyBytes, Consumer<Throwable> errorReporter) {
        this.server = Objects.requireNonNull(server, "server");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.csrfGuard = Objects.requireNonNull(csrfGuard, "csrfGuard");
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be positive: " + maxBodyBytes);
        }
        this.maxBodyBytes = maxBodyBytes;
        this.errorReporter = Objects.requireNonNull(errorReporter, "errorReporter");
    }

    /**
     * Registers a route. The {@code policy} parameter is mandatory — deny-by-default
     * is enforced at the API surface, not by convention.
     *
     * @param method  HTTP method, e.g. {@code "GET"}
     * @param pattern path pattern; literal {@code /} segments with optional
     *                {@code {name}} captures ({@code /api/v1/houses/{houseId}})
     * @param policy  required access policy
     * @param handler the handler to run when all checks pass
     * @throws IllegalArgumentException on invalid pattern or duplicate {@code (method, pattern)}
     */
    public synchronized void register(String method, String pattern, AccessPolicy policy, RouteHandler handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(handler, "handler");
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        List<String> segments = parsePatternSegments(pattern);
        if (routes.stream().anyMatch(r -> r.method().equals(normalizedMethod) && r.pattern().equals(pattern))) {
            throw new IllegalArgumentException("duplicate route: " + normalizedMethod + " " + pattern);
        }
        int literalCount = 0;
        for (String segment : segments) {
            if (!isParamSegment(segment)) {
                literalCount++;
            }
        }
        routes.add(new Route(normalizedMethod, pattern, segments, literalCount, policy, handler));
        routeSnapshot = List.copyOf(routes);
    }

    /** Snapshot of the registered route table for security audits. */
    public List<RegisteredRoute> registeredRoutes() {
        return routeSnapshot.stream()
                .map(r -> new RegisteredRoute(r.method(), r.pattern(), r.policy()))
                .sorted(Comparator.comparing(RegisteredRoute::pattern).thenComparing(RegisteredRoute::method))
                .toList();
    }

    /** Installs the virtual-thread executor and the root dispatcher, then starts serving. */
    public void start() throws IOException {
        Executor perTask = Executors.newVirtualThreadPerTaskExecutor();
        this.executor = perTask;
        server.setExecutor(perTask);
        server.createContext("/", this::dispatch);
        server.start();
    }

    /** Stops the server and shuts the executor down. */
    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
        Executor current = executor;
        if (current instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- dispatch

    private void dispatch(HttpExchange exchange) {
        try {
            doDispatch(exchange);
        } catch (AuthRejectException reject) {
            writeError(exchange, reject.code(), reject.getMessage());
        } catch (IOException | RuntimeException e) {
            errorReporter.accept(e);
            writeInternalServerError(exchange);
        } finally {
            exchange.close();
        }
    }

    private void doDispatch(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        List<String> pathSegments = parseRequestSegments(exchange.getRequestURI().getRawPath())
                .orElseThrow(() -> new AuthRejectException(AuthErrorCode.NOT_FOUND, "no route for path"));

        List<Route> allMatches = routeSnapshot.stream()
                .filter(r -> matches(r.segments(), pathSegments))
                .toList();
        if (allMatches.isEmpty()) {
            throw new AuthRejectException(AuthErrorCode.NOT_FOUND, "no route for path");
        }
        int bestLiteralCount = allMatches.stream().mapToInt(Route::literalCount).max().orElse(0);
        List<Route> pathMatches = allMatches.stream()
                .filter(r -> r.literalCount() == bestLiteralCount)
                .toList();

        Route route = pathMatches.stream().filter(r -> r.method().equals(method)).findFirst().orElse(null);
        if (route == null) {
            String allow = pathMatches.stream().map(Route::method).distinct()
                    .sorted().reduce((a, b) -> a + ", " + b).orElseThrow();
            exchange.getResponseHeaders().set("Allow", allow);
            throw new AuthRejectException(AuthErrorCode.METHOD_NOT_ALLOWED, "method not allowed: " + method);
        }

        RequestAuthenticator.Authentication auth = authenticator.authenticate(exchange);
        if (route.policy() != AccessPolicy.PUBLIC && auth.principal() instanceof Principal.AnonymousPrincipal) {
            throw new AuthRejectException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        }
        boolean checkCsrf = route.policy() != AccessPolicy.PUBLIC
                && auth.viaSessionCookie()
                && MUTATING_METHODS.contains(method);
        if (checkCsrf) {
            csrfGuard.checkHeaders(method, exchange.getRequestHeaders());
        }

        byte[] requestBody = readBoundedBody(exchange, maxBodyBytes);
        if (checkCsrf) {
            csrfGuard.check(method, exchange.getRequestHeaders(), requestBody, auth.sessionCsrfToken());
        }
        if (!route.policy().admits(auth.principal())) {
            throw new AuthRejectException(rejectionCode(auth.principal()));
        }

        Map<String, String> pathParams = extractParams(route.segments(), pathSegments);
        route.handler().handle(new RouteContext(exchange, auth.principal(), pathParams, requestBody));
    }

    private static byte[] readBoundedBody(HttpExchange exchange, int maxBytes) throws IOException {
        String contentLengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLengthHeader != null && !contentLengthHeader.isBlank()) {
            try {
                long declared = Long.parseLong(contentLengthHeader.trim());
                if (declared > maxBytes) {
                    throw new AuthRejectException(AuthErrorCode.PAYLOAD_TOO_LARGE,
                            "Content-Length exceeds " + maxBytes);
                }
            } catch (NumberFormatException ignored) {
                // Fall through to bounded stream read.
            }
        }
        byte[] buf = exchange.getRequestBody().readNBytes(maxBytes + 1);
        if (buf.length > maxBytes) {
            throw new AuthRejectException(AuthErrorCode.PAYLOAD_TOO_LARGE,
                    "request body exceeds " + maxBytes + " bytes");
        }
        return buf;
    }

    /**
     * Chooses the rejection code for a policy failure. Only invoked when
     * {@code !policy.admits(principal)}: anonymous → 401; scoped tokens can
     * only fail human-restricted policies → 403; authenticated humans can only
     * fail role policies → 403 ROLE_REQUIRED.
     */
    private static AuthErrorCode rejectionCode(Principal principal) {
        return switch (principal) {
            case Principal.AnonymousPrincipal _ -> AuthErrorCode.AUTHENTICATION_REQUIRED;
            case Principal.ScopedTokenPrincipal _ -> AuthErrorCode.FORBIDDEN;
            case Principal.HumanPrincipal _ -> AuthErrorCode.ROLE_REQUIRED;
        };
    }

    // ------------------------------------------------------------ path matching

    private static boolean isParamSegment(String segment) {
        return segment.length() > 2 && segment.startsWith("{") && segment.endsWith("}");
    }

    private static List<String> parsePatternSegments(String pattern) {
        if (!pattern.startsWith("/")) {
            throw new IllegalArgumentException("route pattern must start with '/': " + pattern);
        }
        if (pattern.equals("/")) {
            return List.of();
        }
        if (pattern.endsWith("/")) {
            throw new IllegalArgumentException("route pattern must not end with '/': " + pattern);
        }
        String[] raw = pattern.substring(1).split("/", -1);
        List<String> segments = new ArrayList<>(raw.length);
        for (String segment : raw) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("invalid segment in route pattern: " + pattern);
            }
            if ((segment.startsWith("{") || segment.endsWith("}")) && !isParamSegment(segment)) {
                throw new IllegalArgumentException("malformed {param} segment in route pattern: " + pattern);
            }
            segments.add(segment);
        }
        return List.copyOf(segments);
    }

    private static Optional<List<String>> parseRequestSegments(String rawPath) {
        if (rawPath == null || !rawPath.startsWith("/")) {
            return Optional.empty();
        }
        if (rawPath.equals("/")) {
            return Optional.of(List.of());
        }
        if (rawPath.endsWith("/")) {
            return Optional.empty();
        }
        String[] rawSegments = rawPath.substring(1).split("/", -1);
        List<String> decodedSegments = new ArrayList<>(rawSegments.length);
        for (String rawSegment : rawSegments) {
            if (rawSegment.isEmpty()) {
                return Optional.empty();
            }
            String decoded = decodePathSegment(rawSegment);
            if (decoded == null
                    || decoded.isEmpty()
                    || decoded.equals(".")
                    || decoded.equals("..")
                    || decoded.indexOf('/') >= 0
                    || decoded.indexOf('\\') >= 0
                    || decoded.indexOf('\0') >= 0) {
                return Optional.empty();
            }
            decodedSegments.add(decoded);
        }
        return Optional.of(decodedSegments);
    }

    /**
     * RFC 3986 percent-decoder for a single path segment: keeps {@code +}
     * literal (unlike form decoding) and returns {@code null} on malformed
     * {@code %XX} escapes.
     */
    private static String decodePathSegment(String raw) {
        if (raw.indexOf('%') < 0) {
            return raw;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) {
                    return null;
                }
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else {
                byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.writeBytes(utf8);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean matches(List<String> patternSegments, List<String> pathSegments) {
        if (patternSegments.size() != pathSegments.size()) {
            return false;
        }
        for (int i = 0; i < patternSegments.size(); i++) {
            String patternSegment = patternSegments.get(i);
            if (isParamSegment(patternSegment)) {
                continue;
            }
            if (!patternSegment.equals(pathSegments.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> extractParams(List<String> patternSegments, List<String> pathSegments) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < patternSegments.size(); i++) {
            String patternSegment = patternSegments.get(i);
            if (isParamSegment(patternSegment)) {
                params.put(patternSegment.substring(1, patternSegment.length() - 1), pathSegments.get(i));
            }
        }
        return Map.copyOf(params);
    }

    // -------------------------------------------------------------- error body

    private static void writeError(HttpExchange exchange, AuthErrorCode code, String detail) {
        String msg = detail == null || detail.isBlank() ? code.name() : detail;
        byte[] body = ("{\"errorCode\":\"" + code.name() + "\",\"message\":\""
                + escapeJson(msg) + "\"}").getBytes(StandardCharsets.UTF_8);
        try {
            if (code == AuthErrorCode.PAYLOAD_TOO_LARGE) {
                exchange.getResponseHeaders().set("Connection", "close");
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code.httpStatus(), body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
            if (code == AuthErrorCode.PAYLOAD_TOO_LARGE) {
                drainQuietly(exchange);
            }
        } catch (IOException | RuntimeException ignored) {
            // Response channel already broken; nothing further to do.
        }
    }

    private static void drainQuietly(HttpExchange exchange) {
        // Best-effort small drain so loopback test clients read the 413 status cleanly before socket close.
        byte[] scratch = new byte[4096];
        try {
            for (int i = 0; i < 4; i++) {
                if (exchange.getRequestBody().read(scratch) < 0) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // Ignored.
        }
    }

    private static void writeInternalServerError(HttpExchange exchange) {
        byte[] body = "{\"errorCode\":\"INTERNAL_ERROR\",\"message\":\"internal server error\"}"
                .getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException | RuntimeException ignored) {
            // Response channel already broken; nothing further to do.
        }
    }

    private static String escapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Convenience: the bound address of the wrapped server (for fuse checks). */
    public InetSocketAddress boundAddress() {
        return server.getAddress();
    }
}
