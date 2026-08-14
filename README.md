# broadworks-mcp

A production-ready **MCP (Model Context Protocol) server** that exposes BroadWorks operations to MCP
clients (LLM agents, desktop apps). It is a single **Spring Boot 3.x / Java 21** application built
with **Maven**, using **Spring AI MCP** for the server transport and calling the **Alpaca toolkit**
(`co.ecg.alpaca.toolkit`) directly as the sole BroadWorks interface.

The server is simultaneously:

- an **OAuth 2.1 Authorization Server (AS)** (Spring Authorization Server) fronting **Google OIDC**,
- a **Resource Server (RS)** guarding MCP tool calls with opaque bearer tokens, and
- a **Spring AI MCP server** exposing BroadWorks tools.

Storage is pluggable: **DynamoDB (durable, default)** or **in-memory (fallback)**, with secret fields
encrypted via a **customer-managed KMS key**.

---

## Alpaca license required

> **Use of the Alpaca libraries requires the purchase of an Alpaca License key from ECG, Inc.**
> Developers can email **[jpuckett@e-c-group.com](mailto:jpuckett@e-c-group.com)** for pricing and
> more information.

The server calls the ECG Alpaca toolkit (`co.ecg.alpaca.toolkit`) as its sole BroadWorks interface,
so a valid Alpaca license is required at runtime. The key can be supplied inline via the
`ALPACA_LICENSE_KEY` environment variable (see [Environment variables](#environment-variables) and
[Notes & limitations](#notes--limitations)).

---

## Architecture

```
MCP client ──bearer──▶ Resource Server (local opaque introspection) ──▶ Spring AI MCP (/mcp)
     │                                                                     └▶ ServiceProvider & Group @Tool
     └──DCR / auth-code + PKCE──▶ Spring Authorization Server ──oauth2Login──▶ Google OIDC
                                            │                                       │
                                            ▼                                       ▼
                                       SessionStore ◀───────────── sessions ── DynamoDB / in-memory
   Tools ──▶ AlpacaConnectionFactory ──▶ ResourceStore (KMS-encrypted secrets) ──▶ Alpaca toolkit ──▶ BroadWorks OCI
```

- **OAuth endpoints** use Spring Authorization Server / Google **defaults**
  (`/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, discovery at
  `/.well-known/oauth-authorization-server`, Google callback `/login/oauth2/code/google`).
- **Dynamic Client Registration** (RFC 7591, public clients only) is at `POST /oauth/register`.
- **Protected Resource Metadata** (RFC 9728) is at `GET /.well-known/oauth-protected-resource`
  (and its trailing-slash variant).
- **MCP** is served at the Spring AI defaults (rooted at `/mcp`, SSE at `/sse`).

---

## Prerequisites

- **JDK 21** (the build targets `release=21`).
- **Maven 3.9+**.
- The **Alpaca toolkit JARs** under `lib/` (supplied):
  - `alpaca-commons-12.2.0-RELEASE.jar`
  - `alpaca-model-12.2.0-RELEASE.jar`
  - `alpaca-core-12.2.0-RELEASE-26.jar`
  - `alpaca-library-12.2.0-RELEASE-26.jar`
  - (`alpaca-server-…jar` is kept for reference only and is **not** placed on the classpath.)
- For deployment: **Node 18+**, **AWS CDK v2**, and AWS credentials.

---

## Installing the Alpaca toolkit JARs

The toolkit is not on Maven Central; install the supplied JARs into your local Maven repository with
**minimal generated POMs** (no transitive Spring Boot 2.7 dependencies):

```bash
./scripts/install-alpaca.sh
```

Or let Maven do it on first build via the bundled profile:

```bash
mvn -Pinstall-alpaca clean verify
```

> The install is done with `install:install-file` (not `system` scope) so the `spring-boot-maven-plugin`
> repackage and the container image work correctly.

---

## Build & test

```bash
# Install Alpaca JARs first (once), then:
mvn -Pinstall-alpaca clean verify
```

> **Tip:** the repo-root `run.sh` wraps the common build and deploy/undeploy
> actions. Run `./run.sh help` for the full list; e.g. `./run.sh all`
> (install-alpaca + verify), `./run.sh build`, `./run.sh test`, `./run.sh run`,
> `./run.sh deploy` (set `PUBLIC_HOSTNAME` in `.env` to build the URL and the
> certificate), `./run.sh undeploy`.

- Unit tests cover the token factory, both stores, encryption, ID-token verification, and the MCP
  tools.
- One `@SpringBootTest` boots the full app (in-memory stores) and verifies OAuth discovery, dynamic
  client registration, the 401 bearer challenge, and Resource-Server enforcement.
- The DynamoDB/KMS integration test runs against **LocalStack** via Testcontainers and is **skipped
  automatically when Docker is unavailable**.

No real Google or BroadWorks network calls are ever made in tests.

---

## Running locally

### HTTP transport (default, port 8080)

```bash
STORAGE_BACKEND=IN_MEMORY \
java -jar target/broadworks-mcp-*.jar
```

(No `PUBLIC_HOSTNAME` locally: the base URL defaults to `http://localhost:8080`.)

- Health: `GET http://localhost:8080/actuator/health`
- AS metadata: `GET http://localhost:8080/.well-known/oauth-authorization-server`
- Resource metadata: `GET http://localhost:8080/.well-known/oauth-protected-resource`

### stdio transport (desktop clients)

```bash
java -Dspring.profiles.active=stdio -jar target/broadworks-mcp-*.jar
```

Under the `stdio` profile the web server is disabled, MCP is served over stdin/stdout, storage is
in-memory, and **all logging goes to stderr** (stdout is reserved for the MCP protocol).

---

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `PUBLIC_HOSTNAME` | *(empty)* | Public DNS hostname (no scheme/path), e.g. `mcp.example.com`. The base URL is built as `https://<hostname>` and used for discovery docs and the `resource_metadata` challenge. Empty locally → `http://localhost:8080`. |
| `FORWARD_HEADERS_STRATEGY` | `framework` | How to honor `X-Forwarded-*` headers from the TLS-terminating ALB so request-derived URLs (e.g. the OAuth2 login callback `redirect_uri`) use the external `https` scheme. Set to `none` only when the app is not behind a trusted proxy. |
| `OIDC_ISSUER_URI` | `https://accounts.google.com` | Upstream OIDC issuer. |
| `GOOGLE_CLIENT_ID` | *(empty)* | Google OAuth client id. |
| `GOOGLE_CLIENT_SECRET` | *(empty)* | Google OAuth client secret. |
| `STORAGE_BACKEND` | `DYNAMODB` | `DYNAMODB` (durable) or `IN_MEMORY` (local/tests). |
| `SESSION_TABLE` | `broadworks-mcp-sessions` | DynamoDB table for issued opaque-token sessions, registered clients and SAS authorizations. |
| `HTTP_SESSION_TABLE` | `broadworks-mcp-http-sessions` | DynamoDB table for the interactive Google-login HTTP sessions (own lifecycle and id space, hence its own table). |
| `USER_CONFIG_TABLE` | `broadworks-mcp-user-config` | DynamoDB per-user resource table. |
| `KMS_KEY_ID` | *(empty)* | Customer-managed KMS key id/ARN for secret encryption (required for `DYNAMODB`). |
| `AWS_REGION` | *(SDK default)* | AWS region for DynamoDB/KMS. |
| `APPLICATION_ID` | `broadworks-mcp` | Partition key for the per-user resource table. |
| `OAUTH_REDIRECT_ALLOWLIST` | *(empty)* | Comma-separated list of allowed redirect-URI prefixes for HTTPS **and** custom schemes (e.g. `https://app.example.com/,cursor://`). Loopback HTTP (`127.0.0.1` / `localhost`) is always allowed. |
| `OAUTH_ALLOW_WELL_KNOWN_CLIENTS` | `true` | Also allow the callbacks of the well-known hosted MCP clients (`https://claude.ai/api/mcp/auth_callback`, `https://claude.com/api/mcp/auth_callback`, `https://chatgpt.com/connector_platform_oauth_redirect`, `https://grok.com/connectors-oauth-exchange-code`, `https://vscode.dev/redirect`, `https://insiders.vscode.dev/redirect`) so they register without extra configuration. Set `false` to accept only `OAUTH_REDIRECT_ALLOWLIST`. |
| `CORS_ENABLED` | `true` | Whether CORS (and therefore `OPTIONS` preflight) is handled on `/mcp`, `/.well-known/**`, `/oauth/register` and `/oauth2/**`. |
| `CORS_ALLOWED_ORIGINS` | *(empty)* | Comma-separated origins allowed to call those endpoints from a browser. Empty → the well-known hosted client origins (`https://claude.ai`, `https://claude.com`, `https://chatgpt.com`, `https://grok.com`). Only listed origins are echoed, which doubles as the MCP spec's `Origin` (DNS-rebinding) check; cookies are never allowed and `WWW-Authenticate` is exposed so a browser client can read the challenge. |
| `ACCESS_TOKEN_TTL` | `PT1H` | Opaque access-token lifetime (capped by IdP ID-token expiry). |
| `REFRESH_TOKEN_TTL` | `P30D` | Refresh-token lifetime. |
| `AUTH_CODE_TTL` | `PT5M` | One-time authorization-code lifetime. |
| `PENDING_AUTH_TTL` | `PT15M` | Pending-authorization state lifetime. |
| `REGISTERED_CLIENT_TTL` | `P90D` | Registered (DCR) client lifetime. |
| `ALPACA_CONNECTION_CACHE_TTL` | `PT30M` | Idle lifetime of a cached BroadWorks connection. |
| `ALPACA_LIVE` | `true` | Live BroadWorks OCI login via `LiveAlpacaConnectionFactory`. Default on for runtime; set `false` only for tests (the test suite sets this automatically). |
| `ALPACA_LICENSE_KEY` | *(empty)* | Alpaca toolkit license supplied inline as a string (secret). Loaded into the ECG licensing runtime at connection time, so no on-disk license file is needed. Empty → the license is provisioned by the runtime (license file / license manager). In ECS, supplied from SSM `/broadworks-mcp/alpaca-license-key`. |
| `LOG_LEVEL_ROOT` | `INFO` | Root log level (HTTP profile). |
| `LOG_LEVEL_APP` | `DEBUG` | Level for the application package `co.pitayagroup.mcp.broadworks`. |
| `LOG_LEVEL_MCP_ENDPOINTS` | `DEBUG` | Level for the MCP **and** OAuth endpoint access logs (`co.pitayagroup.mcp.broadworks.web`: `McpEndpointLoggingFilter`, `OAuthEndpointLoggingFilter`). |
| `LOG_LEVEL_MCP` | `INFO` | Level for the Spring AI MCP + MCP SDK protocol internals (`org.springframework.ai.mcp`, `io.modelcontextprotocol`). Set to `DEBUG`/`TRACE` to see the raw protocol handshake. |
| `LOG_LEVEL_SECURITY` | `INFO` | Level for `org.springframework.security` (raise to `DEBUG` to trace the OAuth/Resource-Server filter chain). |

All values are externalized; there are no secrets or magic numbers in code.

---

## Logging & troubleshooting

The MCP endpoints (`/mcp` and the legacy `/sse`) are access-logged by `McpEndpointLoggingFilter`, and
the OAuth / discovery surface (`/.well-known/**`, `/oauth/register`, `/oauth2/**`, `/login/**`) by
`OAuthEndpointLoggingFilter`, to make client interactions easy to follow:

- Every MCP request is stamped with a short **correlation id** (and the client's `Mcp-Session-Id`
  when present) via the SLF4J MDC, so all log lines produced while handling one request share the
  same id. The id is rendered in the console log's correlation slot (e.g. `[a1b2c3d4]`).
- A completion line reports the HTTP method, URI, the JSON-RPC `method` (and, for `tools/call`, the
  tool `name`), the response `status`, and the `durationMs`. `4xx`/`5xx` responses are logged at
  `WARN`/`ERROR` so failures stand out.
- Auth problems are explained where they happen: the token introspector logs **why** a bearer token
  was rejected (unknown / expired), and the bearer-challenge entry point logs each `401` it returns.

**Secrets are never logged.** The bearer token is only ever reported as present/absent, and the
JSON-RPC `params.arguments` (which may carry a BroadWorks password on `broadworks_add_connection`)
are never logged — only the safe envelope fields (`method`, `id`, tool `name`).

Raise verbosity for a troubleshooting session **without a rebuild** by setting the `LOG_LEVEL_*`
environment variables above, for example:

```bash
# See the MCP protocol handshake and the security filter chain in detail.
LOG_LEVEL_MCP=DEBUG LOG_LEVEL_SECURITY=DEBUG \
STORAGE_BACKEND=IN_MEMORY java -jar target/broadworks-mcp-*.jar
```

Under the `stdio` profile all logging already goes to **stderr** at `DEBUG` (stdout is reserved for
the MCP protocol).

### The BroadWorks host does not resolve (ECS)

```
Failed to establish a live BroadWorks connection to portal.vwave.net:
java.net.UnknownHostException: portal.vwave.net: Temporary failure in name resolution
```

The wording matters: *Temporary failure in name resolution* (`EAI_AGAIN`) means the resolver did not
answer or answered `SERVFAIL`, whereas *Name or service not known* (`EAI_NONAME`) means it answered
that the name does not exist. In Fargate the resolver is the Amazon-provided one on the task's own
ENI and is **not** reached through the NAT gateway, so a task that talks to DynamoDB and Google fine
can still fail for one external zone.

`LiveAlpacaConnectionFactory` therefore logs a resolver report next to such a failure — the
`nameserver` / `search` / `options` lines the container is using, a repeat lookup of the host, and a
lookup of its registrable parent domain:

```
WARN  BroadWorks host portal.vwave.net did not resolve; resolver=[nameserver 169.254.169.253] \
      lookup(portal.vwave.net)=failed(...) lookup(vwave.net)=ok(...)
```

Read it as: the parent resolving but the host not means resolution works and the record is at fault;
neither resolving while other traffic is healthy means the resolver cannot answer for that zone.

The service runs with `enableExecuteCommand`, so the same checks can be made interactively (the
image is Ubuntu-based: `getent`/`curl` are present, `dig` is not):

```bash
aws ecs execute-command --cluster <cluster> --task <task-id> --container broadworks-mcp \
  --interactive --command "/bin/sh"

cat /etc/resolv.conf                 # which nameserver is being asked
getent hosts portal.vwave.net        # empty output + exit 2 => did not resolve
curl -sv telnet://216.128.192.41:2208 --max-time 5   # reachability, bypassing DNS
```

If the lookup fails inside the task but succeeds from a public resolver (`dig @1.1.1.1
portal.vwave.net`), the VPC resolver is the problem, not the app or the security groups.

### `SpringApplicationService.CONTEXT is null` right after a successful login

```
BroadWorks Server Creation Error!. Failed to connect to portal.vwave.net - Cannot invoke
"org.springframework.context.ApplicationContext.getBean(java.lang.Class)" because
"co.ecg.alpaca.toolkit.service.SpringApplicationService.CONTEXT" is null
```

The OCI login itself succeeded (`BroadWorksServer login complete and successful!`); the failure comes
right after, on the first response bundle. Parts of the Alpaca toolkit do not get their collaborators
injected but read them from the static holder `SpringApplicationService`, which is populated by that
class's own `ApplicationContextAware` callback — so it has to be a bean. `LiveAlpacaConfig` registers
it (together with a `LicenseService` bean, otherwise every license check logs *Failed to get
LicenseService bean* before falling back to the same `ECGLicense` singleton).

If a future toolkit upgrade reintroduces this, look for the class the holder is asked for: today it is
`LibraryProperties` (`ResponseBundleHandler`), `LicenseService` (`LegacyLicenseService`), the named
executors (`ProcessContext`) and `EncryptionService` (`Echo`, only for requests with "ignore" flags) —
each must be resolvable from this context.

---

## End-to-end auth flow

1. An unauthenticated MCP call returns **401** with
   `WWW-Authenticate: Bearer realm="mcp", resource_metadata="https://<hostname>/.well-known/oauth-protected-resource/mcp"`
   (RFC 9728; the protected resource is `<baseUrl>/mcp`).
2. The client performs **Dynamic Client Registration** (`POST /oauth/register`, public client, no
   secret), then an OAuth 2.1 **authorization-code + PKCE (S256)** flow at `/oauth2/authorize`.
   Optional RFC 8707 `resource` must match the canonical MCP URL (`https://<hostname>/mcp`).
3. The AS redirects to **Google**; on the callback Spring Security verifies the ID token and
   **rejects** logins when `email_verified` is not true.
4. **First use of a DCR client** shows an MCP consent page (`/oauth2/consent`) with the client name,
   scopes, and redirect URI. Subsequent authorizations for the same client+user reuse stored consent.
5. The client exchanges the code at `/oauth2/token` and receives an **opaque** access token
   (+ refresh token). A durable **session** is persisted keyed by the access-token value, bound to
   audience `<baseUrl>/mcp`. Refresh rotation invalidates the previous access-token session.
6. Subsequent MCP calls send `Authorization: Bearer <opaque token>`. The Resource Server introspects
   the token **locally** against the session store (existence, expiry, **audience**), then injects
   `UserInfo{subject,email}` into the tool context. All per-tenant state is keyed by `subject`
   (never email).

**Multi-instance:** With `STORAGE_BACKEND=DYNAMODB` and ECS `desiredCount ≥ 2` (no ALB stickiness),
HTTP login sessions (own table), SAS authorizations/consents, and issued opaque-token sessions are
all shared via DynamoDB, so authorize on task A and token exchange / refresh on task B succeeds.

The interactive login session lives in `HTTP_SESSION_TABLE`, separate from `SESSION_TABLE`: it has a
different lifecycle (minutes, rotated on login) and id space (servlet session ids), and keeping the
two apart stops their schemas from drifting (they previously disagreed on how a creation timestamp is
named and encoded). Every item in both tables now uses `createdAt` / `lastAccessedAt` / `expiresAt`
as ISO-8601 strings; only the native `ttl` attribute is numeric. Deploying the split does **not**
migrate anything: login sessions written by an earlier version are ignored (browsers mid-login simply
sign in again) and expire from the old table via its own TTL.

Registering a public client:

```bash
curl -sS -X POST http://localhost:8080/oauth/register \
  -H 'Content-Type: application/json' \
  -d '{"redirect_uris":["http://127.0.0.1:8123/callback"],"client_name":"My MCP Client"}'
```

---

## MCP tools

| Tool | Description |
|---|---|
| `broadworks_list_connections` | List the authenticated user's stored BroadWorks connections. |
| `broadworks_add_connection` | Add (or replace) a BroadWorks connection (password set later in the web portal). |
| `broadworks_delete_connection` | Delete a stored BroadWorks connection. |
| `broadworks_list_service_providers` | List service providers / enterprises. |
| `broadworks_get_service_provider` | Get a service provider by id. |
| `broadworks_list_groups` | List groups within a service provider (or system-wide). |
| `broadworks_get_group` | Get a group by id within a service provider. |

Each tool resolves the caller's BroadWorks connection from the resource store (by `subject`) via the
`AlpacaConnectionFactory`, calls the Alpaca toolkit, and returns compact DTOs. Adding a new tool set
(Users, Devices, Call Centers, CDRs, …) is just a new `@Tool` bean registered in `McpToolConfig`.

> **Detailed tool schema:** see **[docs/mcp-tools-schema.md](docs/mcp-tools-schema.md)** for the full
> schema of every tool — all input parameters (name, type, required/optional) and the exact shape of
> each return value.

---

## Deployment (AWS CDK)

The `cdk/` app provisions ECS Fargate behind an HTTPS ALB, the three DynamoDB tables (sessions with
the `refresh-index` GSI + http-sessions + user-config) encrypted by a customer-managed KMS key, a
Fargate **task IAM
role** granting scoped KMS + DynamoDB access (the blueprint's "IRSA" role), CloudWatch logs, and SSM
SecureString-backed secrets injected as container env.

1. Create the SSM SecureString parameters:

   ```bash
   aws ssm put-parameter --name /broadworks-mcp/google-client-id     --type SecureString --value "<client-id>"
   aws ssm put-parameter --name /broadworks-mcp/google-client-secret --type SecureString --value "<client-secret>"
   aws ssm put-parameter --name /broadworks-mcp/alpaca-license-key   --type SecureString --value "<license>"
   ```

   Or push from local files: Google OAuth from `.env` (`GOOGLE_CLIENT_ID` /
   `GOOGLE_CLIENT_SECRET`) and the Alpaca license from repo-root
   `alpaca-license.txt` (multi-line OK; git-ignored):

   ```bash
   ./run.sh push-secrets
   ```

   Set `AWS_REGION` (e.g. in `.env`) to target a specific region.

2. Deploy with the public hostname (build the image from the repo-root `Dockerfile` as a CDK
   asset). The hostname builds the server base URL (`https://<hostname>`) **and** provisions the
   ACM certificate for the HTTPS ALB listener:

   ```bash
   cd cdk
   npm install
   npx cdk deploy -c hostname=mcp.example.com
   ```

   The certificate is DNS-validated: after `deploy` starts, add the CNAME record ACM shows in the
   console (or point `hostname` at a Route 53 zone in this account) so validation can complete.
   To reuse an existing, already-validated certificate instead, pass
   `-c certificateArn=arn:aws:acm:<region>:<acct>:certificate/<id>`. **TLS is mandatory**: without
   either a `hostname` or a `certificateArn`, synthesis fails. For local/dev experiments only, opt
   out with `-c allowInsecureHttp=true` (or `ALLOW_INSECURE_HTTP=true`) to get a plain HTTP
   listener. With HTTPS, port 80 is opened solely to redirect to 443.

   The internet-facing ALB is fronted by a WAFv2 WebACL (AWS managed common + known-bad-inputs rule
   groups, a general per-IP rate limit and tighter 100 req / 5 min limits on `/oauth/register` and
   `/oauth2/token`). The two managed rule groups are deliberately **not** applied to
   `/oauth/register`, `/oauth2/authorize` and `/oauth2/token`: their managed rules answer any request
   containing a plain `http://` URL with a bare 403 (served by WAF, so the app never sees it), which
   broke the RFC 8252 loopback redirect URIs (`http://127.0.0.1:<port>/…`, `http://localhost:<port>/…`)
   that local MCP clients such as Claude Desktop, MCP Inspector, VS Code and Cursor use — Dynamic
   Client Registration and the authorization-code flow were impossible for them. Those three
   endpoints remain rate limited by the rules above and are strictly validated by the app itself
   (exact redirect-URI allowlisting, mandatory PKCE S256, public clients only); every other path,
   notably `/mcp`, stays fully covered by both rule groups. WAF logging is enabled and goes to the
   `aws-waf-logs-broadworks-mcp` CloudWatch log group (`authorization` and `cookie` headers redacted),
   so future blocks can be attributed to a concrete rule. The tasks run non-root (uid 10001) with a
   read-only root filesystem; `/tmp` and the JCS disk cache (`/app/.cache`) are ephemeral task
   volumes. Fargate creates those volumes owned by `root:root` (the image's ownership is not
   inherited), so a short-lived root `volume-init` container `chown`s them to uid 10001 and must exit
   successfully before the app container starts — without it the JVM cannot create Tomcat's temp dir
   (`Unable to create tempDir. java.io.tmpdir is set to /tmp`).

   **ECS Exec is enabled** (`enableExecuteCommand`) so a running task can be inspected from the
   inside — the tasks have no public IP, which otherwise leaves failures such as an unresolvable
   BroadWorks host unobservable (see *The BroadWorks host does not resolve*). CDK adds the required
   `ssmmessages:*` permissions to the task role, and the SSM control channel goes out through the NAT
   gateway. Because the root filesystem is read-only, the agent ECS injects into the container gets
   its own ephemeral volumes for `/var/lib/amazon` and `/var/log/amazon` (also `chown`ed by
   `volume-init`); without them every session dies instantly and the task reports
   `ExecuteCommandAgent` as `STOPPED`. Locally you need the AWS CLI
   [Session Manager plugin](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html).

---

## Notes & limitations

- **In-memory storage is non-durable and single-node**: sessions/clients/resources are lost on
  restart and not shared across replicas. Use `STORAGE_BACKEND=DYNAMODB` for production.
- **Alpaca licensing** is provided by the bundled `co.ecg:ecg-licensing` runtime (installed from
  `lib/` by the `install-alpaca` profile / `scripts/install-alpaca.sh`). The license can be supplied
  inline as a string via `ALPACA_LICENSE_KEY` (`.env`), which is loaded into the licensing runtime at
  connection time — no on-disk license file is required (the jar ships its own GPG key ring). An
  invalid key fails fast with a safe error. When the variable is empty the license is left to the
  provisioned runtime (license file / license manager).
- **Live BroadWorks connectivity** uses the Alpaca toolkit's `BroadWorksServer` login machinery and
  is **on by default** (`ALPACA_LIVE` / `broadworks.alpaca.live`, default `true`). Apache JCS
  (`org.apache.jcs:jcs`) is a normal Maven dependency. The CDK stack injects `ALPACA_LICENSE_KEY`
  from SSM. You still need: (1) a stored BroadWorks connection for the user
  (`broadworks_add_connection`), (2) a valid license, and (3) network reachability to the BroadWorks
  OCI host/port. Unit/integration tests set `broadworks.alpaca.live=false` so they use a non-login
  stub factory and never contact BroadWorks.
- **Security**: PKCE (S256) is mandatory; DCR issues public clients only; secrets are KMS-encrypted
  at rest; tokens, passwords, and protocol bodies are never logged.
