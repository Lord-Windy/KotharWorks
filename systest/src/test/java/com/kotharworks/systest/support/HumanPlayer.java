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
package com.kotharworks.systest.support;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;

/**
 * Human mode: one emulated person in their own isolated browser context
 * (separate cookies, localStorage and SSE connections — a different machine,
 * as far as the server can tell). Interactions go through the real SSR pages
 * and {@code wilds-ui.js}: the player clicks, types with per-key delays and
 * pauses to "think", with a seeded RNG so a failing run replays identically.
 *
 * <p>Uncaught page errors and failed same-origin requests are recorded; the
 * {@link Humans} extension fails the test if any page threw.
 */
public final class HumanPlayer {

    private final String name;
    private final BrowserContext context;
    private final Page page;
    private final Random rng;
    private final List<String> console = new CopyOnWriteArrayList<>();
    private final List<String> pageErrors = new CopyOnWriteArrayList<>();

    HumanPlayer(String name, BrowserContext context) {
        this.name = name;
        this.context = context;
        this.page = context.newPage();
        this.rng = new Random(name.hashCode());
        page.onConsoleMessage(msg -> console.add(msg.type() + ": " + msg.text()));
        page.onPageError(error -> pageErrors.add(error));
        page.onResponse(response -> {
            if (response.status() >= 500) {
                pageErrors.add("HTTP " + response.status() + " " + response.url());
            }
        });
    }

    public String name() {
        return name;
    }

    /** Raw Playwright page for anything the helpers do not cover. */
    public Page page() {
        return page;
    }

    public BrowserContext context() {
        return context;
    }

    // ------------------------------------------------------------------ actions

    /** Navigates to a server path, e.g. {@code /chronicle}. */
    public HumanPlayer open(String path) {
        page.navigate(SysEnv.baseUrl() + path);
        return this;
    }

    /** Moves to, hesitates over, then clicks the element. */
    public HumanPlayer click(String selector) {
        Locator target = page.locator(selector).first();
        target.hover();
        think();
        target.click();
        return this;
    }

    /** Clicks the button/link whose visible text contains {@code text}. */
    public HumanPlayer clickText(String text) {
        Locator target = page.getByText(text).first();
        target.hover();
        think();
        target.click();
        return this;
    }

    /** Focuses the field and types {@code text} one key at a time. */
    public HumanPlayer type(String selector, String text) {
        Locator field = page.locator(selector).first();
        field.click();
        field.fill("");
        for (char c : text.toCharArray()) {
            field.pressSequentially(String.valueOf(c),
                    new Locator.PressSequentiallyOptions().setDelay(20 + rng.nextInt(60)));
        }
        return this;
    }

    /** Short human-scale pause between actions (40..220 ms). */
    public void think() {
        page.waitForTimeout(40 + rng.nextInt(180));
    }

    // ------------------------------------------------------------------ expectations

    /** Eventually the page shows {@code text} somewhere in the body. */
    public HumanPlayer sees(String text) {
        assertThat(page.locator("body")).containsText(text);
        return this;
    }

    /** Eventually the element matching {@code selector} contains {@code text}. */
    public HumanPlayer sees(String selector, String text) {
        assertThat(page.locator(selector).first()).containsText(text);
        return this;
    }

    /** Eventually the current URL path equals {@code path} (query ignored). */
    public HumanPlayer isOn(String path) {
        page.waitForURL(url -> java.net.URI.create(url).getPath().equals(path));
        return this;
    }

    /** The {@code data-*} attribute on {@code <body>} (seat, actor, revision...). */
    public String bodyData(String name) {
        return page.locator("body").getAttribute("data-" + name);
    }

    /** The raw value of this person's session cookie, if logged in. */
    public String sessionCookie() {
        return context.cookies().stream()
                .filter(c -> c.name.equals(Api.DEV_SESSION_COOKIE))
                .map(c -> c.value)
                .findFirst()
                .orElse(null);
    }

    /** An {@link Api} sharing this person's login — "the same human in a second tab". */
    public Api api() {
        String cookie = sessionCookie();
        if (cookie == null) {
            throw new AssertionError(name + " is not logged in");
        }
        return Api.resumeSession(cookie);
    }

    /** Restores a session cookie (e.g. one recorded before a server restart). */
    public void restoreSession(String rawCookie) {
        Cookie cookie = new Cookie(Api.DEV_SESSION_COOKIE, rawCookie);
        cookie.setUrl(SysEnv.baseUrl());
        context.addCookies(List.of(cookie));
    }

    // ------------------------------------------------------------------ diagnostics

    public List<String> consoleMessages() {
        return List.copyOf(console);
    }

    public List<String> pageErrors() {
        return List.copyOf(pageErrors);
    }
}
