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

import java.util.Objects;

/**
 * Thrown when an authentication or HTTP security check rejects a request.
 * Carries the machine-readable {@link AuthErrorCode} the router serializes
 * into the error response body alongside its mapped HTTP status.
 */
public final class AuthRejectException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final AuthErrorCode code;

    public AuthRejectException(AuthErrorCode code) {
        super(code.name());
        this.code = Objects.requireNonNull(code, "code");
    }

    public AuthRejectException(AuthErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** The rejection code; also selects the HTTP status of the error response. */
    public AuthErrorCode code() {
        return code;
    }
}
