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
| `SESSION_TABLE` | `broadworks-mcp-sessions` | DynamoDB sessions/clients table. |
| `USER_CONFIG_TABLE` | `broadworks-mcp-user-config` | DynamoDB per-user resource table. |
| `KMS_KEY_ID` | *(empty)* | Customer-managed KMS key id/ARN for secret encryption (required for `DYNAMODB`). |
| `AWS_REGION` | *(SDK default)* | AWS region for DynamoDB/KMS. |
| `APPLICATION_ID` | `broadworks-mcp` | Partition key for the per-user resource table. |
| `OAUTH_REDIRECT_ALLOWLIST` | *(empty)* | Comma-separated list of allowed redirect-URI prefixes for HTTPS **and** custom schemes (e.g. `https://app.example.com/,cursor://`). Loopback HTTP (`127.0.0.1` / `localhost`) is always allowed. |
| `ACCESS_TOKEN_TTL` | `PT1H` | Opaque access-token lifetime (capped by IdP ID-token expiry). |
| `REFRESH_TOKEN_TTL` | `P30D` | Refresh-token lifetime. |
| `AUTH_CODE_TTL` | `PT5M` | One-time authorization-code lifetime. |
| `PENDING_AUTH_TTL` | `PT15M` | Pending-authorization state lifetime. |
| `REGISTERED_CLIENT_TTL` | `P90D` | Registered (DCR) client lifetime. |
| `ALPACA_CONNECTION_CACHE_TTL` | `PT30M` | Idle lifetime of a cached BroadWorks connection. |
| `ALPACA_LICENSE_KEY` | *(empty)* | Alpaca toolkit license supplied inline as a string (secret). Loaded into the ECG licensing runtime at connection time, so no on-disk license file is needed. Empty → the license is provisioned by the runtime (license file / license manager). |
| `LOG_LEVEL_ROOT` | `INFO` | Root log level (HTTP profile). |
| `LOG_LEVEL_APP` | `DEBUG` | Level for the application package `com.broadworks.mcp`. |
| `LOG_LEVEL_MCP_ENDPOINTS` | `DEBUG` | Level for the MCP endpoint access log (`com.broadworks.mcp.web`). |
| `LOG_LEVEL_MCP` | `INFO` | Level for the Spring AI MCP + MCP SDK protocol internals (`org.springframework.ai.mcp`, `io.modelcontextprotocol`). Set to `DEBUG`/`TRACE` to see the raw protocol handshake. |
| `LOG_LEVEL_SECURITY` | `INFO` | Level for `org.springframework.security` (raise to `DEBUG` to trace the OAuth/Resource-Server filter chain). |

All values are externalized; there are no secrets or magic numbers in code.

---

## Logging & troubleshooting

The MCP endpoints (`/mcp` and the legacy `/sse`) are access-logged by `McpEndpointLoggingFilter` to
make client interactions easy to follow:

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
HTTP login sessions, SAS authorizations/consents, and issued opaque-token sessions are all shared
via DynamoDB, so authorize on task A and token exchange / refresh on task B succeeds.

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
| `broadworks_list_service_providers` | List service providers / enterprises. |
| `broadworks_get_service_provider` | Get a service provider by id. |
| `broadworks_list_groups` | List groups within a service provider. |
| `broadworks_get_group` | Get a group by id within a service provider. |

Each tool resolves the caller's BroadWorks connection from the resource store (by `subject`) via the
`AlpacaConnectionFactory`, calls the Alpaca toolkit, and returns compact DTOs. Adding a new tool set
(Users, Devices, Call Centers, CDRs, …) is just a new `@Tool` bean registered in `McpToolConfig`.

---

## Deployment (AWS CDK)

The `cdk/` app provisions ECS Fargate behind an HTTPS ALB, the two DynamoDB tables (sessions with the
`refresh-index` GSI + user-config) encrypted by a customer-managed KMS key, a Fargate **task IAM
role** granting scoped KMS + DynamoDB access (the blueprint's "IRSA" role), CloudWatch logs, and SSM
SecureString-backed secrets injected as container env.

1. Create the SSM SecureString parameters:

   ```bash
   aws ssm put-parameter --name /broadworks-mcp/google-client-id     --type SecureString --value "<client-id>"
   aws ssm put-parameter --name /broadworks-mcp/google-client-secret --type SecureString --value "<client-secret>"
   ```

   Or, if you already keep `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` in `.env`,
   push them straight into SSM (SecureString, overwriting any existing values):

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
   `-c certificateArn=arn:aws:acm:<region>:<acct>:certificate/<id>`. Without either a `hostname` or
   a `certificateArn`, the ALB listens on HTTP only (development).

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
- **Live BroadWorks connectivity** uses the Alpaca toolkit's `BroadWorksServer` login machinery. The
  toolkit's runtime companion (`org.apache.jcs:jcs`, the response cache) is now bundled as a normal
  Maven dependency (with its legacy transitive back-ends excluded — only Doug Lea's `concurrent` and
  the already-present commons-logging API are kept). Live login is **opt-in**: set
  `ALPACA_LIVE=true` (`broadworks.alpaca.live`) to activate `LiveAlpacaConfig`, which wires the
  toolkit's connection beans and a `LiveAlpacaConnectionFactory` that performs a real OCI login and
  caches the `BroadWorksServer` per `(subject, resourceId)`. It still requires a **reachable
  BroadWorks OCI server** (and, optionally, a tuned JCS `cache.ccf` on the classpath / via
  `-Dalpaca.cache.config`). When `ALPACA_LIVE` is unset/false (the default), the connection factory
  resolves and validates the per-tenant resource but performs **no** live login, failing fast with a
  safe error. All per-tenant resolution, argument mapping, and response handling are exercised
  independently of a live server.
- **Security**: PKCE (S256) is mandatory; DCR issues public clients only; secrets are KMS-encrypted
  at rest; tokens, passwords, and protocol bodies are never logged.
