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
package com.kotharworks.wilds.server.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.kotharworks.auth.principal.GlobalRole;
import com.kotharworks.auth.principal.Principal;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.observe.WorldView;
import com.kotharworks.wilds.server.ports.AuthStorePort;
import com.kotharworks.wilds.server.ports.SeatRecord;
import com.kotharworks.wilds.server.ports.UserRecord;
import com.kotharworks.wilds.server.ports.WorldStorePort;

/**
 * The three-layer authorization bridge: {@code libs/auth} {@link Principal}
 * &rarr; permanent {@link SeatRecord} &rarr; in-world {@code OwnerId}. Enforces
 * seat ownership (humans) and {@code wilds:seat} resource scoping (bot tokens),
 * applies the per-seat token-bucket rate limit, and answers the charter quota
 * policy ({@code PLAYER}: one HUMAN seat; {@code RESEARCHER}/{@code ADMIN}:
 * non-HUMAN kinds and up to 25 seats).
 */
public final class SeatAuthorizer {

    /** Resource namespace every wilds bot token is scoped to. */
    public static final String SEAT_NAMESPACE = "wilds:seat";

    private static final int DEFAULT_HUMAN_SEATS = 1;
    private static final int DEFAULT_RESEARCHER_SEATS = 25;

    private final AuthStorePort authStore;
    private final WorldStorePort worldStore;
    private final SeatRateLimiter rateLimiter;

    /** The resolved seat plus its in-world owner (null until the seat registered). */
    public record AuthorizedSeat(SeatRecord seat, OwnerId owner) {

        public AuthorizedSeat {
            Objects.requireNonNull(seat, "seat");
        }

        /** The in-world owner id, or empty while the seat has not registered. */
        public Optional<OwnerId> ownerOrNull() {
            return Optional.ofNullable(owner);
        }
    }

    public SeatAuthorizer(AuthStorePort authStore, WorldStorePort worldStore,
            SeatRateLimiter rateLimiter) {
        this.authStore = Objects.requireNonNull(authStore, "authStore");
        this.worldStore = Objects.requireNonNull(worldStore, "worldStore");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
    }

    /**
     * Resolves the caller's target seat and rate-limits it.
     *
     * @param seatIdParam explicit {@code ?seatId=} target (required to disambiguate
     *                    a multi-seat owner; must match the bot token's scope)
     * @param view        the current lock-free world view (for owner resolution)
     * @throws SeatAuthorizationException 401 (anonymous), 400 (ambiguous seats),
     *                                    403 (ownership/scope violation), 429
     *                                    (rate limited)
     */
    public AuthorizedSeat authorize(Principal principal, Long seatIdParam, WorldView view) {
        SeatRecord seat = switch (principal) {
            case Principal.AnonymousPrincipal _ -> throw new SeatAuthorizationException(401,
                    "AUTHENTICATION_REQUIRED", "authentication required");
            case Principal.HumanPrincipal human -> resolveHumanSeat(human, seatIdParam);
            case Principal.ScopedTokenPrincipal token -> resolveScopedSeat(token, seatIdParam);
        };
        if (!rateLimiter.tryAcquire(seat.seatId())) {
            throw new SeatAuthorizationException(429, "RATE_LIMITED",
                    "seat " + seat.seatId() + " exceeded the request rate limit");
        }
        OwnerId owner = view == null ? null
                : view.findOwnerBySeatKey(seat.seatId()).map(row -> row.id()).orElse(null);
        return new AuthorizedSeat(seat, owner);
    }

    private SeatRecord resolveHumanSeat(Principal.HumanPrincipal human, Long seatIdParam) {
        List<SeatRecord> seats = worldStore.findSeatsByOwner(human.userId());
        if (seatIdParam != null) {
            SeatRecord seat = seats.stream()
                    .filter(candidate -> candidate.seatId() == seatIdParam)
                    .findFirst()
                    .orElseThrow(() -> new SeatAuthorizationException(403, "FORBIDDEN",
                            "seat " + seatIdParam + " is not owned by this account"));
            return seat;
        }
        if (seats.isEmpty()) {
            throw new SeatAuthorizationException(403, "FORBIDDEN",
                    "no seat chartered for this account; POST /api/v1/seats first");
        }
        if (seats.size() > 1) {
            throw new SeatAuthorizationException(400, "SEAT_REQUIRED",
                    "account owns " + seats.size() + " seats; pass ?seatId=");
        }
        return seats.getFirst();
    }

    private SeatRecord resolveScopedSeat(Principal.ScopedTokenPrincipal token, Long seatIdParam) {
        if (!SEAT_NAMESPACE.equals(token.resourceNamespace())) {
            throw new SeatAuthorizationException(403, "FORBIDDEN",
                    "token namespace \"" + token.resourceNamespace() + "\" is not "
                            + SEAT_NAMESPACE);
        }
        if (seatIdParam != null && seatIdParam != token.scopedResourceId()) {
            throw new SeatAuthorizationException(403, "FORBIDDEN",
                    "token is scoped to seat " + token.scopedResourceId() + ", not seat "
                            + seatIdParam);
        }
        SeatRecord seat = worldStore.findSeatById(token.scopedResourceId())
                .orElseThrow(() -> new SeatAuthorizationException(403, "FORBIDDEN",
                        "token's seat " + token.scopedResourceId() + " no longer exists"));
        if (!seat.ownerUserId().equals(token.ownerUserId())) {
            throw new SeatAuthorizationException(403, "FORBIDDEN",
                    "token owner no longer owns seat " + seat.seatId());
        }
        return seat;
    }

    /**
     * The charter quota policy: a plain {@code PLAYER} may hold one
     * {@link ControllerKind#HUMAN} seat (or up to {@code maxSeatsOverride});
     * every other kind, or any further seat, requires {@code RESEARCHER} or
     * {@code ADMIN} and stays under the researcher cap.
     *
     * @throws SeatAuthorizationException 403 {@code FORBIDDEN} on violation
     */
    public void canCharter(Principal.HumanPrincipal principal, ControllerKind kind,
            int existingSeats, Integer maxSeatsOverride) {
        int humanCap = maxSeatsOverride != null ? maxSeatsOverride : DEFAULT_HUMAN_SEATS;
        if (kind == ControllerKind.HUMAN && existingSeats < humanCap) {
            return;
        }
        boolean elevated = principal.hasRole(GlobalRole.RESEARCHER)
                || principal.hasRole(GlobalRole.ADMIN);
        int elevatedCap = maxSeatsOverride != null ? maxSeatsOverride : DEFAULT_RESEARCHER_SEATS;
        if (!elevated || existingSeats >= elevatedCap) {
            throw new SeatAuthorizationException(403, "FORBIDDEN",
                    "chartering a " + kind + " seat (seat " + (existingSeats + 1)
                            + ") requires the RESEARCHER or ADMIN role within cap "
                            + elevatedCap);
        }
    }

    /** The charter cap for a user: their override or the researcher default. */
    public int charterCap(UserRecord user) {
        return user.maxSeatsOverride() != null ? user.maxSeatsOverride() : DEFAULT_RESEARCHER_SEATS;
    }

    /** Human cap with override applied (test convenience). */
    public int humanCap(UserRecord user) {
        return user.maxSeatsOverride() != null ? user.maxSeatsOverride() : DEFAULT_HUMAN_SEATS;
    }
}
