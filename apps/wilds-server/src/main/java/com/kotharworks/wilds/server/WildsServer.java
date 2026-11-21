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
package com.kotharworks.wilds.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;

import com.kotharworks.auth.AuthMode;
import com.kotharworks.auth.dev.DevLocalJwtIssuer;
import com.kotharworks.auth.dev.LoopbackStartupFuse;
import com.kotharworks.auth.http.AuthenticatedRouter;
import com.kotharworks.auth.http.CookieCodec;
import com.kotharworks.auth.http.CsrfGuard;
import com.kotharworks.auth.http.RequestAuthenticator;
import com.kotharworks.auth.http.SessionCookieAuthenticator;
import com.kotharworks.auth.jwt.JwtClaimsVerifier;
import com.kotharworks.auth.jwt.JwtHumanAuthenticator;
import com.kotharworks.auth.oidc.OidcAuthorizationCodeClient;
import com.kotharworks.auth.oidc.OidcConfig;
import com.kotharworks.auth.oidc.OidcJwksAuthenticator;
import com.kotharworks.auth.token.ApiTokenCodec;
import com.kotharworks.auth.token.ScopedApiTokenAuthenticator;
import com.kotharworks.wilds.server.adapters.clock.ClockPump;
import com.kotharworks.wilds.server.adapters.http.AuthRoutes;
import com.kotharworks.wilds.server.adapters.http.CommandRoutes;
import com.kotharworks.wilds.server.adapters.http.QueryRoutes;
import com.kotharworks.wilds.server.adapters.http.SeatRoutes;
import com.kotharworks.wilds.server.adapters.http.SuspendedUserRejectingAuthenticator;
import com.kotharworks.wilds.server.adapters.memory.InMemoryAuthStore;
import com.kotharworks.wilds.server.adapters.memory.InMemoryEventBus;
import com.kotharworks.wilds.server.adapters.memory.InMemoryWorldStore;
import com.kotharworks.wilds.server.adapters.sse.SseRoutes;
import com.kotharworks.wilds.server.domain.SeatAuthorizer;
import com.kotharworks.wilds.server.domain.SeatRateLimiter;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.ports.AuthStorePort;
import com.kotharworks.wilds.server.ports.EventBusPort;
import com.kotharworks.wilds.server.ports.WorldStorePort;
import com.sun.net.httpserver.HttpServer;

/**
 * Composition root of the Ironwood Wilds server: wires the hexagonal rings —
 * {@code libs/auth} principals, the {@link WorldHost} single writer, the
 * in-memory adapters, and every REST/SSE route registered exclusively through
 * the deny-by-default {@link AuthenticatedRouter} — and doubles as the test
 * rig (injectable clock, stores, bus, and token codec; ephemeral port).
 */
public final class WildsServer implements AutoCloseable {

    /** JWT audience/resource client of this server (dev-local issuer matches it). */
    public static final String AUDIENCE = "wilds-server";

    private final ServerConfig config;
    private final Clock clock;
    private final AuthStorePort authStore;
    private final WorldStorePort worldStore;
    private final EventBusPort eventBus;
    private final HttpServer server;
    private final AuthenticatedRouter router;
    private final WorldHost worldHost;
    private final ClockPump pump;
    private final SeatAuthorizer seatAuthorizer;
    private final DevLocalJwtIssuer devIssuer;
    private final OidcConfig oidcConfig;

    /** Production-style constructor: real clock, fresh in-memory adapters. */
    public WildsServer(ServerConfig config) throws IOException {
        this(config, Clock.systemUTC(), null, null, null, new ApiTokenCodec(new SecureRandom()));
    }

    /**
     * Test rig constructor: every wall-clock consumer and store is injected;
     * {@code null} store arguments fall back to fresh in-memory adapters.
     *
     * @throws IOException when the loopback bind fails
     */
    public WildsServer(ServerConfig config, Clock clock, AuthStorePort authStore,
            WorldStorePort worldStore, EventBusPort eventBus, ApiTokenCodec apiTokenCodec)
            throws IOException {
        this.config = Objects.requireNonNull(config, "config").validated();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authStore = authStore != null ? authStore : new InMemoryAuthStore(clock);
        this.worldStore = worldStore != null ? worldStore : new InMemoryWorldStore();
        this.eventBus = eventBus != null ? eventBus : new InMemoryEventBus();
        Objects.requireNonNull(apiTokenCodec, "apiTokenCodec");

        this.server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), config.port()), 0);
        LoopbackStartupFuse.verify(config.authMode(), server.getAddress());
        CookieCodec cookies = CookieCodec.devLoopback();

        JwtClaimsVerifier verifier;
        OidcAuthorizationCodeClient oidcClient;
        OidcJwksAuthenticator oidcAuthenticator;
        if (config.authMode() == AuthMode.OIDC) {
            this.devIssuer = null;
            this.oidcConfig = new OidcConfig(config.oidcIssuerUrl(), config.oidcClientId(),
                    config.oidcClientSecret(), config.publicOrigin(),
                    URI.create(config.oidcAuthorizationEndpoint()),
                    URI.create(config.oidcTokenEndpoint()),
                    URI.create(config.oidcJwksUri()));
            oidcClient = new OidcAuthorizationCodeClient();
            oidcAuthenticator = new OidcJwksAuthenticator(oidcConfig, clock);
            verifier = oidcAuthenticator.jwtVerifier();
        } else {
            this.oidcConfig = null;
            oidcClient = null;
            oidcAuthenticator = null;
            this.devIssuer = new DevLocalJwtIssuer(config.publicOrigin(), AUDIENCE, clock,
                    this.authStore, this.authStore, cookies);
            verifier = devIssuer.verifier();
        }

        var apiTokens = new ScopedApiTokenAuthenticator(this.authStore::findActiveTokenBySha256);
        var humans = new JwtHumanAuthenticator(verifier, config.oidcClientId(), this.authStore);
        var cookieAuth = new SessionCookieAuthenticator(cookies, this.authStore, clock);
        RequestAuthenticator authenticator = new SuspendedUserRejectingAuthenticator(
                RequestAuthenticator.bearerThenCookie(apiTokens, humans, cookieAuth),
                this.authStore);
        CsrfGuard csrfGuard = new CsrfGuard(config.publicOrigin(), config.authMode(),
                () -> server.getAddress().getPort());
        this.router = new AuthenticatedRouter(server, authenticator, csrfGuard);

        this.worldHost = new WorldHost(this.worldStore, this.eventBus, config.worldSeed(),
                config.timeScale());
        this.pump = new ClockPump(worldHost, config.pumpIntervalMs());
        SeatRateLimiter rateLimiter = new SeatRateLimiter(config.ratePerSec(), config.rateBurst());
        this.seatAuthorizer = new SeatAuthorizer(this.authStore, this.worldStore, rateLimiter);

        new AuthRoutes(config, this.authStore, this.worldStore, worldHost, cookies, clock, devIssuer,
                oidcClient, oidcConfig, oidcAuthenticator).register(router);
        new SeatRoutes(this.authStore, this.worldStore, worldHost, seatAuthorizer, apiTokenCodec)
                .register(router);
        new QueryRoutes(worldHost, this.worldStore, seatAuthorizer, config).register(router);
        new CommandRoutes(worldHost, seatAuthorizer).register(router);
        new SseRoutes(worldHost, this.worldStore, this.eventBus, seatAuthorizer)
                .register(router);
    }

    /** Starts the HTTP router and the live pump. */
    public void start() throws IOException {
        router.start();
        pump.start();
    }

    /** The bound port ({@code 0} configs resolve to the ephemeral port). */
    public int port() {
        return server.getAddress().getPort();
    }

    public WorldHost worldHost() {
        return worldHost;
    }

    public AuthenticatedRouter router() {
        return router;
    }

    public AuthStorePort authStore() {
        return authStore;
    }

    public WorldStorePort worldStore() {
        return worldStore;
    }

    public EventBusPort eventBus() {
        return eventBus;
    }

    public SeatAuthorizer seatAuthorizer() {
        return seatAuthorizer;
    }

    /** The dev-local issuer (JWT minting in tests); null in OIDC mode. */
    public DevLocalJwtIssuer devIssuer() {
        return devIssuer;
    }

    /** Stops pump, HTTP server, world host (checkpointing), and the event bus. */
    @Override
    public void close() {
        pump.close();
        router.stop(0);
        worldHost.close();
    }

    /** Prevents accidental unchecked-IOException leakage in tests. */
    public static WildsServer open(ServerConfig config) {
        try {
            return new WildsServer(config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
