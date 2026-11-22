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
package com.kotharworks.auth.jwt;

import java.text.ParseException;
import java.time.Clock;
import java.util.Date;
import java.util.Objects;
import java.util.Set;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthRejectException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.ExpiredJWTException;

/**
 * Production JWT verifier over Nimbus {@link DefaultJWTProcessor}. Signature
 * verification is restricted to asymmetric {@code RS256}/{@code ES256} keys from
 * the supplied {@link JWKSource}, so {@code alg: "none"}, {@code HS256} key
 * confusion, and {@code EdDSA} (which would require Google Tink outside JDK JCA)
 * are hard-rejected. Claims validation is strict ({@code iss}, {@code aud},
 * {@code sub}, {@code exp}, {@code nbf} when present) with a 30-second clock skew.
 *
 * <p>All time comparisons run against the injected {@link Clock} — never against
 * the wall clock — so expiry is deterministic under test. Nimbus's default
 * {@code DefaultJWTClaimsVerifier} calls {@code new Date()} directly, hence the
 * {@link ClockAwareClaimsVerifier#currentTime()} override.
 */
public final class JwtClaimsVerifier {

    private static final int MAX_CLOCK_SKEW_SECONDS = 30;

    private static final Set<JWSAlgorithm> ALLOWED_ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256);

    private final DefaultJWTProcessor<SecurityContext> processor;

    /**
     * @param expectedIssuer  exact {@code iss} every token must carry
     * @param expectedAudience exact {@code aud} every token must carry
     * @param jwkSource       source of asymmetric verification keys
     * @param clock           logical clock all claim-time checks run against
     */
    public JwtClaimsVerifier(String expectedIssuer, String expectedAudience,
            JWKSource<SecurityContext> jwkSource, Clock clock) {
        Objects.requireNonNull(expectedIssuer, "expectedIssuer");
        Objects.requireNonNull(expectedAudience, "expectedAudience");
        Objects.requireNonNull(jwkSource, "jwkSource");
        Objects.requireNonNull(clock, "clock");
        this.processor = new DefaultJWTProcessor<>();
        this.processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALLOWED_ALGORITHMS, jwkSource));
        ClockAwareClaimsVerifier claimsVerifier =
                new ClockAwareClaimsVerifier(expectedIssuer, expectedAudience, clock);
        this.processor.setJWTClaimsSetVerifier(claimsVerifier);
    }

    /**
     * Verifies signature and claims of a serialized JWT.
     *
     * @throws AuthRejectException code {@link AuthErrorCode#TOKEN_EXPIRED} when the
     *         token's {@code exp} has passed on the injected clock, or
     *         {@link AuthErrorCode#INVALID_TOKEN} for any other signature or claim failure
     */
    public JWTClaimsSet verify(String serializedJwt) throws AuthRejectException {
        try {
            return processor.process(serializedJwt, null);
        } catch (ExpiredJWTException e) {
            throw new AuthRejectException(AuthErrorCode.TOKEN_EXPIRED, "token expired: " + e.getMessage());
        } catch (BadJOSEException | JOSEException | ParseException e) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "invalid token: " + e.getMessage());
        }
    }

    /**
     * Claims verifier whose notion of "now" comes from the injected clock.
     * The Nimbus default calls {@code new Date()} directly, which would break
     * deterministic expiry tests.
     *
     * <p>Nimbus 10.10 quirk (bytecode-verified): the
     * {@code (String, JWTClaimsSet, Set)} constructor treats the {@code String}
     * as the <b>accepted audience</b> ({@code acceptedAudienceValues}), not as
     * the issuer. The expected issuer therefore lives in the exact-match claims.
     */
    private static final class ClockAwareClaimsVerifier extends DefaultJWTClaimsVerifier<SecurityContext> {

        private final Clock clock;

        ClockAwareClaimsVerifier(String expectedIssuer, String expectedAudience, Clock clock) {
            super(expectedAudience,
                    new JWTClaimsSet.Builder()
                            .issuer(expectedIssuer)
                            .build(),
                    Set.of("iss", "sub", "aud", "exp"));
            this.clock = clock;
            setMaxClockSkew(MAX_CLOCK_SKEW_SECONDS);
        }

        @Override
        public void verify(JWTClaimsSet claimsSet, SecurityContext context) throws BadJWTException {
            super.verify(claimsSet, context);
            String issuer = claimsSet.getIssuer();
            if (issuer == null || issuer.isBlank()) {
                throw new BadJWTException("JWT iss claim is blank");
            }
            String subject = claimsSet.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new BadJWTException("JWT sub claim is blank");
            }
        }

        @Override
        protected Date currentTime() {
            return Date.from(clock.instant());
        }
    }
}
