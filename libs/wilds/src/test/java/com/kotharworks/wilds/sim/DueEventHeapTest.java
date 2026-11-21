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

import com.kotharworks.wilds.id.RuleId;
import com.kotharworks.wilds.seed.SeededRng;
import com.kotharworks.wilds.seed.WorldSeed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DueEventHeapTest {

    private static ScheduledEvent pulse(long dueAtMs, long seq, long cancellationToken) {
        return new ScheduledEvent(dueAtMs, seq, cancellationToken,
                new DuePayload.SystemPulse(new RuleId("pulse_" + seq)));
    }

    @Test
    void identicalDueTimesPollInStrictSeqOrderRegardlessOfInsertionOrder() {
        SeededRng rng = new WorldSeed(0x5EED_0002L).stream("due-event-heap", 0L, 0L);
        ScheduledEvent[] events = new ScheduledEvent[50];
        for (int i = 0; i < events.length; i++) {
            events[i] = pulse(5_000L, i, i);
        }
        for (int i = events.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            ScheduledEvent tmp = events[i];
            events[i] = events[j];
            events[j] = tmp;
        }
        DueEventHeap shuffled = new DueEventHeap();
        for (ScheduledEvent event : events) {
            shuffled.push(event);
        }
        assertEquals(50, shuffled.size());
        assertFalse(shuffled.isEmpty());
        for (int expectedSeq = 0; expectedSeq < 50; expectedSeq++) {
            ScheduledEvent polled = shuffled.poll();
            assertEquals(5_000L, polled.dueAtMs());
            assertEquals(expectedSeq, polled.seq(), "tie must break by strictly ascending seq");
        }
        assertTrue(shuffled.isEmpty());

        DueEventHeap sequential = new DueEventHeap();
        for (int i = 0; i < 50; i++) {
            sequential.push(pulse(5_000L, i, i));
        }
        for (int expectedSeq = 0; expectedSeq < 50; expectedSeq++) {
            ScheduledEvent polled = sequential.poll();
            assertEquals(5_000L, polled.dueAtMs());
            assertEquals(expectedSeq, polled.seq());
        }
        assertTrue(sequential.isEmpty());
    }

    @Test
    void tenThousandEventPropertyTestMatchesSortedReference() {
        SeededRng rng = new WorldSeed(0x5EED_0002L).stream("due-event-heap", 0L, 0L);
        List<ScheduledEvent> inserted = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            inserted.add(new ScheduledEvent(rng.nextRange(0, 5_000_000), i, i,
                    new DuePayload.SystemPulse(new RuleId("pulse_" + (i % 7)))));
        }
        DueEventHeap heap = new DueEventHeap();
        for (ScheduledEvent event : inserted) {
            heap.push(event);
        }
        List<ScheduledEvent> reference = new ArrayList<>(inserted);
        reference.sort(Comparator.naturalOrder());
        assertEquals(10_000, heap.size());
        assertEquals(reference, heap.snapshotOrderedList(), "snapshot order must match sorted reference");

        List<ScheduledEvent> polled = new ArrayList<>(10_000);
        while (!heap.isEmpty()) {
            polled.add(heap.poll());
        }
        assertEquals(reference, polled, "pop order must match sorted reference");
        assertEquals(10_000, polled.size());
        for (int i = 1; i < polled.size(); i++) {
            assertTrue(polled.get(i - 1).compareTo(polled.get(i)) < 0,
                    "pop order must be strictly ascending on (dueAtMs, seq)");
        }
    }

    @Test
    void emptyHeapContract() {
        DueEventHeap heap = new DueEventHeap();
        assertTrue(heap.isEmpty());
        assertEquals(0, heap.size());
        assertThrows(NoSuchElementException.class, heap::peek);
        assertThrows(NoSuchElementException.class, heap::poll);

        ScheduledEvent event = pulse(1_000L, 0, 7);
        heap.push(event);
        assertFalse(heap.isEmpty());
        assertEquals(1, heap.size());
        assertSame(event, heap.peek());
        assertSame(event, heap.poll());
        assertTrue(heap.isEmpty());
        assertEquals(0, heap.size());
        assertThrows(NoSuchElementException.class, heap::peek);
    }

    @Test
    void snapshotDoesNotMutateAndRestoreRoundTrips() {
        DueEventHeap heap = new DueEventHeap();
        heap.push(pulse(3_000L, 2, 2));
        heap.push(pulse(1_000L, 0, 0));
        heap.push(pulse(2_000L, 1, 1));
        heap.push(pulse(2_000L, 5, 5));

        List<ScheduledEvent> snapshot = heap.snapshotOrderedList();
        assertEquals(snapshot, heap.snapshotOrderedList(), "snapshot must not mutate the heap");
        assertEquals(4, heap.size());

        ScheduledEvent head = heap.peek();
        assertEquals(pulse(1_000L, 0, 0), head);
        assertSame(head, heap.poll(), "snapshot must leave the heap structure intact");

        DueEventHeap restored = new DueEventHeap();
        restored.restoreFrom(snapshot);
        assertEquals(snapshot, restored.snapshotOrderedList());
        assertEquals(4, restored.size());

        List<ScheduledEvent> fromHeap = new ArrayList<>();
        List<ScheduledEvent> fromRestored = new ArrayList<>();
        while (!heap.isEmpty()) {
            fromHeap.add(heap.poll());
        }
        while (!restored.isEmpty()) {
            fromRestored.add(restored.poll());
        }
        assertEquals(snapshot, fromRestored, "restored heap must replay the exact snapshot sequence");
        assertEquals(snapshot.subList(1, snapshot.size()), fromHeap,
                "heap must continue with the identical sequence after the polled head");
        assertTrue(heap.isEmpty());
    }

    @Test
    void cancellationTokenDiscardsStaleEventsAtPopTime() {
        long activeTicket = 42L;
        DueEventHeap heap = new DueEventHeap();
        heap.push(pulse(1_000L, 0, activeTicket));
        heap.push(pulse(1_000L, 1, activeTicket + 1));

        int resolved = 0;
        int discarded = 0;
        long resolvedToken = -1L;
        while (!heap.isEmpty()) {
            ScheduledEvent event = heap.poll();
            if (event.cancellationToken() == activeTicket) {
                resolved++;
                resolvedToken = event.cancellationToken();
            } else {
                discarded++;
            }
        }
        assertEquals(1, resolved);
        assertEquals(1, discarded);
        assertEquals(activeTicket, resolvedToken);
    }
}
