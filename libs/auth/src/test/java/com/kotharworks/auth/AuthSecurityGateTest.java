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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Library-level negative security gate for {@code libs/auth}. Covers all nine
 * acceptance criteria of Ticket 06 plus Pass 06 hardening checks: route audit
 * &amp; default 401, full {@link AccessPolicy} matrix, non-loopback/unresolved
 * dev-local fuse, JWT tamper/alg-none/HS256-confusion/wrong-curve/EdDSA/RS256,
 * multi-audience &amp; omitted {@code preferred_username}, deterministic clock
 * expiry &amp; skew, wrong iss/aud, OIDC PKCE S256 + single-use state/nonce +
 * TTL + HTTP timeout, cookie CSRF (cross-site, same-site, null Origin, forged
 * forms, plain-Map headers, malformed percent escapes), {@code __Host-} cookie
 * codec, router path-traversal/encoded-slash/body-size caps, and {@code iw_bot_}
 * token revocation/suspension.
 */
@Timeout(30)
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
        return rig(AuthenticatedRouter.DEFAULT_MAX_BODY_BYTES);
    }

    private Rig rig(int maxBodyBytes) throws IOException {
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

        router = new AuthenticatedRouter(server, authenticator, csrfGuard, maxBodyBytes);
        LoopbackStartupFuse.verify(AuthMode.DEV_LOCAL, server.getAddress());

        router.register("GET", "/chronicle", AccessPolicy.PUBLIC,
                ctx -> ctx.respondJson(200, "{\"chronicle\":\"open\"}"));
        router.register("GET", "/api/v1/meta", AccessPolicy.AUTHENTICATED_ANY, ctx -> {
            metaInvoked.set(true);
            ctx.respondJson(200, "{\"principal\":\"" + principalKind(ctx.principal()) + "\"}");
        });
        router.register("GET", "/api/v1/houses/{houseId}", AccessPolicy.AUTHENTICATED_ANY,
                ctx -> ctx.respondJson(200, "{\"houseId\":\"" + ctx.pathParams().get("houseId") + "\"}"));
        router.register("GET", "/api/v1/houses/special", AccessPolicy.AUTHENTICATED_ANY,
                ctx -> ctx.respondJson(200, "{\"special\":true}"));
        router.register("GET", "/api/v1/research/lab", AccessPolicy.ROLE_RESEARCHER,
                ctx -> ctx.respondJson(200, "{\"lab\":true}"));
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

    private Reply request(int port, String method, String rawPath, Map<String, String> headers, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath));
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
    void devLocalLoginEstablishesCookieSession_escapesHtml_handlesBadInput_andExpiresWithClock() throws Exception {
        Rig rig = rig();

        // GET /dev/login renders personas in deterministic Alice -> Sam -> Admin order.
        Reply page = request(rig.port(), "GET", "/dev/login?xss=%3Cscript%3Ealert(1)%3C/script%3E", Map.of(), null);
        assertEquals(200, page.status());
        assertFalse(page.bodyContains("<script>"), page.body());
        int aliceIdx = page.body().indexOf("dev-alice");
        int samIdx = page.body().indexOf("dev-sam");
        int adminIdx = page.body().indexOf("dev-admin");
        assertTrue(aliceIdx > 0 && samIdx > aliceIdx && adminIdx > samIdx, page.body());

        // Unknown persona and malformed percent-encoding return 400 (never 500 or reflected XSS).
        Reply unknown = request(rig.port(), "POST", "/dev/login",
                Map.of("Content-Type", "application/x-www-form-urlencoded"),
                "persona=%3Cscript%3Eevil%3C/script%3E");
        assertEquals(400, unknown.status());
        assertTrue(unknown.bodyContains("\"errorCode\":\"UNKNOWN_PERSONA\""), unknown.body());
        assertFalse(unknown.bodyContains("<script>"), unknown.body());

        Reply badUrlEncoding = request(rig.port(), "POST", "/dev/login",
                Map.of("Content-Type", "application/x-www-form-urlencoded"), "persona=%ZZ");
        assertEquals(400, badUrlEncoding.status(), badUrlEncoding.body());

        Reply login = request(rig.port(), "POST", "/dev/login",
                Map.of("Content-Type", "application/x-www-form-urlencoded"), "persona=dev-admin");
        assertEquals(303, login.status(), login.body());
        assertEquals("/play", login.headers().get("Location").getFirst());
        List<String> setCookie = login.headers().get("Set-Cookie");
        assertTrue(setCookie.getFirst().startsWith(CookieCodec.DEV_SESSION_COOKIE + "="),
                "cookie name: " + setCookie.getFirst());
        assertTrue(setCookie.getFirst().contains("HttpOnly") && setCookie.getFirst().contains("SameSite=Lax"),
                setCookie.getFirst());

        String cookieHeader = setCookie.getFirst().split(";")[0];
        Reply meta = request(rig.port(), "GET", "/api/v1/meta", Map.of("Cookie", cookieHeader), null);
        assertEquals(200, meta.status(), meta.body());
        assertTrue(meta.bodyContains("\"principal\":\"HUMAN\""), meta.body());
        assertTrue(rig.metaInvoked().get());

        Reply play = request(rig.port(), "GET", "/play", Map.of("Cookie", cookieHeader), null);
        assertEquals(200, play.status(), play.body());

        // Advancing clock past the 12h session TTL expires the cookie session cleanly to anonymous (401).
        clock.advance(Duration.ofHours(12));
        Reply expiredSession = request(rig.port(), "GET", "/play", Map.of("Cookie", cookieHeader), null);
        assertEquals(401, expiredSession.status(), expiredSession.body());
    }

    @Test
    void keycloakRoleClaimExtraction_unionsRealmAndClientRoles_grantsPlayerBaseline_ignoresHousekeeping() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("u1").audience(AUDIENCE)
                .claim("realm_access", Map.of("roles",
                        List.of("offline_access", "uma_authorization", "default-roles-kotharworks", "RESEARCHER")))
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
        assertEquals(9, routes.size(), "expected route table: " + routes);
        routes.forEach(route -> assertTrue(route.policy() != null, "unannotated route: " + route));
        assertTrue(routes.contains(new AuthenticatedRouter.RegisteredRoute("POST", "/dev/login",
                AccessPolicy.PUBLIC)));

        // PUBLIC route answers anonymous callers.
        assertEquals(200, request(rig.port(), "GET", "/chronicle", Map.of(), null).status());

        // Every non-PUBLIC route rejects unauthenticated callers with 401.
        assertEquals(401, request(rig.port(), "GET", "/api/v1/meta", Map.of(), null).status());
        assertEquals(401, request(rig.port(), "GET", "/api/v1/houses/42", Map.of(), null).status());
        assertEquals(401, request(rig.port(), "GET", "/api/v1/houses/special", Map.of(), null).status());
        assertEquals(401, request(rig.port(), "GET", "/api/v1/research/lab", Map.of(), null).status());
        assertEquals(401, request(rig.port(), "GET", "/play", Map.of(), null).status());
        Reply pause = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Content-Type", "application/json"), "{}");
        assertEquals(401, pause.status());
        assertTrue(pause.bodyContains("\"errorCode\":\"AUTHENTICATION_REQUIRED\""), pause.body());
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

    @Test
    void accessPolicyMatrix_enforcesHumanOnlyResearcherAndAdminBoundaries() throws Exception {
        Rig rig = rig();

        var botToken = new ApiTokenCodec(new SecureRandom()).generate();
        tokenLookup.register(botToken.sha256(), new ScopedTokenRecord(
                new TokenId(10), new UserId(UUID.randomUUID()), "wilds:seat", 7L, false, false));
        Map<String, String> botAuth = Map.of("Authorization", "Bearer " + botToken.token());

        // ScopedTokenPrincipal: allowed on PUBLIC & AUTHENTICATED_ANY; 403 FORBIDDEN on HUMAN_ONLY / ROLE_*.
        assertEquals(200, request(rig.port(), "GET", "/chronicle", botAuth, null).status());
        assertEquals(200, request(rig.port(), "GET", "/api/v1/meta", botAuth, null).status());
        Reply botPlay = request(rig.port(), "GET", "/play", botAuth, null);
        assertEquals(403, botPlay.status());
        assertTrue(botPlay.bodyContains("FORBIDDEN"), botPlay.body());
        Reply botLab = request(rig.port(), "GET", "/api/v1/research/lab", botAuth, null);
        assertEquals(403, botLab.status());
        assertTrue(botLab.bodyContains("FORBIDDEN"), botLab.body());
        Reply botAdmin = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Authorization", "Bearer " + botToken.token(), "Content-Type", "application/json"), "{}");
        assertEquals(403, botAdmin.status());
        assertTrue(botAdmin.bodyContains("FORBIDDEN"), botAdmin.body());

        // PLAYER human: allowed on HUMAN_ONLY; 403 ROLE_REQUIRED on ROLE_RESEARCHER & ROLE_ADMIN.
        String aliceJwt = devIssuer.mintSignedJwt(DevPersona.ALICE);
        assertEquals(200, request(rig.port(), "GET", "/play",
                Map.of("Authorization", "Bearer " + aliceJwt), null).status());
        Reply aliceLab = request(rig.port(), "GET", "/api/v1/research/lab",
                Map.of("Authorization", "Bearer " + aliceJwt), null);
        assertEquals(403, aliceLab.status());
        assertTrue(aliceLab.bodyContains("ROLE_REQUIRED"), aliceLab.body());
        Reply aliceAdmin = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Authorization", "Bearer " + aliceJwt, "Content-Type", "application/json"), "{}");
        assertEquals(403, aliceAdmin.status());
        assertTrue(aliceAdmin.bodyContains("ROLE_REQUIRED"), aliceAdmin.body());

        // RESEARCHER human: allowed on ROLE_RESEARCHER; 403 ROLE_REQUIRED on ROLE_ADMIN.
        String samJwt = devIssuer.mintSignedJwt(DevPersona.SAM);
        assertEquals(200, request(rig.port(), "GET", "/api/v1/research/lab",
                Map.of("Authorization", "Bearer " + samJwt), null).status());
        assertEquals(403, request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Authorization", "Bearer " + samJwt, "Content-Type", "application/json"), "{}").status());

        // ADMIN human (and lowercase RFC 9110 "bearer " scheme): allowed on ROLE_RESEARCHER & ROLE_ADMIN.
        String adminJwt = devIssuer.mintSignedJwt(DevPersona.ADMIN);
        assertEquals(200, request(rig.port(), "GET", "/api/v1/research/lab",
                Map.of("Authorization", "bearer " + adminJwt), null).status());
        assertEquals(200, request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Authorization", "bearer " + adminJwt, "Content-Type", "application/json"), "{}").status());
    }

    // ------------------------------------------------- criterion 2 (loopback fuse)

    @Test
    void loopbackFuse_refusesWildcardLanUnresolvedAndNullBinds_allowsLoopback() {
        assertThrows(IllegalStateException.class,
                () -> LoopbackStartupFuse.verify(AuthMode.DEV_LOCAL, null));
        assertThrows(IllegalStateException.class,
                () -> LoopbackStartupFuse.verify(AuthMode.DEV_LOCAL, new InetSocketAddress(8080)));
        assertThrows(IllegalStateException.class,
                () -> LoopbackStartupFuse.verify(AuthMode.DEV_LOCAL, new InetSocketAddress("0.0.0.0", 8080)));
        assertThrows(IllegalStateException.class,
                () -> LoopbackStartupFuse.verify(AuthMode.DEV_LOCAL, new InetSocketAddress("::", 8080)));
        assertThrows(IllegalStateException.class,
                () -> LoopbackStartupFuse.verify(AuthMode.DEV_LOCAL, new InetSocketAddress("192.168.1.50", 8080)));
        assertThrows(IllegalStateException.class,
                () -> LoopbackStartupFuse.verify(AuthMode.DEV_LOCAL,
                        InetSocketAddress.createUnresolved("127.0.0.1", 8080)));
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

    // ------------------- criterion 4 (alg:none, HS256 confusion, RS256, wrong curve, EdDSA)

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
    void hs256SignedWithPublicKeyBytes_wrongCurve_edDsa_andRs256Verification() throws Exception {
        Rig rig = rig();
        ECKey publicEcKey = devIssuer.publicKey();
        byte[] confusionKey = publicEcKey.getX().decode(); // 32 bytes of public EC point X
        assertEquals(32, confusionKey.length);

        JWTClaimsSet validClaims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("dev-alice").audience(AUDIENCE)
                .issueTime(Date.from(clock.instant()))
                .expirationTime(Date.from(clock.instant().plus(Duration.ofMinutes(5))))
                .build();
        SignedJWT hs256Attack = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), validClaims);
        hs256Attack.sign(new MACSigner(confusionKey));

        Reply reply = request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer " + hs256Attack.serialize()), null);
        assertEquals(401, reply.status());
        assertTrue(reply.bodyContains("INVALID_TOKEN"), reply.body());
        assertFalse(rig.metaInvoked().get());

        // EdDSA header is hard-rejected (outside allowed RS256/ES256 set).
        String edDsaHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"EdDSA\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String fakeEdDsa = edDsaHeader + "." + hs256Attack.serialize().split("\\.")[1] + ".c2ln";
        assertEquals(401, request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer " + fakeEdDsa), null).status());

        // ES384 / P-384 curve is rejected even if present in JWKS.
        ECKey p384 = new ECKeyGenerator(Curve.P_384).keyID("p384").keyUse(KeyUse.SIGNATURE).generate();
        RSAKey rsaKey = new RSAKeyGenerator(2048).keyID("rsa-main").keyUse(KeyUse.SIGNATURE).generate();
        JwtClaimsVerifier rsaAndEcVerifier = new JwtClaimsVerifier(ISSUER, AUDIENCE,
                new ImmutableJWKSet<SecurityContext>(new JWKSet(List.of(p384.toPublicJWK(), rsaKey.toPublicJWK()))),
                clock);

        SignedJWT es384Jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES384).keyID("p384").build(), validClaims);
        es384Jwt.sign(new ECDSASigner(p384));
        var es384Reject = assertThrows(AuthRejectException.class,
                () -> rsaAndEcVerifier.verify(es384Jwt.serialize()));
        assertEquals(AuthErrorCode.INVALID_TOKEN, es384Reject.code());

        // Valid RS256 signature verifies cleanly; wrong RSA key rejects.
        SignedJWT rs256Jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rsa-main").type(JOSEObjectType.JWT).build(),
                validClaims);
        rs256Jwt.sign(new RSASSASigner(rsaKey));
        assertEquals("dev-alice", rsaAndEcVerifier.verify(rs256Jwt.serialize()).getSubject());

        RSAKey otherRsa = new RSAKeyGenerator(2048).keyID("rsa-main").keyUse(KeyUse.SIGNATURE).generate();
        SignedJWT forgedRs256 = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rsa-main").build(), validClaims);
        forgedRs256.sign(new RSASSASigner(otherRsa));
        assertThrows(AuthRejectException.class, () -> rsaAndEcVerifier.verify(forgedRs256.serialize()));
    }

    @Test
    void jwtClaims_acceptMultiAudienceAndMissingPreferredUsername_rejectMissingOrBlankClaimsAndFutureNbf()
            throws Exception {
        Rig rig = rig();
        ECKey ecKey = new ECKeyGenerator(Curve.P_256).keyID("k1").keyUse(KeyUse.SIGNATURE).generate();
        JwtClaimsVerifier verifier = new JwtClaimsVerifier(ISSUER, AUDIENCE,
                new ImmutableJWKSet<SecurityContext>(new JWKSet(List.of(ecKey.toPublicJWK()))), clock);
        JwtHumanAuthenticator humanAuth = new JwtHumanAuthenticator(verifier, AUDIENCE, provisioner);

        // (a) Multi-audience Keycloak JWT ["account", AUDIENCE] + omitted preferred_username succeeds!
        JWTClaimsSet multiAudClaims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("kc-user-42")
                .audience(List.of("account", AUDIENCE))
                .issueTime(Date.from(clock.instant()))
                .expirationTime(Date.from(clock.instant().plus(Duration.ofMinutes(5))))
                .build();
        String multiAudToken = signEs256(ecKey, multiAudClaims);
        Principal.HumanPrincipal principal = humanAuth.authenticate(multiAudToken);
        assertEquals("kc-user-42", principal.displayName(), "falls back to sub when preferred_username is absent");
        assertEquals(Set.of(GlobalRole.PLAYER), principal.roles());

        // (b) Missing sub, blank sub, and missing exp all reject with INVALID_TOKEN.
        JWTClaimsSet noSub = new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE)
                .expirationTime(Date.from(clock.instant().plus(Duration.ofMinutes(5))))
                .build();
        assertEquals(AuthErrorCode.INVALID_TOKEN,
                assertThrows(AuthRejectException.class, () -> verifier.verify(signEs256(ecKey, noSub))).code());

        JWTClaimsSet blankSub = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("   ").audience(AUDIENCE)
                .expirationTime(Date.from(clock.instant().plus(Duration.ofMinutes(5))))
                .build();
        assertEquals(AuthErrorCode.INVALID_TOKEN,
                assertThrows(AuthRejectException.class, () -> verifier.verify(signEs256(ecKey, blankSub))).code());

        JWTClaimsSet noExp = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("u1").audience(AUDIENCE)
                .build();
        assertEquals(AuthErrorCode.INVALID_TOKEN,
                assertThrows(AuthRejectException.class, () -> verifier.verify(signEs256(ecKey, noExp))).code());

        // (c) nbf within 30s clock skew passes; nbf 60s in the future rejects with INVALID_TOKEN.
        JWTClaimsSet skewOk = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("u1").audience(AUDIENCE)
                .notBeforeTime(Date.from(clock.instant().plusSeconds(25)))
                .expirationTime(Date.from(clock.instant().plus(Duration.ofMinutes(5))))
                .build();
        assertDoesNotThrow(() -> verifier.verify(signEs256(ecKey, skewOk)));

        JWTClaimsSet futureNbf = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("u1").audience(AUDIENCE)
                .notBeforeTime(Date.from(clock.instant().plusSeconds(60)))
                .expirationTime(Date.from(clock.instant().plus(Duration.ofMinutes(5))))
                .build();
        assertEquals(AuthErrorCode.INVALID_TOKEN,
                assertThrows(AuthRejectException.class, () -> verifier.verify(signEs256(ecKey, futureNbf))).code());

        assertFalse(rig.metaInvoked().get());
    }

    private static String signEs256(ECKey key, JWTClaimsSet claims) throws com.nimbusds.jose.JOSEException {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
                claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
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
    void oidcPkceRoundTrip_verifiesChallengeStateNonceSingleUseExpiryAndTimeout() throws Exception {
        rig(); // ensure the shared clock drives every verifier
        // Non-HTTP(S) OIDC URIs (e.g. file://) are rejected at config construction.
        assertThrows(IllegalArgumentException.class, () -> new OidcConfig(
                "https://mock-idc.example", "kotharworks-server", "secret", "http://127.0.0.1",
                URI.create("https://mock-idc.example/authorize"),
                URI.create("https://mock-idc.example/token"),
                URI.create("file:///etc/passwd")));

        try (MockOidcServer mock = new MockOidcServer(clock)) {
            OidcConfig config = mock.config("https://mock-idc.example", "kotharworks-server", "dev-secret",
                    "http://127.0.0.1/");
            var client = new OidcAuthorizationCodeClient(clock);
            var authenticator = new OidcJwksAuthenticator(config, clock);

            var pending = client.begin(config);
            URI authorizationUri = pending.request().authorizationUri();
            Map<String, String> authorizeParams = queryParams(authorizationUri);
            assertEquals("S256", authorizeParams.get("code_challenge_method"));
            assertEquals("http://127.0.0.1/auth/callback", authorizeParams.get("redirect_uri"),
                    "trailing slash on publicOrigin must not double-slash /auth/callback");
            assertTrue(authorizationUri.getRawQuery().contains(
                    "redirect_uri=http%3A%2F%2F127.0.0.1%2Fauth%2Fcallback"));
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

            // State validation: mismatch rejects without burning pending; exact value passes once only.
            var reject = assertThrows(AuthRejectException.class,
                    () -> client.validateCallbackState(pending, "wrong-state"));
            assertEquals(AuthErrorCode.INVALID_TOKEN, reject.code());
            assertDoesNotThrow(() -> client.validateCallbackState(pending, pending.request().state()));
            var replayStateReject = assertThrows(AuthRejectException.class,
                    () -> client.validateCallbackState(pending, pending.request().state()));
            assertEquals(AuthErrorCode.INVALID_TOKEN, replayStateReject.code());

            // Fresh login completed end-to-end via completeAuthorizationCode.
            var pendingLogin = client.begin(config);
            mock.nextNonce(pendingLogin.request().nonce());
            var completed = client.completeAuthorizationCode(config, authenticator,
                    pendingLogin.request().state(), "test-code-123");
            assertEquals("authorization_code", mock.capturedForm().get("grant_type"));
            assertEquals("test-code-123", mock.capturedForm().get("code"));
            assertEquals(pendingLogin.request().codeVerifier(), mock.capturedForm().get("code_verifier"));
            assertEquals("kotharworks-server", mock.capturedForm().get("client_id"));
            assertEquals("dev-secret", mock.capturedForm().get("client_secret"));
            assertEquals("oidc-user", completed.idTokenClaims().getSubject());
            assertTrue(mock.jwksFetches().get() >= 1,
                    "verifier keys must come from the mock /jwks endpoint");

            // Replaying the same callback state a second time is rejected (single-use).
            var replayCallback = assertThrows(AuthRejectException.class,
                    () -> client.completeAuthorizationCode(config, authenticator,
                            pendingLogin.request().state(), "test-code-123"));
            assertEquals(AuthErrorCode.INVALID_TOKEN, replayCallback.code());

            // Wrong nonce in id_token is rejected.
            var pendingBadNonce = client.begin(config);
            mock.nextNonce("attacker-chosen-nonce");
            var nonceReject = assertThrows(AuthRejectException.class,
                    () -> client.completeAuthorizationCode(config, authenticator,
                            pendingBadNonce.request().state(), "test-code-456"));
            assertEquals(AuthErrorCode.INVALID_TOKEN, nonceReject.code());

            // PendingLogin TTL expiry on injected clock (0ms sleep): +11 minutes expires state.
            var pendingExpiring = client.begin(config);
            clock.advance(Duration.ofMinutes(11));
            var expiredState = assertThrows(AuthRejectException.class,
                    () -> client.completeAuthorizationCode(config, authenticator,
                            pendingExpiring.request().state(), "test-code-789"));
            assertEquals(AuthErrorCode.INVALID_TOKEN, expiredState.code());

            // Stalled token endpoint times out cleanly with INVALID_TOKEN instead of hanging.
            var shortTimeoutClient = new OidcAuthorizationCodeClient(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                    new SecureRandom(), clock, Duration.ofMillis(150), Duration.ofMinutes(5));
            var pendingTimeout = shortTimeoutClient.begin(config);
            mock.stallTokenResponses(true);
            var timeoutReject = assertThrows(AuthRejectException.class,
                    () -> shortTimeoutClient.completeAuthorizationCode(config, authenticator,
                            pendingTimeout.request().state(), "stalled-code"));
            assertEquals(AuthErrorCode.INVALID_TOKEN, timeoutReject.code());
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
    void cookieCsrfGuard_rejectsCrossSiteSameSiteNullOriginAndForgedForms_bearerBypasses() throws Exception {
        Rig rig = rig();
        var created = sessions.create(new UserId(UUID.randomUUID()),
                new ExternalIdentity(ISSUER, "dev-admin"), "Admin (dev)",
                Set.of(GlobalRole.PLAYER, GlobalRole.RESEARCHER, GlobalRole.ADMIN),
                clock.instant().plus(Duration.ofHours(1)));
        String cookie = cookies.sessionCookieName() + "=" + created.rawSessionToken();

        // (a) Foreign Origin and sandboxed "Origin: null" → 403 CSRF_REJECTED before the handler runs.
        Reply evilOrigin = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Origin", "https://evil.example",
                        "Content-Type", "application/json"),
                "{}");
        assertEquals(403, evilOrigin.status());
        assertTrue(evilOrigin.bodyContains("CSRF_REJECTED"), evilOrigin.body());
        assertFalse(rig.adminInvoked().get());

        Reply nullOrigin = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Origin", "null", "Content-Type", "application/json"), "{}");
        assertEquals(403, nullOrigin.status());
        assertTrue(nullOrigin.bodyContains("CSRF_REJECTED"), nullOrigin.body());

        // (b) Sec-Fetch-Site: cross-site AND Sec-Fetch-Site: same-site → 403.
        Reply crossSite = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Sec-Fetch-Site", "cross-site",
                        "Content-Type", "application/json"),
                "{}");
        assertEquals(403, crossSite.status());
        assertTrue(crossSite.bodyContains("CSRF_REJECTED"), crossSite.body());

        Reply sameSite = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Sec-Fetch-Site", "same-site",
                        "Content-Type", "application/json"),
                "{}");
        assertEquals(403, sameSite.status());
        assertTrue(sameSite.bodyContains("CSRF_REJECTED"), sameSite.body());

        // (c) Direct plain-Map check with mixed-case keys ("Sec-fetch-site" / "ORIGIN") rejects properly.
        CsrfGuard directGuard = new CsrfGuard("https://wilds.example", AuthMode.OIDC, () -> 443);
        Map<String, List<String>> mixedCaseHeaders = new HashMap<>();
        mixedCaseHeaders.put("Sec-fetch-site", List.of("cross-site"));
        mixedCaseHeaders.put("Content-type", List.of("application/json"));
        assertThrows(AuthRejectException.class,
                () -> directGuard.check("POST", mixedCaseHeaders, new byte[0], "tok"));
        Map<String, List<String>> upperOrigin = Map.of(
                "ORIGIN", List.of("https://evil.example"),
                "CONTENT-TYPE", List.of("application/json"));
        assertThrows(AuthRejectException.class,
                () -> directGuard.check("POST", upperOrigin, new byte[0], "tok"));

        // (d) Forged form POSTs with BOTH Origin and Sec-Fetch-Site omitted → 403.
        for (String formContentType : List.of(
                "application/x-www-form-urlencoded",
                "text/plain",
                "multipart/form-data; boundary=---xyz",
                "application/json-evil")) {
            Reply forgedForm = request(rig.port(), "POST", "/api/v1/admin/pause",
                    Map.of("Cookie", cookie, "Content-Type", formContentType),
                    "paused=true");
            assertEquals(403, forgedForm.status(), "must reject Content-Type: " + formContentType);
            assertTrue(forgedForm.bodyContains("CSRF_REJECTED"), forgedForm.body());
        }
        assertFalse(rig.adminInvoked().get());

        // (e) Malformed percent-encoding in form body returns 403 CSRF_REJECTED (never 500).
        for (String malformedBody : List.of(
                "csrf_token=%ZZ",
                "bad=%ZZ&csrf_token=" + created.record().csrfToken())) {
            Reply malformedForm = request(rig.port(), "POST", "/api/v1/admin/pause",
                    Map.of("Cookie", cookie, "Origin", rig.baseUrl(),
                            "Content-Type", "application/x-www-form-urlencoded"),
                    malformedBody);
            assertEquals(403, malformedForm.status(), malformedForm.body());
            assertTrue(malformedForm.bodyContains("CSRF_REJECTED"), malformedForm.body());
        }

        // (f) Authorization: Bearer bypasses cookie CSRF even with a stale cookie and evil Origin.
        String adminJwt = devIssuer.mintSignedJwt(DevPersona.ADMIN);
        Reply bearer = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Authorization", "Bearer " + adminJwt, "Cookie", cookie,
                        "Origin", "https://evil.example", "Content-Type", "application/json"),
                "{}");
        assertEquals(200, bearer.status(), bearer.body());
        assertTrue(rig.adminInvoked().get());

        // (g) Same-bound-origin JSON request with Sec-Fetch-Site: same-origin passes.
        rig.adminInvoked().set(false);
        Reply sameOrigin = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Origin", rig.baseUrl(),
                        "Sec-Fetch-Site", "same-origin",
                        "Content-Type", "application/json; charset=utf-8"),
                "{}");
        assertEquals(200, sameOrigin.status(), sameOrigin.body());
        assertTrue(rig.adminInvoked().get());

        // (h) X-CSRF-Token header: wrong value → 403, matching value → 200.
        rig.adminInvoked().set(false);
        assertEquals(403, request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Origin", rig.baseUrl(), "X-CSRF-Token", "wrong"), "").status());
        assertEquals(200, request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Origin", rig.baseUrl(),
                        "X-CSRF-Token", created.record().csrfToken()), "").status());
        assertTrue(rig.adminInvoked().get());

        // (i) Form post: missing token → 403, matching csrf_token → 200, wrong csrf_token → 403.
        rig.adminInvoked().set(false);
        Reply formNoToken = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Origin", rig.baseUrl(),
                        "Content-Type", "application/x-www-form-urlencoded"),
                "paused=true");
        assertEquals(403, formNoToken.status());
        assertFalse(rig.adminInvoked().get());

        Reply formWithToken = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Origin", rig.baseUrl(),
                        "Content-Type", "application/x-www-form-urlencoded"),
                "paused=true&csrf_token=" + created.record().csrfToken());
        assertEquals(200, formWithToken.status(), formWithToken.body());
        assertTrue(rig.adminInvoked().get());

        Reply formWrongToken = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Cookie", cookie, "Origin", rig.baseUrl(),
                        "Content-Type", "application/x-www-form-urlencoded"),
                "paused=true&csrf_token=nope");
        assertEquals(403, formWrongToken.status());
        assertTrue(formWrongToken.bodyContains("CSRF_REJECTED"), formWrongToken.body());
    }

    @Test
    void cookieCodec_productionHostPrefix_clearHeader_andMultiHeaderExtraction() throws Exception {
        Rig rig = rig();

        CookieCodec prod = CookieCodec.forOrigin("https://wilds.kotharworks.com", AuthMode.OIDC);
        assertEquals(CookieCodec.HOST_SESSION_COOKIE, prod.sessionCookieName());
        String setHeader = prod.setCookieHeaderValue("tok123");
        assertEquals("__Host-kw_session=tok123; Path=/; HttpOnly; SameSite=Lax; Secure", setHeader);
        assertFalse(setHeader.toLowerCase().contains("domain="));
        assertEquals("__Host-kw_session=; Path=/; HttpOnly; SameSite=Lax; Secure; Max-Age=0",
                prod.clearCookieHeaderValue());

        CookieCodec dev = CookieCodec.forOrigin("http://127.0.0.1:8080", AuthMode.DEV_LOCAL);
        assertEquals(CookieCodec.DEV_SESSION_COOKIE, dev.sessionCookieName());
        assertEquals("kw_dev_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0", dev.clearCookieHeaderValue());

        // Multi-line HTTP/2 Cookie headers: session cookie in second Cookie header still authenticates.
        var created = sessions.create(new UserId(UUID.randomUUID()),
                new ExternalIdentity(ISSUER, "dev-alice"), "Alice (dev)",
                Set.of(GlobalRole.PLAYER), clock.instant().plus(Duration.ofHours(1)));
        HttpRequest multiCookieReq = HttpRequest.newBuilder(URI.create(rig.baseUrl() + "/play"))
                .header("Cookie", "theme=dark")
                .header("Cookie", cookies.sessionCookieName() + "=" + created.rawSessionToken())
                .GET()
                .build();
        HttpResponse<String> response = neverFollowRedirects()
                .send(multiCookieReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());

        // SessionRecord defensively clones byte[] digest on construction and access.
        byte[] digest = created.record().sessionSha256();
        digest[0] ^= (byte) 0xFF;
        assertNotEquals(digest[0], created.record().sessionSha256()[0]);
    }

    @Test
    void routerHardening_rejectsPathTraversalEncodedSlashes_prioritizesLiterals_andCapsBodySize() throws Exception {
        Rig rig = rig(1024); // 1 KiB body cap for deterministic 413 testing
        String aliceJwt = devIssuer.mintSignedJwt(DevPersona.ALICE);
        String adminJwt = devIssuer.mintSignedJwt(DevPersona.ADMIN);
        Map<String, String> authHeader = Map.of("Authorization", "Bearer " + aliceJwt);

        // Path traversal, dot-segments, encoded slashes, double slashes, and trailing slashes → 404.
        for (String maliciousPath : List.of(
                "/api/v1/../chronicle",
                "/api/v1/%2e%2e/chronicle",
                "/api/v1/houses/..",
                "/api/v1/houses/.",
                "/api/v1/houses/%2e%2e",
                "/api/v1/houses/42%2F99",
                "/api/v1/houses/42%5C99",
                "/api/v1/houses/42%00",
                "//chronicle",
                "/chronicle/")) {
            Reply r = request(rig.port(), "GET", maliciousPath, authHeader, null);
            assertEquals(404, r.status(), "must reject path: " + maliciousPath);
        }

        // Literal segment (/api/v1/houses/special, registered AFTER {houseId}) beats {houseId} wildcard;
        // '+' in a path segment remains literal '+' (RFC 3986, not form-decoded space).
        Reply special = request(rig.port(), "GET", "/api/v1/houses/special", authHeader, null);
        assertEquals(200, special.status());
        assertTrue(special.bodyContains("\"special\":true"), special.body());

        Reply plusId = request(rig.port(), "GET", "/api/v1/houses/a+b", authHeader, null);
        assertEquals(200, plusId.status());
        assertTrue(plusId.bodyContains("\"houseId\":\"a+b\""), plusId.body());

        // Oversized body (> 1024 bytes) on authenticated route returns 413 PAYLOAD_TOO_LARGE.
        String oversizedJson = "{\"pad\":\"" + "x".repeat(2048) + "\"}";
        Reply tooLarge = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Authorization", "Bearer " + adminJwt, "Content-Type", "application/json"),
                oversizedJson);
        assertEquals(413, tooLarge.status(), tooLarge.body());
        assertTrue(tooLarge.bodyContains("PAYLOAD_TOO_LARGE"), tooLarge.body());
        assertFalse(rig.adminInvoked().get());

        // Unauthenticated caller sending an oversized body to a protected route still gets 401 before body read.
        Reply unauthOversized = request(rig.port(), "POST", "/api/v1/admin/pause",
                Map.of("Content-Type", "application/json"), oversizedJson);
        assertEquals(401, unauthOversized.status(), unauthOversized.body());
    }

    // --------------------------- criterion 9 (iw_bot_ codec, revocation, suspension)

    @Test
    void apiTokenCodec_andScopedTokenRevocationSuspension() throws Exception {
        Rig rig = rig();
        var codec = new ApiTokenCodec(new SecureRandom());

        var generated = codec.generate();
        assertEquals(ApiTokenCodec.expectedTokenLength(), generated.token().length());
        assertTrue(IW_BOT_PATTERN.matcher(generated.token()).matches(), generated.token());
        assertTrue(ApiTokenCodec.matchesDigest(generated.token(), generated.sha256()));
        assertFalse(ApiTokenCodec.matchesDigest(generated.token(), ApiTokenCodec.sha256("iw_bot_aaaaaaaa_"
                + "wrongwrongwrongwrongwrongwrongwrongwrongwr")));

        // GeneratedToken defensively clones its digest byte[].
        byte[] copy = generated.sha256();
        copy[0] ^= (byte) 0xFF;
        assertTrue(ApiTokenCodec.matchesDigest(generated.token(), generated.sha256()));

        UserId owner = new UserId(UUID.randomUUID());
        tokenLookup.register(generated.sha256(), new ScopedTokenRecord(
                new TokenId(1), owner, "place", 42, false, false));

        // Malformed-length iw_bot_ token rejects immediately without querying the store.
        int lookupsBefore = tokenLookup.lookups().get();
        Reply wrongLength = request(rig.port(), "GET", "/api/v1/meta",
                Map.of("Authorization", "Bearer iw_bot_too_short"), null);
        assertEquals(401, wrongLength.status());
        assertEquals(lookupsBefore, tokenLookup.lookups().get(),
                "malformed-length iw_bot_ token must not hit ScopedTokenLookup");

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
            return users.computeIfAbsent(externalId, _ -> new UserId(UUID.randomUUID()));
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
        private final AtomicInteger lookups = new AtomicInteger();

        void register(byte[] sha256Digest, ScopedTokenRecord record) {
            byDigest.put(Base64.getEncoder().encodeToString(sha256Digest), record);
        }

        AtomicInteger lookups() {
            return lookups;
        }

        @Override
        public Optional<ScopedTokenRecord> findByDigest(byte[] sha256Digest) {
            lookups.incrementAndGet();
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
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private volatile String nonceForNextIdToken = "";
        private volatile boolean stallToken = false;

        MockOidcServer(MutableClock clock) throws IOException, com.nimbusds.jose.JOSEException {
            this.clock = clock;
            this.signingKey = new ECKeyGenerator(Curve.P_256)
                    .keyID("mock-oidc-es256")
                    .keyUse(KeyUse.SIGNATURE)
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
            if (stallToken) {
                try {
                    closedLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedForm.clear();
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
                    .audience(List.of("kotharworks-server", "account"))
                    .claim("azp", "kotharworks-server")
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

        OidcConfig config(String issuerUrl, String clientId, String clientSecret, String publicOrigin) {
            int port = httpServer.getAddress().getPort();
            return new OidcConfig(issuerUrl, clientId, clientSecret, publicOrigin,
                    URI.create("http://127.0.0.1:" + port + "/authorize"),
                    URI.create("http://127.0.0.1:" + port + "/token"),
                    URI.create("http://127.0.0.1:" + port + "/jwks"));
        }

        void nextNonce(String nonce) {
            this.nonceForNextIdToken = nonce;
        }

        void stallTokenResponses(boolean stall) {
            this.stallToken = stall;
        }

        Map<String, String> capturedForm() {
            return Map.copyOf(capturedForm);
        }

        AtomicInteger jwksFetches() {
            return jwksFetches;
        }

        @Override
        public void close() {
            closedLatch.countDown();
            httpServer.stop(0);
        }
    }
}
