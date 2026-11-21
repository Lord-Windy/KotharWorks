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
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthRejectException;
import com.nimbusds.jwt.JWTClaimsSet;

/**
 * OIDC Authorization Code client with PKCE {@code S256}: builds authorization
 * redirects carrying a 256-bit {@code state}, a 256-bit {@code nonce}, and a
 * 43-char {@code code_verifier} (32 random bytes, RFC 7636) whose SHA-256
 * digest is the {@code code_challenge}; performs the code-for-token exchange
 * over the JDK {@link HttpClient}; and validates the returned {@code id_token}
 * (signature/iss/aud via {@link OidcJwksAuthenticator#jwtVerifier()} plus
 * {@code nonce} equality).
 */
public final class OidcAuthorizationCodeClient {

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final int RANDOM_BYTES = 32; // 256 bits → 43 base64url chars

    private final HttpClient httpClient;
    private final SecureRandom random;

    public OidcAuthorizationCodeClient(HttpClient httpClient, SecureRandom random) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.random = Objects.requireNonNull(random, "random");
    }

    public OidcAuthorizationCodeClient(HttpClient httpClient) {
        this(httpClient, new SecureRandom());
    }

    public OidcAuthorizationCodeClient() {
        this(HttpClient.newHttpClient(), new SecureRandom());
    }

    /** Everything generated at login start that the callback phase must see again. */
    public record AuthorizationRequest(URI authorizationUri, String state, String nonce,
            String codeVerifier, String codeChallenge) {
    }

    /** Flat token-endpoint response fields this client consumes. */
    public record TokenResponse(String accessToken, String idToken, String tokenType, long expiresIn) {
    }

    /** In-flight login: the request plus when it began (for host-side timeouts). */
    public record PendingLogin(AuthorizationRequest request, Instant startedAt) {
    }

    /** Builds the authorization redirect target with PKCE S256 parameters. */
    public PendingLogin begin(OidcConfig config) {
        Objects.requireNonNull(config, "config");
        String state = randomToken();
        String nonce = randomToken();
        String codeVerifier = randomToken();
        String codeChallenge = s256Challenge(codeVerifier);
        String redirectUri = config.publicOrigin() + "/auth/callback";

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
        return new PendingLogin(new AuthorizationRequest(authorizationUri, state, nonce, codeVerifier, codeChallenge),
                Instant.now());
    }

    /** Constant-time {@code state} check; mismatch rejects {@link AuthErrorCode#INVALID_TOKEN}. */
    public void validateCallbackState(PendingLogin pending, String receivedState) {
        Objects.requireNonNull(pending, "pending");
        if (receivedState == null || !MessageDigest.isEqual(
                pending.request().state().getBytes(StandardCharsets.UTF_8),
                receivedState.getBytes(StandardCharsets.UTF_8))) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "state mismatch");
        }
    }

    /**
     * Exchanges the authorization code for tokens (PKCE {@code code_verifier}
     * attached). Non-2xx responses reject with {@link AuthErrorCode#INVALID_TOKEN}.
     */
    public TokenResponse exchangeAuthorizationCode(OidcConfig config, PendingLogin pending, String code) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(code, "code");
        String redirectUri = config.publicOrigin() + "/auth/callback";
        String form = "grant_type=authorization_code"
                + "&code=" + urlEncode(code)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&client_id=" + urlEncode(config.clientId())
                + "&client_secret=" + urlEncode(config.clientSecret())
                + "&code_verifier=" + urlEncode(pending.request().codeVerifier());
        HttpRequest request = HttpRequest.newBuilder(config.tokenEndpoint())
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
        JWTClaimsSet json;
        try {
            json = JWTClaimsSet.parse(response.body());
        } catch (java.text.ParseException e) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "malformed token response");
        }
        String accessToken = stringField(json, "access_token");
        String idToken = stringField(json, "id_token");
        if (accessToken == null || idToken == null) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "token response missing tokens");
        }
        String tokenType = stringField(json, "token_type");
        Object expires = json.getClaim("expires_in");
        long expiresIn = expires instanceof Number number ? number.longValue() : 0L;
        return new TokenResponse(accessToken, idToken, tokenType, expiresIn);
    }

    /**
     * Verifies the {@code id_token} against the provider JWKS and checks the
     * {@code nonce} claim equals the one bound into {@code pending}.
     *
     * @throws AuthRejectException {@link AuthErrorCode#INVALID_TOKEN} on signature,
     *         claim, or nonce failure
     */
    public JWTClaimsSet validateIdToken(OidcJwksAuthenticator authenticator, String idToken, PendingLogin pending) {
        Objects.requireNonNull(authenticator, "authenticator");
        Objects.requireNonNull(pending, "pending");
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
        return claims;
    }

    // ----------------------------------------------------------------- helpers

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

    private static String stringField(JWTClaimsSet json, String name) {
        try {
            return json.getStringClaim(name);
        } catch (java.text.ParseException e) {
            return null;
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
