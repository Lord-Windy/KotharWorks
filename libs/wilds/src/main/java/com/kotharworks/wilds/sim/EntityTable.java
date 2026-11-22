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

import com.kotharworks.wilds.id.EntityId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Dense generational entity table: flat parallel arrays of rows and slot generations
 * addressed by typed {@link EntityId} value ids.
 *
 * <p>Stale-handle safety: removing a row bumps the slot generation, so any cached id of the
 * old generation is rejected by {@link #get}, {@link #getOrNull} and {@link #replace}.
 * Allocation always takes the <em>lowest</em> unoccupied slot index below the high-water
 * mark (a min-order free list, never LIFO stack order), so the id sequence is a pure
 * function of which slots are occupied — identical before and after a snapshot restore.
 * Iteration is strictly ascending slot order, 100% deterministic.
 */
public final class EntityTable<I extends EntityId, R> {

    /** Creates the concrete id for an allocated {@code (index, generation)} slot. */
    @FunctionalInterface
    public interface IdFactory<I extends EntityId> {
        I create(int index, int generation);
    }

    /** One table slot in a snapshot dump; {@code row} is null for an unoccupied slot. */
    public record SlotDump<I extends EntityId, R>(int index, int generation, R row) {
    }

    /** Deterministic min-order free-slot heap over unoccupied slot indexes. */
    private static final class IntMinHeap {

        private int[] values = new int[8];
        private int count;

        void push(int value) {
            if (count == values.length) {
                values = Arrays.copyOf(values, count * 2);
            }
            values[count++] = value;
            siftUp(count - 1);
        }

        int pollMin() {
            if (count == 0) {
                throw new NoSuchElementException("free slot heap is empty");
            }
            int min = values[0];
            values[0] = values[--count];
            siftDown(0);
            return min;
        }

        int peekMin() {
            if (count == 0) {
                throw new NoSuchElementException("free slot heap is empty");
            }
            return values[0];
        }

        boolean isEmpty() {
            return count == 0;
        }

        void clear() {
            count = 0;
        }

        private void siftUp(int i) {
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (values[parent] <= values[i]) {
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
                if (left < count && values[left] < values[smallest]) {
                    smallest = left;
                }
                if (right < count && values[right] < values[smallest]) {
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
            int tmp = values[a];
            values[a] = values[b];
            values[b] = tmp;
        }
    }

    private static final int INITIAL_CAPACITY = 16;

    private final IdFactory<I> idFactory;
    private final Function<R, I> idExtractor;
    private Object[] rows = new Object[INITIAL_CAPACITY];
    private int[] generations = new int[INITIAL_CAPACITY];
    private int highWaterMark;
    private int liveCount;
    private final IntMinHeap freeSlots = new IntMinHeap();

    public EntityTable(IdFactory<I> idFactory, Function<R, I> idExtractor) {
        this.idFactory = Objects.requireNonNull(idFactory, "idFactory");
        this.idExtractor = Objects.requireNonNull(idExtractor, "idExtractor");
    }

    /**
     * Non-mutating preview of exactly the id the next {@link #insertNew} would allocate:
     * the lowest free slot with its current generation, or {@code (highWaterMark, 1)}.
     */
    public I nextId() {
        if (!freeSlots.isEmpty()) {
            int index = freeSlots.peekMin();
            return idFactory.create(index, generations[index]);
        }
        return idFactory.create(highWaterMark, 1);
    }

    /**
     * Allocates the lowest unoccupied slot (appending at the high-water mark on first use,
     * initializing its generation to 1), builds the row via {@code rowBuilder}, and stores it.
     *
     * <p>Atomic on failure: if {@code rowBuilder} throws, returns null, or produces a
     * mismatched id, no slot, generation, free-list entry, or high-water mark is modified.
     */
    public R insertNew(Function<I, R> rowBuilder) {
        Objects.requireNonNull(rowBuilder, "rowBuilder");
        final boolean reuseFreeSlot = !freeSlots.isEmpty();
        final int index = reuseFreeSlot ? freeSlots.peekMin() : highWaterMark;
        final int generation = reuseFreeSlot ? generations[index] : 1;
        if (!reuseFreeSlot && index == Integer.MAX_VALUE) {
            throw new IllegalStateException("entity table index space exhausted");
        }
        I id = idFactory.create(index, generation);
        R row = Objects.requireNonNull(rowBuilder.apply(id), "rowBuilder produced a null row");
        I rowId = Objects.requireNonNull(idExtractor.apply(row), "idExtractor produced a null id");
        if (rowId.index() != index || rowId.generation() != generation) {
            throw new IllegalStateException("row id " + rowId.toWire()
                    + " does not match allocated slot " + id.toWire());
        }
        if (reuseFreeSlot) {
            if (freeSlots.isEmpty() || freeSlots.peekMin() != index || generations[index] != generation) {
                throw new IllegalStateException("re-entrant table mutation during insertNew for " + id.toWire());
            }
            ensureCapacity(index);
            freeSlots.pollMin();
        } else {
            if (!freeSlots.isEmpty() || highWaterMark != index) {
                throw new IllegalStateException("re-entrant table mutation during insertNew for " + id.toWire());
            }
            ensureCapacity(index);
            generations[index] = 1;
            highWaterMark = index + 1;
        }
        rows[index] = row;
        liveCount++;
        return row;
    }

    /** Row for {@code id}, or null when the index is out of range, empty, or stale. */
    public R getOrNull(I id) {
        Objects.requireNonNull(id, "id");
        return isLive(id) ? rowAt(id.index()) : null;
    }

    /** Row for {@code id}, or empty when the index is out of range, empty, or stale. */
    public Optional<R> get(I id) {
        return Optional.ofNullable(getOrNull(id));
    }

    /** Replaces a live row whose extracted id matches its slot and generation. */
    public void replace(R updatedRow) {
        Objects.requireNonNull(updatedRow, "updatedRow");
        I id = Objects.requireNonNull(idExtractor.apply(updatedRow), "idExtractor produced a null id");
        if (!isLive(id)) {
            throw new IllegalStateException("stale or missing row: " + id.toWire());
        }
        rows[id.index()] = updatedRow;
    }

    /**
     * Clears the slot for {@code id} if live, bumps its generation (invalidating every
     * cached id of the old generation) and returns true; otherwise returns false.
     */
    public boolean remove(I id) {
        Objects.requireNonNull(id, "id");
        if (!isLive(id)) {
            return false;
        }
        int index = id.index();
        if (generations[index] == Integer.MAX_VALUE) {
            throw new IllegalStateException("slot " + index + " generation exhausted");
        }
        rows[index] = null;
        generations[index]++;
        freeSlots.push(index);
        liveCount--;
        return true;
    }

    /** Visits every live row in strictly ascending slot-index order. */
    public void forEach(Consumer<R> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (int i = 0; i < highWaterMark; i++) {
            if (rows[i] != null) {
                consumer.accept(rowAt(i));
            }
        }
    }

    /** All live rows in strictly ascending slot-index order. */
    public List<R> snapshotRows() {
        List<R> snapshot = new ArrayList<>(liveCount);
        for (int i = 0; i < highWaterMark; i++) {
            if (rows[i] != null) {
                snapshot.add(rowAt(i));
            }
        }
        return List.copyOf(snapshot);
    }

    /**
     * Snapshot of every slot {@code 0 .. highWaterMark - 1} in ascending index order,
     * including unoccupied slots with their bumped generations; unoccupied rows are null.
     */
    public List<SlotDump<I, R>> dumpSlots() {
        List<SlotDump<I, R>> slots = new ArrayList<>(highWaterMark);
        for (int i = 0; i < highWaterMark; i++) {
            slots.add(new SlotDump<>(i, generations[i], rows[i] == null ? null : rowAt(i)));
        }
        return List.copyOf(slots);
    }

    /**
     * Restores a {@link #dumpSlots()} dump: indices must be exactly {@code 0..n-1} ascending,
     * generations {@code >= 1}, and every non-null row's extracted id must match its slot.
     * Rebuilds the backing arrays (at {@code max(INITIAL_CAPACITY, n)}, trimming excess
     * capacity) and the min-order free list from the null slots {@code < n}.
     */
    public void restoreSlots(List<SlotDump<I, R>> slots) {
        Objects.requireNonNull(slots, "slots");
        int slotCount = slots.size();
        int backingCapacity = Math.max(INITIAL_CAPACITY, slotCount);
        Object[] restoredRows = new Object[backingCapacity];
        int[] restoredGenerations = new int[backingCapacity];
        int restoredLive = 0;
        for (int i = 0; i < slotCount; i++) {
            SlotDump<I, R> dump = Objects.requireNonNull(slots.get(i), "slots contains a null entry");
            if (dump.index() != i) {
                throw new IllegalArgumentException("slot dumps must be ascending 0..n-1: expected index "
                        + i + " but got " + dump.index());
            }
            if (dump.generation() < 1) {
                throw new IllegalArgumentException(
                        "slot " + i + " generation must be >= 1: " + dump.generation());
            }
            restoredGenerations[i] = dump.generation();
            if (dump.row() != null) {
                I rowId = Objects.requireNonNull(idExtractor.apply(dump.row()), "idExtractor produced a null id");
                if (rowId.index() != i || rowId.generation() != dump.generation()) {
                    throw new IllegalArgumentException("row id " + rowId.toWire() + " does not match slot ("
                            + i + ", " + dump.generation() + ")");
                }
                restoredRows[i] = dump.row();
                restoredLive++;
            }
        }
        rows = restoredRows;
        generations = restoredGenerations;
        highWaterMark = slotCount;
        liveCount = restoredLive;
        freeSlots.clear();
        for (int i = 0; i < slotCount; i++) {
            if (restoredRows[i] == null) {
                freeSlots.push(i);
            }
        }
    }

    /** Number of occupied slots. */
    public int liveCount() {
        return liveCount;
    }

    /** Number of slots ever initialized: {@code 0 .. highWaterMark - 1} exist. */
    public int highWaterMark() {
        return highWaterMark;
    }

    private boolean isLive(I id) {
        int index = id.index();
        return index >= 0 && index < highWaterMark
                && rows[index] != null
                && generations[index] == id.generation();
    }

    private void ensureCapacity(int requiredIndex) {
        if (requiredIndex < rows.length) {
            return;
        }
        int capacity = Math.max(INITIAL_CAPACITY, rows.length);
        while (capacity <= requiredIndex) {
            capacity = Math.multiplyExact(capacity, 2);
        }
        rows = Arrays.copyOf(rows, capacity);
        generations = Arrays.copyOf(generations, capacity);
    }

    @SuppressWarnings("unchecked")
    private R rowAt(int index) {
        return (R) rows[index];
    }
}
