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
package com.kotharworks.auth.spi;

import com.kotharworks.auth.principal.ExternalIdentity;
import com.kotharworks.auth.principal.UserId;

/**
 * Host SPI: just-in-time identity linking. Implementations (in-memory or
 * Postgres) return the stable {@link UserId} for an external {@code (iss, sub)}
 * pair, creating the backing user row on first sight — idempotent per identity.
 */
@FunctionalInterface
public interface IdentityProvisioner {

    /** Returns the existing or freshly provisioned user for the identity. */
    UserId provision(ExternalIdentity externalId, String displayName);
}
