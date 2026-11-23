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

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Polling helper for "eventually" assertions against the live, wall-clock
 * driven world. Always prefer this over {@code Thread.sleep}: the world runs at
 * a compressed time scale, so fixed sleeps are either flaky or slow.
 */
public final class Await {

    private static final Duration POLL = Duration.ofMillis(100);

    private Await() {
    }

    /** Polls {@code probe} until {@code accept} holds; returns the accepted value. */
    public static <T> T until(String what, Duration timeout, Supplier<T> probe,
            Predicate<? super T> accept) {
        return until(what, timeout, POLL, probe, accept);
    }

    /** {@link #until} with an explicit poll interval (bots poll gently: reads are rate limited). */
    public static <T> T until(String what, Duration timeout, Duration poll, Supplier<T> probe,
            Predicate<? super T> accept) {
        long deadline = System.nanoTime() + timeout.toNanos();
        T last = null;
        RuntimeException lastError = null;
        while (true) {
            try {
                last = probe.get();
                if (accept.test(last)) {
                    return last;
                }
                lastError = null;
            } catch (RuntimeException e) {
                lastError = e;
            }
            if (System.nanoTime() > deadline) {
                AssertionError failure = new AssertionError("timed out after " + timeout
                        + " waiting for: " + what + "\n  last value: " + last);
                if (lastError != null) {
                    failure.initCause(lastError);
                }
                throw failure;
            }
            sleep(poll);
        }
    }

    /** {@link #until} with the suite-wide default timeout. */
    public static <T> T until(String what, Supplier<T> probe, Predicate<? super T> accept) {
        return until(what, SysEnv.eventually(), probe, accept);
    }

    /** Waits until {@code condition} is true. */
    public static void that(String what, Supplier<Boolean> condition) {
        until(what, condition, Boolean.TRUE::equals);
    }

    /** Interrupt-safe sleep (use only for pacing bots/humans, never for synchronization). */
    public static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
