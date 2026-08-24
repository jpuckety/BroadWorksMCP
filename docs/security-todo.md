# Security review TODO

Review date: 2026-08-24

Scope: Spring Boot 4.1 MCP server as an internet-facing OAuth 2.1 authorization server + resource server + BroadWorks admin plane. Covered filter chains, DCR, token/session stores, KMS, portal API, MCP tools, SSRF host checks, logging, Dockerfile, and the CDK/WAF stack.

This is a design/code review, not a live penetration test. Overall posture is **above average for an MCP server**, with real thought put into tokens, encryption, CSRF, CORS, and tenant isolation. Residual risk is still high because any authenticated Google user plus a BroadWorks login can mutate live telephony data through an LLM, and a few controls are checked once then trusted forever.

---

## What is in good shape

- **Split filter chains** with default-deny: AS at highest precedence, portal at `/portal/**` + `/api/portal/**`, everything else on the app chain. Actuator exposure is only `health` and `info`.
- **OAuth 2.1 hygiene**: public DCR only (no client secrets), PKCE required, refresh-token rotation, opaque access tokens, SHA-256 at rest, RFC 8707 audience bound to the configured MCP resource.
- **Redirect allow-list** is structural (scheme/host/port + path-segment), rejects `userinfo`, and does not treat `https://app.example.com.evil.tld` as a prefix of `app.example.com`.
- **Portal CSRF** uses a JS-readable cookie; MCP/DCR CSRF exemptions are limited to non-browser bearer/public-client paths. CORS is origin-allow-listed with `allowCredentials=false`.
- **Secrets**: Google/Alpaca come from SSM; BroadWorks passwords and IdP tokens are KMS-encrypted with an encryption context; `.env` / `alpaca-license.txt` are gitignored; in-memory/unencrypted storage cannot silently go to production.
- **Tenant isolation**: resources are keyed by IdP `sub` + `resourceId`; tools resolve connections only for `UserContext.current()`.
- **Infra**: private Fargate tasks, HTTPS ALB (HTTP only as an explicit opt-out), CMK with rotation + PITR, WAF rate limits, non-root image, read-only root filesystem.

---

## Recommended priority

1. Identity allow-list / Workspace `hd` (if this is not public SaaS).
2. Re-check `HostAllowlist` on every connect/verify.
3. Bearer-only `/mcp`; consent on; no passwords on tools; split or confirm writes.
4. Pin the Google redirect URI; drop default DEBUG on `co.ecg.alpaca`.
5. Disable or tightly IAM-scope ECS Exec; tighten WAF exclusions when you have sampled blocks.

Not done in the original review: live attack, dependency CVE scan, or Alpaca bytecode review.

---

## Critical / high

### [ ] 1. Restrict who can become an operator of this admin plane

`VerifiedEmailOidcUserService` only requires `email_verified=true`. There is no hosted-domain (`hd`), email allow-list, or group check. Combined with `requireAuthorizationConsent(false)`, the first Google login to a dynamically registered public client issues a token that can call every mutating tool.

That is acceptable only if this is intentionally a multi-tenant SaaS and BroadWorks credentials are the real gate. For an internal/enterprise deployment it is an open registration desk onto create/modify user, group, service-pack, and service-authorization tools.

**Fix:** restrict IdP identities (Google Workspace `hd`, allow-list, or IdP groups) and turn consent back on so users see which MCP client they are authorizing.

### [ ] 2. Close the confused-deputy / prompt-injection surface on mutating MCP tools

A valid bearer (or, see item 4, a portal session) can immediately:

- `broadworks_create_user` / `broadworks_modify_user` (create accepts a **password**)
- `broadworks_create_group` / `broadworks_modify_group`
- assign/unassign user and group services, modify SP/group authorizations

There is no confirmation step, dry-run, or tool-level role. Anything the LLM reads from BroadWorks (names, emails, notes) can steer later tool calls. `broadworks_add_connection` correctly refuses passwords; `broadworks_create_user` does the opposite, so BroadWorks user passwords can land in the model transcript.

**Fix:** split read vs write tools (or require a step-up / human confirm for writes); never take passwords on MCP tools; treat tool results as untrusted input to the model.

### [ ] 3. Re-check SSRF host allow-list on every connect (DNS rebinding)

`HostAllowlist` is solid at the moment it runs: all A/AAAA records, IPv4-mapped IPv6, ULA, link-local (including `169.254.169.254`), `localhost`, `metadata.google.internal`. `ConnectionValidation` uses it on both MCP add and the portal.

`CachingAlpacaConnectionFactory` / `LiveAlpacaConnectionFactory` then connect later **without resolving again**. A hostname that is public at save time can be rebound to link-local or RFC1918 before `verify` or the first tool call. Port is attacker-chosen (`1–65535`), so this is not limited to OCI 2208.

On Fargate the interesting targets are `169.254.170.2` (task credentials / metadata) and anything else in the VPC. Alpaca speaks OCI, not arbitrary HTTP, so full IMDS credential theft is not a given — but this is still unauthenticated-to-the-target TCP from your task role’s network.

`ALLOW_PRIVATE_NETWORK_TARGETS=true` disables **all** of this, including the blocked-hostname list. Do not set that in ECS.

**Fix:** re-resolve and re-apply `HostAllowlist` inside `login()` / `verify()` immediately before `server.connect`; optionally pin the allowed addresses for the life of the connection.

---

## Medium

### [ ] 4. Require a bearer token on `/mcp` (do not accept a portal Google session)

The app chain is `anyRequest().authenticated()` with both `oauth2Login` and the opaque resource server. A browser that is signed into `/portal` can `POST /mcp` with the session cookie and no bearer. Those paths are in `CSRF_EXEMPT_PATHS`.

Cross-site browsers are mostly saved by CORS (`credentials: false`) and SameSite=Lax. Same-origin XSS, a malicious extension, or a same-site gadget would get the full tool set without ever completing DCR/PKCE.

**Fix:** require a bearer on `/mcp` and `/sse` (for example `requestMatchers("/mcp", "/mcp/**", "/sse").authenticated()` plus a filter that rejects session-only auth).

### [ ] 5. Pin the Google `redirect_uri` instead of deriving it from the request

`PublicBaseUrlProperties.callbackUri()` exists but `ClientRegistration` is built from `CommonOAuth2Provider.GOOGLE` with no fixed redirect. Spring will build `{baseUrl}/login/oauth2/code/google` from the incoming request. `server.forward-headers-strategy: framework` trusts `X-Forwarded-*` with no trusted-proxy list.

Tasks are only reachable from the ALB, which limits this, but a poisoned `X-Forwarded-Host`/`Proto` can still change the redirect Spring sends to Google if that URI is also registered in the Google client.

**Fix:** set the Google registration redirect to `publicBaseUrl.callbackUri()` and restrict forwarded-header trust to the ALB.

### [ ] 6. Harden open DCR + well-known redirects + missing consent

Unauthenticated `POST /oauth/register` is rate-limited (100/5 min/IP) and redirect-checked. Clients are always materialized as public + PKCE. Residual issues:

- Any scope string is stored (unused for authz today — decorative, but dangerous if you later honor scopes).
- `@NotEmpty` on the DCR record is never activated (`@Valid` is missing); empty-list is checked by hand only.
- `https://vscode.dev/redirect` (and similar) are allow-listed; those relays are a known open-redirect class.
- Loopback HTTP is always allowed (correct for native apps, useless as a remote-phishing sink).

**Fix:** add `@Valid`, ignore/override requested scopes, consider dropping `vscode.dev` unless you need it, and enable consent.

### [ ] 7. Stop defaulting logs to DEBUG

`logback-spring.xml` defaults `co.pitayagroup.mcp.broadworks` and `co.ecg.alpaca` to **DEBUG**. App code is careful not to log secrets; the Alpaca toolkit is a third-party JAR and may log OCI fields (including passwords) at DEBUG. CloudWatch retains a month.

**Fix:** default app/Alpaca to INFO; raise DEBUG only via `LOG_LEVEL_*`.

### [ ] 8. Disable or tightly gate ECS Exec in production

`enableExecuteCommand: true` plus the task role’s DynamoDB + KMS access means `ecs:ExecuteCommand` is equivalent to reading every tenant secret (env has `GOOGLE_CLIENT_SECRET` and `ALPACA_LICENSE_KEY`; KMS can unwrap stored passwords). Fine as break-glass if IAM is tight; dangerous if that action is on a broad role.

**Fix:** disable Exec in prod, or gate it with a dedicated break-glass role and session logging.

### [ ] 9. Narrow WAF managed-rule exclusions

Documented and partly justified (loopback `http://` and JSON-RPC bodies trip Core Rule Set). `/mcp`, `/sse`, `/oauth/register`, `/oauth2/authorize`, `/oauth2/token` rely on app checks + IP rate limits only. `STARTS_WITH /mcp` also skips any future path under that prefix.

**Fix:** keep rate limits; add WAF logging (already present) and narrow exclusions to the specific managed rules that fire, rather than the whole groups.

---

## Low / defense-in-depth

### [ ] 10. Close HostAllowlist gaps

No CGNAT `100.64.0.0/10`, no IPv4 “this network” `0.0.0.0/8` beyond `isAnyLocalAddress()`, no connect-time pin. Decimal/IPv4-mapped forms look handled.

### [ ] 11. Reject `#` in Dynamo sort-key parts

Dynamo sort key is `subject + "#" + resourceId`. A `sub` containing `#` would break `begins_with` isolation. Google `sub` is typically numeric; still worth rejecting `#` in both parts.

### [ ] 12. Tighten serialization allow-list

`SerializationFilters` is the right idea (Dynamo write → RCE). It still allows `java.net.URL` (DNS on deserialize) and broad `java.util.*` / `org.springframework.security.**`. Residual only if the table is already writable.

### [ ] 13. Add method-level security

No `@EnableMethodSecurity` / `@PreAuthorize`. One missed matcher and tools are reachable. Defense in depth belongs on the service/tool methods.

### [ ] 14. Keep error surfaces thin

`/whoami` and `/error` are authenticated/public respectively. Keep `server.error.include-stacktrace=never` (Boot default) and do not expose details on `/actuator/health`.

### [ ] 15. Treat `JAVA_OPTS` as an injection sink

Dockerfile `sh -c "exec java $JAVA_OPTS ..."` is an injection sink if an attacker can set `JAVA_OPTS` (they already own the task definition).

### [ ] 16. Keep portal `UserContext` typed to known principals

Portal `UserContext` only understands `OAuth2AuthenticatedPrincipal` + `sub`. That works for Google `OidcUser`; a future login type would silently 401 rather than widen access.

### [ ] 17. Do not use the stdio profile for isolated multi-user access

stdio profile has no HTTP security and no local principal implementation; tools throw “No authenticated user”. Fine if unused in prod; do not point a desktop client at it expecting isolation.

### [ ] 18. Treat `lib/` Alpaca JARs as a trusted-code boundary

Supply chain: Alpaca JARs are local, not Maven Central. Checksums / signed artifacts.

---

## Follow-up reviews

### [ ] Live attack / penetration test
### [ ] Dependency CVE scan
### [ ] Alpaca bytecode review
