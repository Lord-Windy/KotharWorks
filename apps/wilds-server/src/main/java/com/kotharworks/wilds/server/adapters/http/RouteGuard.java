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

import java.io.IOException;
import java.util.NoSuchElementException;

import com.kotharworks.auth.http.AuthenticatedRouter.RouteContext;
import com.kotharworks.wilds.server.domain.SeatAuthorizationException;

/**
 * Uniform domain-to-wire error mapping for route handlers: domain authorization
 * failures render at their own status ({@code 401}/{@code 403}/{@code 404}/
 * {@code 429}), malformed input renders {@code 400 INVALID_ARGUMENT}, and
 * projector rejections render {@code 404}/{@code 403}. Anything else propagates
 * to the router's {@code 500 INTERNAL_ERROR}.
 */
public final class RouteGuard {

    /** A guarded handler body. */
    @FunctionalInterface
    public interface Handler {
        void handle(RouteContext ctx) throws IOException;
    }

    private RouteGuard() {
    }

    /** Runs {@code handler} with the uniform error mapping applied. */
    public static void run(RouteContext ctx, Handler handler) throws IOException {
        try {
            handler.handle(ctx);
        } catch (SeatAuthorizationException e) {
            ApiJson.error(ctx, e.httpStatus(), e.errorCode(), message(e.errorCode(), e));
        } catch (IllegalArgumentException e) {
            ApiJson.error(ctx, 400, "INVALID_ARGUMENT", message("INVALID_ARGUMENT", e));
        } catch (NoSuchElementException e) {
            ApiJson.error(ctx, 404, "UNKNOWN_TARGET", message("UNKNOWN_TARGET", e));
        }
    }

    private static String message(String fallback, RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : fallback;
    }
}
