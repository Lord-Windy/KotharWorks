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
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.kotharworks.auth.http.AccessPolicy;
import com.kotharworks.auth.http.AuthenticatedRouter;
import com.kotharworks.auth.http.AuthenticatedRouter.RouteContext;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.server.domain.CommandOutcome;
import com.kotharworks.wilds.server.domain.SeatAuthorizer;
import com.kotharworks.wilds.server.domain.SeatAuthorizer.AuthorizedSeat;
import com.kotharworks.wilds.server.domain.WorldHost;

/**
 * The single world-mutation endpoint for actors: {@code POST /api/v1/commands}.
 * The caller's {@code OwnerId} is injected by the server (never client
 * supplied); the envelope carries the {@code cmdId} idempotency key. Outcomes:
 * {@code 200 OK + receipt}, {@code 409} engine rejection ({@code STALE_STATE},
 * {@code FORBIDDEN}, ...), {@code 503 STORAGE_UNAVAILABLE} with untouched RAM.
 */
public final class CommandRoutes {

    private final WorldHost worldHost;
    private final SeatAuthorizer seatAuthorizer;

    public CommandRoutes(WorldHost worldHost, SeatAuthorizer seatAuthorizer) {
        this.worldHost = Objects.requireNonNull(worldHost, "worldHost");
        this.seatAuthorizer = Objects.requireNonNull(seatAuthorizer, "seatAuthorizer");
    }

    /** Registers the command route. */
    public void register(AuthenticatedRouter router) {
        router.register("POST", "/api/v1/commands", AccessPolicy.AUTHENTICATED_ANY, ctx ->
                RouteGuard.run(ctx, this::submit));
    }

    private void submit(RouteContext ctx) throws IOException {
        Map<String, String> query = ApiJson.query(ctx);
        Long seatIdParam = query.containsKey("seatId") && !query.get("seatId").isBlank()
                ? ApiJson.queryLong(query, "seatId", -1)
                : null;
        AuthorizedSeat authorized = seatAuthorizer.authorize(ctx.principal(), seatIdParam,
                worldHost.view());
        if (authorized.owner() == null) {
            ApiJson.error(ctx, 409, "UNKNOWN_TARGET", "seat not yet in-world");
            return;
        }
        CommandRequestCodec.ParsedCommand parsed = CommandRequestCodec.parse(ctx.requestBody(),
                authorized.owner());
        CommandOutcome outcome = worldHost.submit(authorized.seat().seatId(), parsed.cmdId(),
                parsed.command());
        switch (outcome) {
            case CommandOutcome.Ok ok -> ctx.respondJson(200, JsonCodec.write(
                    new JsonCodec.JsonObj(List.of(
                            JsonCodec.JsonObj.entry("status", ApiJson.str("OK")),
                            JsonCodec.JsonObj.entry("receipt", receipt(ok))))));
            case CommandOutcome.Rejected rejected -> ApiJson.errorConflict(ctx,
                    rejected.errorCode(), rejected.message(), rejected.currentRevision());
            case CommandOutcome.StorageUnavailable unavailable -> ApiJson.error(ctx, 503,
                    "STORAGE_UNAVAILABLE", unavailable.message());
        }
    }

    private static JsonCodec.JsonObj receipt(CommandOutcome.Ok ok) {
        var receipt = ok.receipt();
        return new JsonCodec.JsonObj(List.of(
                JsonCodec.JsonObj.entry("cmdId", ApiJson.str(receipt.cmdId().toString())),
                JsonCodec.JsonObj.entry("admittedMs", ApiJson.num(receipt.admittedMs())),
                JsonCodec.JsonObj.entry("walSeq", ApiJson.num(receipt.walSeq())),
                JsonCodec.JsonObj.entry("revision", ApiJson.num(receipt.revision()))));
    }
}
