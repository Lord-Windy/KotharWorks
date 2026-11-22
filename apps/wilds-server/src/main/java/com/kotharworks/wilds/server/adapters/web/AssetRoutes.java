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
package com.kotharworks.wilds.server.adapters.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.NoSuchElementException;

import com.kotharworks.auth.http.AccessPolicy;
import com.kotharworks.auth.http.AuthenticatedRouter;
import com.kotharworks.auth.http.AuthenticatedRouter.RouteContext;
import com.kotharworks.wilds.server.adapters.http.RouteGuard;

/**
 * Static console assets: {@code GET /assets/wilds-ui.js} and
 * {@code GET /assets/wilds.css}, served verbatim from the classpath. The file
 * set is a fixed whitelist (no path traversal surface, no directory listing):
 * a missing resource maps to {@code 404 UNKNOWN_TARGET} via
 * {@link RouteGuard}'s {@link NoSuchElementException} handling.
 */
public final class AssetRoutes {

    private static final Map<String, String> WHITELIST = Map.of(
            "wilds-ui.js", "text/javascript; charset=utf-8",
            "wilds.css", "text/css; charset=utf-8");

    /** Registers one asset route per whitelisted file (both public). */
    public void register(AuthenticatedRouter router) {
        for (Map.Entry<String, String> asset : WHITELIST.entrySet()) {
            String name = asset.getKey();
            router.register("GET", "/assets/" + name, AccessPolicy.PUBLIC,
                    ctx -> RouteGuard.run(ctx, routeCtx -> serve(routeCtx, name)));
        }
    }

    private static void serve(RouteContext ctx, String name) throws IOException {
        byte[] bytes = load(name);
        ctx.respond(200, WHITELIST.get(name), bytes);
    }

    private static byte[] load(String name) throws IOException {
        var stream = AssetRoutes.class.getResourceAsStream("/assets/" + name);
        if (stream == null) {
            throw new NoSuchElementException("asset \"" + name + "\" is not on the classpath");
        }
        try (stream) {
            return stream.readAllBytes();
        } catch (UncheckedIOException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
