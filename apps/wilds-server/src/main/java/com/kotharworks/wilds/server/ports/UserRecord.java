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

import java.time.Instant;
import java.util.Objects;

import com.kotharworks.auth.principal.UserId;

/**
 * A provisioned account row: JIT-upserted on first verified login. Suspended
 * accounts are rejected instantly by every authenticator (no positive cache
 * anywhere: lookups always read through to this record).
 *
 * @param userId           permanent internal user id
 * @param displayName      display name from the verified identity
 * @param maxSeatsOverride nullable charter-cap override (null = policy default)
 * @param suspendedAt      nullable suspension instant; non-null means locked out
 * @param suspendedReason  administrative reason recorded at suspension time
 */
public record UserRecord(UserId userId, String displayName, Integer maxSeatsOverride,
        Instant suspendedAt, String suspendedReason) {

    public UserRecord {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
    }

    /** A fresh, unsuspended user row. */
    public static UserRecord active(UserId userId, String displayName, Integer maxSeatsOverride) {
        return new UserRecord(userId, displayName, maxSeatsOverride, null, null);
    }
}
