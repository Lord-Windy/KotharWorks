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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.kotharworks.auth.http.AccessPolicy;
import com.kotharworks.auth.http.AuthenticatedRouter;
import com.kotharworks.auth.http.AuthenticatedRouter.RouteContext;
import com.kotharworks.wilds.server.adapters.http.RouteGuard;
import com.kotharworks.wilds.server.domain.ControllerKind;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.ports.SeatRecord;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import com.kotharworks.wilds.server.ports.WorldRecord;
import com.kotharworks.wilds.server.ports.WorldStorePort;

/**
 * Public world observatory: {@code GET /chronicle} renders the active world's
 * seed and version triple, the logical clock, the seat roster broken down by
 * {@link ControllerKind}, and the public event feed (already projected to
 * {@code publicSummary}-only rows by the store). Every dynamic value renders
 * through {@link Html#text(CharSequence)} — stored HTML-rich seat names appear
 * escaped here exactly as they do in the private console.
 */
public final class ChronicleRoutes {

    private static final int FEED_LIMIT = 50;

    private final WorldHost worldHost;
    private final WorldStorePort worldStore;

    public ChronicleRoutes(WorldHost worldHost, WorldStorePort worldStore) {
        this.worldHost = Objects.requireNonNull(worldHost, "worldHost");
        this.worldStore = Objects.requireNonNull(worldStore, "worldStore");
    }

    /** Registers the public chronicle route. */
    public void register(AuthenticatedRouter router) {
        router.register("GET", "/chronicle", AccessPolicy.PUBLIC, ctx ->
                RouteGuard.run(ctx, this::chronicle));
    }

    private void chronicle(RouteContext ctx) throws IOException {
        WorldRecord world = worldHost.worldRecord();
        long nowMs = worldHost.view().nowMs();
        List<SeatRecord> seats = worldStore.findAllSeats();
        Map<ControllerKind, Integer> counts = new EnumMap<>(ControllerKind.class);
        for (SeatRecord seat : seats) {
            counts.merge(seat.controllerKind(), 1, Integer::sum);
        }
        List<StoredWorldEvent> feed = worldStore.readEventsSince(worldHost.worldId(), null,
                0, FEED_LIMIT);

        Html html = Html.create()
                .doctype()
                .open("html", new Html.Attr("lang", "en"))
                .open("head")
                .voidTag("meta", new Html.Attr("charset", "utf-8"))
                .voidTag("meta", new Html.Attr("name", "viewport"),
                        new Html.Attr("content", "width=device-width, initial-scale=1"))
                .el("title", new Html.Attr[0], "Ironwood Wilds — Chronicle")
                .voidTag("link", new Html.Attr("rel", "stylesheet"),
                        new Html.Attr("href", "/assets/wilds.css"))
                .close("head")
                .open("body");
        headerBar(html, world, nowMs, counts);
        html.open("div", new Html.Attr("class", "panel"))
                .open("h2").text("Public World Feed").close("h2")
                .open("ul", new Html.Attr("id", "public-feed"));
        for (StoredWorldEvent event : feed) {
            html.open("li").text("#" + event.cursor() + " [" + event.kind() + "] ")
                    .text(event.publicSummary() == null ? "" : event.publicSummary())
                    .close("li");
        }
        if (feed.isEmpty()) {
            html.open("li").text("No public events yet — the wilds are quiet.").close("li");
        }
        html.close("ul").close("div");
        html.open("p")
                .open("a", new Html.Attr("href", "/play")).text("Enter the wilds").close("a")
                .text(" (human console; sign-in required)")
                .close("p")
                .close("body")
                .close("html");
        ctx.respondText(200, "text/html; charset=utf-8", html.render());
    }

    private static void headerBar(Html html, WorldRecord world, long nowMs,
            Map<ControllerKind, Integer> counts) {
        html.open("header", new Html.Attr("class", "console-bar"))
                .open("h1").text("Ironwood Wilds — Chronicle").close("h1")
                .close("header");
        html.open("div", new Html.Attr("class", "panel"))
                .open("h2").text("World").close("h2")
                .open("dl", new Html.Attr("class", "place-facts"))
                .open("dt").text("Seed").close("dt")
                .open("dd").text(String.valueOf(world.worldSeed())).close("dd")
                .open("dt").text("Rules version").close("dt")
                .open("dd").text(String.valueOf(world.rulesVersion())).close("dd")
                .open("dt").text("Engine version").close("dt")
                .open("dd").text(String.valueOf(world.engineVersion())).close("dd")
                .open("dt").text("Protocol version").close("dt")
                .open("dd").text(String.valueOf(world.protocolVersion())).close("dd")
                .open("dt").text("Logical time (nowMs)").close("dt")
                .open("dd").text(String.valueOf(nowMs)).close("dd")
                .close("dl")
                .close("div");
        html.open("div", new Html.Attr("class", "panel"))
                .open("h2").text("Seats").close("h2")
                .open("ul");
        for (ControllerKind kind : ControllerKind.values()) {
            html.open("li").text(kind.name() + ": " + counts.getOrDefault(kind, 0)).close("li");
        }
        html.close("ul").close("div");
    }
}
