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
package com.kotharworks.wilds.server.ports;

/**
 * What {@link WorldStorePort#ensureActiveWorld(long, int, int, int, long, OnVersionMismatch)}
 * does when the stored active world was pinned by a different binary (seed, rules, engine
 * or protocol version, or a non-null rules fingerprint differ). Selected by
 * {@code --on-version-mismatch}.
 */
public enum OnVersionMismatch {
    /**
     * Refuse to boot: throw {@code WorldVersionMismatchException} and write nothing
     * (the default — an operator must choose to abandon a world). On PostgreSQL the
     * refusal happens before pending schema migrations are applied (a read-only
     * pre-migration check), so a refused boot makes zero writes to any schema, including
     * {@code public.schema_migrations}, and the binary that matches the stored world can
     * still boot the database.
     */
    REFUSE,
    /**
     * Archive the mismatched world ({@code status = 'ARCHIVED'}, {@code ended_at} set) and
     * create a fresh active world in the same atomic step. The archived world's WAL,
     * checkpoints and events are kept intact and exportable.
     */
    ARCHIVE
}
