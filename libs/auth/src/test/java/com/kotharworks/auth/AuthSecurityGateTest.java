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
package com.kotharworks.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.kotharworks.auth.dev.DevLocalJwtIssuer;
import com.kotharworks.auth.dev.DevPersona;
import com.kotharworks.auth.dev.LoopbackStartupFuse;
import com.kotharworks.auth.http.AccessPolicy;
import com.kotharworks.auth.http.AuthenticatedRouter;
import com.kotharworks.auth.http.CookieCodec;
import com.kotharworks.auth.http.CsrfGuard;
import com.kotharworks.auth.http.RequestAuthenticator;
import com.kotharworks.auth.http.SessionCookieAuthenticator;
import com.kotharworks.auth.jwt.JwtClaimsVerifier;
import com.kotharworks.auth.jwt.JwtHumanAuthenticator;
import com.kotharworks.auth.jwt.KeycloakRoleClaimExtractor;
import com.kotharworks.auth.oidc.OidcAuthorizationCodeClient;
import com.kotharworks.auth.oidc.OidcConfig;
import com.kotharworks.auth.oidc.OidcJwksAuthenticator;
import com.kotharworks.auth.principal.ExternalIdentity;
import com.kotharworks.auth.principal.GlobalRole;
import com.kotharworks.auth.principal.Principal;
import com.kotharworks.auth.principal.TokenId;
import com.kotharworks.auth.principal.UserId;
import com.kotharworks.auth.spi.IdentityProvisioner;
import com.kotharworks.auth.spi.ScopedTokenLookup;
import com.kotharworks.auth.spi.ScopedTokenRecord;
import com.kotharworks.auth.spi.SessionRecord;
import com.kotharworks.auth.spi.SessionStore;
import com.kotharworks.auth.token.ApiTokenCodec;
import com.kotharworks.auth.token.ScopedApiTokenAuthenticator;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Library-level negative security gate for {@code libs/auth}. Covers all nine
 * acceptance criteria of Ticket 06: route audit & default 401, non-loopback
 * dev-local fuse, JWT tamper/alg-none/HS256-confusion rejection, deterministic
 * clock expiry, wrong iss/aud, OIDC PKCE S256 + state/nonce, cookie CSRF
 * rejection, and the {@code iw_bot_} token codec with revocation/suspension.
 */
class AuthSecurityGateTest {

    private static final String ISSUER = "https://dev.kotharworks.local";
    private static final String AUDIENCE = "kotharworks-server";
    private static final Instant BASE_TIME = Instant.parse("2026-01-15T12:00:00Z");
    private static final Pattern IW_BOT_PATTERN =
            Pattern.compile("^iw_bot_[A-Za-z0-9_-]{8}_[A-Za-z0-9_-]{43}$");

    private final MutableClock clock = new MutableClock(BASE_TIME);
    private final InMemoryIdentityProvisioner provisioner = new InMemoryIdentityProvisioner();
    private final InMemorySessionStore sessions = new InMemorySessionStore();
    private final InMemoryScopedTokenLookup tokenLookup = new InMemoryScopedTokenLookup();
    private final CookieCodec cookies = CookieCodec.devLoopback();

    private HttpServer server;
    private AuthenticatedRouter router;
    private DevLocalJwtIssuer devIssuer;

    @AfterEach
    void stopServer() {
        if (router != null) {
            router.stop(0);
            router = null;
            server = null;
        }
    }

    // ------------------------------------------------------------------ rig

    /** Starts a real loopback server with the standard route table. */
    private Rig rig() throws IOException {
        stopServer();
        AtomicBoolean adminInvoked = new AtomicBoolean(false);
        AtomicBoolean metaInvoked = new AtomicBoolean(false);

        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        devIssuer = new DevLocalJwtIssuer(ISSUER, AUDIENCE, clock, provisioner, sessions, cookies);

        var apiTokens = new ScopedApiTokenAuthenticator(tokenLookup);
        var humans = new JwtHumanAuthenticator(devIssuer.verifier(), "kotharworks-server", provisioner);
        var cookieAuth = new SessionCookieAuthenticator(cookies, sessions, clock);
        var authenticator = RequestAuthenticator.bearerThenCookie(apiTokens, humans, cookieAuth);
        var csrfGuard = new CsrfGuard("http://127.0.0.1", AuthMode.DEV_LOCAL, () -> server.getAddress().getPort());

        router = new AuthenticatedRouter(server, authenticator, csrfGuard);
        LoopbackStartupFuse.verify(AuthMode.DEV_LOCAL, server.getAddress());

        router.register("GET", "/chronicle", AccessPolicy.PUBLIC,
                ctx -> ctx.respondJson(200, "{\"chronicle\":\"open\"}"));
        router.register("GET", "/api/v1/meta", AccessPolicy.AUTHENTICATED_ANY, ctx -> {
            metaInvoked.set(true);
            ctx.respondJson(200, "{\"principal\":\"" + principalKind(ctx.principal()) + "\"}");
        });
        router.register("GET", "/api/v1/houses/{houseId}", AccessPolicy.AUTHENTICATED_ANY,
                ctx -> ctx.respondJson(200, "{\"houseId\":\"" + ctx.pathParams().get("houseId") + "\"}"));
        router.register("GET", "/play", AccessPolicy.HUMAN_ONLY,
                ctx -> ctx.respondText(200, "text/html; charset=utf-8", "<html>play</html>"));
        router.register("POST", "/api/v1/admin/pause", AccessPolicy.ROLE_ADMIN, ctx -> {
            adminInvoked.set(true);
            ctx.respondJson(200, "{\"paused\":true}");
        });
        router.register("GET", "/dev/login", AccessPolicy.PUBLIC, devIssuer.loginPageHandler());
        router.register("POST", "/dev/login", AccessPolicy.PUBLIC, devIssuer.loginSubmitHandler());
        router.start();

        return new Rig(server.getAddress().getPort(), adminInvoked, metaInvoked);
    }

    private static String principalKind(Principal principal) {
        return switch (principal) {
            case Principal.AnonymousPrincipal _ -> "ANONYMOUS";
            case Principal.ScopedTokenPrincipal _ -> "SCOPED_TOKEN";
            case Principal.HumanPrincipal _ -> "HUMAN";
        };
    }

    private record Rig(int port, AtomicBoolean adminInvoked, AtomicBoolean metaInvoked) {

        String baseUrl() {
            return "http://127.0.0.1:" + port;
        }
    }

    /** Fires one request at the rig and returns status line + body + response headers. */
    private record Reply(int status, String body, Map<String, List<String>> headers) {

        boolean bodyContains(String needle) {
            return body.contains(needle);
        }
    }

    private Reply request(int port, String method, String path, Map<String, String> headers, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        headers.forEach(builder::header);
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<byte[]> response =
                neverFollowRedirects().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        String responseBody = new String(response.body(), StandardCharsets.UTF_8);
        return new Reply(response.statusCode(), responseBody, response.headers().map());
    }

    private HttpClient neverFollowRedirects() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    // --------------------------------------------------- criterion 0 (happy path)

    @Test
    void devLocalLoginEstablishesCookieSessionThroughProductionVerifier() throws Exception {
        Rig rig = rig();

        Reply login = request(rig.port(), "POST", "/dev/login",
                Map.of("Content-Type", "application/x-www-form-urlencoded"), "persona=dev-admin");
        assertEquals(303, login.status(), login.body());
        assertEquals("/play", login.headers().get("Location").get(0));
        List<String> setCookie = login.headers().get("Set-Cookie");
        assertTrue(setCookie.get(0).startsWith(CookieCodec.DEV_SESSION_COOKIE + "="),
                "cookie name: " + setCookie.get(0));
        assertTrue(setCookie.get(0).contains("HttpOnly") && setCookie.get(0).contains("SameSite=Lax"),
                setCookie.get(0));

        String cookieHeader = setCookie.get(0).split(";")[0];
        Reply meta = request(rig.port(), "GET", "/api/v1/meta", Map.of("Cookie", cookieHeader), null);
        assertEquals(200, meta.status(), meta.body());
        assertTrue(meta.bodyContains("\"principal\":\"HUMAN\""), meta.body());
        assertTrue(rig.metaInvoked().get());

        Reply play = request(rig.port(), "GET", "/play", Map.of("Cookie", cookieHeader), null);
        assertEquals(200, play.status(), play.body());
    }

    @Test
    void keycloakRoleClaimExtraction_unionsRealmAndClientRoles_grantsPlayerBaseline_ignoresHousekeeping() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("u1").audience(AUDIENCE)
                .claim("realm_access", Map.of("roles", List.of("offline_access", "uma_authorization", "RESEARCHER")))
                .claim("resource_access", Map.of("kotharworks-server",
                        Map.of("roles", List.of("ADMIN", "uma_authorization"))))
                .build();
        Set<GlobalRole> roles = KeycloakRoleClaimExtractor.extract(claims, "kotharworks-server");
        assertEquals(Set.of(GlobalRole.PLAYER, GlobalRole.RESEARCHER, GlobalRole.ADMIN), roles);

        // No claims at all: still at least PLAYER.
        JWTClaimsSet bare = new JWTClaimsSet.Builder().issuer(ISSUER).subject("u2").audience(AUDIENCE).build();
        assertEquals(Set.of(GlobalRole.PLAYER), KeycloakRoleClaimExtractor.extract(bare, "kotharworks-server"));

        // Other clients' roles do not leak.
        JWTClaimsSet otherClient = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("u3").audience(AUDIENCE)
                .claim("resource_access", Map.of("another-client", Map.of("roles", List.of("ADMIN"))))
                .build();
        assertEquals(Set.of(GlobalRole.PLAYER),
                KeycloakRoleClaimExtractor.extract(otherClient, "kotharworks-server"));
    }

    // ------------------------------------------- criterion 1 (audit & default 401)

    @Test
    void routeAudit_registeredRoutesDeclarePolicies_andNonPublicRoutesDefaultTo401() throws Exception {
        Rig rig = rig();

        List<AuthenticatedRouter.RegisteredRoute> routes = router.registeredRoutes();
        assertEquals(7, routes.size(), "expected route table: " + routes);
        routes.forEach(route -> assertTrue(route.policy() != null, "unannotated route: " + route));
        assertTrue(routes.contains(new AuthenticatedRouter.RegisteredRoute("POST", "/dev/login",
                AccessPolicy.PUBLIC)));

        // PUBLIC route answers anonymous callers.
        assertEquals(200, request(rig.port(), "GET", "/chronicle", Map.of(), null).status());

        // Every non-PUBLIC route rejects unauthenticated callers with 401.
        assertEquals(401, request(rig.port(), "GET", "/api/v1/meta", Map.of(), null).status());
        assertEquals(401, request(rig.port(), "GET", "/api/v1/houses/42", Map.of(), null).status());
        assertEquals(401, request(rig.port(), "GET", "/play", Map.of(), null).status());
        Reply pause = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Content-Type", "application/json"), "{}");
        assertEquals(401, pause.status());
        assertTrue(pause.bodyContains("AUTHENTICATION_REQUIRED"), pause.body());
        assertFalse(rig.adminInvoked().get());

        // Unknown path → 404; known path, wrong method → 405 with Allow header.
        Reply notFound = request(rig.port(), "GET", "/nope", Map.of(), null);
        assertEquals(404, notFound.status());
        assertTrue(notFound.bodyContains("NOT_FOUND"), notFound.body());
        Reply methodNotAllowed = request(rig.port(), "POST", "/api/v1/meta",
                Map.of("Content-Type", "application/json"), "{}");
        assertEquals(405, methodNotAllowed.status());
        assertTrue(methodNotAllowed.bodyContains("METHOD_NOT_ALLOWED"), methodNotAllowed.body());
        assertEquals(List.of("GET"), methodNotAllowed.headers().get("Allow"));
    }

    // ------------------------------------------------- criterion 2 (loopback fuse)

    @Test
    void loopbackFuse_refusesWildcardAndLanBinds_allowsLoopback() {
        assertThrows(IllegalStateException.class,
                () -> LoopbackStartupFuse.verify(AuthMode.DEV_LOCAL, new InetSocketAddress("0.0.0.0", 8080)));
        assertThrows(IllegalStateException.class,
                () -> LoopbackStartupFuse.verify(AuthMode.DEV_LOCAL, new InetSocketAddress("::", 8080)));
        assertThrows(IllegalStateException.class,
                () -> LoopbackStartupFuse.verify(AuthMode.DEV_LOCAL, new InetSocketAddress("192.168.1.50", 8080)));
        assertDoesNotThrow(() ->
                LoopbackStartupFuse.verify(AuthMode.DEV_LOCAL, new InetSocketAddress("127.0.0.1", 8080)));
        assertDoesNotThrow(() ->
                LoopbackStartupFuse.verify(AuthMode.DEV_LOCAL, new InetSocketAddress("::1", 8080)));
        // OIDC binds are not inspected.
        assertDoesNotThrow(() ->
                LoopbackStartupFuse.verify(AuthMode.OIDC, new InetSocketAddress("0.0.0.0", 8080)));
    }

    // ------------------------------------------------------ criterion 3 (tamper)

    @Test
    void tamperedJwtPayload_editedClaims_keptSignature_rejectsWithInvalidToken() throws Exception {
        Rig rig = rig();
        String token = devIssuer.mintSignedJwt(DevPersona.ALICE);

        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(payload.contains("[\"PLAYER\"]"), payload);
        String editedPayload = payload.replace("[\"PLAYER\"]", "[\"ADMIN\"]");
        assertNotEquals(payload, editedPayload, "tamper must actually change the payload");
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(editedPayload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        Reply reply = request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer " + tampered), null);
        assertEquals(401, reply.status());
        assertTrue(reply.bodyContains("INVALID_TOKEN"), reply.body());
        assertFalse(rig.metaInvoked().get());
    }

    // ------------------------------------- criterion 4 (alg:none & HS256 confusion)

    @Test
    void algNoneJwt_isHardRejected() throws Exception {
        Rig rig = rig();
        String payload = devIssuer.mintSignedJwt(DevPersona.ALICE).split("\\.")[1];
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String unsigned = header + "." + payload + ".";

        Reply reply = request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer " + unsigned), null);
        assertEquals(401, reply.status());
        assertTrue(reply.bodyContains("INVALID_TOKEN"), reply.body());
    }

    @Test
    void hs256SignedWithPublicKeyBytes_keyConfusion_isHardRejected() throws Exception {
        Rig rig = rig();
        ECKey publicEcKey = devIssuer.publicKey();
        byte[] confusionKey = publicEcKey.getX().decode(); // 32 bytes of public EC point X
        assertEquals(32, confusionKey.length);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("dev-alice").audience(AUDIENCE)
                .issueTime(Date.from(clock.instant()))
                .expirationTime(Date.from(clock.instant().plus(Duration.ofMinutes(5))))
                .build();
        SignedJWT attack = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        attack.sign(new MACSigner(confusionKey));

        Reply reply = request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer " + attack.serialize()), null);
        assertEquals(401, reply.status());
        assertTrue(reply.bodyContains("INVALID_TOKEN"), reply.body());
        assertFalse(rig.metaInvoked().get());
    }

    // --------------------------------------- criterion 5 (deterministic expiry)

    @Test
    void deterministicClockExpiry_advancingPastExp_rejectsWithTokenExpired() throws Exception {
        Rig rig = rig();
        String token = devIssuer.mintSignedJwt(DevPersona.ALICE); // exp = T0 + 5 min

        // Token is valid right now.
        assertEquals(200, request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer " + token), null).status());

        clock.advance(Duration.ofMinutes(6)); // 0ms sleep — pure clock manipulation
        Reply expired = request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer " + token), null);
        assertEquals(401, expired.status());
        assertTrue(expired.bodyContains("TOKEN_EXPIRED"), expired.body());
    }

    // ------------------------------------- criterion 6 (wrong issuer / audience)

    @Test
    void validlySignedJwts_withWrongIssuerOrAudience_rejectWithInvalidToken() throws Exception {
        Rig rig = rig();
        String wrongIssuer = devIssuer.mintSignedJwt(DevPersona.ALICE, "https://evil-issuer", AUDIENCE,
                Duration.ofMinutes(5));
        Reply issuerReply = request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer " + wrongIssuer), null);
        assertEquals(401, issuerReply.status());
        assertTrue(issuerReply.bodyContains("INVALID_TOKEN"), issuerReply.body());

        String wrongAudience = devIssuer.mintSignedJwt(DevPersona.ALICE, ISSUER, "other-audience",
                Duration.ofMinutes(5));
        Reply audienceReply = request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer " + wrongAudience), null);
        assertEquals(401, audienceReply.status());
        assertTrue(audienceReply.bodyContains("INVALID_TOKEN"), audienceReply.body());
        assertFalse(rig.metaInvoked().get());
    }

    // --------------------------------------------- criterion 7 (OIDC PKCE S256)

    @Test
    void oidcPkceRoundTrip_verifiesChallengeVerifierStateAndNonce() throws Exception {
        Rig rig = rig(); // ensure the shared clock drives every verifier
        try (MockOidcServer mock = new MockOidcServer(clock)) {
            OidcConfig config = mock.config("https://mock-idc.example", "kotharworks-server", "dev-secret");
            var client = new OidcAuthorizationCodeClient();
            var authenticator = new OidcJwksAuthenticator(config, clock);

            var pending = client.begin(config);
            URI authorizationUri = pending.request().authorizationUri();
            Map<String, String> authorizeParams = queryParams(authorizationUri);
            assertEquals("S256", authorizeParams.get("code_challenge_method"));
            assertEquals(pending.request().state(), authorizeParams.get("state"));
            assertEquals(pending.request().nonce(), authorizeParams.get("nonce"));
            assertEquals(43, pending.request().codeVerifier().length());
            // Independent S256 computation: BASE64URL(SHA256(verifier)).
            byte[] expectedDigest = MessageDigest.getInstance("SHA-256")
                    .digest(pending.request().codeVerifier().getBytes(StandardCharsets.US_ASCII));
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(expectedDigest),
                    authorizeParams.get("code_challenge"));
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(expectedDigest),
                    pending.request().codeChallenge());

            // State validation: mismatch rejects, exact value passes.
            var reject = assertThrows(AuthRejectException.class,
                    () -> client.validateCallbackState(pending, "wrong-state"));
            assertEquals(AuthErrorCode.INVALID_TOKEN, reject.code());
            assertDoesNotThrow(() -> client.validateCallbackState(pending, pending.request().state()));

            // Token exchange: PKCE verifier travels to the token endpoint.
            mock.nextNonce(pending.request().nonce());
            var tokens = client.exchangeAuthorizationCode(config, pending, "test-code-123");
            assertEquals("authorization_code", mock.capturedForm().get("grant_type"));
            assertEquals("test-code-123", mock.capturedForm().get("code"));
            assertEquals(pending.request().codeVerifier(), mock.capturedForm().get("code_verifier"));
            assertEquals("kotharworks-server", mock.capturedForm().get("client_id"));
            assertEquals("dev-secret", mock.capturedForm().get("client_secret"));

            // id_token validation against JWKS served by the mock + nonce check.
            JWTClaimsSet idClaims = client.validateIdToken(authenticator, tokens.idToken(), pending);
            assertEquals("oidc-user", idClaims.getSubject());
            assertTrue(mock.jwksFetches().get() >= 1,
                    "verifier keys must come from the mock /jwks endpoint");

            // A second id_token minted with a different nonce must be rejected.
            mock.nextNonce("attacker-chosen-nonce");
            var tokens2 = client.exchangeAuthorizationCode(config, pending, "test-code-123");
            var nonceReject = assertThrows(AuthRejectException.class,
                    () -> client.validateIdToken(authenticator, tokens2.idToken(), pending));
            assertEquals(AuthErrorCode.INVALID_TOKEN, nonceReject.code());
        }
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        String query = uri.getQuery();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return params;
    }

    // ------------------------------------------------ criterion 8 (cookie CSRF)

    @Test
    void cookieCsrfGuard_rejectsCrossSiteAndForeignOrigins_bearerRequestsBypass() throws Exception {
        Rig rig = rig();
        var created = sessions.create(new UserId(UUID.randomUUID()),
                new ExternalIdentity(ISSUER, "dev-admin"), "Admin (dev)",
                Set.of(GlobalRole.PLAYER, GlobalRole.RESEARCHER, GlobalRole.ADMIN),
                clock.instant().plus(Duration.ofHours(1)));
        String cookie = cookies.sessionCookieName() + "=" + created.rawSessionToken();

        // (a) Foreign Origin → 403 CSRF_REJECTED before the handler runs.
        Reply evilOrigin = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Origin", "https://evil.example",
                        "Content-Type", "application/json"),
                "{}");
        assertEquals(403, evilOrigin.status());
        assertTrue(evilOrigin.bodyContains("CSRF_REJECTED"), evilOrigin.body());
        assertFalse(rig.adminInvoked().get());

        // (b) Sec-Fetch-Site: cross-site → 403.
        Reply crossSite = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Sec-Fetch-Site", "cross-site",
                        "Content-Type", "application/json"),
                "{}");
        assertEquals(403, crossSite.status());
        assertTrue(crossSite.bodyContains("CSRF_REJECTED"), crossSite.body());

        // (c) Authorization: Bearer bypasses cookie CSRF even with a stale cookie.
        String adminJwt = devIssuer.mintSignedJwt(DevPersona.ADMIN);
        Reply bearer = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Authorization", "Bearer " + adminJwt, "Cookie", cookie,
                        "Origin", "https://evil.example", "Content-Type", "application/json"),
                "{}");
        assertEquals(200, bearer.status(), bearer.body());
        assertTrue(rig.adminInvoked().get());

        // (d) Same-bound-origin JSON request passes.
        rig.adminInvoked().set(false);
        Reply sameOrigin = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Origin", rig.baseUrl(),
                        "Content-Type", "application/json"),
                "{}");
        assertEquals(200, sameOrigin.status(), sameOrigin.body());
        assertTrue(rig.adminInvoked().get());

        // (e) Form post without CSRF token → 403.
        rig.adminInvoked().set(false);
        Reply formNoToken = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Origin", rig.baseUrl(),
                        "Content-Type", "application/x-www-form-urlencoded"),
                "paused=true");
        assertEquals(403, formNoToken.status());
        assertTrue(formNoToken.bodyContains("CSRF_REJECTED"), formNoToken.body());
        assertFalse(rig.adminInvoked().get());

        // (f) Form post with matching csrf_token field passes.
        Reply formWithToken = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Origin", rig.baseUrl(),
                        "Content-Type", "application/x-www-form-urlencoded"),
                "paused=true&csrf_token=" + created.record().csrfToken());
        assertEquals(200, formWithToken.status(), formWithToken.body());
        assertTrue(rig.adminInvoked().get());

        // (g) Form post with a wrong CSRF token → 403.
        Reply formWrongToken = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Origin", rig.baseUrl(),
                        "Content-Type", "application/x-www-form-urlencoded"),
                "paused=true&csrf_token=nope");
        assertEquals(403, formWrongToken.status());
        assertTrue(formWrongToken.bodyContains("CSRF_REJECTED"), formWrongToken.body());
    }

    // --------------------------- criterion 9 (iw_bot_ codec, revocation, suspension)

    @Test
    void apiTokenCodec_andScopedTokenRevocationSuspension() throws Exception {
        Rig rig = rig();
        var codec = new ApiTokenCodec(new SecureRandom());

        var generated = codec.generate();
        assertTrue(IW_BOT_PATTERN.matcher(generated.token()).matches(), generated.token());
        assertTrue(ApiTokenCodec.matchesDigest(generated.token(), generated.sha256()));
        assertFalse(ApiTokenCodec.matchesDigest(generated.token(), ApiTokenCodec.sha256("iw_bot_aaaaaaaa_"
                + "wrongwrongwrongwrongwrongwrongwrongwrongwr")));

        UserId owner = new UserId(UUID.randomUUID());
        tokenLookup.register(generated.sha256(), new ScopedTokenRecord(
                new TokenId(1), owner, "place", 42, false, false));

        Reply active = request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer " + generated.token()), null);
        assertEquals(200, active.status(), active.body());
        assertTrue(active.bodyContains("\"principal\":\"SCOPED_TOKEN\""), active.body());

        // Mutated secret byte → digest misses → unknown token → 401.
        String mutated = generated.token().substring(0, generated.token().length() - 2) + "zz";
        Reply mutatedReply = request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer " + mutated), null);
        assertEquals(401, mutatedReply.status());
        assertTrue(mutatedReply.bodyContains("INVALID_TOKEN"), mutatedReply.body());

        // Revoked token → 401.
        var revokedToken = codec.generate();
        tokenLookup.register(revokedToken.sha256(), new ScopedTokenRecord(
                new TokenId(2), owner, "place", 42, true, false));
        Reply revoked = request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer " + revokedToken.token()), null);
        assertEquals(401, revoked.status());
        assertTrue(revoked.bodyContains("INVALID_TOKEN"), revoked.body());

        // Suspended owner → 403 ACCOUNT_SUSPENDED.
        var suspendedToken = codec.generate();
        tokenLookup.register(suspendedToken.sha256(), new ScopedTokenRecord(
                new TokenId(3), owner, "place", 42, false, true));
        Reply suspended = request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer " + suspendedToken.token()), null);
        assertEquals(403, suspended.status());
        assertTrue(suspended.bodyContains("ACCOUNT_SUSPENDED"), suspended.body());
    }

    // ---------------------------------------------------------- test fixtures

    /** Mutable logical clock: {@link #advance} moves it without sleeping. */
    static final class MutableClock extends Clock {

        private final Instant base;
        private volatile Duration offset = Duration.ZERO;

        MutableClock(Instant base) {
            this.base = base;
        }

        void advance(Duration delta) {
            offset = offset.plus(delta);
        }

        @Override
        public Instant instant() {
            return base.plus(offset);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException("test clock is UTC-only");
        }
    }

    /** Idempotent JIT provisioner: one stable UserId per (iss, sub). */
    static final class InMemoryIdentityProvisioner implements IdentityProvisioner {

        private final Map<ExternalIdentity, UserId> users = new ConcurrentHashMap<>();

        @Override
        public UserId provision(ExternalIdentity externalId, String displayName) {
            return users.computeIfAbsent(externalId, id -> new UserId(UUID.randomUUID()));
        }
    }

    /** In-memory session store keyed by Base64 of the SHA-256 digest. */
    static final class InMemorySessionStore implements SessionStore {

        private final Map<String, SessionRecord> byDigest = new ConcurrentHashMap<>();

        @Override
        public CreatedSession create(UserId userId, ExternalIdentity externalId, String displayName,
                Set<GlobalRole> grantedRoles, Instant expiresAt) {
            String rawToken = CookieCodec.newRandomToken();
            String csrfToken = CookieCodec.newRandomToken();
            SessionRecord record = new SessionRecord(CookieCodec.sha256Digest(rawToken), userId, externalId,
                    displayName, grantedRoles, csrfToken, expiresAt);
            byDigest.put(Base64.getEncoder().encodeToString(record.sessionSha256()), record);
            return new CreatedSession(rawToken, record);
        }

        @Override
        public Optional<SessionRecord> findByDigest(byte[] sha256Digest) {
            return Optional.ofNullable(byDigest.get(Base64.getEncoder().encodeToString(sha256Digest)));
        }

        @Override
        public void revokeByDigest(byte[] sha256Digest) {
            byDigest.remove(Base64.getEncoder().encodeToString(sha256Digest));
        }
    }

    /** In-memory scoped-token lookup for the {@code iw_bot_} authenticator. */
    static final class InMemoryScopedTokenLookup implements ScopedTokenLookup {

        private final Map<String, ScopedTokenRecord> byDigest = new ConcurrentHashMap<>();

        void register(byte[] sha256Digest, ScopedTokenRecord record) {
            byDigest.put(Base64.getEncoder().encodeToString(sha256Digest), record);
        }

        @Override
        public Optional<ScopedTokenRecord> findByDigest(byte[] sha256Digest) {
            return Optional.ofNullable(byDigest.get(Base64.getEncoder().encodeToString(sha256Digest)));
        }
    }

    /**
     * Loopback mock OIDC provider: {@code /jwks} serves a test EC key's public
     * JWK set; {@code /token} captures the exchange form and mints an ES256
     * {@code id_token} with the nonce preset via {@link #nextNonce}.
     */
    static final class MockOidcServer implements AutoCloseable {

        private final HttpServer httpServer;
        private final ECKey signingKey;
        private final MutableClock clock;
        private final Map<String, String> capturedForm = new ConcurrentHashMap<>();
        private final AtomicInteger jwksFetches = new AtomicInteger();
        private volatile String nonceForNextIdToken = "";

        MockOidcServer(MutableClock clock) throws IOException, com.nimbusds.jose.JOSEException {
            this.clock = clock;
            this.signingKey = new ECKeyGenerator(Curve.P_256)
                    .keyID("mock-oidc-es256")
                    .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
                    .generate();
            this.httpServer = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            this.httpServer.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            this.httpServer.createContext("/jwks", exchange -> {
                jwksFetches.incrementAndGet();
                byte[] body = new JWKSet(List.of(signingKey.toPublicJWK())).toString()
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            this.httpServer.createContext("/token", this::handleToken);
            this.httpServer.start();
        }

        private void handleToken(HttpExchange exchange) throws IOException {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            for (String pair : form.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    capturedForm.put(pair.substring(0, eq),
                            java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            String nonce = capturedForm.getOrDefault("nonce", nonceForNextIdToken);
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("https://mock-idc.example").subject("oidc-user")
                    .audience("kotharworks-server")
                    .issueTime(Date.from(clock.instant()))
                    .expirationTime(Date.from(clock.instant().plus(Duration.ofMinutes(5))))
                    .claim("nonce", nonce)
                    .build();
            SignedJWT idToken = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(signingKey.getKeyID()).build(), claims);
            try {
                idToken.sign(new ECDSASigner(signingKey));
            } catch (com.nimbusds.jose.JOSEException e) {
                throw new IllegalStateException(e);
            }
            String body = "{\"access_token\":\"mock-at\",\"token_type\":\"Bearer\",\"expires_in\":300,"
                    + "\"id_token\":\"" + idToken.serialize() + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        OidcConfig config(String issuerUrl, String clientId, String clientSecret) {
            int port = httpServer.getAddress().getPort();
            return new OidcConfig(issuerUrl, clientId, clientSecret, "http://127.0.0.1",
                    URI.create("http://127.0.0.1:" + port + "/authorize"),
                    URI.create("http://127.0.0.1:" + port + "/token"),
                    URI.create("http://127.0.0.1:" + port + "/jwks"));
        }

        /** Presets the nonce claim of the next minted id_token. */
        void nextNonce(String nonce) {
            this.nonceForNextIdToken = nonce;
        }

        Map<String, String> capturedForm() {
            return Map.copyOf(capturedForm);
        }

        AtomicInteger jwksFetches() {
            return jwksFetches;
        }

        @Override
        public void close() {
            httpServer.stop(0);
        }
    }
}
