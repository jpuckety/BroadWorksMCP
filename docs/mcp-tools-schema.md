# BroadWorks MCP — Tool Schema Reference

This document describes the complete schema of the tools exposed by the **broadworks-mcp** server:
every tool name, its input parameters (name, type, required/optional, meaning), and the shape of the
value it returns.

All tools are served over the Spring AI MCP transport (rooted at `/mcp`). Each tool call is
authenticated with an opaque bearer token and is scoped to the caller's `subject` (see the
[README](../README.md) for the end-to-end auth flow). Secrets (BroadWorks passwords) are never
accepted by, returned from, or logged by any tool.

> **Alpaca license required.** These tools call the ECG Alpaca toolkit to reach BroadWorks. Using the
> Alpaca libraries requires the purchase of an Alpaca License key from **ECG, Inc.** For pricing and
> more information, email **[jpuckett@e-c-group.com](mailto:jpuckett@e-c-group.com)**.

---

## Conventions

- **Types** use JSON terms: `string`, `integer`, `boolean`, `object`, `array`.
- **Required** parameters must be supplied by the caller; **optional** parameters may be omitted.
- Several list tools return a shared **`Page`** envelope (compact columnar form) — see
  [The `Page` envelope](#the-page-envelope) below.
- Pagination limits are enforced server-side:
  - default page size: **25** rows,
  - hard maximum page size: **50** rows,
  - hard maximum cell budget (rows × columns): **400** cells per page.

---

## Tool index

| Tool | Purpose | Returns |
|---|---|---|
| [`broadworks_list_connections`](#broadworks_list_connections) | List the caller's stored BroadWorks connections. | `array` of `ConnectionSummary` |
| [`broadworks_add_connection`](#broadworks_add_connection) | Add or replace a BroadWorks connection (password-less). | `ConnectionSummary` |
| [`broadworks_delete_connection`](#broadworks_delete_connection) | Delete a stored connection. | `string` |
| [`broadworks_list_service_providers`](#broadworks_list_service_providers) | List / search service providers and enterprises. | `Page` |
| [`broadworks_get_service_provider`](#broadworks_get_service_provider) | Get a single service provider by id. | `ServiceProviderDetail` |
| [`broadworks_list_groups`](#broadworks_list_groups) | List / search groups (per service provider or system-wide). | `Page` |
| [`broadworks_get_group`](#broadworks_get_group) | Get a single group by id. | `GroupDetail` |

---

## Connection management tools

These tools manage the per-user connection resources that the other tools resolve at call time. They
do not contact BroadWorks.

### `broadworks_list_connections`

List the BroadWorks server connections configured for the authenticated user (passwords are never
returned).

**Parameters:** none.

**Returns:** an `array` of [`ConnectionSummary`](#connectionsummary) objects.

---

### `broadworks_add_connection`

Add (or replace) a BroadWorks server connection for the authenticated user.

> **No password is accepted or stored.** The connection is saved without a password and cannot be
> used until the user sets the password in the web portal. `needsPassword` is `true` until then.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `displayName` | `string` | required | Human-friendly name / nickname for the connection, e.g. `ECG Production`. |
| `hostname` | `string` | required | BroadWorks OCI hostname (no scheme or path), e.g. `portal.example.com`. |
| `port` | `integer` | required | BroadWorks OCI port, e.g. `2208`. |
| `username` | `string` | required | BroadWorks login username. |
| `resourceId` | `string` | optional | Explicit resource id to create/replace; when omitted a stable id is derived from the display name. |

**Returns:** a [`ConnectionSummary`](#connectionsummary) for the stored connection
(`needsPassword=true` until the password is set in the web portal).

---

### `broadworks_delete_connection`

Delete a BroadWorks server connection owned by the authenticated user.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `resourceId` | `string` | required | The resource id of the connection to delete. |

**Returns:** a `string` confirmation message, e.g. `Deleted BroadWorks connection '<resourceId>'`.

---

## Service provider tools

### `broadworks_list_service_providers`

List (or search) the BroadWorks service providers (and enterprises) accessible to the authenticated
user. Results are paginated and capped server-side.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `cursor` | `string` | optional | Opaque pagination cursor returned as `next_cursor` by a previous call; omit to start from the first page. |
| `limit` | `integer` | optional | Maximum rows to return in this page. Clamped to the server ceiling of `50`; defaults to `25` when omitted. |
| `search` | `string` | optional | Case-insensitive filter matched against the service provider name; omit to list all. |
| `searchMode` | `string` | optional | How the search value is matched: `STARTSWITH`, `CONTAINS`, or `EQUALTO` (default `CONTAINS`). Ignored when `search` is omitted. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** a [`Page`](#the-page-envelope) whose rows follow the schema
`["serviceProviderId", "serviceProviderName", "enterprise", "resellerId"]`:

| Column | Type | Description |
|---|---|---|
| `serviceProviderId` | `string` | The service provider id. |
| `serviceProviderName` | `string` | The service provider display name. |
| `enterprise` | `boolean` | Whether this service provider is an enterprise. |
| `resellerId` | `string` | The owning reseller id, if any. |

---

### `broadworks_get_service_provider`

Get details for a single BroadWorks service provider by id.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** a [`ServiceProviderDetail`](#serviceproviderdetail) object.

---

## Group tools

### `broadworks_list_groups`

List (or search) BroadWorks groups. When a service provider id is supplied the search is scoped to
that service provider; when it is omitted the search spans the entire system (all service providers).
Results are paginated and capped server-side.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | optional | The service provider id whose groups to list. Omit to search groups across the entire system (all service providers). |
| `search` | `string` | optional | Case-insensitive filter matched against the group name; omit to list all. |
| `searchMode` | `string` | optional | How the search value is matched: `STARTSWITH`, `CONTAINS`, or `EQUALTO` (default `CONTAINS`). Ignored when `search` is omitted. |
| `cursor` | `string` | optional | Opaque pagination cursor returned as `next_cursor` by a previous call; omit to start from the first page. |
| `limit` | `integer` | optional | Maximum rows to return in this page. Clamped to the server ceiling of `50`; defaults to `25` when omitted. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** a [`Page`](#the-page-envelope) whose rows follow the schema
`["groupId", "groupName", "userLimit"]`:

| Column | Type | Description |
|---|---|---|
| `groupId` | `string` | The group id. |
| `groupName` | `string` | The group display name. |
| `userLimit` | `string` | The configured user limit (as reported by BroadWorks). |

---

### `broadworks_get_group`

Get details for a single BroadWorks group within a service provider.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id. |
| `groupId` | `string` | required | The group id. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** a [`GroupDetail`](#groupdetail) object.

---

## Return schemas

### `ConnectionSummary`

Non-secret summary of a configured BroadWorks/Alpaca connection (the password is intentionally
omitted).

| Field | Type | Description |
|---|---|---|
| `resourceId` | `string` | Stable identifier for this connection within the user's set. |
| `displayName` | `string` | Human-friendly name / nickname. |
| `hostname` | `string` | BroadWorks OCI host. |
| `port` | `integer` | BroadWorks OCI port. |
| `username` | `string` | BroadWorks login user. |
| `needsPassword` | `boolean` | Whether this connection still needs a password set in the web portal before it can be used (blank stored password). |

### `ServiceProviderDetail`

| Field | Type | Description |
|---|---|---|
| `serviceProviderId` | `string` | The service provider id. |
| `serviceProviderName` | `string` | The service provider display name. |
| `defaultDomain` | `string` | The default domain. |
| `enterprise` | `boolean` | Whether this service provider is an enterprise. |
| `resellerId` | `string` | The owning reseller id, if any. |

### `GroupDetail`

| Field | Type | Description |
|---|---|---|
| `groupId` | `string` | The group id. |
| `groupName` | `string` | The group display name. |
| `serviceProviderId` | `string` | The owning service provider id. |
| `defaultDomain` | `string` | The group's default domain. |

### The `Page` envelope

Tabular list results are returned in a compact columnar form: `schema` names the columns once and
every entry in `rows` is a positional value list in the same order, avoiding repeating the field keys
on every object. The remaining fields are pagination/observability metadata.

| Field | Type | Description |
|---|---|---|
| `schema` | `array` of `string` | Ordered column names describing each entry in `rows`. |
| `rows` | `array` of `array` | The page of records, each a positional list matching `schema`. |
| `returned` | `integer` | The number of rows in this page (equals `rows.length`). |
| `totalMatching` | `integer` | The total number of matching records across all pages. |
| `hasMore` | `boolean` | Whether more rows remain beyond this page. |
| `nextCursor` | `string` | Opaque cursor to pass back (as `cursor`) to fetch the next page, or `null` when `hasMore` is `false`. |
| `truncationReason` | `string` | A short explanation of why the page was capped, or `null` when nothing was truncated. |
| `suggestion` | `string` | Guidance for the caller (e.g. call again with the cursor, or that all results were returned). |
