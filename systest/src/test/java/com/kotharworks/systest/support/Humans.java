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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.assertions.PlaywrightAssertions;

/**
 * JUnit 5 extension for human-mode tests. Add {@code @ExtendWith(Humans.class)}
 * and take a {@code Humans} parameter; call {@link #spawn(String)} once per
 * person in the scenario. One headless Chromium per test class, one isolated
 * browser context per person.
 *
 * <p>On failure each person's screenshot, page HTML, console log and a
 * Playwright trace ({@code npx playwright show-trace <zip>}) land in
 * {@code $SYSTEST_ARTIFACTS_DIR/<TestClass>/<method>/}. A passing test still
 * fails if any page raised an uncaught JavaScript error or saw a 5xx.
 */
public final class Humans implements BeforeAllCallback, AfterAllCallback,
        AfterTestExecutionCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(Humans.class);

    private Playwright playwright;
    private Browser browser;
    private final List<HumanPlayer> players = new ArrayList<>();

    @Override
    public void beforeAll(ExtensionContext context) {
        PlaywrightAssertions.setDefaultAssertionTimeout(SysEnv.eventually().toMillis());
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(!SysEnv.headed())
                .setSlowMo(SysEnv.slowMoMs()));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    /** A new person with a fresh, isolated browser profile. */
    public HumanPlayer spawn(String name) {
        BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 900)
                .setLocale("en-AU")
                .setTimezoneId("Australia/Brisbane"));
        ctx.setDefaultTimeout(SysEnv.eventually().toMillis());
        ctx.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true).setSnapshots(true).setSources(false).setTitle(name));
        HumanPlayer player = new HumanPlayer(name, ctx);
        players.add(player);
        return player;
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        boolean failed = context.getExecutionException().isPresent();
        Path dir = SysEnv.artifactsDir()
                .resolve(context.getRequiredTestClass().getSimpleName())
                .resolve(context.getRequiredTestMethod().getName());
        for (HumanPlayer player : players) {
            try {
                if (failed) {
                    capture(player, dir);
                } else {
                    player.context().tracing().stop();
                }
            } catch (RuntimeException | IOException e) {
                System.err.println("[systest] artifact capture failed for " + player.name() + ": " + e);
            }
        }
        if (!failed) {
            List<String> errors = new ArrayList<>();
            for (HumanPlayer player : players) {
                for (String error : player.pageErrors()) {
                    errors.add(player.name() + ": " + error);
                }
            }
            if (!errors.isEmpty()) {
                context.getStore(NS).put("jsErrors", errors);
            }
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        for (HumanPlayer player : players) {
            try {
                player.context().close();
            } catch (RuntimeException ignored) {
                // browser may already be gone after a crash; nothing to keep
            }
        }
        players.clear();
        @SuppressWarnings("unchecked")
        List<String> errors = context.getStore(NS).get("jsErrors", List.class);
        if (errors != null) {
            throw new AssertionError("browser reported errors during a passing scenario:\n  "
                    + String.join("\n  ", errors));
        }
    }

    private static void capture(HumanPlayer player, Path dir) throws IOException {
        Files.createDirectories(dir);
        String base = player.name().replaceAll("[^A-Za-z0-9_-]", "_");
        Page page = player.page();
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(dir.resolve(base + ".png")).setFullPage(true));
        Files.writeString(dir.resolve(base + ".html"), page.content(), StandardCharsets.UTF_8);
        List<String> log = new ArrayList<>();
        log.add("url: " + page.url());
        player.consoleMessages().forEach(m -> log.add("console " + m));
        player.pageErrors().forEach(m -> log.add("ERROR " + m));
        Files.write(dir.resolve(base + ".log"), log, StandardCharsets.UTF_8);
        player.context().tracing().stop(new Tracing.StopOptions()
                .setPath(dir.resolve(base + "-trace.zip")));
        System.err.println("[systest] failure artifacts for " + player.name() + " -> " + dir);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
            ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == Humans.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext,
            ExtensionContext extensionContext) {
        return this;
    }
}
