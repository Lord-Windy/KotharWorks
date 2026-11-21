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
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthRejectException;
import com.kotharworks.auth.principal.Principal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Deny-by-default HTTP router over {@link HttpServer} with a virtual-thread
 * executor. A single root {@code HttpContext("/")} dispatcher matches
 * {@code (method, pathPattern)} pairs with {@code {param}} extraction —
 * unknown paths return {@code 404 NOT_FOUND}, known paths with the wrong
 * method return {@code 405 METHOD_NOT_ALLOWED} (with an {@code Allow} header).
 *
 * <p>Every registered route declares an explicit {@link AccessPolicy} (there
 * is no route without one); the pipeline per request is: route match →
 * authentication → CSRF (cookie-authenticated mutating requests on non-public
 * routes) → policy → handler. {@link #registeredRoutes()} exposes the live
 * route table for automated security audits.
 *
 * <p>Request bodies are buffered fully in memory (the CSRF form-token check
 * needs them); sized for Phase-0 API/JSON traffic, not uploads.
 */
public final class AuthenticatedRouter {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final HttpServer server;
    private final RequestAuthenticator authenticator;
    private final CsrfGuard csrfGuard;
    private final List<Route> routes = new ArrayList<>();

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

    private record Route(String method, String pattern, AccessPolicy policy, RouteHandler handler) {

        List<String> segments() {
            return splitSegments(pattern);
        }
    }

    public AuthenticatedRouter(HttpServer server, RequestAuthenticator authenticator, CsrfGuard csrfGuard) {
        this.server = Objects.requireNonNull(server, "server");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.csrfGuard = Objects.requireNonNull(csrfGuard, "csrfGuard");
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
     * @throws IllegalArgumentException on duplicate {@code (method, pattern)}
     */
    public synchronized void register(String method, String pattern, AccessPolicy policy, RouteHandler handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(handler, "handler");
        String normalizedMethod = method.toUpperCase(Locale.ROOT);
        if (routes.stream().anyMatch(r -> r.method().equals(normalizedMethod) && r.pattern().equals(pattern))) {
            throw new IllegalArgumentException("duplicate route: " + normalizedMethod + " " + pattern);
        }
        routes.add(new Route(normalizedMethod, pattern, policy, handler));
    }

    /** Snapshot of the registered route table for security audits. */
    public synchronized List<RegisteredRoute> registeredRoutes() {
        return routes.stream()
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
            writeError(exchange, reject.code());
        } catch (IOException | RuntimeException e) {
            writeInternalServerError(exchange, e);
        } finally {
            exchange.close();
        }
    }

    private void doDispatch(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        List<String> pathSegments = splitSegments(exchange.getRequestURI().getPath());

        List<Route> pathMatches;
        synchronized (this) {
            pathMatches = routes.stream().filter(r -> matches(r.segments(), pathSegments)).toList();
        }
        if (pathMatches.isEmpty()) {
            throw new AuthRejectException(AuthErrorCode.NOT_FOUND, "no route for path");
        }
        Route route = pathMatches.stream().filter(r -> r.method().equals(method)).findFirst().orElse(null);
        if (route == null) {
            String allow = pathMatches.stream().map(Route::method).distinct()
                    .sorted().reduce((a, b) -> a + ", " + b).orElseThrow();
            exchange.getResponseHeaders().set("Allow", allow);
            throw new AuthRejectException(AuthErrorCode.METHOD_NOT_ALLOWED, "method not allowed: " + method);
        }

        RequestAuthenticator.Authentication auth = authenticator.authenticate(exchange);
        if (route.policy() != AccessPolicy.PUBLIC && auth.viaSessionCookie() && MUTATING_METHODS.contains(method)) {
            csrfGuard.check(method, exchange.getRequestHeaders(), requestBody, auth.sessionCsrfToken());
        }
        if (!route.policy().admits(auth.principal())) {
            throw new AuthRejectException(rejectionCode(route.policy(), auth.principal()));
        }

        Map<String, String> pathParams = extractParams(route.segments(), pathSegments);
        route.handler().handle(new RouteContext(exchange, auth.principal(), pathParams, requestBody));
    }

    /**
     * Chooses the rejection code for a policy failure. Only invoked when
     * {@code !policy.admits(principal)}: anonymous → 401; scoped tokens can
     * only fail human-restricted policies → 403; authenticated humans can only
     * fail role policies → 403 ROLE_REQUIRED.
     */
    private static AuthErrorCode rejectionCode(AccessPolicy policy, Principal principal) {
        return switch (principal) {
            case Principal.AnonymousPrincipal _ -> AuthErrorCode.AUTHENTICATION_REQUIRED;
            case Principal.ScopedTokenPrincipal _ -> AuthErrorCode.FORBIDDEN;
            case Principal.HumanPrincipal _ -> AuthErrorCode.ROLE_REQUIRED;
        };
    }

    // ------------------------------------------------------------ path matching

    private static List<String> splitSegments(String path) {
        String[] raw = path.split("/");
        List<String> segments = new ArrayList<>(raw.length);
        for (String segment : raw) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private static boolean matches(List<String> patternSegments, List<String> pathSegments) {
        if (patternSegments.size() != pathSegments.size()) {
            return false;
        }
        for (int i = 0; i < patternSegments.size(); i++) {
            String patternSegment = patternSegments.get(i);
            if (patternSegment.startsWith("{") && patternSegment.endsWith("}")) {
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
            if (patternSegment.startsWith("{") && patternSegment.endsWith("}")) {
                params.put(patternSegment.substring(1, patternSegment.length() - 1), pathSegments.get(i));
            }
        }
        return params;
    }

    // -------------------------------------------------------------- error body

    private static void writeError(HttpExchange exchange, AuthErrorCode code) {
        byte[] body = ("{\"error\":\"" + code.name() + "\"}").getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code.httpStatus(), body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException | RuntimeException ignored) {
            // Response channel already broken; nothing further to do.
        }
    }

    private static void writeInternalServerError(HttpExchange exchange, Exception cause) {
        byte[] body = "{\"error\":\"INTERNAL_ERROR\"}".getBytes(StandardCharsets.UTF_8);
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

    /** Convenience: the bound address of the wrapped server (for fuse checks). */
    public InetSocketAddress boundAddress() {
        return server.getAddress();
    }
}
