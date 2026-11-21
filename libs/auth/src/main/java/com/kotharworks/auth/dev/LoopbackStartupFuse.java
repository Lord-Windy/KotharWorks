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
package com.kotharworks.auth.dev;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;

import com.kotharworks.auth.AuthMode;

/**
 * Startup fuse for {@link AuthMode#DEV_LOCAL}: refuses to boot the dev-local
 * issuer (self-signed JWTs, no external identity provider) on any non-loopback
 * bind. Wildcard binds ({@code 0.0.0.0}, {@code ::}) and LAN addresses throw
 * {@link IllegalStateException} immediately — an unauthenticated persona
 * switcher must never be reachable off the developer's machine. {@link
 * AuthMode#OIDC} binds are not inspected.
 */
public final class LoopbackStartupFuse {

    private LoopbackStartupFuse() {
    }

    /**
     * @param authMode    the configured auth mode
     * @param bindAddress the address the HTTP server will bind
     * @throws IllegalStateException when {@code authMode == DEV_LOCAL} and the
     *         bind address is missing, wildcard, or non-loopback
     */
    public static void verify(AuthMode authMode, InetSocketAddress bindAddress) {
        Objects.requireNonNull(authMode, "authMode");
        if (authMode != AuthMode.DEV_LOCAL) {
            return;
        }
        if (bindAddress == null || bindAddress.getAddress() == null) {
            throw new IllegalStateException(
                    "dev-local auth mode requires an explicit loopback bind, got: " + bindAddress);
        }
        InetAddress address = bindAddress.getAddress();
        if (address.isAnyLocalAddress() || !address.isLoopbackAddress()) {
            throw new IllegalStateException(
                    "dev-local auth mode refuses non-loopback bind: " + bindAddress);
        }
    }
}
