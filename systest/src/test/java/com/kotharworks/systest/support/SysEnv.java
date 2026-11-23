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

import java.nio.file.Path;
import java.time.Duration;

/**
 * Environment knobs for the live suite. Every value has a default that matches
 * {@code systest/compose.yaml}, so tests never hard-code the stack layout.
 *
 * <ul>
 *   <li>{@code SYSTEST_BASE_URL} — server origin (default {@code http://127.0.0.1:8080};
 *       the runner shares the server's network namespace, so loopback is real loopback
 *       and the dev-local auth fuse stays intact).</li>
 *   <li>{@code SYSTEST_STATE_DIR} — survives the kill -9/restart phase.</li>
 *   <li>{@code SYSTEST_ARTIFACTS_DIR} — screenshots, traces, dumps on failure.</li>
 *   <li>{@code SYSTEST_SOAK_SECONDS} — wall duration of the bot fleet soak.</li>
 *   <li>{@code SYSTEST_HEADED} — {@code 1} to run Chromium headed (local debugging).</li>
 *   <li>{@code SYSTEST_SLOWMO_MS} — extra Playwright delay per action.</li>
 * </ul>
 */
public final class SysEnv {

    private SysEnv() {
    }

    public static String baseUrl() {
        return env("SYSTEST_BASE_URL", "http://127.0.0.1:8080");
    }

    public static Path stateDir() {
        return Path.of(env("SYSTEST_STATE_DIR", "target/systest-state"));
    }

    public static Path artifactsDir() {
        return Path.of(env("SYSTEST_ARTIFACTS_DIR", "target/systest-artifacts"));
    }

    public static Duration soakDuration() {
        return Duration.ofSeconds(Long.parseLong(env("SYSTEST_SOAK_SECONDS", "20")));
    }

    public static boolean headed() {
        return "1".equals(env("SYSTEST_HEADED", "0"));
    }

    public static double slowMoMs() {
        return Double.parseDouble(env("SYSTEST_SLOWMO_MS", "0"));
    }

    /** Default ceiling for "eventually" assertions against the live world. */
    public static Duration eventually() {
        return Duration.ofSeconds(Long.parseLong(env("SYSTEST_EVENTUALLY_SECONDS", "20")));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}
