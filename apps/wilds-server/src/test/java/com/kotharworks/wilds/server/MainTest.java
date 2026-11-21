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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kotharworks.auth.AuthMode;
import org.junit.jupiter.api.Test;

/** {@link ServerConfig} parsing and defaults (the rewritten {@code Main} entry). */
class MainTest {

    @Test
    void defaultsMatchTheDocumentedContract() {
        ServerConfig config = ServerConfig.defaults();
        assertEquals(8080, config.port());
        assertEquals(1L, config.worldSeed());
        assertEquals(1L, config.timeScale());
        assertEquals(1_000L, config.pumpIntervalMs());
        assertEquals(AuthMode.DEV_LOCAL, config.authMode());
        assertEquals("http://127.0.0.1:8080", config.publicOrigin());
        assertEquals(10L, config.ratePerSec());
        assertEquals(20L, config.rateBurst());
        assertNull(config.oidcIssuerUrl());
    }

    @Test
    void parseReadsEveryFlag() {
        ServerConfig config = ServerConfig.parse(new String[] {
                "--port", "9000", "--seed", "42", "--time-scale", "20",
                "--pump-interval-ms", "50", "--auth-mode", "dev-local",
                "--public-origin", "http://127.0.0.1:9000",
                "--rate-per-sec", "5", "--rate-burst", "9"});
        assertEquals(9000, config.port());
        assertEquals(42L, config.worldSeed());
        assertEquals(20L, config.timeScale());
        assertEquals(50L, config.pumpIntervalMs());
        assertEquals(AuthMode.DEV_LOCAL, config.authMode());
        assertEquals(5L, config.ratePerSec());
        assertEquals(9L, config.rateBurst());
    }

    @Test
    void parseMapsAuthModes() {
        assertEquals(AuthMode.DEV_LOCAL, ServerConfig.parse(
                new String[] {"--auth-mode", "dev-local"}).authMode());
        assertEquals(AuthMode.OIDC, ServerConfig.parse(new String[] {
                "--auth-mode", "oidc", "--oidc-issuer", "https://id.example",
                "--oidc-client-id", "wilds", "--oidc-client-secret", "s",
                "--oidc-authorization-endpoint", "https://id.example/auth",
                "--oidc-token-endpoint", "https://id.example/token",
                "--oidc-jwks-uri", "https://id.example/certs"}).authMode());
    }

    @Test
    void unknownFlagRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.parse(new String[] {"--nope", "1"}));
        assertTrue(e.getMessage().contains("--nope"));
    }

    @Test
    void nonIntegerValueRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.parse(new String[] {"--port", "eighty"}));
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.parse(new String[] {"--seed", "1.5"}));
    }

    @Test
    void missingValueRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.parse(new String[] {"--port"}));
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.parse(new String[] {"--time-scale", "--seed", "3"}));
    }

    @Test
    void invalidAuthModeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.parse(new String[] {"--auth-mode", "kerberos"}));
    }

    @Test
    void oidcModeRequiresItsFlags() {
        ServerConfig partial = ServerConfig.parse(new String[] {"--auth-mode", "oidc"});
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                partial::validated);
        assertTrue(e.getMessage().contains("--oidc-issuer-url"));
    }
}
