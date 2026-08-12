# Security TODO

Outstanding items from the security review. Everything not listed here was fixed in the
remediation pass (C2, C3, H1, H2, H4, M1, M3, M4, M5, M6, M7, M8, M9); the identifiers below are
the ones from that review so the two documents line up.

## Accepted risks (no action planned)

### C1 — Any Google account gets full MCP access

**Decision: accepted.** Every verified Google account is intentionally granted full MCP access;
there is no email/domain allow-list and no tenant-membership check. `oidcUserService()` continues
to require `email_verified` only.

What this means in practice, and why the rest of the design still holds:

- Tenant isolation is per-`subject`, not per-organisation. A user can only ever see the BroadWorks
  connections they themselves created (`DynamoDbResourceStore` scopes every read and write to the
  authenticated subject), so "everyone can log in" does **not** mean "everyone shares data".
- The BroadWorks credentials a user supplies are their own, so the blast radius of an unknown
  account is bounded by what that account can already do against BroadWorks directly.
- The internet-facing surface is now rate-limited by WAF (see M6, fixed) and can no longer be used
  to reach internal hosts (C2, fixed).

If this decision is ever revisited, the change is small: an allow-list check in `oidcUserService()`
plus a re-check in `StoreOpaqueTokenIntrospector` (so already-issued tokens stop working too).

## Open — should be scheduled

### H3 — Ancient, unmaintained dependencies

`pom.xml` still pulls:

- `apache-jcs:1.3` (2007) — the Alpaca response cache configured by `src/main/resources/cache.ccf`.
  Unmaintained and carries known Java-deserialization issues. Note the JEP-290 filters added for
  C3 only cover *our* session/authorization payloads; they do not constrain JCS's own disk-cache
  deserialization.
- `concurrent:concurrent:1.0` (2004) — superseded entirely by `java.util.concurrent`.

Action: migrate the cache to `commons-jcs3` or Caffeine, drop `concurrent:1.0`, then add
`dependency-check-maven` (or Dependabot) so this cannot silently rot again.

Related, lower urgency: `license3j:1.0.7` (2013) and `java-semver:0.9.0` (2017) are stale.

### M2 — Client registration is unauthenticated and only coarsely throttled

`DynamicClientRegistrationController` accepts anonymous `POST /oauth/register` and writes a
90-day (`REGISTERED_CLIENT_TTL:P90D`) DynamoDB item per call. The WAF rate-based rule added for M6
(100 requests / 5 min / IP) blunts the storage/cost amplification but does not remove it.

Remaining work:

- Cap the length of `client_name` and the number of `redirect_uris`.
- Shorten the default registration TTL, or make it proportional to observed use.

### Restrict the scopes accepted at registration

`DynamicClientRegistrationController` echoes back whatever scopes a client asks for. This is
currently harmless because `StoreOpaqueTokenIntrospector` grants `NO_AUTHORITIES` and nothing is
authorised per-scope — but it becomes a real gap the moment scopes start being enforced. Restrict
registration to the known set (`openid`, `email`, `profile`) now, while it is a no-op change.

### Fail fast when Google OIDC is unconfigured

`SecurityConfig` falls back to the placeholder client id `unconfigured-google-client` when
`GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` are absent, so a misconfigured production deployment
starts and then fails at login instead of refusing to boot. Mirror the approach used for the
in-memory storage guard (`StorageConfig.validateInMemoryUsage`): allow the placeholder only under a
dev/local/stdio profile.

### Idle BroadWorks connections are never proactively evicted

`CachingAlpacaConnectionFactory` holds per-user connections — and therefore decrypted BroadWorks
credentials — in a `ConcurrentHashMap` that is only pruned on access. An idle entry stays resident
for the lifetime of the task. Add active eviction on `alpacaProperties.connectionCacheTtl()`
(a scheduled sweep, or Caffeine's `expireAfterAccess` if H3 brings it in anyway).

## Open — minor / operational

- **Unauthenticated log amplification.** `StoreOpaqueTokenIntrospector` and
  `BearerChallengeEntryPoint` log at WARN on every failed introspection, so an anonymous caller can
  inflate CloudWatch volume and cost. Drop to DEBUG, or sample/rate-limit the WARN.
- **A KMS `Decrypt` now runs on the introspection hot path.** `DynamoDbSessionStore.toSession`
  decrypts the stored upstream id token on every token introspection, even though nothing reads it
  today. If this shows up in latency or KMS spend, either stop persisting the upstream tokens
  (nothing consumes them) or decrypt them lazily.
- **AWS account id in a local CDK file.** `cdk/cdk.context.json` contains account `264723482771`.
  It is currently untracked (never committed), so this is only a reminder: keep it that way — add
  it to `.gitignore` or use environment-driven lookups rather than committing cached context.
- **Read-only root filesystem: mount ownership (confirmed on the first deploy).** Fargate does
  create the ephemeral volumes empty and owned by `root:root` 0755 — the image's ownership for
  `/tmp` and `/app/.cache` is *not* inherited — so the non-root app (uid 10001) failed at startup
  with `WebServerException: Unable to create tempDir. java.io.tmpdir is set to /tmp`. The task
  definition now runs a short-lived root `volume-init` container that `chown`s both mounts to
  10001 (and `chmod 1777 /tmp`) before the app container starts (`dependsOn` condition `SUCCESS`);
  both containers keep an immutable root filesystem. Any new writable mount must be added to that
  fix-up list too.
- **WAF managed rule groups are scoped down on the OAuth endpoints (accepted trade-off).** Both
  managed rule groups (`AWSManagedRulesCommonRuleSet`, `AWSManagedRulesKnownBadInputsRuleSet`)
  answered any request carrying a plain `http://` URL with a bare 403 before it reached the app,
  which made RFC 8252 loopback redirect URIs — the ones local MCP clients use — unusable: DCR at
  `/oauth/register`, `/oauth2/authorize` and `/oauth2/token` all failed. Those three paths are now
  excluded from both rule groups via a `scopeDownStatement`; they stay rate limited (100 req / 5 min
  / IP) and strictly validated by the app (exact redirect-URI allowlisting, mandatory PKCE S256,
  public clients only), and every other path — notably `/mcp` — remains fully covered. The exact
  firing rule could not be identified because the deploy IAM user lacks
  `wafv2:ListWebACLs` / `wafv2:GetWebACL` and WAF logging was off. Logging now lands in the
  `aws-waf-logs-broadworks-mcp` log group: revisit and replace the path exclusion with a narrow
  `ruleActionOverrides` for the single offending rule once the logs name it.
