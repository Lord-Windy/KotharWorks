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
package com.kotharworks.systest.support;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One HTTP identity against the live server: anonymous, a cookie-session human
 * (logged in through the real {@code POST /dev/login} persona switcher), or a
 * bot holding an {@code iw_bot_} bearer token. Redirects are never followed, so
 * tests can assert on {@code 303}s exactly.
 *
 * <p>Every call returns a {@link Resp}; nothing throws on non-2xx, so negative
 * tests read naturally: {@code assertEquals(403, api.mintToken(id).status())}.
 */
public final class Api {

    /** Session cookie name the server uses on loopback HTTP origins. */
    public static final String DEV_SESSION_COOKIE = "kw_dev_session";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final String baseUrl;
    private final CookieManager cookies;
    private final HttpClient client;
    private final String bearer;
    private final String label;

    private Api(String label, String bearer) {
        this.baseUrl = SysEnv.baseUrl();
        this.cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.bearer = bearer;
        this.label = label;
    }

    /** An unauthenticated client. */
    public static Api anonymous() {
        return new Api("anonymous", null);
    }

    /** A bot client authenticating with a scoped {@code iw_bot_} token. */
    public static Api bot(String token) {
        return new Api("bot", token);
    }

    /** Logs {@code persona} in through the dev-local persona switcher form. */
    public static Api login(Persona persona) {
        Api api = new Api(persona.name().toLowerCase(java.util.Locale.ROOT), null);
        Resp resp = api.postForm("/dev/login", Map.of("persona", persona.subject()));
        if (resp.status() != 303 || api.sessionCookie().isEmpty()) {
            throw new AssertionError("dev-local login failed for " + persona + ": " + resp);
        }
        return api;
    }

    /** A human client resuming an existing raw session cookie value. */
    public static Api resumeSession(String rawSessionCookie) {
        Api api = new Api("resumed", null);
        HttpCookie cookie = new HttpCookie(DEV_SESSION_COOKIE, rawSessionCookie);
        cookie.setPath("/");
        cookie.setVersion(0);
        api.cookies.getCookieStore().add(URI.create(api.baseUrl), cookie);
        return api;
    }

    /** The raw BFF session cookie, if this client holds one. */
    public Optional<String> sessionCookie() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals(DEV_SESSION_COOKIE))
                .map(HttpCookie::getValue)
                .findFirst();
    }

    // ------------------------------------------------------------------ raw verbs

    public Resp get(String path, String... headers) {
        return send(request(path, headers).GET());
    }

    /**
     * DELETE with a JSON content type: cookie-authenticated mutations must be JSON
     * (or carry a CSRF token) to pass the server's CSRF guard, exactly like the UI.
     */
    public Resp delete(String path) {
        return send(request(path).header("Content-Type", "application/json").DELETE());
    }

    public Resp postJson(String path, Object body, String... headers) {
        String json = body instanceof String s ? s : Json.write(body);
        return send(request(path, headers)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    public Resp postForm(String path, Map<String, String> form, String... headers) {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (!encoded.isEmpty()) {
                encoded.append('&');
            }
            encoded.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return send(request(path, headers)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encoded.toString())));
    }

    /** Builds a request with this identity's credentials; for streaming callers. */
    public HttpRequest.Builder request(String path, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT);
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return builder;
    }

    public HttpClient client() {
        return client;
    }

    /**
     * Sends a request. Reads ({@code GET}) that hit {@code 429 RATE_LIMITED} are
     * retried with backoff like a well-behaved client: the per-seat budget counts
     * reads too, and polling must never starve a scenario. Mutations are never
     * retried here, so tests can observe their 429s directly.
     */
    private Resp send(HttpRequest.Builder builder) {
        HttpRequest request = builder.build();
        Resp resp = sendOnce(request);
        for (int attempt = 0; attempt < 40 && resp.status() == 429
                && request.method().equals("GET"); attempt++) {
            Await.sleep(Duration.ofMillis(250));
            resp = sendOnce(request);
        }
        return resp;
    }

    private Resp sendOnce(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return new Resp(request.method() + " " + request.uri().getRawPath()
                    + (request.uri().getRawQuery() == null ? "" : "?" + request.uri().getRawQuery()),
                    response.statusCode(), response.headers().map(), response.body());
        } catch (IOException e) {
            throw new AssertionError(label + ": " + request.method() + " " + request.uri()
                    + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    // ------------------------------------------------------------------ game API

    public Resp meta() {
        return get("/api/v1/meta");
    }

    public Resp me() {
        return get("/api/v1/auth/me");
    }

    public Resp charterSeat(String seatName, String controllerKind) {
        return postJson("/api/v1/seats", Json.obj("seatName", seatName,
                "controllerKind", controllerKind));
    }

    public Resp mintToken(long seatId) {
        return postJson("/api/v1/seats/" + seatId + "/tokens", Json.obj("label", "systest"));
    }

    public Resp revokeToken(long seatId, long tokenId) {
        return delete("/api/v1/seats/" + seatId + "/tokens/" + tokenId);
    }

    public Resp observation() {
        return get("/api/v1/observation");
    }

    public Resp observation(long seatId) {
        return get("/api/v1/observation?seatId=" + seatId);
    }

    public Resp events(long since) {
        return get("/api/v1/events?since=" + since + "&limit=500");
    }

    public Resp events(long seatId, long since) {
        return get("/api/v1/events?seatId=" + seatId + "&since=" + since + "&limit=500");
    }

    public Resp publicEvents(long since) {
        return get("/api/v1/public/events?since=" + since + "&limit=500");
    }

    /** Submits a command envelope (a fresh {@code cmdId} is added when absent). */
    public Resp command(Map<String, Object> envelope) {
        return command(null, envelope);
    }

    /** Submits a command for an explicit seat ({@code ?seatId=}) when {@code seatId != null}. */
    public Resp command(Long seatId, Map<String, Object> envelope) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(envelope);
        body.putIfAbsent("cmdId", UUID.randomUUID().toString());
        return postJson("/api/v1/commands" + (seatId == null ? "" : "?seatId=" + seatId), body);
    }

    // ------------------------------------------------------------------ command builders

    public static Map<String, Object> travel(String actorId, long revision, String destination) {
        return Json.obj("type", "enqueue", "targetActor", actorId, "expectedRevision", revision,
                "tasks", List.of(Json.obj("type", "travel", "destination", destination)));
    }

    public static Map<String, Object> waitTask(String actorId, long revision, long durationMs,
            int batches) {
        return Json.obj("type", "enqueue", "targetActor", actorId, "expectedRevision", revision,
                "tasks", List.of(Json.obj("type", "wait", "durationMs", durationMs,
                        "batches", batches)));
    }

    public static Map<String, Object> cancel(String actorId, long revision, boolean cancelActive) {
        return Json.obj("type", "cancel_queue", "targetActor", actorId,
                "expectedRevision", revision, "cancelActive", cancelActive);
    }

    public static Map<String, Object> failPolicy(String actorId, long revision, String policy) {
        return Json.obj("type", "set_fail_policy", "targetActor", actorId,
                "expectedRevision", revision, "policy", policy);
    }

    /** An HTTP response captured for assertions. */
    public record Resp(String request, int status, Map<String, List<String>> headers, String body) {

        public Json.Node json() {
            return Json.parse(body);
        }

        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().getFirst();
                }
            }
            return null;
        }

        /** The {@code errorCode} of an error envelope, or {@code null}. */
        public String errorCode() {
            try {
                return json().get("errorCode").text();
            } catch (RuntimeException notJson) {
                return null;
            }
        }

        /** Asserts the status and returns {@code this} for chaining. */
        public Resp expect(int expected) {
            if (status != expected) {
                throw new AssertionError("expected HTTP " + expected + " but got " + this);
            }
            return this;
        }

        /** Asserts a 2xx status and returns the parsed body. */
        public Json.Node ok() {
            if (status < 200 || status > 299) {
                throw new AssertionError("expected 2xx but got " + this);
            }
            return json();
        }

        @Override
        public String toString() {
            List<String> parts = new ArrayList<>();
            parts.add(request + " -> " + status);
            if (body != null && !body.isEmpty()) {
                parts.add(body.length() > 600 ? body.substring(0, 600) + "..." : body);
            }
            return String.join(" ", parts);
        }
    }
}
