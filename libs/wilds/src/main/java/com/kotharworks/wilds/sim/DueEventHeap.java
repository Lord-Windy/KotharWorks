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
package com.kotharworks.wilds.sim;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Deterministic binary min-heap of {@link ScheduledEvent}s ordered by
 * {@code (dueAtMs, seq)}. Zero {@code remove(Object)} heap scans: cancellation is O(1)
 * via {@code cancellationToken} comparison when the event is popped by the consumer.
 */
public final class DueEventHeap {

    private static final Comparator<ScheduledEvent> ORDER =
            Comparator.comparingLong(ScheduledEvent::dueAtMs).thenComparingLong(ScheduledEvent::seq);

    private ScheduledEvent[] heap = new ScheduledEvent[16];
    private int count;

    /** Pushes an event; ordering is fully determined by {@code (dueAtMs, seq)}. */
    public void push(ScheduledEvent event) {
        Objects.requireNonNull(event, "event");
        if (count == heap.length) {
            heap = Arrays.copyOf(heap, count * 2);
        }
        heap[count++] = event;
        siftUp(count - 1);
    }

    /** Head event without removing it. */
    public ScheduledEvent peek() {
        if (count == 0) {
            throw new NoSuchElementException("due event heap is empty");
        }
        return heap[0];
    }

    /** Pops and returns the head event ({@code (dueAtMs, seq)}-smallest). */
    public ScheduledEvent poll() {
        if (count == 0) {
            throw new NoSuchElementException("due event heap is empty");
        }
        ScheduledEvent head = heap[0];
        count--;
        heap[0] = heap[count];
        heap[count] = null;
        siftDown(0);
        return head;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public int size() {
        return count;
    }

    /**
     * All events sorted by {@code (dueAtMs, seq)} without mutating the heap.
     */
    public List<ScheduledEvent> snapshotOrderedList() {
        ScheduledEvent[] copy = Arrays.copyOf(heap, count);
        Arrays.sort(copy, ORDER);
        return List.copyOf(Arrays.asList(copy));
    }

    /** Resets the heap and pushes every event from a snapshot list. */
    public void restoreFrom(List<ScheduledEvent> events) {
        Objects.requireNonNull(events, "events");
        count = 0;
        Arrays.fill(heap, null);
        for (ScheduledEvent event : events) {
            push(Objects.requireNonNull(event, "events contains a null element"));
        }
    }

    private void siftUp(int i) {
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (ORDER.compare(heap[parent], heap[i]) <= 0) {
                return;
            }
            swap(parent, i);
            i = parent;
        }
    }

    private void siftDown(int i) {
        while (true) {
            int left = 2 * i + 1;
            int right = left + 1;
            int smallest = i;
            if (left < count && ORDER.compare(heap[left], heap[smallest]) < 0) {
                smallest = left;
            }
            if (right < count && ORDER.compare(heap[right], heap[smallest]) < 0) {
                smallest = right;
            }
            if (smallest == i) {
                return;
            }
            swap(smallest, i);
            i = smallest;
        }
    }

    private void swap(int a, int b) {
        ScheduledEvent tmp = heap[a];
        heap[a] = heap[b];
        heap[b] = tmp;
    }
}
