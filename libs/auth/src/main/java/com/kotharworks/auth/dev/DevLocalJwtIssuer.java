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
package com.kotharworks.auth.dev;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.kotharworks.auth.http.AccessPolicy;
import com.kotharworks.auth.http.AuthenticatedRouter;
import com.kotharworks.auth.http.CookieCodec;
import com.kotharworks.auth.jwt.JwtClaimsVerifier;
import com.kotharworks.auth.jwt.KeycloakRoleClaimExtractor;
import com.kotharworks.auth.principal.ExternalIdentity;
import com.kotharworks.auth.spi.IdentityProvisioner;
import com.kotharworks.auth.spi.SessionStore;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Loopback-only local issuer for {@code dev-local} mode: generates an
 * ephemeral in-memory ES256 ({@code P-256}) signing key and feeds its public
 * JWK set into the <b>exact production</b> {@link JwtClaimsVerifier}, so dev
 * logins exercise the same verification path as real OIDC tokens. Exposes
 * programmatic minting for tests plus the {@code GET}/{@code POST /dev/login}
 * persona-switcher handlers, which mint a persona JWT, verify it, provision
 * the identity, create the BFF session, and redirect to {@code /play} with
 * the session cookie set.
 */
public final class DevLocalJwtIssuer {

    /** TTL of dev persona JWTs. */
    public static final Duration JWT_TTL = Duration.ofMinutes(5);

    private static final String KEY_ID = "dev-local-es256";
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(12);
    private static final String REDIRECT_AFTER_LOGIN = "/play";

    private final String issuer;
    private final String audience;
    private final Clock clock;
    private final IdentityProvisioner provisioner;
    private final SessionStore sessions;
    private final CookieCodec cookies;
    private final Duration sessionTtl;
    private final ECKey signingKey;
    private final JwtClaimsVerifier verifier;

    public DevLocalJwtIssuer(String issuer, String audience, Clock clock, IdentityProvisioner provisioner,
            SessionStore sessions, CookieCodec cookies, Duration sessionTtl) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.cookies = Objects.requireNonNull(cookies, "cookies");
        this.sessionTtl = sessionTtl != null ? sessionTtl : DEFAULT_SESSION_TTL;
        try {
            this.signingKey = new ECKeyGenerator(Curve.P_256)
                    .keyID(KEY_ID)
                    .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot generate dev-local EC key", e);
        }
        this.verifier = new JwtClaimsVerifier(issuer, audience,
                new ImmutableJWKSet<SecurityContext>(
                        new com.nimbusds.jose.jwk.JWKSet(List.of(signingKey.toPublicJWK()))), clock);
    }

    /** Convenience constructor with the default 12h session TTL. */
    public DevLocalJwtIssuer(String issuer, String audience, Clock clock, IdentityProvisioner provisioner,
            SessionStore sessions, CookieCodec cookies) {
        this(issuer, audience, clock, provisioner, sessions, cookies, DEFAULT_SESSION_TTL);
    }

    /** The production verifier wired to this issuer's ephemeral public key. */
    public JwtClaimsVerifier verifier() {
        return verifier;
    }

    /** The public half of the ephemeral signing key (for tests/audits). */
    public ECKey publicKey() {
        return signingKey.toPublicJWK();
    }

    /** Mints a persona JWT with the default 5-minute TTL. */
    public String mintSignedJwt(DevPersona persona) {
        return mintSignedJwt(persona, issuer, audience, JWT_TTL);
    }

    /**
     * Mints a persona JWT with explicit issuer/audience/TTL — the override
     * hooks the security gate uses for wrong-iss/aud and expiry attacks.
     */
    public String mintSignedJwt(DevPersona persona, String issuerOverride, String audienceOverride,
            Duration ttl) {
        Objects.requireNonNull(persona, "persona");
        Instant now = clock.instant();
        Instant expiry = now.plus(ttl != null ? ttl : JWT_TTL);
        List<String> roleNames = persona.roles().stream()
                .map(Enum::name)
                .sorted()
                .toList();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuerOverride)
                .subject(persona.subject())
                .audience(audienceOverride)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .notBeforeTime(Date.from(now))
                .claim("preferred_username", persona.displayName())
                .claim("realm_access", Map.of("roles", roleNames))
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(signingKey.getKeyID())
                .type(com.nimbusds.jose.JOSEObjectType.JWT)
                .build();
        SignedJWT signedJwt = new SignedJWT(header, claims);
        try {
            signedJwt.sign(new ECDSASigner(signingKey));
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign dev-local JWT", e);
        }
        return signedJwt.serialize();
    }

    /** {@code GET /dev/login}: renders the persona switcher (register as {@link AccessPolicy#PUBLIC}). */
    public AuthenticatedRouter.RouteHandler loginPageHandler() {
        return ctx -> ctx.respondText(200, "text/html; charset=utf-8", renderLoginPage());
    }

    /**
     * {@code POST /dev/login}: parses {@code persona=<subject>} from the form
     * body, mints + verifies the persona JWT, provisions the identity, creates
     * the BFF session, and answers {@code 303} to {@code /play} with the
     * session cookie set. Unknown personas get {@code 400}.
     */
    public AuthenticatedRouter.RouteHandler loginSubmitHandler() {
        return ctx -> {
            String subject = formField(ctx.requestBody(), "persona");
            DevPersona persona = subject == null ? null : DevPersona.fromSubject(subject).orElse(null);
            if (persona == null) {
                ctx.respondJson(400,
                        "{\"errorCode\":\"UNKNOWN_PERSONA\",\"message\":\"unknown persona\"}");
                return;
            }
            String jwt = mintSignedJwt(persona);
            var claims = verifier.verify(jwt); // same gate a production token passes
            var roles = KeycloakRoleClaimExtractor.extract(claims, null);
            var externalId = new ExternalIdentity(claims.getIssuer(), claims.getSubject());
            var userId = provisioner.provision(externalId, persona.displayName());
            var created = sessions.create(userId, externalId, persona.displayName(), roles,
                    clock.instant().plus(sessionTtl));
            ctx.exchange().getResponseHeaders().set("Set-Cookie",
                    cookies.setCookieHeaderValue(created.rawSessionToken()));
            ctx.respondRedirect(REDIRECT_AFTER_LOGIN);
        };
    }

    // ------------------------------------------------------------------ HTML

    private String renderLoginPage() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">")
                .append("<title>dev-local login</title></head>\n<body>\n")
                .append("<h1>dev-local login</h1>\n<p>Loopback persona switcher. Choose a persona:</p>\n");
        for (DevPersona persona : DevPersona.all()) {
            html.append("<form method=\"post\" action=\"/dev/login\">")
                    .append("<input type=\"hidden\" name=\"persona\" value=\"")
                    .append(escapeHtml(persona.subject()))
                    .append("\">")
                    .append("<button type=\"submit\">")
                    .append(escapeHtml(persona.displayName()))
                    .append(" (")
                    .append(escapeHtml(String.join(", ", persona.roles().stream()
                            .map(Enum::name)
                            .sorted()
                            .toList())))
                    .append(")</button></form>\n");
        }
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String formField(byte[] body, String field) {
        String form = new String(body, StandardCharsets.UTF_8);
        try {
            for (String pair : form.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String name = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (name.equals(field)) {
                    return value;
                }
            }
        } catch (IllegalArgumentException malformedPercentEncoding) {
            return null;
        }
        return null;
    }
}
