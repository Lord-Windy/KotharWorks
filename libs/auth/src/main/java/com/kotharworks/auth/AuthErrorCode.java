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
package com.kotharworks.auth;

/**
 * Closed family of machine-readable auth rejection codes, each mapped to the
 * HTTP status the {@code AuthenticatedRouter} writes when it rejects a request.
 * Exhaustively switchable.
 */
public enum AuthErrorCode {
    /** No or insufficient credentials on a non-public route. */
    AUTHENTICATION_REQUIRED(401),
    /** Credentials are present but syntactically or cryptographically invalid. */
    INVALID_TOKEN(401),
    /** Credentials were valid but have expired (checked against the injected clock). */
    TOKEN_EXPIRED(401),
    /** The owner of otherwise-valid credentials is suspended. */
    ACCOUNT_SUSPENDED(403),
    /** The principal is authenticated but lacks the role the route requires. */
    ROLE_REQUIRED(403),
    /** The principal kind may never access this route (e.g. bot token on human route). */
    FORBIDDEN(403),
    /** Cookie-authenticated mutating request failed the cross-site request guard. */
    CSRF_REJECTED(403),
    /** No route is registered for the request path. */
    NOT_FOUND(404),
    /** A route exists for the path but not for the request method. */
    METHOD_NOT_ALLOWED(405),
    /** The request body exceeds the router's configured byte cap. */
    PAYLOAD_TOO_LARGE(413);

    private final int httpStatus;

    AuthErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** The HTTP status this rejection maps to. */
    public int httpStatus() {
        return httpStatus;
    }
}
