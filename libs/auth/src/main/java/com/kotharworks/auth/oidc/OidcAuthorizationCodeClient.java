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
package com.kotharworks.auth.oidc;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthRejectException;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;

/**
 * OIDC Authorization Code client with PKCE {@code S256}: builds authorization
 * redirects carrying a 256-bit {@code state}, a 256-bit {@code nonce}, and a
 * 43-char {@code code_verifier} (32 random bytes, RFC 7636) whose SHA-256
 * digest is the {@code code_challenge}; tracks bounded, single-use, TTL-gated
 * {@link PendingLogin} state against an injected {@link Clock}; performs the
 * timeout-bounded code-for-token exchange over the JDK {@link HttpClient}; and
 * validates the returned {@code id_token} (signature/iss/aud via
 * {@link OidcJwksAuthenticator#jwtVerifier()} plus {@code nonce} and multi-aud
 * {@code azp} checks).
 */
public final class OidcAuthorizationCodeClient {

    /** Default TCP/TLS connect timeout for the OIDC token endpoint. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Default per-request timeout for the OIDC token endpoint. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** Default maximum lifetime of an in-flight OIDC authorization dance. */
    public static final Duration DEFAULT_PENDING_LOGIN_TTL = Duration.ofMinutes(10);

    private static final int MAX_PENDING_LOGINS = 4_096;
    private static final int MAX_TOKEN_RESPONSE_CHARS = 65_536;
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final int RANDOM_BYTES = 32; // 256 bits → 43 base64url chars

    private final HttpClient httpClient;
    private final SecureRandom random;
    private final Clock clock;
    private final Duration requestTimeout;
    private final Duration pendingLoginTtl;

    private final Object pendingLock = new Object();
    private final Map<String, PendingSlot> pendingLogins = new LinkedHashMap<>();

    private static final class PendingSlot {

        final PendingLogin pending;
        boolean stateConsumed;
        boolean codeExchanged;
        boolean idTokenValidated;

        PendingSlot(PendingLogin pending) {
            this.pending = pending;
        }
    }

    public OidcAuthorizationCodeClient(HttpClient httpClient, SecureRandom random, Clock clock,
            Duration requestTimeout, Duration pendingLoginTtl) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.pendingLoginTtl = Objects.requireNonNull(pendingLoginTtl, "pendingLoginTtl");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (pendingLoginTtl.isZero() || pendingLoginTtl.isNegative()) {
            throw new IllegalArgumentException("pendingLoginTtl must be positive");
        }
    }

    public OidcAuthorizationCodeClient(HttpClient httpClient, SecureRandom random, Clock clock) {
        this(httpClient, random, clock, DEFAULT_REQUEST_TIMEOUT, DEFAULT_PENDING_LOGIN_TTL);
    }

    public OidcAuthorizationCodeClient(HttpClient httpClient, SecureRandom random) {
        this(httpClient, random, Clock.systemUTC(), DEFAULT_REQUEST_TIMEOUT, DEFAULT_PENDING_LOGIN_TTL);
    }

    public OidcAuthorizationCodeClient(HttpClient httpClient) {
        this(httpClient, new SecureRandom(), Clock.systemUTC(), DEFAULT_REQUEST_TIMEOUT, DEFAULT_PENDING_LOGIN_TTL);
    }

    public OidcAuthorizationCodeClient(Clock clock) {
        this(defaultHttpClient(), new SecureRandom(), clock, DEFAULT_REQUEST_TIMEOUT, DEFAULT_PENDING_LOGIN_TTL);
    }

    public OidcAuthorizationCodeClient() {
        this(defaultHttpClient(), new SecureRandom(), Clock.systemUTC(),
                DEFAULT_REQUEST_TIMEOUT, DEFAULT_PENDING_LOGIN_TTL);
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Everything generated at login start that the callback phase must see again. */
    public record AuthorizationRequest(URI authorizationUri, String state, String nonce,
            String codeVerifier, String codeChallenge) {
    }

    /** Flat token-endpoint response fields this client consumes. */
    public record TokenResponse(String accessToken, String idToken, String tokenType, long expiresIn) {
    }

    /** In-flight login: the request plus when it began on the injected clock. */
    public record PendingLogin(AuthorizationRequest request, Instant startedAt) {
    }

    /** Verified result of completing an OIDC authorization-code callback end-to-end. */
    public record CompletedLogin(TokenResponse tokens, JWTClaimsSet idTokenClaims) {
    }

    /**
     * Builds the authorization redirect target with PKCE S256 parameters and
     * registers the single-use {@link PendingLogin} for callback verification.
     */
    public PendingLogin begin(OidcConfig config) {
        Objects.requireNonNull(config, "config");
        String state = randomToken();
        String nonce = randomToken();
        String codeVerifier = randomToken();
        String codeChallenge = s256Challenge(codeVerifier);
        String redirectUri = callbackRedirectUri(config);

        String query = "response_type=code"
                + "&client_id=" + urlEncode(config.clientId())
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&scope=openid"
                + "&state=" + urlEncode(state)
                + "&nonce=" + urlEncode(nonce)
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=S256";
        URI endpoint = config.authorizationEndpoint();
        String separator = endpoint.getQuery() == null ? "?" : "&";
        URI authorizationUri = URI.create(endpoint.toString() + separator + query);
        Instant now = clock.instant();
        PendingLogin pending = new PendingLogin(
                new AuthorizationRequest(authorizationUri, state, nonce, codeVerifier, codeChallenge), now);
        synchronized (pendingLock) {
            evictExpiredLocked(now);
            while (pendingLogins.size() >= MAX_PENDING_LOGINS) {
                Iterator<String> it = pendingLogins.keySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
            pendingLogins.put(state, new PendingSlot(pending));
        }
        return pending;
    }

    /**
     * Completes an OIDC callback end-to-end: atomically consumes the single-use
     * {@code state}, verifies {@link PendingLogin} TTL against the injected
     * clock, exchanges {@code code} with the PKCE {@code code_verifier}, and
     * verifies the returned {@code id_token} against JWKS + {@code nonce}.
     */
    public CompletedLogin completeAuthorizationCode(OidcConfig config, OidcJwksAuthenticator authenticator,
            String receivedState, String code) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(authenticator, "authenticator");
        if (receivedState == null || receivedState.isBlank() || code == null || code.isBlank()) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "missing state or authorization code");
        }
        PendingLogin pending;
        synchronized (pendingLock) {
            evictExpiredLocked(clock.instant());
            PendingSlot slot = pendingLogins.get(receivedState);
            pending = slot == null ? null : slot.pending;
        }
        if (pending == null) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "unknown or expired login state");
        }
        try {
            validateCallbackState(pending, receivedState);
            TokenResponse tokens = exchangeAuthorizationCode(config, pending, code);
            JWTClaimsSet claims = validateIdToken(authenticator, tokens.idToken(), pending);
            return new CompletedLogin(tokens, claims);
        } finally {
            synchronized (pendingLock) {
                pendingLogins.remove(pending.request().state());
            }
        }
    }

    /**
     * Constant-time, single-use, TTL-gated {@code state} check; mismatch,
     * expiry, or replay rejects with {@link AuthErrorCode#INVALID_TOKEN}.
     */
    public void validateCallbackState(PendingLogin pending, String receivedState) {
        Objects.requireNonNull(pending, "pending");
        if (receivedState == null || !MessageDigest.isEqual(
                pending.request().state().getBytes(StandardCharsets.UTF_8),
                receivedState.getBytes(StandardCharsets.UTF_8))) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "state mismatch");
        }
        synchronized (pendingLock) {
            PendingSlot slot = requireActiveSlotLocked(pending);
            if (slot.stateConsumed) {
                pendingLogins.remove(pending.request().state());
                throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "login state already used");
            }
            slot.stateConsumed = true;
        }
    }

    /**
     * Exchanges the authorization code for tokens (PKCE {@code code_verifier}
     * attached, single-use per {@link PendingLogin}). Non-2xx responses,
     * timeouts, or expired/replayed logins reject with
     * {@link AuthErrorCode#INVALID_TOKEN}.
     */
    public TokenResponse exchangeAuthorizationCode(OidcConfig config, PendingLogin pending, String code) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "blank authorization code");
        }
        synchronized (pendingLock) {
            PendingSlot slot = requireActiveSlotLocked(pending);
            if (slot.codeExchanged) {
                pendingLogins.remove(pending.request().state());
                throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "authorization code already exchanged");
            }
            slot.codeExchanged = true;
        }
        String redirectUri = callbackRedirectUri(config);
        String form = "grant_type=authorization_code"
                + "&code=" + urlEncode(code)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&client_id=" + urlEncode(config.clientId())
                + "&client_secret=" + urlEncode(config.clientSecret())
                + "&code_verifier=" + urlEncode(pending.request().codeVerifier());
        HttpRequest request = HttpRequest.newBuilder(config.tokenEndpoint())
                .timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "token endpoint I/O failure: "
                    + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "token exchange interrupted");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN,
                    "token endpoint returned " + response.statusCode());
        }
        Map<String, Object> json;
        try {
            json = JSONObjectUtils.parse(response.body(), MAX_TOKEN_RESPONSE_CHARS);
        } catch (java.text.ParseException e) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "malformed token response");
        }
        String accessToken = stringField(json, "access_token");
        String idToken = stringField(json, "id_token");
        if (accessToken == null || accessToken.isBlank() || idToken == null || idToken.isBlank()) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "token response missing tokens");
        }
        String tokenType = stringField(json, "token_type");
        Object expires = json.get("expires_in");
        long expiresIn = expires instanceof Number number ? number.longValue() : 0L;
        return new TokenResponse(accessToken, idToken, tokenType, expiresIn);
    }

    /**
     * Verifies the {@code id_token} against the provider JWKS, checks the
     * {@code nonce} claim equals the one bound into {@code pending}, enforces
     * OIDC multi-audience {@code azp} binding when applicable, and retires the
     * single-use {@link PendingLogin}.
     *
     * @throws AuthRejectException {@link AuthErrorCode#INVALID_TOKEN} or
     *         {@link AuthErrorCode#TOKEN_EXPIRED} on signature, claim, TTL, or nonce failure
     */
    public JWTClaimsSet validateIdToken(OidcJwksAuthenticator authenticator, String idToken, PendingLogin pending) {
        Objects.requireNonNull(authenticator, "authenticator");
        Objects.requireNonNull(pending, "pending");
        if (idToken == null || idToken.isBlank()) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "missing id_token");
        }
        synchronized (pendingLock) {
            PendingSlot slot = requireActiveSlotLocked(pending);
            if (slot.idTokenValidated) {
                pendingLogins.remove(pending.request().state());
                throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "pending login already completed");
            }
            slot.idTokenValidated = true;
            pendingLogins.remove(pending.request().state());
        }
        JWTClaimsSet claims = authenticator.jwtVerifier().verify(idToken);
        String nonce;
        try {
            nonce = claims.getStringClaim("nonce");
        } catch (java.text.ParseException e) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "malformed nonce claim");
        }
        String expectedNonce = pending.request().nonce();
        if (nonce == null || !MessageDigest.isEqual(
                expectedNonce.getBytes(StandardCharsets.UTF_8), nonce.getBytes(StandardCharsets.UTF_8))) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "nonce mismatch");
        }
        verifyAuthorizedParty(claims, authenticator.clientId());
        return claims;
    }

    // ----------------------------------------------------------------- helpers

    private PendingSlot requireActiveSlotLocked(PendingLogin pending) {
        Instant now = clock.instant();
        String state = pending.request().state();
        PendingSlot slot = pendingLogins.get(state);
        if (slot == null || slot.pending != pending) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "unknown or already-consumed login state");
        }
        if (isExpired(pending, now)) {
            pendingLogins.remove(state);
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "login state expired");
        }
        return slot;
    }

    private void evictExpiredLocked(Instant now) {
        pendingLogins.values().removeIf(slot -> isExpired(slot.pending, now));
    }

    private boolean isExpired(PendingLogin pending, Instant now) {
        return now.isBefore(pending.startedAt())
                || !now.isBefore(pending.startedAt().plus(pendingLoginTtl));
    }

    private static void verifyAuthorizedParty(JWTClaimsSet claims, String expectedClientId) {
        String azp;
        try {
            azp = claims.getStringClaim("azp");
        } catch (java.text.ParseException e) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "malformed azp claim");
        }
        if (azp != null && !azp.equals(expectedClientId)) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "azp claim mismatch");
        }
        if (claims.getAudience() != null && claims.getAudience().size() > 1 && !expectedClientId.equals(azp)) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN,
                    "multi-audience id_token missing matching azp claim");
        }
    }

    private static String callbackRedirectUri(OidcConfig config) {
        String origin = config.publicOrigin().trim();
        while (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length() - 1);
        }
        return origin + "/auth/callback";
    }

    private String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    /** RFC 7636 {@code S256}: {@code BASE64URL(SHA256(ASCII(code_verifier)))}. */
    private static String s256Challenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return BASE64URL.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK is missing SHA-256", e);
        }
    }

    private static String stringField(Map<String, Object> json, String name) {
        try {
            return JSONObjectUtils.getString(json, name);
        } catch (java.text.ParseException e) {
            return null;
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
