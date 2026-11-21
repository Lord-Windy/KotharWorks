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
package com.kotharworks.wilds.server.adapters.http;

import java.util.Objects;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthRejectException;
import com.kotharworks.auth.http.RequestAuthenticator;
import com.kotharworks.auth.principal.Principal;
import com.kotharworks.auth.principal.UserId;
import com.kotharworks.wilds.server.ports.AuthStorePort;
import com.kotharworks.wilds.server.ports.UserRecord;
import com.sun.net.httpserver.HttpExchange;

/**
 * Suspension gate wrapped around the standard {@code bearerThenCookie}
 * authenticator: every request identifying a user reads the live
 * {@link UserRecord} through (no positive cache anywhere) and rejects with
 * {@code 403 ACCOUNT_SUSPENDED} the instant the account is suspended — JWTs,
 * session cookies, and {@code iw_bot_} tokens all covered (the token path also
 * fails closed inside the store's digest lookup).
 */
public final class SuspendedUserRejectingAuthenticator implements RequestAuthenticator {

    private final RequestAuthenticator delegate;
    private final AuthStorePort authStore;

    public SuspendedUserRejectingAuthenticator(RequestAuthenticator delegate,
            AuthStorePort authStore) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.authStore = Objects.requireNonNull(authStore, "authStore");
    }

    @Override
    public Authentication authenticate(HttpExchange exchange) throws AuthRejectException {
        Authentication authentication = delegate.authenticate(exchange);
        UserId userId = switch (authentication.principal()) {
            case Principal.HumanPrincipal human -> human.userId();
            case Principal.ScopedTokenPrincipal token -> token.ownerUserId();
            case Principal.AnonymousPrincipal _ -> null;
        };
        if (userId != null) {
            UserRecord user = authStore.findUser(userId).orElse(null);
            if (user != null && user.suspendedAt() != null) {
                throw new AuthRejectException(AuthErrorCode.ACCOUNT_SUSPENDED,
                        "account suspended: " + user.suspendedReason());
            }
        }
        return authentication;
    }
}
