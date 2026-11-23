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
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Tiny file-backed key/value store that carries facts across the suite's
 * {@code kill -9} + restart boundary: a {@code before-restart} test records
 * what it saw, the {@code after-restart} test (a fresh JVM, after the server
 * container was SIGKILLed and booted again) asserts against it.
 */
public final class RunState {

    private static final String FILE = "run-state.properties";

    private RunState() {
    }

    public static synchronized void put(String key, Object value) {
        Properties props = load();
        props.setProperty(key, String.valueOf(value));
        Path file = SysEnv.stateDir().resolve(FILE);
        try {
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file)) {
                props.store(out, "kotharworks systest run state");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static synchronized String get(String key) {
        String value = load().getProperty(key);
        if (value == null) {
            throw new AssertionError("run state has no \"" + key + "\" — did the before-restart "
                    + "phase run? (state file: " + SysEnv.stateDir().resolve(FILE) + ")");
        }
        return value;
    }

    public static long getLong(String key) {
        return Long.parseLong(get(key));
    }

    private static Properties load() {
        Properties props = new Properties();
        Path file = SysEnv.stateDir().resolve(FILE);
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file)) {
                props.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return props;
    }
}
