# OAuth / Session Security Fixes — Plan

**Status:** Partially implemented (foundation only; **does not compile / wire yet**)  
**Goal:** Fix Critical, High, and Medium findings from the OAuth flow & session management review against MCP Authorization (2025-11-25).  
**Config reference:** `.env` (`PUBLIC_HOSTNAME=broadworks.mcp.ecg.co`, DynamoDB, Grok redirect allowlist).

---

## Context (from review)

| Severity | Issue | MCP / OAuth impact |
|----------|--------|---------------------|
| **Critical** | SAS auth codes / refresh grants live in process-local `InMemoryOAuth2AuthorizationService` while ECS `desiredCount: 2` has no stickiness | Intermittent code exchange & refresh failures across tasks |
| **High** | No RFC 8707 `resource` / audience binding; RS only checks token existence + expiry | Violates MCP “MUST validate token audience” |
| **High** | `requireAuthorizationConsent(false)` with static Google client + open DCR | Confused-deputy / consent-skip risk |
| **Medium** | Token rotation does not invalidate prior access-token sessions | Old ATs remain valid until TTL |
| **Medium** | `email_verified` enforced only in unused `GoogleIdentityProvider`; live path is Spring `oauth2Login` | Unverified Google emails accepted |
| **Medium** | Custom-scheme redirects always allowed at DCR | Open redirect surface via DCR |

Out of scope for this issue (Low from review): CIMD, 401 `scope` challenge, README path wording, client expiry soft-check polish beyond what’s needed for store APIs.

---

## Work done so far

New / modified code is **staged or dirty in the working tree** (not committed). Roughly **+755 lines** across 6 files.

### Added (new files)

| File | Purpose |
|------|---------|
| `auth/store/AuthorizationStore.java` | Interface for durable SAS authorizations **and** consents |
| `auth/store/AuthorizationSerialization.java` | JDK serialization for `OAuth2Authorization` / `OAuth2AuthorizationConsent` (both `Serializable`) |
| `auth/store/inmemory/InMemoryAuthorizationStore.java` | Process-local store for tests / IN_MEMORY backend |
| `auth/store/dynamodb/DynamoDbAuthorizationStore.java` | DynamoDB single-table store: `oauth#<id>` payload + `oauthtok#<type>#<value>` pointer items + `oauthconsent#…` |
| `auth/session/StoreBackedAuthorizationConsentService.java` | `OAuth2AuthorizationConsentService` → `AuthorizationStore` |

### Modified

| File | Change |
|------|--------|
| `auth/session/StoreBackedAuthorizationService.java` | **Rewritten** to use `AuthorizationStore` instead of `InMemoryOAuth2AuthorizationService`; on access-token issue/rotate calls `deleteSessionsByAuthorizationId` + writes `Session` with `authorizationId` + `audience`; resolves audience via `publicBaseUrl.mcpResourceUrl()` / optional `resource` param |

### Design decisions already encoded in the new code

1. **Durable SAS state** via JDK-serialized blobs in the existing sessions DynamoDB table (no new CDK table), keyed by `oauth#…` / token pointers — same multi-instance pattern as `DynamoDbHttpSessionRepository`.
2. **Audience** defaults to canonical MCP resource `{baseUrl}/mcp`; foreign `resource` is logged and forced to canonical at session sync (authorize-time rejection still TODO).
3. **Token rotation** intended via `SessionStore.deleteSessionsByAuthorizationId` before creating the new session row.
4. **Consent service** is durable-ready but not yet required by client settings / consent UI / bean wiring.

### Explicitly **not** done yet (still old code)

- `Session` record — still 11 fields; **no** `authorizationId` / `audience`
- `SessionStore` — **no** `deleteSessionsByAuthorizationId`
- `InMemorySessionStore` / `DynamoDbSessionStore` — not updated for new fields, authz pointer, client expiry check
- `PublicBaseUrlProperties` — **no** `mcpResourceUrl()`, `resourceMatches()`, Google callback URI fix
- `StorageConfig` / `AuthorizationServerConfig` — **not** wiring `AuthorizationStore` or consent service beans (new services are orphaned)
- `StoreOpaqueTokenIntrospector` — **no** audience check
- `StoreBackedRegisteredClientRepository` — still `requireAuthorizationConsent(false)`
- Consent page / controller — missing
- RFC 8707 resource validation on authorize/token — missing
- Live `email_verified` on `oauth2Login` — missing
- `RedirectAllowlistProperties` custom-scheme lockdown — missing
- Tests / README / CDK comments — not updated

**Implication:** The tree is currently **inconsistent**. `StoreBackedAuthorizationService` already depends on APIs and constructors that do not exist yet (`deleteSessionsByAuthorizationId`, `Session(…, authorizationId, audience)`, `mcpResourceUrl()`, `resourceMatches()`). Finish remaining wiring before expecting a green build.

---

## Remaining work

### Phase 1 — Make the durable path compile and run (Critical + Medium rotation)

1. **Extend `Session`**
   - Add `authorizationId`, `audience`.
   - Update all `new Session(...)` call sites (tests + DynamoDB mapping).

2. **Extend `SessionStore` + implementations**
   - Add `deleteSessionsByAuthorizationId(String)`.
   - **InMemory:** map `authorizationId → sessionId`; replace on create; enforce client `expiresAt` on `getClient`.
   - **DynamoDB:** persist `authorizationId` / `audience`; write reverse pointer `authz-sess#<authorizationId>`; delete old session on rotation; optional in-process reject of expired DCR clients.

3. **Wire storage beans (`StorageConfig`)**
   - `IN_MEMORY` → `InMemoryAuthorizationStore`
   - `DYNAMODB` → `DynamoDbAuthorizationStore(client, sessionTable)`
   - Same table as sessions/clients is intentional (prefixes avoid collisions).

4. **Wire AS beans (`AuthorizationServerConfig`)**
   - `OAuth2AuthorizationService` → `StoreBackedAuthorizationService(authorizationStore, sessionStore, publicBaseUrl)`
   - `OAuth2AuthorizationConsentService` → `StoreBackedAuthorizationConsentService(authorizationStore)`
   - Remove reliance on bare in-memory SAS defaults.

5. **Update CDK / ops comments** (optional but accurate)
   - `broadworks-mcp-stack.ts` already claims OAuth is multi-instance safe via HTTP sessions only; extend comment that SAS authorizations/consents are now DynamoDB-backed too.

### Phase 2 — Audience / resource indicators (High)

1. **`PublicBaseUrlProperties`**
   - `mcpResourceUrl()` → `baseUrl() + "/mcp"`
   - `resourceMatches(requested, canonical)` (case-insensitive scheme/host, strip trailing slash)
   - Align `callbackUri()` with real Spring path `/login/oauth2/code/google` (or add `googleLoginCallbackUri()` and use it)

2. **`StoreOpaqueTokenIntrospector`**
   - Inject expected resource (`PublicBaseUrlProperties.mcpResourceUrl()`).
   - Reject token if `session.audience()` is null/blank or does not match (MCP MUST).
   - Put `aud` (or resource) on principal attributes for diagnostics if useful.

3. **Authorize / token request validation (preferred complete fix)**
   - If client sends `resource`, reject when it does not match canonical MCP resource (`invalid_target` / appropriate OAuth error).
   - Hook via SAS authorization-endpoint / token-endpoint authentication validators or a small filter.
   - Always bind issued session audience to canonical resource.

4. **PRM already advertises `resource={base}/mcp`** — keep consistent with challenge metadata path `/mcp`.

### Phase 3 — Per-client consent (High)

1. **`StoreBackedRegisteredClientRepository`**
   - Set `requireAuthorizationConsent(true)`.

2. **Consent UI**
   - SAS consent page (e.g. `GET /oauth2/consent`) showing **client name**, **scopes**, **redirect URI**, principal.
   - HTML form POST back to `/oauth2/authorize` with `client_id`, `state`, approved `scope`s (no Thymeleaf required — controller can return HTML).
   - Configure `authorizationEndpoint.consentPage("/oauth2/consent")` on SAS.

3. **Security filter chain**
   - Ensure authenticated browser session can reach consent page (session from Google login).
   - CSRF already disabled on app chain; confirm consent POST works.
   - Consent decisions stored via `StoreBackedAuthorizationConsentService` (already written).

4. **Tests**
   - Consent required for new client; second authorize for same client+user skips consent page when consent stored.

### Phase 4 — Medium leftovers

1. **`email_verified` on live login path**
   - Custom `OidcUserService` (or equivalent) in `SecurityConfig.oauth2Login` that rejects when `email_verified != true`.
   - Keep factor-stamping authorities mapper behavior (auth_time for SAS ID token mint).
   - Leave `GoogleIdentityProvider` for offline claim tests **or** document it as non-production path; fix callback URI consistency.

2. **Redirect allowlist**
   - `RedirectAllowlistProperties`: custom schemes only if they match configured prefixes (same list as HTTPS), not “always allow”.
   - Loopback HTTP remains always allowed.
   - Update README / `.env` comments: custom schemes must be listed in `OAUTH_REDIRECT_ALLOWLIST`.
   - Add unit tests: `cursor://…` rejected unless allowlisted; `http://127.0.0.1/…` still ok.

3. **Dead code hygiene (light)**
   - `OpaqueTokenFactory` remains unused for issuance (SAS generates REFERENCE tokens) — leave or drop bean; not required for security fix.
   - Prefer not deleting `GoogleIdentityProvider` until email_verified is on the live path and tests still cover claims.

### Phase 5 — Tests & verification

1. **Update existing tests** for new `Session` constructor (`authorizationId`, `audience`).
   - `OAuthSecurityIntegrationTest`, `InMemorySessionStoreTest`, `DynamoDbStoresIT`, `McpToolsListIntegrationTest`, etc.

2. **New unit / integration tests**
   - Authorization store round-trip + cross-“instance” findByToken (code / refresh) for in-memory; DynamoDB IT if Docker available.
   - Token rotation: second access token invalidates first session row.
   - Introspector rejects wrong/missing audience.
   - DCR rejects non-allowlisted custom scheme.
   - Consent endpoint reachable / metadata still publishes registration + `none` auth method.
   - Discovery issuer/resource consistency unchanged.

3. **Build**
   - `./scripts/install-alpaca.sh` then `mvn -Pinstall-alpaca test` (or project’s `./run.sh test`).
   - Confirm app starts with `STORAGE_BACKEND=IN_MEMORY` and with DynamoDB config shape.

4. **Docs**
   - README end-to-end auth: durable multi-instance note, consent step, audience binding, challenge `resource_metadata` path with `/mcp`.
   - Comment in CDK stack about SAS authorization durability.

---

## Suggested implementation order

```
1. Session + SessionStore (+ inmem/DDB)     ← unblock compile
2. PublicBaseUrlProperties helpers
3. StorageConfig + AuthorizationServerConfig wiring
4. StoreOpaqueTokenIntrospector audience check
5. Consent flag + consent controller + SAS consentPage
6. Resource parameter validation on authorize/token
7. email_verified OidcUserService
8. Redirect allowlist tighten
9. Tests + README
```

---

## Acceptance criteria

- [ ] With `desiredCount ≥ 2` and no ALB stickiness: authorize on task A, token exchange / refresh on task B succeeds.
- [ ] Access tokens are bound to `https://<PUBLIC_HOSTNAME>/mcp` (or localhost default); RS rejects missing/wrong audience.
- [ ] First use of a DCR client requires explicit MCP consent UI showing client identity; subsequent use can reuse stored consent.
- [ ] Refresh rotation invalidates previous opaque access token in `SessionStore`.
- [ ] Google users without `email_verified=true` cannot complete login.
- [ ] Custom-scheme redirect URIs require allowlist entry; loopback HTTP still works; existing Grok HTTPS prefix still works.
- [ ] `mvn test` green; no secrets committed.

---

## Risk notes

- **JDK serialization** of `OAuth2Authorization` (attributes include Security principal): same class of risk as HTTP session serialization — redeploys with incompatible Security class changes may drop pending auths (user re-auths). Acceptable; match HTTP session behavior.
- **DynamoDB `TransactWriteItems`** 100-item limit: fine for typical token pointer counts; already batched in `DynamoDbAuthorizationStore`.
- **Consent UX** adds a click for every new MCP client id — required for confused-deputy mitigation with DCR + static Google client.
- **Breaking Session shape** requires coordinated test updates; old DynamoDB session rows without `audience` will fail introspection until users re-auth (acceptable).

---

## Current git footprint (at plan write time)

```
A  StoreBackedAuthorizationConsentService.java
M  StoreBackedAuthorizationService.java
A  AuthorizationSerialization.java
A  AuthorizationStore.java
A  DynamoDbAuthorizationStore.java
A  InMemoryAuthorizationStore.java
```

Everything else in this plan is still on the pre-fix baseline.
