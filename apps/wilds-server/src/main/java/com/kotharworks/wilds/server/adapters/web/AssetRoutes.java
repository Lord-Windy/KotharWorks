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
import java.util.LinkedHashMap;
import java.util.Map;

import com.kotharworks.auth.http.AccessPolicy;
import com.kotharworks.auth.http.AuthenticatedRouter;
import com.kotharworks.auth.http.AuthenticatedRouter.RouteContext;
import com.kotharworks.wilds.server.adapters.http.RouteGuard;

/**
 * Static console assets: {@code GET /assets/wilds-ui.js} and
 * {@code GET /assets/wilds.css}, loaded eagerly from the classpath at
 * construction time so missing resources fail fast at boot and requests incur
 * zero classpath I/O. The file set is a fixed whitelist (no path traversal
 * surface, no directory listing).
 */
public final class AssetRoutes {

    private static final Map<String, String> WHITELIST = Map.of(
            "wilds-ui.js", "text/javascript; charset=utf-8",
            "wilds.css", "text/css; charset=utf-8");

    private record LoadedAsset(String contentType, byte[] bytes) {
    }

    private final Map<String, LoadedAsset> assets;

    public AssetRoutes() {
        Map<String, LoadedAsset> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : WHITELIST.entrySet()) {
            loaded.put(entry.getKey(), new LoadedAsset(entry.getValue(), load(entry.getKey())));
        }
        this.assets = Map.copyOf(loaded);
    }

    /** Registers one asset route per whitelisted file (both public). */
    public void register(AuthenticatedRouter router) {
        for (Map.Entry<String, LoadedAsset> entry : assets.entrySet()) {
            String name = entry.getKey();
            LoadedAsset asset = entry.getValue();
            router.register("GET", "/assets/" + name, AccessPolicy.PUBLIC,
                    ctx -> RouteGuard.run(ctx, routeCtx -> serve(routeCtx, asset)));
        }
    }

    private static void serve(RouteContext ctx, LoadedAsset asset) throws IOException {
        ctx.exchange().getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ctx.exchange().getResponseHeaders().set("Cache-Control", "no-cache");
        ctx.respond(200, asset.contentType(), asset.bytes());
    }

    private static byte[] load(String name) {
        var stream = AssetRoutes.class.getResourceAsStream("/assets/" + name);
        if (stream == null) {
            throw new IllegalStateException("asset \"" + name + "\" is not on the classpath");
        }
        try (stream) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load classpath asset \"" + name + "\"", e);
        }
    }
}
