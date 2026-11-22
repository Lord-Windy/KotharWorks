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
package com.kotharworks.wilds.server.adapters.sse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.kotharworks.auth.http.AccessPolicy;
import com.kotharworks.auth.http.AuthenticatedRouter;
import com.kotharworks.auth.http.AuthenticatedRouter.RouteContext;
import com.kotharworks.wilds.server.adapters.http.ApiJson;
import com.kotharworks.wilds.server.adapters.http.EventCodec;
import com.kotharworks.wilds.server.adapters.http.RouteGuard;
import com.kotharworks.wilds.server.adapters.memory.InMemoryEventBus;
import com.kotharworks.wilds.server.domain.SeatAuthorizer;
import com.kotharworks.wilds.server.domain.SeatAuthorizer.AuthorizedSeat;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.ports.EventBusPort;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import com.kotharworks.wilds.server.ports.WorldStorePort;
import com.sun.net.httpserver.HttpExchange;

/**
 * Live Server-Sent Events: {@code GET /api/v1/stream?since={cursor}}. The
 * handler subscribes to {@link EventBusPort} before sending {@code 200}
 * chunked headers ({@code sendResponseHeaders(200, 0L)}), replays missed
 * events (persisted + any WAL-committed deferred batch on {@link WorldHost}),
 * then streams live events as {@code id:\nevent:\ndata:\n\n} frames, flushing
 * after each. Overflow markers re-replay from {@link WorldHost}; {@code : keepalive}
 * fires on every 25 s poll timeout; and the poison marker wakes the reader so
 * server shutdown never hangs a client.
 */
public final class SseRoutes {

    private static final long KEEPALIVE_SECONDS = 25L;
    private static final int REPLAY_BATCH = 500;

    private final WorldHost worldHost;
    private final EventBusPort eventBus;
    private final SeatAuthorizer seatAuthorizer;

    public SseRoutes(WorldHost worldHost, WorldStorePort worldStore, EventBusPort eventBus,
            SeatAuthorizer seatAuthorizer) {
        this.worldHost = Objects.requireNonNull(worldHost, "worldHost");
        Objects.requireNonNull(worldStore, "worldStore");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.seatAuthorizer = Objects.requireNonNull(seatAuthorizer, "seatAuthorizer");
    }

    /** Registers the SSE stream route. */
    public void register(AuthenticatedRouter router) {
        router.register("GET", "/api/v1/stream", AccessPolicy.AUTHENTICATED_ANY, ctx ->
                RouteGuard.run(ctx, this::stream));
    }

    private void stream(RouteContext ctx) throws IOException {
        Map<String, String> query = ApiJson.query(ctx);
        Long seatIdParam = query.containsKey("seatId") && !query.get("seatId").isBlank()
                ? ApiJson.queryLong(query, "seatId", -1)
                : null;
        long since = ApiJson.queryLong(query, "since", 0);
        if (since < 0) {
            throw new IllegalArgumentException(
                    "query parameter \"since\" must be >= 0, got: " + since);
        }
        AuthorizedSeat authorized = seatAuthorizer.authorize(ctx.principal(), seatIdParam,
                worldHost.view());
        long seatId = authorized.seat().seatId();
        int worldId = worldHost.worldId();

        BlockingQueue<StoredWorldEvent> queue;
        try {
            queue = eventBus.subscribe(worldId, seatId);
        } catch (IllegalStateException busClosed) {
            ApiJson.error(ctx, 503, "STORAGE_UNAVAILABLE", "server is shutting down");
            return;
        }
        HttpExchange exchange = ctx.exchange();
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            // 0L (not -1L): chunked streaming mode on the JDK HttpServer.
            exchange.sendResponseHeaders(200, 0L);
            long lastSent = since;
            try (OutputStream out = exchange.getResponseBody()) {
                lastSent = catchUp(out, seatId, lastSent);
                while (true) {
                    StoredWorldEvent event = queue.poll(KEEPALIVE_SECONDS, TimeUnit.SECONDS);
                    if (event == null) {
                        out.write((": keepalive\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        continue;
                    }
                    if (InMemoryEventBus.OVERFLOW.kind().equals(event.kind())) {
                        lastSent = catchUp(out, seatId, lastSent);
                        continue;
                    }
                    if (event.cursor() < 1) {
                        return; // poison marker: the event bus closed (server shutdown)
                    }
                    if (event.cursor() <= lastSent) {
                        continue; // already replayed
                    }
                    writeFrame(out, event);
                    lastSent = event.cursor();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException clientGone) {
            // Client disconnect: the normal way a stream ends.
        } finally {
            eventBus.unsubscribe(queue);
        }
    }

    private long catchUp(OutputStream out, long seatId, long lastSent) throws IOException {
        long cursor = lastSent;
        while (true) {
            List<StoredWorldEvent> batch = worldHost.readSeatEventsSince(
                    seatId, cursor, REPLAY_BATCH);
            if (batch.isEmpty()) {
                return cursor;
            }
            for (StoredWorldEvent event : batch) {
                if (event.cursor() > cursor) {
                    writeFrame(out, event);
                    cursor = event.cursor();
                }
            }
        }
    }

    private static void writeFrame(OutputStream out, StoredWorldEvent event) throws IOException {
        String frame = "id: " + event.cursor() + "\nevent: " + event.kind() + "\ndata: "
                + EventCodec.toJson(event) + "\n\n";
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
