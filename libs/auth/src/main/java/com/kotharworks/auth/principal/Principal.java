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
package com.kotharworks.auth.principal;

import java.util.Objects;
import java.util.Set;

/**
 * Closed family of authenticated request identities. The split between
 * {@link HumanPrincipal} and {@link ScopedTokenPrincipal} is the security
 * boundary: automation tokens can never carry global roles, so they can never
 * create accounts, seats, or mint further tokens.
 *
 * <p>Exhaustively switchable via the sealed permit clause.
 */
public sealed interface Principal permits Principal.HumanPrincipal, Principal.ScopedTokenPrincipal,
        Principal.AnonymousPrincipal {

    /** A verified human OIDC identity with its provisioned user and global roles. */
    record HumanPrincipal(UserId userId, ExternalIdentity externalId, String displayName,
            Set<GlobalRole> roles) implements Principal {

        public HumanPrincipal {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(externalId, "externalId");
            Objects.requireNonNull(displayName, "displayName");
            roles = Set.copyOf(roles);
        }

        /** Whether this human holds the given global role. */
        public boolean hasRole(GlobalRole role) {
            return roles.contains(role);
        }
    }

    /**
     * A resource-scoped automation key. Deliberately carries <b>zero</b>
     * {@link GlobalRole}s and no user-facing display name: it identifies a
     * token bound to one resource namespace/id owned by {@code ownerUserId},
     * nothing more.
     */
    record ScopedTokenPrincipal(TokenId tokenId, UserId ownerUserId, String resourceNamespace,
            long scopedResourceId) implements Principal {

        public ScopedTokenPrincipal {
            Objects.requireNonNull(tokenId, "tokenId");
            Objects.requireNonNull(ownerUserId, "ownerUserId");
            Objects.requireNonNull(resourceNamespace, "resourceNamespace");
        }
    }

    /** No credentials (or only expired/unknown ambient credentials). */
    record AnonymousPrincipal() implements Principal {

        public static final AnonymousPrincipal INSTANCE = new AnonymousPrincipal();
    }
}
