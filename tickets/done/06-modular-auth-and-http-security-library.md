# Ticket 06 — Modular AuthN & HTTP Security Library (`libs/auth`)

- **`st` Key:** `bbc37528` (`KOTHARWORKS` · `tech-auth`)
- **Status:** Backlog *(auto-promotes when `4e263046` completes)*
- **Depends on:** Ticket `01` (`4e263046`) *(independent of `libs/wilds`; can run before or alongside Tickets `02–05`)*
- **Modules touched:** Root `pom.xml`, `PROJECTS.md`, `libs/auth` *(new module)*

---

## 1. Objective

Create the standalone, game-agnostic **`libs/auth`** (`com.kotharworks:auth`) module implementing:
1. Strongly typed **`sealed interface Principal`** (`HumanPrincipal`, `ScopedTokenPrincipal`, `AnonymousPrincipal`) separating human OIDC identities from resource-scoped automation keys (preserving the bot-token security boundary from Day 1 without building any bot).
2. **Nimbus JOSE+JWT** asymmetric verification (`RS256` and `ES256` — supported natively by JDK JCA + Nimbus `DefaultJWTProcessor` with zero extra crypto providers) + Keycloak role extraction (`PLAYER`, `RESEARCHER`, `ADMIN`) with an injected `java.time.Clock`.
3. **Loopback-Fused `dev-local` Mode** (`DevLocalJwtIssuer` + `LoopbackStartupFuse` + `/dev/login` HTML persona switcher) exercising the exact production JWT verifier offline, alongside **OIDC JWKS + Authorization Code PKCE (`S256`)** support for drop-in Keycloak (`--auth-mode=oidc`).
4. **Deny-by-Default `AuthenticatedRouter`**, `__Host-kw_session` / `kw_dev_session` BFF cookie codec, and **`CsrfGuard`**.
5. Library-level **`AuthSecurityGateTest`** negative security suite.

---

## 2. Explicit Non-Goals

- **Zero passwords** or password hashing anywhere.
- **Zero dependency on `libs/wilds` or SQL/JDBI:** `libs/auth` defines pure small host-SPI interfaces (`IdentityProvisioner`, `SessionStore`, `ScopedTokenLookup`) implemented in memory or Postgres by the host application (`apps/wilds-server`).

---

## 3. Deliverables & Structure

### 3.1 Maven Module & Dependency Pinning
- Pin `nimbus-jose-jwt` (`com.nimbusds:nimbus-jose-jwt`, version `10.10` — zero transitive runtime dependencies) in root `pom.xml` `<dependencyManagement>`.
- Create `libs/auth/pom.xml`, register `<module>libs/auth</module>` and `com.kotharworks:auth` in root `pom.xml` `<dependencyManagement>`, and add `libs/auth` to `PROJECTS.md`.

### 3.2 Package Layout (`com.kotharworks.auth`)
1. **`principal/`:**
   - `UserId(UUID value)`, `ExternalIdentity(String issuer, String subject)`, `TokenId(long value)`
   - `enum GlobalRole { PLAYER, RESEARCHER, ADMIN }`
   - `sealed interface Principal permits Principal.HumanPrincipal, Principal.ScopedTokenPrincipal, Principal.AnonymousPrincipal`
     *(Note: `ScopedTokenPrincipal` intentionally carries **zero** `GlobalRole`s so automation tokens can never create accounts/seats or mint tokens.)*
2. **`jwt/`:**
   - `JwtClaimsVerifier`: wraps Nimbus `DefaultJWTProcessor` with `JWSVerificationKeySelector` restricted to asymmetric `RS256` and `ES256` (**hard-rejecting `alg: "none"` and `HS256`**, and avoiding `EdDSA` which requires Google Tink outside JDK JCA), plus strict `iss`, `aud`, `sub`, `exp`, `nbf` validation (30s max clock skew). **Important:** subclass Nimbus `DefaultJWTClaimsVerifier` and override `protected Date currentTime() { return Date.from(clock.instant()); }` (because Nimbus's default implementation calls `new Date()` directly!) and distinguish `TOKEN_EXPIRED` vs `INVALID_TOKEN` against `clock.instant()`.
   - `KeycloakRoleClaimExtractor`: extracts `PLAYER`, `RESEARCHER`, `ADMIN` from `realm_access.roles` and `resource_access[clientId].roles` (granting every valid human JWT at least `GlobalRole.PLAYER` and ignoring `offline_access` / `uma_authorization`).
3. **`dev/`:**
   - `LoopbackStartupFuse`: inspects `InetSocketAddress` when `authMode == DEV_LOCAL`; throws `IllegalStateException` immediately if the address is wildcard (`0.0.0.0`, `::`) or non-loopback!
   - `DevLocalJwtIssuer`: generates an ephemeral in-memory `ECKey` (`P-256` / `ES256`), feeds its public JWKSet directly into `JwtClaimsVerifier`, exposes programmatic `mintSignedJwt(persona)` for tests, and renders `GET /dev/login` + `POST /dev/login` with built-in test personas (`dev-alice` `[PLAYER]`, `dev-sam` `[PLAYER, RESEARCHER]`, `dev-admin` `[PLAYER, RESEARCHER, ADMIN]`) — verifying the minted JWT through `JwtClaimsVerifier`, provisioning the `UserId` + BFF session via `IdentityProvisioner` / `SessionStore`, setting the session cookie, and redirecting (`303`) to `/play`.
4. **`oidc/`:**
   - `OidcConfig(String issuerUrl, String clientId, String clientSecret, String publicOrigin, URI authorizationEndpoint, URI tokenEndpoint, URI jwksUri)`
   - `OidcJwksAuthenticator` (remote/cached JWKSource -> `JwtClaimsVerifier`) & `OidcAuthorizationCodeClient` (`256-bit state`, `nonce`, and PKCE `S256` `code_verifier` / `code_challenge` builder + token exchange over JDK `java.net.http.HttpClient`).
5. **`token/`:**
   - `ApiTokenCodec`: generates `iw_bot_<prefix8>_<43charBase64Url>` from 32 bytes of `SecureRandom`, computes 32-byte `SHA-256` digests, and compares digests in constant time via `MessageDigest.isEqual`.
   - `ScopedApiTokenAuthenticator`: resolves `Bearer iw_bot_...` via host-supplied `ScopedTokenLookup` (rejecting revoked tokens or suspended owners).
6. **`http/`:**
   - `enum AccessPolicy { PUBLIC, AUTHENTICATED_ANY, HUMAN_ONLY, ROLE_RESEARCHER, ROLE_ADMIN }`
   - `CookieCodec`: `__Host-kw_session` (`HttpOnly; Secure; Path=/; SameSite=Lax` for HTTPS origins) and `kw_dev_session` (`HttpOnly; Path=/; SameSite=Lax` on loopback HTTP), hashing raw cookie tokens via `SHA-256` before store lookup.
   - `CsrfGuard`: enforced on every non-`PUBLIC` cookie-authenticated mutating (`POST`/`PUT`/`DELETE`/`PATCH`) request — rejects `Sec-Fetch-Site: cross-site` and any present `Origin` that does not match the configured `publicOrigin` (or bound `http://127.0.0.1:<boundPort>` / `http://[::1]:<boundPort>` in `DEV_LOCAL` mode so ephemeral `port = 0` integration tests work seamlessly!), and requires either `Content-Type: application/json` or a matching per-session `csrf_token` (`X-CSRF-Token` header or form field). `Authorization: Bearer` requests and `PUBLIC` login routes (`/dev/login`) bypass cookie CSRF cleanly even if a stale cookie is present.
   - `AuthenticatedRouter`: wraps `com.sun.net.httpserver.HttpServer` (configured with `Executors.newVirtualThreadPerTaskExecutor()`), mounting a single root `HttpContext("/")` dispatcher that matches `(method, pathPattern)` with `{param}` extraction (returning `404` on unknown paths and `405` on wrong method), requiring an explicit `AccessPolicy` on every registered route, and exposing `registeredRoutes()` for automated security audit!

---

## 4. Tests & Acceptance Criteria (`AuthSecurityGateTest` — Library Checks)

1. **Unannotated Route Audit & Default `401`:** Every registered non-`PUBLIC` route returns `401` with no credentials; calling unregistered paths returns `404`.
2. **Non-Loopback `dev-local` Startup Fuse:** `LoopbackStartupFuse.verify(DEV_LOCAL, new InetSocketAddress("0.0.0.0", 8080))` and LAN IPs throw `IllegalStateException`; `127.0.0.1` and `::1` pass.
3. **Tampered JWT Payload:** Editing Base64 claims (`"roles":["ADMIN"]`) without the private key returns `401 INVALID_TOKEN`.
4. **`alg: "none"` & `HS256` Key-Confusion Attack:** Unsigned JWTs and symmetric `HS256` JWTs signed with the public key bytes return `401 INVALID_TOKEN`.
5. **Deterministic Clock Expiry (`0ms` sleep):** Advancing a mutable test `Clock` `+6 minutes` past `exp` immediately rejects the token with `401 TOKEN_EXPIRED`.
6. **Wrong Issuer (`iss`) or Wrong Audience (`aud`):** Validly signed ES256 JWTs with wrong `iss` or `aud` return `401 INVALID_TOKEN`.
7. **OIDC PKCE S256 & State/Nonce Verification:** Round-trip test against a local loopback mock OIDC token/JWKS server verifying PKCE `S256` challenge, `state` mismatch rejection, and `nonce` check.
8. **Cookie CSRF Rejection:** Cookie-authenticated `POST` with `Origin: https://evil.example` or `Sec-Fetch-Site: cross-site` returns `403 CSRF_REJECTED` before invoking the route handler!
9. **`ApiTokenCodec` & Revocation/Suspension:** Generated `iw_bot_...` token verifies via `SHA-256`, rejects mutated secret bytes, rejects revoked tokens (`401`), and rejects suspended owner (`403 ACCOUNT_SUSPENDED`).
