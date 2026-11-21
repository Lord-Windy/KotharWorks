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
package com.kotharworks.wilds.server.domain;

/**
 * Domain-level authorization failure ({@code SeatAuthorizer}, seat charter
 * policy, command ownership checks). HTTP routes catch this and render
 * {@code {"errorCode":..,"message":..}} at {@link #httpStatus()}.
 */
public final class SeatAuthorizationException extends RuntimeException {

    private final int httpStatus;
    private final String errorCode;

    public SeatAuthorizationException(int httpStatus, String errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = java.util.Objects.requireNonNull(errorCode, "errorCode");
    }

    /** The HTTP status to render ({@code 401}/{@code 403}/{@code 404}/{@code 429}). */
    public int httpStatus() {
        return httpStatus;
    }

    /** Stable machine-readable error code ({@code FORBIDDEN}, {@code RATE_LIMITED}, ...). */
    public String errorCode() {
        return errorCode;
    }
}
