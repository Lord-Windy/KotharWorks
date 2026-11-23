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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.kotharworks.auth.http.AccessPolicy;
import com.kotharworks.auth.http.AuthenticatedRouter;
import com.kotharworks.auth.http.AuthenticatedRouter.RouteContext;
import com.kotharworks.wilds.command.ErrorCode;
import com.kotharworks.wilds.observe.ObservationProjector;
import com.kotharworks.wilds.observe.ObservationProjector.ObservationRejectedException;
import com.kotharworks.wilds.observe.WorldView;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.server.ServerConfig;
import com.kotharworks.wilds.server.domain.SeatAuthorizer;
import com.kotharworks.wilds.server.domain.SeatAuthorizer.AuthorizedSeat;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import com.kotharworks.wilds.server.ports.WorldStorePort;
import com.kotharworks.wilds.worldgen.DescriptionComposer.InspectTarget;
import com.kotharworks.wilds.worldgen.DescriptionComposer.ZoomLevel;
import com.kotharworks.wilds.worldgen.StubDescriptionComposer;

/**
 * Read routes: {@code /api/v1/meta} (public), {@code /api/v1/rules},
 * {@code /api/v1/observation} (lock-free volatile {@code WorldView} read through
 * the fog projector), {@code /api/v1/examine}, {@code /api/v1/events}
 * (seat-private) and {@code /api/v1/public/events} (public projection only).
 */
public final class QueryRoutes {

    private static final int DEFAULT_EVENT_LIMIT = 200;
    private static final int MAX_EVENT_LIMIT = 1_000;

    private final WorldHost worldHost;
    private final WorldStorePort worldStore;
    private final SeatAuthorizer seatAuthorizer;
    private final ServerConfig config;

    public QueryRoutes(WorldHost worldHost, WorldStorePort worldStore,
            SeatAuthorizer seatAuthorizer, ServerConfig config) {
        this.worldHost = Objects.requireNonNull(worldHost, "worldHost");
        this.worldStore = Objects.requireNonNull(worldStore, "worldStore");
        this.seatAuthorizer = Objects.requireNonNull(seatAuthorizer, "seatAuthorizer");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Registers the read routes. */
    public void register(AuthenticatedRouter router) {
        router.register("GET", "/api/v1/meta", AccessPolicy.PUBLIC, ctx ->
                RouteGuard.run(ctx, this::meta));
        router.register("GET", "/api/v1/rules", AccessPolicy.AUTHENTICATED_ANY, ctx ->
                RouteGuard.run(ctx, this::rules));
        router.register("GET", "/api/v1/observation", AccessPolicy.AUTHENTICATED_ANY, ctx ->
                RouteGuard.run(ctx, this::observation));
        router.register("GET", "/api/v1/examine", AccessPolicy.AUTHENTICATED_ANY, ctx ->
                RouteGuard.run(ctx, this::examine));
        router.register("GET", "/api/v1/events", AccessPolicy.AUTHENTICATED_ANY, ctx ->
                RouteGuard.run(ctx, this::events));
        router.register("GET", "/api/v1/public/events", AccessPolicy.PUBLIC, ctx ->
                RouteGuard.run(ctx, this::publicEvents));
    }

    // ------------------------------------------------------------------ handlers

    private void meta(RouteContext ctx) throws IOException {
        var world = worldHost.worldRecord();
        WorldView view = worldHost.view();
        ctx.respondJson(200, JsonCodec.write(new JsonCodec.JsonObj(List.of(
                JsonCodec.JsonObj.entry("worldId", ApiJson.num(world.worldId())),
                JsonCodec.JsonObj.entry("nowMs", ApiJson.num(worldHost.currentLogicalNowMs())),
                JsonCodec.JsonObj.entry("worldSeed", ApiJson.num(world.worldSeed())),
                JsonCodec.JsonObj.entry("rulesVersion", ApiJson.num(world.rulesVersion())),
                JsonCodec.JsonObj.entry("engineVersion", ApiJson.num(world.engineVersion())),
                JsonCodec.JsonObj.entry("protocolVersion", ApiJson.num(world.protocolVersion())),
                JsonCodec.JsonObj.entry("timeScale", ApiJson.num(config.timeScale())),
                JsonCodec.JsonObj.entry("pumpIntervalMs", ApiJson.num(config.pumpIntervalMs()))))));
    }

    private void rules(RouteContext ctx) throws IOException {
        ctx.respondJson(200, ObservationJson.rules(RuleRegistry.minimalDefault(),
                RuleRegistry.WILDS_RULES_VERSION));
    }

    private void observation(RouteContext ctx) throws IOException {
        WorldView view = worldHost.view();
        AuthorizedSeat authorized = authorize(ctx, ApiJson.query(ctx), view);
        if (authorized.owner() == null) {
            ApiJson.error(ctx, 404, "UNKNOWN_TARGET", "seat not yet in-world");
            return;
        }
        var observation = ObservationProjector.projectOwner(view, authorized.owner(),
                StubDescriptionComposer.INSTANCE);
        ctx.respondJson(200, ObservationJson.observation(observation, worldHost.currentLogicalNowMs()));
    }

    private void examine(RouteContext ctx) throws IOException {
        WorldView view = worldHost.view();
        Map<String, String> query = ApiJson.query(ctx);
        AuthorizedSeat authorized = authorize(ctx, query, view);
        if (authorized.owner() == null) {
            ApiJson.error(ctx, 404, "UNKNOWN_TARGET", "seat not yet in-world");
            return;
        }
        InspectTarget target = parseTarget(query);
        ZoomLevel zoom = parseZoom(query);
        try {
            String text = ObservationProjector.examine(view, authorized.owner(),
                    target, zoom, StubDescriptionComposer.INSTANCE);
            ctx.respondJson(200, JsonCodec.write(new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("text", ApiJson.str(text))))));
        } catch (ObservationRejectedException rejected) {
            renderRejection(ctx, rejected);
        }
    }

    private void events(RouteContext ctx) throws IOException {
        Map<String, String> query = ApiJson.query(ctx);
        AuthorizedSeat authorized = authorize(ctx, query, worldHost.view());
        long since = parseSince(query);
        int limit = eventLimit(query);
        List<StoredWorldEvent> rows = worldHost.readSeatEventsSince(
                authorized.seat().seatId(), since, limit);
        respondEventPage(ctx, since, rows, EventCodec::toJsonValue);
    }

    private void publicEvents(RouteContext ctx) throws IOException {
        Map<String, String> query = ApiJson.query(ctx);
        long since = parseSince(query);
        int limit = eventLimit(query);
        List<StoredWorldEvent> rows = worldStore.readEventsSince(worldHost.worldId(), null,
                since, limit);
        respondEventPage(ctx, since, rows, EventCodec::toPublicJsonValue);
    }

    // ------------------------------------------------------------------ helpers

    private AuthorizedSeat authorize(RouteContext ctx, Map<String, String> query, WorldView view) {
        Long seatIdParam = query.containsKey("seatId") && !query.get("seatId").isBlank()
                ? ApiJson.queryLong(query, "seatId", -1)
                : null;
        return seatAuthorizer.authorize(ctx.principal(), seatIdParam, view);
    }

    private static long parseSince(Map<String, String> query) {
        long since = ApiJson.queryLong(query, "since", 0);
        if (since < 0) {
            throw new IllegalArgumentException(
                    "query parameter \"since\" must be >= 0, got: " + since);
        }
        return since;
    }

    private static int eventLimit(Map<String, String> query) {
        long limit = ApiJson.queryLong(query, "limit", DEFAULT_EVENT_LIMIT);
        if (limit < 1 || limit > MAX_EVENT_LIMIT) {
            throw new IllegalArgumentException(
                    "query parameter \"limit\" must be 1.." + MAX_EVENT_LIMIT);
        }
        return (int) limit;
    }

    private static void respondEventPage(RouteContext ctx, long since, List<StoredWorldEvent> rows,
            java.util.function.Function<StoredWorldEvent, JsonCodec.JsonValue> renderer)
            throws IOException {
        List<JsonCodec.JsonValue> rendered = new ArrayList<>(rows.size());
        long nextSince = since;
        for (StoredWorldEvent row : rows) {
            rendered.add(renderer.apply(row));
            nextSince = row.cursor();
        }
        ctx.respondJson(200, JsonCodec.write(new JsonCodec.JsonObj(List.of(
                JsonCodec.JsonObj.entry("events", new JsonCodec.JsonArr(rendered)),
                JsonCodec.JsonObj.entry("nextSince", ApiJson.num(nextSince))))));
    }

    private static InspectTarget parseTarget(Map<String, String> query) {
        String type = query.getOrDefault("type", "");
        String id = query.getOrDefault("id", "");
        if (id.isBlank()) {
            throw new IllegalArgumentException("query parameter \"id\" is required");
        }
        return switch (type) {
            case "place" -> new InspectTarget.PlaceTarget(com.kotharworks.wilds.id.PlaceId.parse(id));
            case "actor" -> new InspectTarget.ActorTarget(com.kotharworks.wilds.id.ActorId.parse(id));
            default -> throw new IllegalArgumentException(
                    "query parameter \"type\" must be place|actor, got: \"" + type + "\"");
        };
    }

    private static ZoomLevel parseZoom(Map<String, String> query) {
        String zoom = query.getOrDefault("zoom", "glance");
        return switch (zoom.toLowerCase(Locale.ROOT)) {
            case "glance" -> ZoomLevel.GLANCE;
            case "look" -> ZoomLevel.LOOK;
            case "examine" -> ZoomLevel.EXAMINE;
            default -> throw new IllegalArgumentException(
                    "query parameter \"zoom\" must be glance|look|examine, got: \"" + zoom + "\"");
        };
    }

    private static void renderRejection(RouteContext ctx, ObservationRejectedException rejected)
            throws IOException {
        if (rejected.errorCode() == ErrorCode.FORBIDDEN) {
            ApiJson.error(ctx, 403, rejected.errorCode().name(), rejected.getMessage());
        } else {
            ApiJson.error(ctx, 404, rejected.errorCode().name(), rejected.getMessage());
        }
    }
}
