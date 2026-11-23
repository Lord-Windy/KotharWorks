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
package com.kotharworks.systest.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The packaged {@code wilds-headless} CLI as a real OS process (the in-process
 * {@code HeadlessCliTest} covers logic; this covers packaging, classpath and
 * cross-JVM determinism). Classpath comes from {@code SYSTEST_HEADLESS_CP}.
 */
@Tag("headless")
@Tag("smoke")
@Order(2)
class HeadlessCliProcessTest {

    /**
     * Pinned {@code run --seed 1} hash. If you change rules or the engine on
     * purpose, update this together with the golden hashes in libs/wilds.
     */
    private static final String RUN_SEED_1_HASH = "worldHash=0xab445e3244f12c08";

    @Test
    @DisplayName("inspect prints the seeded bootstrap topology")
    void inspect() throws Exception {
        Result r = headless("inspect", "--seed", "1");
        assertEquals(0, r.exit(), r::toString);
        assertTrue(r.out().contains("places=3 edges=4"), r::toString);
        assertTrue(r.out().contains("Origin Clearing"), r::toString);
    }

    @Test
    @DisplayName("run is bit-for-bit deterministic across separate JVMs")
    void deterministicRun() throws Exception {
        Result first = headless("run", "--seed", "1");
        Result second = headless("run", "--seed", "1");
        assertEquals(0, first.exit(), first::toString);
        assertEquals(first.out(), second.out(), "two JVMs, same seed, different output");
        assertTrue(first.out().contains(RUN_SEED_1_HASH), first::toString);
    }

    @Test
    @DisplayName("bad usage exits 1 with a message, never a stack trace")
    void badUsage() throws Exception {
        Result r = headless("frobnicate");
        assertEquals(1, r.exit(), r::toString);
        assertTrue(!r.err().contains("\tat "), "stack trace leaked: " + r);
    }

    private record Result(int exit, String out, String err) {
    }

    private static Result headless(String... args) throws IOException, InterruptedException {
        String cp = System.getenv("SYSTEST_HEADLESS_CP");
        Assumptions.assumeTrue(cp != null && !cp.isBlank(), "SYSTEST_HEADLESS_CP not set");
        List<String> cmd = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"),
                "-cp", cp, "com.kotharworks.wilds.headless.HeadlessMain"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("headless CLI hung: " + cmd);
        }
        return new Result(p.exitValue(),
                new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
