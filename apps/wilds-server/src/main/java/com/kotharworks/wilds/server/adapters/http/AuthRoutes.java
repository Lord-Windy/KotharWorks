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
package com.kotharworks.wilds.server.adapters.http;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.kotharworks.auth.AuthMode;
import com.kotharworks.auth.dev.DevLocalJwtIssuer;
import com.kotharworks.auth.http.AccessPolicy;
import com.kotharworks.auth.http.AuthenticatedRouter;
import com.kotharworks.auth.http.CookieCodec;
import com.kotharworks.auth.http.AuthenticatedRouter.RouteContext;
import com.kotharworks.auth.jwt.KeycloakRoleClaimExtractor;
import com.kotharworks.auth.oidc.OidcAuthorizationCodeClient;
import com.kotharworks.auth.oidc.OidcConfig;
import com.kotharworks.auth.oidc.OidcJwksAuthenticator;
import com.kotharworks.auth.principal.Principal;
import com.kotharworks.auth.spi.SessionStore;
import com.kotharworks.wilds.observe.WorldView;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.server.ServerConfig;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.ports.AuthStorePort;
import com.kotharworks.wilds.server.ports.SeatRecord;
import com.kotharworks.wilds.server.ports.UserRecord;
import com.kotharworks.wilds.server.ports.WorldStorePort;

/**
 * Auth routes: {@code /auth/login}, {@code /auth/callback}, the dev-local
 * persona switcher ({@code /dev/login}, only in {@code DEV_LOCAL}), logout,
 * {@code /api/v1/auth/me}, and the admin suspension endpoint. Single-use OIDC
 * PKCE login state and TTL are enforced inside {@link OidcAuthorizationCodeClient}.
 */
public final class AuthRoutes {

    private static final Duration SESSION_TTL = Duration.ofHours(12);

    private final ServerConfig config;
    private final AuthStorePort authStore;
    private final WorldStorePort worldStore;
    private final WorldHost worldHost;
    private final CookieCodec cookies;
    private final Clock clock;
    private final DevLocalJwtIssuer devIssuer;
    private final OidcAuthorizationCodeClient oidcClient;
    private final OidcConfig oidcConfig;
    private final OidcJwksAuthenticator oidcAuthenticator;

    public AuthRoutes(ServerConfig config, AuthStorePort authStore, WorldStorePort worldStore,
            WorldHost worldHost, CookieCodec cookies, Clock clock, DevLocalJwtIssuer devIssuer,
            OidcAuthorizationCodeClient oidcClient, OidcConfig oidcConfig,
            OidcJwksAuthenticator oidcAuthenticator) {
        this.config = Objects.requireNonNull(config, "config");
        this.authStore = Objects.requireNonNull(authStore, "authStore");
        this.worldStore = Objects.requireNonNull(worldStore, "worldStore");
        this.worldHost = Objects.requireNonNull(worldHost, "worldHost");
        this.cookies = Objects.requireNonNull(cookies, "cookies");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.devIssuer = devIssuer;
        this.oidcClient = oidcClient;
        this.oidcConfig = oidcConfig;
        this.oidcAuthenticator = oidcAuthenticator;
        if (config.authMode() == AuthMode.OIDC) {
            Objects.requireNonNull(oidcClient, "oidcClient (OIDC mode)");
            Objects.requireNonNull(oidcConfig, "oidcConfig (OIDC mode)");
            Objects.requireNonNull(oidcAuthenticator, "oidcAuthenticator (OIDC mode)");
        } else {
            Objects.requireNonNull(devIssuer, "devIssuer (DEV_LOCAL mode)");
        }
    }

    /** Registers every auth route (all through the deny-by-default router). */
    public void register(AuthenticatedRouter router) {
        router.register("GET", "/auth/login", AccessPolicy.PUBLIC, ctx ->
                RouteGuard.run(ctx, this::login));
        router.register("GET", "/auth/callback", AccessPolicy.PUBLIC, ctx ->
                RouteGuard.run(ctx, this::callback));
        if (config.authMode() == AuthMode.DEV_LOCAL) {
            router.register("GET", "/dev/login", AccessPolicy.PUBLIC,
                    devIssuer.loginPageHandler());
            router.register("POST", "/dev/login", AccessPolicy.PUBLIC,
                    devIssuer.loginSubmitHandler());
        }
        router.register("POST", "/auth/logout", AccessPolicy.HUMAN_ONLY, ctx ->
                RouteGuard.run(ctx, this::logout));
        router.register("GET", "/api/v1/auth/me", AccessPolicy.HUMAN_ONLY, ctx ->
                RouteGuard.run(ctx, this::me));
        router.register("POST", "/api/v1/admin/users/{userId}/suspend", AccessPolicy.ROLE_ADMIN,
                ctx -> RouteGuard.run(ctx, this::suspend));
    }

    // ------------------------------------------------------------------ handlers

    private void login(RouteContext ctx) throws java.io.IOException {
        if (config.authMode() == AuthMode.DEV_LOCAL) {
            ctx.respondRedirect("/dev/login");
            return;
        }
        OidcAuthorizationCodeClient.PendingLogin pending = oidcClient.begin(oidcConfig);
        ctx.respondRedirect(pending.request().authorizationUri().toString());
    }

    private void callback(RouteContext ctx) throws java.io.IOException {
        if (config.authMode() != AuthMode.OIDC) {
            ApiJson.error(ctx, 400, "UNSUPPORTED_AUTH_MODE",
                    "/auth/callback is only available in --auth-mode=oidc");
            return;
        }
        Map<String, String> query = ApiJson.query(ctx);
        String state = query.get("state");
        String code = query.get("code");
        OidcAuthorizationCodeClient.CompletedLogin completed =
                oidcClient.completeAuthorizationCode(oidcConfig, oidcAuthenticator, state, code);
        var claims = completed.idTokenClaims();
        Set<com.kotharworks.auth.principal.GlobalRole> roles =
                KeycloakRoleClaimExtractor.extract(claims, config.oidcClientId());
        var externalId = new com.kotharworks.auth.principal.ExternalIdentity(
                claims.getIssuer(), claims.getSubject());
        String displayName = displayName(claims);
        UserRecord user = authStore.upsertUser(externalId, displayName);
        SessionStore.CreatedSession session = authStore.createSession(user.userId(), externalId,
                displayName, roles, clock.instant().plus(SESSION_TTL));
        ctx.exchange().getResponseHeaders().set("Set-Cookie",
                cookies.setCookieHeaderValue(session.rawSessionToken()));
        ctx.respondRedirect("/play");
    }

    private void logout(RouteContext ctx) throws java.io.IOException {
        String rawToken = cookies.extractSessionCookie(ctx.exchange().getRequestHeaders().get("Cookie"))
                .orElse(null);
        if (rawToken != null) {
            authStore.revokeSession(CookieCodec.sha256Digest(rawToken));
        }
        ctx.exchange().getResponseHeaders().set("Set-Cookie",
                cookies.clearCookieHeaderValue());
        ctx.respondJson(200, "{\"loggedOut\":true}");
    }

    private void me(RouteContext ctx) throws java.io.IOException {
        Principal.HumanPrincipal human = (Principal.HumanPrincipal) ctx.principal();
        WorldView view = worldHost.view();
        List<SeatRecord> seats = worldStore.findSeatsByOwner(human.userId());
        List<JsonCodec.JsonValue> seatJson = new ArrayList<>(seats.size());
        for (SeatRecord seat : seats) {
            String ownerId = view.findOwnerBySeatKey(seat.seatId())
                    .map(row -> row.id().toWire()).orElse(null);
            List<JsonCodec.JsonObj.Entry> entries = new ArrayList<>();
            entries.add(JsonCodec.JsonObj.entry("seatId", ApiJson.num(seat.seatId())));
            entries.add(JsonCodec.JsonObj.entry("seatName", ApiJson.str(seat.seatName())));
            entries.add(JsonCodec.JsonObj.entry("controllerKind",
                    ApiJson.str(seat.controllerKind().name())));
            entries.add(JsonCodec.JsonObj.entry("ownerId", ownerId == null
                    ? JsonCodec.JsonNull.INSTANCE
                    : ApiJson.str(ownerId)));
            seatJson.add(new JsonCodec.JsonObj(entries));
        }
        List<JsonCodec.JsonValue> roles = human.roles().stream()
                .sorted(Enum::compareTo)
                .map(role -> (JsonCodec.JsonValue) ApiJson.str(role.name()))
                .toList();
        ctx.respondJson(200, JsonCodec.write(new JsonCodec.JsonObj(List.of(
                JsonCodec.JsonObj.entry("userId", ApiJson.str(human.userId().value().toString())),
                JsonCodec.JsonObj.entry("displayName", ApiJson.str(human.displayName())),
                JsonCodec.JsonObj.entry("roles", new JsonCodec.JsonArr(roles)),
                JsonCodec.JsonObj.entry("seats", new JsonCodec.JsonArr(seatJson))))));
    }

    private void suspend(RouteContext ctx) throws java.io.IOException {
        UUID userId = UUID.fromString(ctx.pathParams().get("userId"));
        UserRecord user = authStore.findUser(
                new com.kotharworks.auth.principal.UserId(userId)).orElse(null);
        if (user == null) {
            ApiJson.error(ctx, 404, "UNKNOWN_TARGET", "no user " + userId);
            return;
        }
        String reason = "suspended by admin";
        if (ctx.requestBody().length > 0) {
            JsonCodec.JsonObj body = ApiJson.parseObject(ctx.requestBody());
            String supplied = ApiJson.optString(body, "reason");
            if (supplied != null && !supplied.isBlank()) {
                reason = ApiJson.validatePrintable(supplied, "reason", 1, 200);
            }
        }
        authStore.suspendUser(user.userId(), reason);
        ctx.respondJson(200, JsonCodec.write(new JsonCodec.JsonObj(List.of(
                JsonCodec.JsonObj.entry("suspended", new JsonCodec.JsonBool(true)),
                JsonCodec.JsonObj.entry("userId", ApiJson.str(userId.toString()))))));
    }

    private static String displayName(com.nimbusds.jwt.JWTClaimsSet claims) {
        try {
            String preferred = claims.getStringClaim("preferred_username");
            if (preferred != null && !preferred.isBlank()) {
                return preferred;
            }
        } catch (java.text.ParseException ignored) {
            // fall through to the subject
        }
        return claims.getSubject();
    }
}
