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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.kotharworks.auth.http.AccessPolicy;
import com.kotharworks.auth.http.AuthenticatedRouter;
import com.kotharworks.auth.http.AuthenticatedRouter.RouteContext;
import com.kotharworks.auth.http.CookieCodec;
import com.kotharworks.auth.principal.Principal;
import com.kotharworks.auth.spi.SessionRecord;
import com.kotharworks.wilds.observe.ActorDto;
import com.kotharworks.wilds.observe.OwnerObservation;
import com.kotharworks.wilds.observe.ObservationProjector;
import com.kotharworks.wilds.observe.PlaceDto;
import com.kotharworks.wilds.observe.WorldView;
import com.kotharworks.wilds.server.adapters.http.ApiJson;
import com.kotharworks.wilds.server.adapters.http.RouteGuard;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.ports.AuthStorePort;
import com.kotharworks.wilds.server.ports.SeatRecord;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import com.kotharworks.wilds.server.ports.WorldStorePort;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.worldgen.StubDescriptionComposer;

/**
 * The server-rendered structural console: {@code GET /play}.
 *
 * <p><strong>Deliberate deviation from a router-level {@code HUMAN_ONLY}
 * policy:</strong> {@code AuthenticatedRouter.doDispatch} rejects anonymous
 * callers with a 401 <em>before any handler runs</em>, which would make the
 * required browser redirect impossible. {@code /play} is therefore registered
 * {@link AccessPolicy#PUBLIC} and the human-only semantics are enforced right
 * here: anonymous browsers get a {@code 303} to {@code /auth/login} (which
 * itself chains to {@code /dev/login} in dev-local mode), scoped
 * {@code iw_bot_} tokens get {@code 403 FORBIDDEN}, and only a
 * {@link Principal.HumanPrincipal} reaches the renderer — the same pattern
 * {@code /auth/login} uses.
 *
 * <p>Views: unseated humans get a Claim Seat form (the page's JS fetches
 * {@code POST /api/v1/seats}); freshly chartered seats get a self-refreshing
 * "entering the world" page; seated humans get the console — header bar,
 * catch-up banner, fogged place &amp; topology panel, actor &amp; task queue
 * panel with {@code expectedRevision}-stamped controls, and the live SSE
 * event log. Every dynamic value renders through {@link Html#text(CharSequence)}
 * or escaped {@link Html.Attr} values, so stored HTML-rich seat names can
 * never break out of the document.
 */
public final class PlayRoutes {

    private static final int CATCH_UP_LIMIT = 50;
    private static final int LOG_LIMIT = 1_000;
    private static final int LOG_TAIL = 30;
    private static final int QUEUE_SLOTS = 5;

    private final AuthStorePort authStore;
    private final WorldStorePort worldStore;
    private final WorldHost worldHost;
    private final CookieCodec cookies;

    public PlayRoutes(AuthStorePort authStore, WorldStorePort worldStore, WorldHost worldHost,
            CookieCodec cookies) {
        this.authStore = Objects.requireNonNull(authStore, "authStore");
        this.worldStore = Objects.requireNonNull(worldStore, "worldStore");
        this.worldHost = Objects.requireNonNull(worldHost, "worldHost");
        this.cookies = Objects.requireNonNull(cookies, "cookies");
    }

    /** Registers the console route. */
    public void register(AuthenticatedRouter router) {
        router.register("GET", "/play", AccessPolicy.PUBLIC, ctx ->
                RouteGuard.run(ctx, this::play));
    }

    // ------------------------------------------------------------------ routing

    private void play(RouteContext ctx) throws IOException {
        switch (ctx.principal()) {
            case Principal.AnonymousPrincipal _ -> ctx.respondRedirect("/auth/login");
            case Principal.ScopedTokenPrincipal _ -> ApiJson.error(ctx, 403, "FORBIDDEN",
                    "the browser console is human-only; scoped tokens drive the REST API");
            case Principal.HumanPrincipal human -> render(ctx, human);
        }
    }

    private void render(RouteContext ctx, Principal.HumanPrincipal human) throws IOException {
        String csrfToken = csrfTokenFor(ctx);
        List<SeatRecord> seats = worldStore.findSeatsByOwner(human.userId());
        if (seats.isEmpty()) {
            respondHtml(ctx, unseatedPage(csrfToken));
            return;
        }
        SeatRecord seat = seats.getFirst();
        WorldView view = worldHost.view();
        OwnerRow owner = view.findOwnerBySeatKey(seat.seatId()).orElse(null);
        if (owner == null) {
            respondHtml(ctx, registeringPage(seat, csrfToken));
            return;
        }
        respondHtml(ctx, consolePage(ctx, human, seat, owner, view, csrfToken));
    }

    private static void respondHtml(RouteContext ctx, Html html) throws IOException {
        ctx.respondText(200, "text/html; charset=utf-8", html.render());
    }

    /** Resolves this browser session's CSRF token for form fields and the meta tag. */
    private String csrfTokenFor(RouteContext ctx) {
        return cookies.extractSessionCookie(ctx.exchange().getRequestHeaders().get("Cookie"))
                .map(CookieCodec::sha256Digest)
                .flatMap(authStore::findActiveSessionBySha256)
                .map(SessionRecord::csrfToken)
                .orElse("");
    }

    // ------------------------------------------------------------------ page scaffolding

    private static Html head(String title, String csrfToken, boolean selfRefresh) {
        Html html = Html.create().doctype()
                .open("html", new Html.Attr("lang", "en"))
                .open("head")
                .voidTag("meta", new Html.Attr("charset", "utf-8"))
                .voidTag("meta", new Html.Attr("name", "viewport"),
                        new Html.Attr("content", "width=device-width, initial-scale=1"));
        if (selfRefresh) {
            html.voidTag("meta", new Html.Attr("http-equiv", "refresh"),
                    new Html.Attr("content", "1"));
        }
        return html.el("title", new Html.Attr[0], "Ironwood Wilds — " + title)
                .voidTag("meta", new Html.Attr("name", "csrf-token"),
                        new Html.Attr("content", csrfToken))
                .voidTag("link", new Html.Attr("rel", "stylesheet"),
                        new Html.Attr("href", "/assets/wilds.css"))
                .open("script", new Html.Attr("src", "/assets/wilds-ui.js"),
                        new Html.Attr("defer", "defer"))
                .close("script")
                .close("head");
    }

    private static Html signOutForm(Html html, String csrfToken) {
        return html.open("form", new Html.Attr("method", "post"),
                        new Html.Attr("action", "/auth/logout"))
                .voidTag("input", new Html.Attr("type", "hidden"),
                        new Html.Attr("name", "csrf_token"), new Html.Attr("value", csrfToken))
                .voidTag("input", new Html.Attr("type", "submit"),
                        new Html.Attr("value", "Sign Out"))
                .close("form");
    }

    private static Html noticeArea(Html html) {
        return html.open("div", new Html.Attr("id", "notice"), new Html.Attr("hidden", "hidden"))
                .close("div");
    }

    // ------------------------------------------------------------------ unseated view

    private Html unseatedPage(String csrfToken) {
        return head("Claim Seat", csrfToken, false)
                .open("body")
                .open("header", new Html.Attr("class", "console-bar"))
                .open("h1").text("Ironwood Wilds").close("h1")
                .close("header")
                .open("div", new Html.Attr("class", "panel"))
                .open("h2").text("Claim Seat").close("h2")
                .open("p").text("You are signed in but hold no seat in the world yet. "
                        + "Charter one to enter the wilds.").close("p")
                .open("form", new Html.Attr("id", "claim-seat-form"))
                .voidTag("input", new Html.Attr("type", "hidden"),
                        new Html.Attr("name", "csrf_token"), new Html.Attr("value", csrfToken))
                .voidTag("input", new Html.Attr("type", "hidden"),
                        new Html.Attr("name", "controllerKind"), new Html.Attr("value", "HUMAN"))
                .open("label", new Html.Attr("for", "seat-name"))
                .text("Seat name (3..40 characters)").close("label")
                .voidTag("input", new Html.Attr("type", "text"), new Html.Attr("id", "seat-name"),
                        new Html.Attr("name", "seatName"), new Html.Attr("minlength", "3"),
                        new Html.Attr("maxlength", "40"), new Html.Attr("required", "required"))
                .voidTag("input", new Html.Attr("type", "submit"),
                        new Html.Attr("value", "Claim Seat"))
                .close("form")
                .close("div")
                .open("p")
                .open("a", new Html.Attr("href", "/chronicle")).text("Public chronicle")
                .close("a")
                .close("p")
                .close("body")
                .close("html");
    }

    // ------------------------------------------------------------------ registering view

    private Html registeringPage(SeatRecord seat, String csrfToken) {
        return head("Entering", csrfToken, true)
                .open("body")
                .open("header", new Html.Attr("class", "console-bar"))
                .open("h1").text("Ironwood Wilds").close("h1")
                .close("header")
                .open("div", new Html.Attr("class", "panel"))
                .open("p").text("Seat \"" + seat.seatName()
                        + "\" is entering the world — this page refreshes itself.")
                .close("p")
                .close("div")
                .close("body")
                .close("html");
    }

    // ------------------------------------------------------------------ seated console

    private Html consolePage(RouteContext ctx, Principal.HumanPrincipal human, SeatRecord seat,
            OwnerRow owner, WorldView view, String csrfToken) throws IOException {
        OwnerObservation observation = ObservationProjector.projectOwner(view, owner.id(),
                StubDescriptionComposer.INSTANCE);
        ActorDto actor = observation.actors().getFirst();
        Map<String, String> placeLabels = new HashMap<>();
        for (PlaceDto place : observation.places()) {
            placeLabels.put(place.id().toWire(), place.label());
        }

        Html html = head(seat.seatName(), csrfToken, false)
                .open("body", new Html.Attr("class", "console"),
                        new Html.Attr("data-world-id", String.valueOf(worldHost.worldId())),
                        new Html.Attr("data-seat-id", String.valueOf(seat.seatId())),
                        new Html.Attr("data-actor-id", actor.id().toWire()),
                        new Html.Attr("data-expected-revision", String.valueOf(actor.revision())),
                        new Html.Attr("data-place-id", actor.placeId().toWire()),
                        new Html.Attr("data-server-now-ms", String.valueOf(view.nowMs())));
        headerBar(html, human, seat, view, csrfToken);
        noticeArea(html);
        catchUpBanner(ctx, html, seat);
        html.open("div", new Html.Attr("class", "panel"), new Html.Attr("id", "away-panel"),
                        new Html.Attr("hidden", "hidden"))
                .open("h2").text("While You Were Away").close("h2")
                .open("ul", new Html.Attr("id", "away-list")).close("ul")
                .close("div");
        placePanel(html, actor, observation, placeLabels);
        actorPanel(html, actor, view.nowMs());
        eventLogPanel(html, seat);
        return html.open("div", new Html.Attr("id", "drawer"), new Html.Attr("hidden", "hidden"))
                .open("button", new Html.Attr("id", "drawer-close"),
                        new Html.Attr("type", "button"))
                .text("Close")
                .close("button")
                .close("div")
                .close("body")
                .close("html");
    }

    private void headerBar(Html html, Principal.HumanPrincipal human, SeatRecord seat,
            WorldView view, String csrfToken) {
        // No title concept exists on WorldRecord (seed + version triple only), so the
        // header line carries world id/seed/clock instead of inventing a new field.
        html.open("header", new Html.Attr("class", "console-bar"))
                .open("h1").text("Ironwood Wilds").close("h1")
                .open("span", new Html.Attr("class", "meta"))
                .text("Seat: " + seat.seatName())
                .text(" — world " + worldHost.worldId() + ", seed " + view.seed().value()
                        + ", nowMs " + view.nowMs())
                .text(" — " + human.displayName())
                .close("span");
        signOutForm(html, csrfToken);
        html.close("header");
    }

    private void catchUpBanner(RouteContext ctx, Html html, SeatRecord seat) throws IOException {
        Map<String, String> query = ApiJson.query(ctx);
        long since = ApiJson.queryLong(query, "since", 0);
        if (since <= 0) {
            return;
        }
        List<StoredWorldEvent> missed = worldStore.readEventsSince(worldHost.worldId(),
                seat.seatId(), since, CATCH_UP_LIMIT);
        html.open("div", new Html.Attr("class", "panel"))
                .open("h2").text("While You Were Away").close("h2");
        if (missed.isEmpty()) {
            html.open("p").text("No events since cursor " + since + ".").close("p");
        } else {
            html.open("ul", new Html.Attr("id", "catch-up"));
            for (StoredWorldEvent event : missed) {
                html.open("li").text("#" + event.cursor() + " [" + event.kind() + "] ")
                        .text(summaryOf(event)).close("li");
            }
            html.close("ul")
                    .open("p")
                    .open("a", new Html.Attr("href", "/play"))
                    .text("Back to the live console")
                    .close("a")
                    .close("p");
        }
        html.close("div");
    }

    private static void placePanel(Html html, ActorDto actor, OwnerObservation observation,
            Map<String, String> placeLabels) {
        PlaceDto current = observation.places().stream()
                .filter(place -> place.id().equals(actor.placeId()))
                .findFirst()
                .orElse(null);
        String currentLabel = current != null ? current.label() : actor.placeId().toWire();
        List<PlaceDto.EdgeDto> edges = current != null ? current.edges() : List.of();

        html.open("div", new Html.Attr("class", "panel"))
                .open("h2").text("Current Place & Fogged Topology").close("h2")
                .open("p").text("You stand at ")
                .open("strong", new Html.Attr("id", "place-label")).text(currentLabel)
                .close("strong")
                .close("p")
                .open("p", new Html.Attr("id", "place-look")).text(actor.look()).close("p");
        html.open("button", new Html.Attr("type", "button"),
                        new Html.Attr("data-examine-id", actor.placeId().toWire()))
                .text("Examine")
                .close("button");
        html.open("div", new Html.Attr("id", "travel"), new Html.Attr("class", "travel-list"));
        for (PlaceDto.EdgeDto edge : edges) {
            String wire = edge.to().toWire();
            html.open("button", new Html.Attr("type", "button"),
                            new Html.Attr("class", "cmd"),
                            new Html.Attr("data-cmd", "travel"),
                            new Html.Attr("data-destination", wire),
                            new Html.Attr("data-expected-revision",
                                    String.valueOf(actor.revision())))
                    .text("Travel → " + placeLabels.getOrDefault(wire, wire)
                            + " (" + durationText(edge.travelDurationMs()) + ")")
                    .close("button");
        }
        html.close("div").close("div");
    }

    private static void actorPanel(Html html, ActorDto actor, long nowMs) {
        String revision = String.valueOf(actor.revision());
        html.open("div", new Html.Attr("class", "panel"))
                .open("h2").text("Actor & Task Queue").close("h2")
                .open("dl", new Html.Attr("class", "place-facts"))
                .open("dt").text("Actor").close("dt")
                .open("dd").text(actor.id().toWire()).close("dd")
                .open("dt").text("Revision").close("dt")
                .open("dd")
                .open("span", new Html.Attr("id", "revision"),
                        new Html.Attr("data-expected-revision", revision))
                .text(revision)
                .close("span")
                .close("dd")
                .open("dt").text("Energy").close("dt")
                .open("dd")
                .open("span", new Html.Attr("id", "energy")).text(percentText(
                        actor.energyMilliAtNow())).close("span")
                .close("dd")
                .open("dt").text("Fail Policy").close("dt")
                .open("dd").text(actor.failPolicy().name()).close("dd")
                .close("dl");

        ActiveTask task = actor.activeTask();
        if (task != null) {
            html.open("div", new Html.Attr("id", "task-progress"),
                            new Html.Attr("data-started-ms", String.valueOf(task.startedAtMs())),
                            new Html.Attr("data-due-ms", String.valueOf(task.dueAtMs())),
                            new Html.Attr("data-server-now-ms", String.valueOf(nowMs)))
                    .open("div", new Html.Attr("id", "task-progress-fill")).close("div")
                    .open("span", new Html.Attr("id", "task-name"))
                    .text(activeTaskText(task))
                    .close("span")
                    .close("div");
        } else {
            html.open("div", new Html.Attr("id", "task-progress"),
                            new Html.Attr("hidden", "hidden"))
                    .open("div", new Html.Attr("id", "task-progress-fill")).close("div")
                    .open("span", new Html.Attr("id", "task-name"))
                    .text("Idle — no active task.")
                    .close("span")
                    .close("div");
        }

        html.open("h2").text("Pending Queue").close("h2")
                .open("ul", new Html.Attr("id", "queue"));
        for (int slot = 0; slot < QUEUE_SLOTS; slot++) {
            html.open("li").text(slot < actor.pendingQueue().size()
                    ? slot + ": " + specText(actor.pendingQueue().get(slot))
                    : slot + ": (empty)").close("li");
        }
        html.close("ul")
                .open("div", new Html.Attr("class", "controls"));
        html.open("button", new Html.Attr("type", "button"), new Html.Attr("class", "cmd"),
                        new Html.Attr("data-cmd", "wait5s"),
                        new Html.Attr("data-expected-revision", revision))
                .text("Wait 5s")
                .close("button");
        html.open("button", new Html.Attr("type", "button"), new Html.Attr("class", "cmd"),
                        new Html.Attr("data-cmd", "cancel-pending"),
                        new Html.Attr("data-expected-revision", revision))
                .text("Cancel Pending")
                .close("button");
        html.open("button", new Html.Attr("type", "button"), new Html.Attr("class", "cmd"),
                        new Html.Attr("data-cmd", "cancel-active"),
                        new Html.Attr("data-expected-revision", revision))
                .text("Cancel Active")
                .close("button");
        html.open("button", new Html.Attr("type", "button"), new Html.Attr("class", "cmd"),
                        new Html.Attr("id", "fail-policy-btn"),
                        new Html.Attr("data-cmd", "fail-policy"),
                        new Html.Attr("data-policy", actor.failPolicy().name()),
                        new Html.Attr("data-expected-revision", revision))
                .text("Fail Policy: " + actor.failPolicy().name())
                .close("button");
        html.close("div").close("div");
    }

    private void eventLogPanel(Html html, SeatRecord seat) {
        List<StoredWorldEvent> rows = worldStore.readEventsSince(worldHost.worldId(),
                seat.seatId(), 0, LOG_LIMIT);
        List<StoredWorldEvent> tail = rows.subList(Math.max(0, rows.size() - LOG_TAIL),
                rows.size());
        long lastCursor = rows.isEmpty() ? 0 : rows.getLast().cursor();
        html.open("div", new Html.Attr("class", "panel"))
                .open("h2").text("Live Event Log").close("h2")
                .open("ul", new Html.Attr("id", "event-log"),
                        new Html.Attr("data-last-cursor", String.valueOf(lastCursor)));
        for (StoredWorldEvent event : tail) {
            html.open("li", new Html.Attr("data-cursor", String.valueOf(event.cursor())))
                    .text("#" + event.cursor() + " [" + event.kind() + "] ")
                    .text(summaryOf(event))
                    .close("li");
        }
        html.close("ul").close("div");
    }

    // ------------------------------------------------------------------ formatting helpers

    /** Private detail first; public summary as the fallback; empty when both are null. */
    private static String summaryOf(StoredWorldEvent event) {
        if (event.privateSummary() != null) {
            return event.privateSummary();
        }
        return event.publicSummary() != null ? event.publicSummary() : "";
    }

    /** Integer-only milli display: 98_400 -> "98.4%". */
    private static String percentText(long energyMilli) {
        long tenths = energyMilli / 100;
        return tenths / 10 + "." + tenths % 10 + "%";
    }

    /** Integer-only duration display: 5_200 -> "5.2s". */
    private static String durationText(long durationMs) {
        return durationMs / 1000 + "." + (durationMs % 1000) / 100 + "s";
    }

    private static String specText(TaskSpec spec) {
        return switch (spec) {
            case TaskSpec.Wait(long durationMs, int batches) ->
                    "Wait " + durationMs + "ms × " + batches;
            case TaskSpec.Travel(var destination) -> "Travel → " + destination.toWire();
        };
    }

    private static String activeTaskText(ActiveTask task) {
        return specText(task.spec()) + " (batches left: " + task.batchesLeft() + ")";
    }
}
