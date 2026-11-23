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
import java.util.Objects;
import java.util.UUID;

import com.kotharworks.auth.http.AccessPolicy;
import com.kotharworks.auth.http.AuthenticatedRouter;
import com.kotharworks.auth.http.AuthenticatedRouter.RouteContext;
import com.kotharworks.auth.principal.Principal;
import com.kotharworks.auth.token.ApiTokenCodec;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.observe.WorldView;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.server.domain.CommandOutcome;
import com.kotharworks.wilds.server.domain.ControllerKind;
import com.kotharworks.wilds.server.domain.SeatAuthorizationException;
import com.kotharworks.wilds.server.domain.SeatAuthorizer;
import com.kotharworks.wilds.server.domain.SeatCharter;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.ports.AuthStorePort;
import com.kotharworks.wilds.server.ports.CreatedToken;
import com.kotharworks.wilds.server.ports.SeatRecord;
import com.kotharworks.wilds.server.ports.UserRecord;
import com.kotharworks.wilds.server.ports.WorldStorePort;

/**
 * Seat routes: chartering ({@code POST /api/v1/seats} — the only path into the
 * world for registration, submitted as a {@code RegisterOwner} command through
 * {@code WorldHost} with the deterministic {@link SeatCharter#registerCmdId}
 * {@code cmdId} that an archive rollover's seat carry-over also uses) and {@code iw_bot_}
 * token minting/revocation for non-HUMAN seats ({@code ROLE_RESEARCHER}, seat
 * must be owned by the caller regardless of role).
 */
public final class SeatRoutes {

    private static final String SEAT_NAMESPACE = SeatAuthorizer.SEAT_NAMESPACE;

    private final AuthStorePort authStore;
    private final WorldStorePort worldStore;
    private final WorldHost worldHost;
    private final SeatAuthorizer seatAuthorizer;
    private final ApiTokenCodec apiTokenCodec;
    private final Object charterLock = new Object();

    public SeatRoutes(AuthStorePort authStore, WorldStorePort worldStore, WorldHost worldHost,
            SeatAuthorizer seatAuthorizer, ApiTokenCodec apiTokenCodec) {
        this.authStore = Objects.requireNonNull(authStore, "authStore");
        this.worldStore = Objects.requireNonNull(worldStore, "worldStore");
        this.worldHost = Objects.requireNonNull(worldHost, "worldHost");
        this.seatAuthorizer = Objects.requireNonNull(seatAuthorizer, "seatAuthorizer");
        this.apiTokenCodec = Objects.requireNonNull(apiTokenCodec, "apiTokenCodec");
    }

    /** Registers the seat routes. */
    public void register(AuthenticatedRouter router) {
        router.register("POST", "/api/v1/seats", AccessPolicy.HUMAN_ONLY, ctx ->
                RouteGuard.run(ctx, this::charter));
        router.register("POST", "/api/v1/seats/{seatId}/tokens", AccessPolicy.ROLE_RESEARCHER,
                ctx -> RouteGuard.run(ctx, this::mintToken));
        router.register("DELETE", "/api/v1/seats/{seatId}/tokens/{tokenId}",
                AccessPolicy.ROLE_RESEARCHER, ctx -> RouteGuard.run(ctx, this::revokeToken));
    }

    // ------------------------------------------------------------------ chartering

    private void charter(RouteContext ctx) throws IOException {
        Principal.HumanPrincipal human = (Principal.HumanPrincipal) ctx.principal();
        JsonCodec.JsonObj body = ApiJson.parseObject(ctx.requestBody());
        String trimmed = ApiJson.validatePrintable(
                ApiJson.requireString(body, "seatName"), "seatName", 3, 40);
        ControllerKind kind = parseKind(ApiJson.requireString(body, "controllerKind"));
        String suppliedActorLabel = ApiJson.optString(body, "initialActorLabel");
        String label = suppliedActorLabel != null && !suppliedActorLabel.isBlank()
                ? ApiJson.validatePrintable(suppliedActorLabel, "initialActorLabel", 1, 80)
                : SeatCharter.defaultActorLabel(trimmed);
        UserRecord user = authStore.findUser(human.userId())
                .orElseGet(() -> UserRecord.active(human.userId(), human.displayName(), null));

        // Charter critical section: idempotent same-name lookup precedes quota check so
        // a PLAYER retrying after a transient 503 WorldHost commit failure can complete
        // registration cleanly without tripping their 1-seat cap.
        SeatRecord seat;
        boolean fresh;
        synchronized (charterLock) {
            List<SeatRecord> existing = worldStore.findSeatsByOwner(human.userId());
            seat = existing.stream()
                    .filter(candidate -> candidate.seatName().equals(trimmed))
                    .findFirst()
                    .orElse(null);
            fresh = seat == null;
            if (!fresh && seat.controllerKind() != kind) {
                throw new SeatAuthorizationException(409, "DUPLICATE_SEAT",
                        "seat \"" + trimmed + "\" is already chartered with kind "
                                + seat.controllerKind());
            }
            if (fresh) {
                seatAuthorizer.canCharter(human, kind, existing.size(), user.maxSeatsOverride());
                try {
                    seat = worldStore.createSeat(human.userId(), trimmed, kind);
                } catch (IllegalArgumentException e) {
                    if (e.getMessage() != null && e.getMessage().startsWith("DUPLICATE_SEAT")) {
                        throw new SeatAuthorizationException(409, "DUPLICATE_SEAT",
                                "seat name \"" + trimmed + "\" is already chartered");
                    }
                    throw e;
                }
            }
        }
        UUID cmdId = SeatCharter.registerCmdId(worldHost.worldId(), seat.seatId());
        CommandOutcome outcome = worldHost.submit(seat.seatId(), cmdId,
                new WorldCommand.RegisterOwner(seat.seatId(), seat.seatName(), label));
        switch (outcome) {
            case CommandOutcome.Ok ok -> respondChartered(ctx, seat, ok.receipt().cmdId(), fresh);
            case CommandOutcome.Rejected rejected -> ApiJson.errorConflict(ctx,
                    rejected.errorCode(), rejected.message(), rejected.currentRevision());
            case CommandOutcome.StorageUnavailable unavailable -> ApiJson.error(ctx, 503,
                    "STORAGE_UNAVAILABLE", unavailable.message());
        }
    }

    private void respondChartered(RouteContext ctx, SeatRecord seat, UUID cmdId, boolean fresh)
            throws IOException {
        WorldView view = worldHost.view();
        var ownerRow = view.findOwnerBySeatKey(seat.seatId()).orElse(null);
        String ownerId = ownerRow == null ? null : ownerRow.id().toWire();
        String actorId = ownerRow == null || view.actorsOwnedBy(ownerRow.id()).isEmpty()
                ? null
                : view.actorsOwnedBy(ownerRow.id()).getFirst().id().toWire();
        List<JsonCodec.JsonObj.Entry> entries = new ArrayList<>();
        entries.add(JsonCodec.JsonObj.entry("seatId", ApiJson.num(seat.seatId())));
        entries.add(JsonCodec.JsonObj.entry("seatName", ApiJson.str(seat.seatName())));
        entries.add(JsonCodec.JsonObj.entry("controllerKind",
                ApiJson.str(seat.controllerKind().name())));
        entries.add(JsonCodec.JsonObj.entry("ownerId", ownerId == null
                ? JsonCodec.JsonNull.INSTANCE
                : ApiJson.str(ownerId)));
        entries.add(JsonCodec.JsonObj.entry("actorId", actorId == null
                ? JsonCodec.JsonNull.INSTANCE
                : ApiJson.str(actorId)));
        entries.add(JsonCodec.JsonObj.entry("cmdId", ApiJson.str(cmdId.toString())));
        ctx.respondJson(fresh ? 201 : 200, JsonCodec.write(new JsonCodec.JsonObj(entries)));
    }

    private static ControllerKind parseKind(String value) {
        try {
            return ControllerKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "controllerKind must be HUMAN|SCRIPT_BOT|GENETIC_AGENT, got: \""
                            + value + "\"");
        }
    }

    // ------------------------------------------------------------------ bot tokens

    private void mintToken(RouteContext ctx) throws IOException {
        Principal.HumanPrincipal human = (Principal.HumanPrincipal) ctx.principal();
        long seatId = ApiJson.pathLong(ctx.pathParams(), "seatId");
        SeatRecord seat = requireOwnedSeat(seatId, human);
        if (seat.controllerKind() == ControllerKind.HUMAN) {
            throw new SeatAuthorizationException(403, "FORBIDDEN",
                    "HUMAN seats are played in the browser; bot tokens are only minted for "
                            + "SCRIPT_BOT/GENETIC_AGENT seats");
        }
        String label = "seat:" + seatId + " bot token";
        if (ctx.requestBody().length > 0) {
            JsonCodec.JsonObj body = ApiJson.parseObject(ctx.requestBody());
            String supplied = ApiJson.optString(body, "label");
            if (supplied != null && !supplied.isBlank()) {
                label = ApiJson.validatePrintable(supplied, "label", 1, 80);
            }
        }
        ApiTokenCodec.GeneratedToken generated = apiTokenCodec.generate();
        CreatedToken created = authStore.createToken(human.userId(), SEAT_NAMESPACE, seatId,
                label, generated.token(), generated.prefix(), generated.sha256());
        ctx.respondJson(201, JsonCodec.write(new JsonCodec.JsonObj(List.of(
                JsonCodec.JsonObj.entry("tokenId", ApiJson.num(created.tokenId())),
                JsonCodec.JsonObj.entry("token", ApiJson.str(created.rawToken())),
                JsonCodec.JsonObj.entry("prefix", ApiJson.str(created.prefix()))))));
    }

    private void revokeToken(RouteContext ctx) throws IOException {
        Principal.HumanPrincipal human = (Principal.HumanPrincipal) ctx.principal();
        long seatId = ApiJson.pathLong(ctx.pathParams(), "seatId");
        long tokenId = ApiJson.pathLong(ctx.pathParams(), "tokenId");
        SeatRecord seat = requireOwnedSeat(seatId, human);
        if (!authStore.revokeToken(human.userId(), SEAT_NAMESPACE, seat.seatId(), tokenId)) {
            throw new SeatAuthorizationException(404, "UNKNOWN_TARGET",
                    "no token " + tokenId + " on seat " + seatId);
        }
        ctx.respondJson(200, "{\"revoked\":true}");
    }

    private SeatRecord requireOwnedSeat(long seatId, Principal.HumanPrincipal human) {
        SeatRecord seat = worldStore.findSeatById(seatId)
                .orElseThrow(() -> new SeatAuthorizationException(404, "UNKNOWN_TARGET",
                        "no seat " + seatId));
        if (!seat.ownerUserId().equals(human.userId())) {
            throw new SeatAuthorizationException(403, "FORBIDDEN",
                    "seat " + seatId + " is not owned by this account");
        }
        return seat;
    }
}
