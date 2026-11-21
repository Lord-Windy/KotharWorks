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

import com.kotharworks.wilds.id.OwnerId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTableTest {

    private record OwnerRow(OwnerId id, String label) {
    }

    private static EntityTable<OwnerId, OwnerRow> newTable() {
        return new EntityTable<>(OwnerId::new, OwnerRow::id);
    }

    private static EntityTable.SlotDump<OwnerId, OwnerRow> dump(int index, int generation, OwnerRow row) {
        return new EntityTable.SlotDump<>(index, generation, row);
    }

    /** Five rows r0..r4 at indices 0..4 (generation 1), then indices 1 and 3 removed. */
    private static EntityTable<OwnerId, OwnerRow> tableWithHoles() {
        EntityTable<OwnerId, OwnerRow> table = newTable();
        OwnerId[] ids = new OwnerId[5];
        for (int i = 0; i < 5; i++) {
            final int index = i;
            ids[i] = table.insertNew(id -> new OwnerRow(id, "r" + index)).id();
        }
        assertEquals(new OwnerId(0, 1), ids[0]);
        assertTrue(table.remove(ids[1]));
        assertTrue(table.remove(ids[3]));
        assertFalse(table.remove(ids[1]), "second remove of the same id must be a no-op");
        return table;
    }

    @Test
    void reusesLowestFreeSlotWithBumpedGeneration() {
        EntityTable<OwnerId, OwnerRow> table = tableWithHoles();
        assertEquals(Optional.empty(), table.get(new OwnerId(1, 1)));
        OwnerRow reborn = table.insertNew(id -> new OwnerRow(id, "reborn"));
        assertEquals(new OwnerId(1, 2), reborn.id());
        assertEquals(Optional.empty(), table.get(new OwnerId(1, 1)), "old generation must stay stale");
        assertEquals(Optional.of(reborn), table.get(new OwnerId(1, 2)));
    }

    @Test
    void freeSlotSelectionIsStrictlyMinOrderThenAppend() {
        EntityTable<OwnerId, OwnerRow> table = tableWithHoles();
        assertEquals(new OwnerId(1, 2), table.insertNew(id -> new OwnerRow(id, "a")).id());
        assertEquals(new OwnerId(3, 2), table.insertNew(id -> new OwnerRow(id, "b")).id());
        assertEquals(new OwnerId(5, 1), table.insertNew(id -> new OwnerRow(id, "c")).id());
        assertEquals(6, table.liveCount());
        assertEquals(6, table.highWaterMark());
    }

    @Test
    void nextIdIsNonMutatingPreview() {
        EntityTable<OwnerId, OwnerRow> table = tableWithHoles();
        OwnerId preview = table.nextId();
        assertEquals(new OwnerId(1, 2), preview);
        assertEquals(preview, table.nextId());
        assertEquals(3, table.liveCount());
        assertEquals(5, table.highWaterMark());
        assertTrue(table.get(preview).isEmpty());
        OwnerRow filled = table.insertNew(id -> new OwnerRow(id, "filled"));
        assertEquals(preview, filled.id());
        assertEquals(newTable().nextId(), new OwnerId(0, 1));
    }

    @Test
    void iterationIsStrictlyAscendingSlotIndex() {
        EntityTable<OwnerId, OwnerRow> table = newTable();
        OwnerId[] ids = new OwnerId[6];
        for (int i = 0; i < 6; i++) {
            final int index = i;
            ids[i] = table.insertNew(id -> new OwnerRow(id, "r" + index)).id();
        }
        assertTrue(table.remove(ids[4]));
        assertTrue(table.remove(ids[0]));
        assertEquals(new OwnerId(0, 2), table.insertNew(id -> new OwnerRow(id, "new0")).id());
        assertTrue(table.remove(ids[2]));
        assertEquals(new OwnerId(2, 2), table.insertNew(id -> new OwnerRow(id, "new2")).id());

        List<String> labels = new ArrayList<>();
        table.forEach(row -> labels.add(row.label()));
        assertEquals(List.of("new0", "r1", "new2", "r3", "r5"), labels);

        List<OwnerId> iteratedIds = new ArrayList<>();
        table.forEach(row -> iteratedIds.add(row.id()));
        assertEquals(table.snapshotRows().stream().map(OwnerRow::id).toList(), iteratedIds);
        for (int i = 1; i < iteratedIds.size(); i++) {
            assertTrue(iteratedIds.get(i - 1).index() < iteratedIds.get(i).index(),
                    "iteration must ascend by slot index");
        }
    }

    @Test
    void replaceValidatesLiveGeneration() {
        EntityTable<OwnerId, OwnerRow> table = tableWithHoles();
        table.replace(new OwnerRow(new OwnerId(0, 1), "replaced"));
        assertEquals(Optional.of(new OwnerRow(new OwnerId(0, 1), "replaced")),
                table.get(new OwnerId(0, 1)));
        assertThrows(IllegalStateException.class,
                () -> table.replace(new OwnerRow(new OwnerId(1, 1), "stale")));
        assertThrows(IllegalStateException.class,
                () -> table.replace(new OwnerRow(new OwnerId(99, 1), "ghost")));
    }

    @Test
    void getRejectsOutOfRangeAndStaleHandles() {
        EntityTable<OwnerId, OwnerRow> table = tableWithHoles();
        assertNull(table.getOrNull(new OwnerId(9, 1)));
        assertNull(table.getOrNull(new OwnerId(1, 1)));
        assertNull(table.getOrNull(new OwnerId(3, 1)));
        assertTrue(table.get(new OwnerId(9, 1)).isEmpty());
    }

    @Test
    void dumpAndRestorePreserveBumpedGenerationsAndAllocation() {
        EntityTable<OwnerId, OwnerRow> original = tableWithHoles();
        OwnerRow r0 = new OwnerRow(new OwnerId(0, 1), "r0");
        OwnerRow r2 = new OwnerRow(new OwnerId(2, 1), "r2");
        OwnerRow r4 = new OwnerRow(new OwnerId(4, 1), "r4");
        assertEquals(
                List.of(dump(0, 1, r0), dump(1, 2, null), dump(2, 1, r2), dump(3, 2, null), dump(4, 1, r4)),
                original.dumpSlots());

        EntityTable<OwnerId, OwnerRow> restored = newTable();
        restored.restoreSlots(original.dumpSlots());
        assertEquals(original.dumpSlots(), restored.dumpSlots());
        assertEquals(original.snapshotRows(), restored.snapshotRows());
        assertEquals(original.liveCount(), restored.liveCount());
        assertEquals(original.highWaterMark(), restored.highWaterMark());
        assertEquals(original.nextId(), restored.nextId());

        OwnerRow onOriginal = original.insertNew(id -> new OwnerRow(id, "reborn"));
        OwnerRow onRestored = restored.insertNew(id -> new OwnerRow(id, "reborn"));
        assertEquals(new OwnerId(1, 2), onOriginal.id());
        assertEquals(onOriginal, onRestored);
    }

    @Test
    void restoreSlotsValidatesInput() {
        EntityTable<OwnerId, OwnerRow> table = newTable();
        OwnerRow anyRow = new OwnerRow(new OwnerId(0, 1), "x");

        List<EntityTable.SlotDump<OwnerId, OwnerRow>> duplicate =
                List.of(dump(0, 1, anyRow), dump(0, 2, null));
        assertThrows(IllegalArgumentException.class, () -> table.restoreSlots(duplicate));

        List<EntityTable.SlotDump<OwnerId, OwnerRow>> gap =
                List.of(dump(0, 1, anyRow), dump(1, 1, null), dump(3, 1, null));
        assertThrows(IllegalArgumentException.class, () -> table.restoreSlots(gap));

        assertThrows(IllegalArgumentException.class,
                () -> table.restoreSlots(List.of(dump(0, 0, null))));

        assertThrows(IllegalArgumentException.class, () -> table
                .restoreSlots(List.of(dump(0, 1, new OwnerRow(new OwnerId(2, 1), "mismatch")))));
    }
}
