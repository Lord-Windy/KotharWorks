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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A live {@code GET /api/v1/stream} subscriber running on a virtual thread.
 * Frames are parsed per the SSE spec ({@code id:} / {@code event:} /
 * {@code data:}, comment lines counted separately) and kept for assertions.
 * Always use in try-with-resources.
 */
public final class SseStream implements AutoCloseable {

    /** One dispatched SSE event. */
    public record Event(long id, String kind, Json.Node data) {
    }

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final List<String> comments = new CopyOnWriteArrayList<>();
    private final CountDownLatch headersReceived = new CountDownLatch(1);
    private final Thread reader;
    private volatile int status = -1;
    private volatile InputStream body;
    private volatile Throwable failure;
    private volatile boolean closed;

    private SseStream(Api api, String path) {
        HttpRequest request = api.request(path, "Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();
        this.reader = Thread.ofVirtual().name("sse-" + path).start(() -> run(api, request));
    }

    /** Opens the caller's stream from {@code since} (optionally for an explicit seat). */
    public static SseStream open(Api api, long since, Long seatId) {
        String path = "/api/v1/stream?since=" + since + (seatId == null ? "" : "&seatId=" + seatId);
        return new SseStream(api, path);
    }

    public static SseStream open(Api api, long since) {
        return open(api, since, null);
    }

    private void run(Api api, HttpRequest request) {
        try {
            HttpResponse<InputStream> response = api.client().send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            body = response.body();
            headersReceived.countDown();
            if (status != 200) {
                return;
            }
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(body, StandardCharsets.UTF_8))) {
                long id = -1;
                String kind = "message";
                StringBuilder data = new StringBuilder();
                String line;
                while (!closed && (line = lines.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (!data.isEmpty()) {
                            events.add(new Event(id, kind, Json.parse(data.toString())));
                        }
                        id = -1;
                        kind = "message";
                        data.setLength(0);
                    } else if (line.startsWith(":")) {
                        comments.add(line.substring(1).trim());
                    } else if (line.startsWith("id:")) {
                        id = Long.parseLong(line.substring(3).trim());
                    } else if (line.startsWith("event:")) {
                        kind = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(line.substring(5).stripLeading());
                    }
                }
            }
        } catch (IOException e) {
            if (!closed) {
                failure = e;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            headersReceived.countDown();
        }
    }

    /** Blocks until the response status line + headers arrive; returns the HTTP status. */
    public int awaitHeaders(Duration timeout) {
        try {
            if (!headersReceived.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("SSE headers not received within " + timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
        return status;
    }

    /** Waits for the first event matching {@code match}. */
    public Event await(String what, Predicate<Event> match) {
        return Await.until(what + " (seen: " + kinds() + ")", this::events,
                list -> list.stream().anyMatch(match))
                .stream().filter(match).findFirst().orElseThrow();
    }

    /** Waits for an event of {@code kind} with cursor greater than {@code after}. */
    public Event awaitKind(String kind, long after) {
        return await("SSE " + kind + " after cursor " + after,
                e -> e.kind().equals(kind) && e.id() > after);
    }

    public List<Event> events() {
        assertHealthy();
        return List.copyOf(events);
    }

    public List<String> comments() {
        return List.copyOf(comments);
    }

    public int status() {
        return status;
    }

    public long lastCursor() {
        return events.isEmpty() ? 0 : events.getLast().id();
    }

    private List<String> kinds() {
        List<String> out = new ArrayList<>();
        for (Event e : events) {
            out.add(e.id() + ":" + e.kind());
        }
        return out;
    }

    private void assertHealthy() {
        if (failure != null) {
            throw new AssertionError("SSE stream failed: " + failure, failure);
        }
    }

    @Override
    public void close() {
        closed = true;
        InputStream stream = body;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // closing a half-read chunked stream may complain; we are done with it
            }
        }
        reader.interrupt();
    }
}
