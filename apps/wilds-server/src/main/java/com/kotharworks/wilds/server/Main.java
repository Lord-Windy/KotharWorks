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
import java.util.concurrent.TimeUnit;

/**
 * Thin entry point: parse flags, boot the hexagonal server, print the listen
 * line, and park the main thread until the process is killed (the shutdown
 * hook checkpoints the world through {@code WorldHost#close}).
 */
public final class Main {

    private static final String USAGE = """
            usage: wilds-server [flags]
              --port <int>                      bind port (default 8080, loopback only)
              --seed <long>                     world seed (default 1)
              --time-scale <long>               logical ms per wall ms (default 1)
              --pump-interval-ms <long>         wall interval between pump ticks (default 1000)
              --auth-mode dev-local|oidc        auth mode (default dev-local)
              --public-origin <url>             external origin (default http://127.0.0.1:<port>)
              --oidc-issuer <url>               OIDC issuer (OIDC mode)
              --oidc-client-id <id>             OIDC resource client id (OIDC mode)
              --oidc-client-secret <secret>     OIDC client secret (OIDC mode)
              --oidc-authorization-endpoint <url>
              --oidc-token-endpoint <url>
              --oidc-jwks-uri <url>
              --rate-per-sec <long>             per-seat sustained rate (default 10)
              --rate-burst <long>               per-seat burst (default 20)
              --bind <addr>                     bind address (default 127.0.0.1)
              --storage memory|postgres         persistence backend (default memory)
              --jdbc-url <url>                  PostgreSQL JDBC URL (postgres storage)
              --db-user <user>                  PostgreSQL user (postgres storage)
              --db-password <pass>              PostgreSQL password (postgres storage)
              --checkpoint-interval-ms <long>   uptime ms between checkpoints (default 300000)
            """;

    private Main() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        ServerConfig config;
        try {
            config = ServerConfig.parse(args).validated();
        } catch (IllegalArgumentException e) {
            System.err.println("wilds-server: " + e.getMessage());
            System.err.print(USAGE);
            System.exit(2);
            return;
        }
        try (WildsServer server = new WildsServer(config)) {
            server.start();
            System.out.println("wilds-server listening on http://127.0.0.1:" + server.port()
                    + " (mode=" + config.authMode().name().toLowerCase(java.util.Locale.ROOT)
                    + ", timeScale=" + config.timeScale() + ", world=" + config.worldSeed()
                    + ", storage=" + config.storage().name().toLowerCase(java.util.Locale.ROOT)
                    + ", bind=" + config.bindAddress() + ")");
            Runtime.getRuntime().addShutdownHook(
                    Thread.ofVirtual().name("wilds-server-shutdown").unstarted(server::close));
            TimeUnit.DAYS.sleep(365_000L); // park main; virtual-thread executors are daemons
        }
    }
}
