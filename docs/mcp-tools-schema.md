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
| [`broadworks_flush_cache`](#broadworks_flush_cache) | Flush the Alpaca OCI response cache for the current connection. | `CacheFlushResult` |
| [`broadworks_list_service_providers`](#broadworks_list_service_providers) | List / search service providers and enterprises. | `Page` |
| [`broadworks_get_service_provider`](#broadworks_get_service_provider) | Get a single service provider by id. | `ServiceProviderDetail` |
| [`broadworks_delete_service_provider`](#broadworks_delete_service_provider) | Delete a service provider (mutates live data; URL-first portal confirmation). | `string` |
| [`broadworks_list_groups`](#broadworks_list_groups) | List / search groups (per service provider or system-wide). | `Page` |
| [`broadworks_get_group`](#broadworks_get_group) | Get a single group by id. | `GroupDetail` |
| [`broadworks_delete_group`](#broadworks_delete_group) | Delete a group (mutates live data; URL-first portal confirmation). | `string` |
| [`broadworks_list_users`](#broadworks_list_users) | List / search users (per group, service provider, or system-wide). | `Page` |
| [`broadworks_get_user`](#broadworks_get_user) | Get a single user by id. | `UserDetail` |
| [`broadworks_delete_user`](#broadworks_delete_user) | Delete a user (mutates live data; URL-first portal confirmation). | `string` |
| [`broadworks_list_service_packs`](#broadworks_list_service_packs) | List the service packs defined on a service provider. | `array` of `ServicePackSummary` |
| [`broadworks_get_service_pack`](#broadworks_get_service_pack) | Get a single service pack's detail. | `ServicePackDetail` |
| [`broadworks_create_service_pack`](#broadworks_create_service_pack) | Create a service pack (mutates live data). | `ServicePackDetail` |
| [`broadworks_modify_service_pack`](#broadworks_modify_service_pack) | Modify a service pack; add-only for services (mutates live data). | `ServicePackDetail` |
| [`broadworks_delete_service_pack`](#broadworks_delete_service_pack) | Delete a service pack (mutates live data; URL-first portal confirmation). | `string` |
| [`broadworks_get_service_provider_service_authorization`](#broadworks_get_service_provider_service_authorization) | Get a service provider's user/group service authorization. | `ServiceAuthorizationSet` |
| [`broadworks_modify_service_provider_service_authorization`](#broadworks_modify_service_provider_service_authorization) | Modify a service provider's service authorization (mutates live data). | `ServiceAuthorizationSet` |
| [`broadworks_get_group_service_authorization`](#broadworks_get_group_service_authorization) | Get a group's service-pack/group/user service authorization. | `ServiceAuthorizationSet` |
| [`broadworks_modify_group_service_authorization`](#broadworks_modify_group_service_authorization) | Modify a group's service authorization (mutates live data). | `ServiceAuthorizationSet` |
| [`broadworks_assign_group_services`](#broadworks_assign_group_services) | Assign group services to a group (mutates live data). | `array` of `string` |
| [`broadworks_unassign_group_services`](#broadworks_unassign_group_services) | Unassign group services from a group (mutates live data). | `array` of `string` |
| [`broadworks_get_user_assigned_services`](#broadworks_get_user_assigned_services) | Get a user's assigned group/user services. | `AssignedServicesResult` |
| [`broadworks_assign_user_services`](#broadworks_assign_user_services) | Assign user services / service packs to a user (mutates live data). | `AssignedServicesResult` |
| [`broadworks_unassign_user_services`](#broadworks_unassign_user_services) | Unassign user services / service packs from a user (mutates live data). | `AssignedServicesResult` |

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

### `broadworks_flush_cache`

Flush the Alpaca OCI response cache for the current BroadWorks connection so the next get/list
calls BroadWorks live instead of returning a cached response. Mutating tools already flush
automatically after a successful write. Get tools also accept `refresh=true` to flush immediately
before that read.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** a [`CacheFlushResult`](#cacheflushresult) object.

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
| `refresh` | `boolean` | optional | When `true`, flush the Alpaca OCI response cache before listing so the result is fetched live from BroadWorks. |

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
| `refresh` | `boolean` | optional | When `true`, flush the Alpaca OCI response cache before reading so the result is fetched live from BroadWorks. |

**Returns:** a [`ServiceProviderDetail`](#serviceproviderdetail) object.

---

### `broadworks_delete_service_provider`

Delete a BroadWorks service provider (or enterprise). **Mutates live BroadWorks data and is
irreversible.** BroadWorks may reject the deletion if the service provider still contains groups.

URL-capable MCP clients always open a portal confirmation page that a human must Confirm or
Deny; the agent cannot approve the delete, and `areYouSure` is ignored. Clients without URL
elicitation must pass `areYouSure=true` or the call is refused with no BroadWorks change.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The id of the service provider to delete. |
| `areYouSure` | `boolean` | fallback only | Required only when the client cannot do URL elicitation. URL-capable clients always get a portal prompt and this flag is ignored. Set `true` to confirm the delete when the client has no URL elicitation. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** a `string` confirmation message, e.g. `Deleted service provider '<id>'`.

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
| `refresh` | `boolean` | optional | When `true`, flush the Alpaca OCI response cache before reading so the result is fetched live from BroadWorks. |

**Returns:** a [`GroupDetail`](#groupdetail) object.

---

### `broadworks_delete_group`

Delete a BroadWorks group within a service provider. **Mutates live BroadWorks data and is
irreversible.** BroadWorks may reject the deletion if the group still contains users.

URL-capable MCP clients always open a portal confirmation page that a human must Confirm or
Deny; the agent cannot approve the delete, and `areYouSure` is ignored. Clients without URL
elicitation must pass `areYouSure=true` or the call is refused with no BroadWorks change.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id that owns the group. |
| `groupId` | `string` | required | The id of the group to delete. |
| `areYouSure` | `boolean` | fallback only | Required only when the client cannot do URL elicitation. URL-capable clients always get a portal prompt and this flag is ignored. Set `true` to confirm the delete when the client has no URL elicitation. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** a `string` confirmation message, e.g. `Deleted group '<groupId>' from service provider <id>`.

---

## User tools

### `broadworks_list_users`

List (or search) BroadWorks users. The listing scope is derived from the supplied ids: when both a
service provider id and a group id are given the search is scoped to that group; when only a service
provider id is given it spans that service provider; when neither is given it spans the entire
system. Supplying a group id without a service provider id is rejected. Each supplied search field is
combined as an AND criterion using the shared `searchMode`; omit all to list everything in scope.
Results are paginated and capped server-side.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | optional | The service provider id to scope the listing to. Omit (together with `groupId`) to search users across the entire system. |
| `groupId` | `string` | optional | The group id to scope the listing to. Requires `serviceProviderId` to also be supplied. Omit to search across the whole service provider or system. |
| `lastName` | `string` | optional | Filter matched against the user's last name. |
| `firstName` | `string` | optional | Filter matched against the user's first name. |
| `userId` | `string` | optional | Filter matched against the user id. |
| `phoneNumber` | `string` | optional | Filter matched against the user's phone number. |
| `emailAddress` | `string` | optional | Filter matched against the user's email address. |
| `searchMode` | `string` | optional | How the search values are matched: `STARTSWITH`, `CONTAINS`, or `EQUALTO` (default `CONTAINS`). Applies to all supplied search fields. |
| `cursor` | `string` | optional | Opaque pagination cursor returned as `next_cursor` by a previous call; omit to start from the first page. |
| `limit` | `integer` | optional | Maximum rows to return in this page. Clamped to the server ceiling of `50`; defaults to `25` when omitted. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** a [`Page`](#the-page-envelope) whose rows follow the schema
`["userId", "groupId", "serviceProviderId", "lastName", "firstName", "phoneNumber", "extension", "emailAddress"]`:

| Column | Type | Description |
|---|---|---|
| `userId` | `string` | The (system-unique) user id. |
| `groupId` | `string` | The owning group id (from the row when present, otherwise the request parameter). |
| `serviceProviderId` | `string` | The owning service provider id (from the row when present, otherwise the request parameter). |
| `lastName` | `string` | The user's last name, if any. |
| `firstName` | `string` | The user's first name, if any. |
| `phoneNumber` | `string` | The user's phone number, if any. |
| `extension` | `string` | The user's extension, if any. |
| `emailAddress` | `string` | The user's email address, if any. |

---

### `broadworks_get_user`

Get details for a single BroadWorks user by their (system-unique) user id.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `userId` | `string` | required | The (system-unique) user id. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |
| `refresh` | `boolean` | optional | When `true`, flush the Alpaca OCI response cache before reading so the result is fetched live from BroadWorks. |

**Returns:** a [`UserDetail`](#userdetail) object.

---

### `broadworks_delete_user`

Delete a BroadWorks user by their (system-unique) user id. **Mutates live BroadWorks data and is
irreversible.**

URL-capable MCP clients always open a portal confirmation page that a human must Confirm or
Deny; the agent cannot approve the delete, and `areYouSure` is ignored. Clients without URL
elicitation must pass `areYouSure=true` or the call is refused with no BroadWorks change.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `userId` | `string` | required | The (system-unique) id of the user to delete. |
| `areYouSure` | `boolean` | fallback only | Required only when the client cannot do URL elicitation. URL-capable clients always get a portal prompt and this flag is ignored. Set `true` to confirm the delete when the client has no URL elicitation. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** a `string` confirmation message, e.g. `Deleted user '<userId>'`.

---

## Service pack tools

A service pack is a named bundle of user services defined on a service provider; it can be authorized
to groups and assigned to users. Service names throughout these tools are BroadWorks display names
(e.g. `Call Waiting`) and are validated against the known user services — an unknown name is rejected.

### `broadworks_list_service_packs`

List the names of the service packs defined on a service provider.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id that owns the service packs. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** an `array` of [`ServicePackSummary`](#servicepacksummary) objects.

---

### `broadworks_get_service_pack`

Get the details of a single service pack, including its description, availability, licensed quantity,
assigned/allowed quantities, and included user services.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id that owns the service pack. |
| `servicePackName` | `string` | required | The name of the service pack to inspect. |
| `refresh` | `boolean` | optional | When `true`, flush the Alpaca OCI response cache before reading so the result is fetched live from BroadWorks. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** a [`ServicePackDetail`](#servicepackdetail) object.

---

### `broadworks_create_service_pack`

Create a new service pack on a service provider. **Mutates live BroadWorks data.** Fails if a pack
with the same name already exists.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id that will own the new service pack. |
| `servicePackName` | `string` | required | The name for the new service pack (unique within the service provider). |
| `description` | `string` | optional | Description for the service pack. |
| `availableForUse` | `boolean` | optional | Whether the pack is available for assignment; omit to use the BroadWorks default. |
| `quantity` | `integer` | optional | Licensed quantity as a positive integer; omit when `unlimited=true`. |
| `unlimited` | `boolean` | optional | Set `true` for an unlimited licensed quantity; when `true`, `quantity` is ignored. |
| `services` | `array` of `string` | optional | User service display names to include in the pack (validated). The included services are fixed at creation time. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** the newly created [`ServicePackDetail`](#servicepackdetail).

---

### `broadworks_modify_service_pack`

Modify an existing service pack (partial update: omitted fields are left unchanged). **Mutates live
BroadWorks data.**

> **Included user services cannot be changed in place.** BroadWorks offers no way to remove or replace
> a service in a pack — the `addServices` parameter can only **add** services (via a separate
> add-service request). To remove a service, delete and recreate the pack.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id that owns the service pack. |
| `servicePackName` | `string` | required | The current name of the service pack to modify. |
| `newServicePackName` | `string` | optional | New name for the pack; omit to leave unchanged (cannot be cleared). |
| `description` | `string` | optional | New description; omit to leave unchanged, pass an empty string to clear. |
| `availableForUse` | `boolean` | optional | Whether the pack is available for assignment; omit to leave unchanged. |
| `quantity` | `integer` | optional | New licensed quantity as a positive integer; omit to leave unchanged. Ignored when `unlimited=true`. |
| `unlimited` | `boolean` | optional | Set `true` to make the licensed quantity unlimited; omit to leave unchanged. |
| `addServices` | `array` of `string` | optional | User service display names to **add** to the pack (add-only; validated). |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** the refreshed [`ServicePackDetail`](#servicepackdetail).

---

### `broadworks_delete_service_pack`

Delete a service pack from a service provider. **Mutates live BroadWorks data and is irreversible.**
BroadWorks may reject the deletion if the pack is still authorized to groups or assigned to users.

URL-capable MCP clients always open a portal confirmation page that a human must Confirm or
Deny; the agent cannot approve the delete, and `areYouSure` is ignored. Clients without URL
elicitation must pass `areYouSure=true` or the call is refused with no BroadWorks change.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id that owns the service pack. |
| `servicePackName` | `string` | required | The name of the service pack to delete. |
| `areYouSure` | `boolean` | fallback only | Required only when the client cannot do URL elicitation. URL-capable clients always get a portal prompt and this flag is ignored. Set `true` to confirm the delete when the client has no URL elicitation. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** a `string` confirmation message, e.g. `Deleted service pack '<name>' from service provider <id>`.

---

## Service authorization & assignment tools

Service authorization controls how many of each user service, group service, and service pack a
service provider grants to itself and a group grants to itself. Assignment/activation controls which
group services are assigned to a group and which user services + service packs are assigned to a user.
Modifications follow a partial-update discipline: only the entries you supply are sent, and BroadWorks
leaves every omitted service untouched. Service names are BroadWorks display names (e.g. `Call
Waiting`), validated against the known user/group services — an unknown name is rejected.

The authorization-modify tools accept arrays of [`ServiceAuthorization`](#serviceauthorization)
entries. For each entry set `authorized=true` with a `quantity` (a positive integer, or
`unlimited=true`) to grant, or `authorized=false` to revoke (unauthorize) the service.

### `broadworks_get_service_provider_service_authorization`

Get a service provider's user- and group-service authorization. Service packs are authorized at the
group level, so the `servicePacks` list is empty here.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id whose authorization to read. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** a [`ServiceAuthorizationSet`](#serviceauthorizationset) (`servicePacks` empty).

---

### `broadworks_modify_service_provider_service_authorization`

Modify a service provider's user/group service authorization. **Mutates live BroadWorks data.** At
least one entry (user or group) is required.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id whose authorization to change. |
| `userServices` | `array` of [`ServiceAuthorization`](#serviceauthorization) | optional | User service authorization entries to change; omit services you are not changing. |
| `groupServices` | `array` of [`ServiceAuthorization`](#serviceauthorization) | optional | Group service authorization entries to change; omit services you are not changing. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** the refreshed [`ServiceAuthorizationSet`](#serviceauthorizationset).

---

### `broadworks_get_group_service_authorization`

Get a group's service-pack, group-service, and user-service authorization.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id that owns the group. |
| `groupId` | `string` | required | The id of the group whose authorization to read. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** a [`ServiceAuthorizationSet`](#serviceauthorizationset).

---

### `broadworks_modify_group_service_authorization`

Modify a group's service-pack/group/user service authorization. **Mutates live BroadWorks data.** At
least one entry (service pack, group service, or user service) is required.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id that owns the group. |
| `groupId` | `string` | required | The id of the group whose authorization to change. |
| `userServices` | `array` of [`ServiceAuthorization`](#serviceauthorization) | optional | User service authorization entries to change; omit services you are not changing. |
| `groupServices` | `array` of [`ServiceAuthorization`](#serviceauthorization) | optional | Group service authorization entries to change; omit services you are not changing. |
| `servicePacks` | `array` of [`ServiceAuthorization`](#serviceauthorization) | optional | Service pack authorization entries to change; the `serviceName` field carries the service pack name. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** the refreshed [`ServiceAuthorizationSet`](#serviceauthorizationset).

---

### `broadworks_assign_group_services`

Assign one or more group services to a group so it can use them. **Mutates live BroadWorks data.** The
group must already be authorized for a service before it can be assigned.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id that owns the group. |
| `groupId` | `string` | required | The id of the group to assign services to. |
| `serviceNames` | `array` of `string` | required | Group service display names to assign (e.g. `Auto Attendant`). |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** an `array` of `string` — the group service names that were assigned.

---

### `broadworks_unassign_group_services`

Unassign one or more group services from a group. **Mutates live BroadWorks data.**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `serviceProviderId` | `string` | required | The service provider id that owns the group. |
| `groupId` | `string` | required | The id of the group to unassign services from. |
| `serviceNames` | `array` of `string` | required | Group service display names to unassign (e.g. `Auto Attendant`). |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** an `array` of `string` — the group service names that were unassigned.

---

### `broadworks_get_user_assigned_services`

Get the services assigned to a user, split into group services and user services (each with an active
flag).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `userId` | `string` | required | The id of the user whose assigned services to read. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** an [`AssignedServicesResult`](#assignedservicesresult) object.

---

### `broadworks_assign_user_services`

Assign one or more user services and/or service packs to a user. **Mutates live BroadWorks data.** The
group must be authorized for a service or pack before it can be assigned to a user. At least one
service name or service pack name is required.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `userId` | `string` | required | The id of the user to assign services to. |
| `serviceNames` | `array` of `string` | optional | User service display names to assign (e.g. `Call Waiting`); omit if only assigning service packs. |
| `servicePackNames` | `array` of `string` | optional | Service pack names to assign; omit if only assigning individual user services. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** the refreshed [`AssignedServicesResult`](#assignedservicesresult).

---

### `broadworks_unassign_user_services`

Unassign one or more user services and/or service packs from a user. **Mutates live BroadWorks data.**
At least one service name or service pack name is required.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `userId` | `string` | required | The id of the user to unassign services from. |
| `serviceNames` | `array` of `string` | optional | User service display names to unassign (e.g. `Call Waiting`); omit if only unassigning service packs. |
| `servicePackNames` | `array` of `string` | optional | Service pack names to unassign; omit if only unassigning individual user services. |
| `resourceId` | `string` | optional | BroadWorks resource id when multiple connections are configured. |

**Returns:** the refreshed [`AssignedServicesResult`](#assignedservicesresult).

---

## Return schemas

### `CacheFlushResult`

Outcome of `broadworks_flush_cache`.

| Field | Type | Description |
|---|---|---|
| `flushed` | `boolean` | Whether the cache was actually cleared. |
| `message` | `string` | A short, agent-facing summary of the outcome. |

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

### `UserDetail`

| Field | Type | Description |
|---|---|---|
| `userId` | `string` | The (system-unique) user id. |
| `groupId` | `string` | The owning group id. |
| `serviceProviderId` | `string` | The owning service provider id. |
| `firstName` | `string` | The user's first name, if any. |
| `lastName` | `string` | The user's last name, if any. |
| `phoneNumber` | `string` | The user's phone number, if any. |
| `extension` | `string` | The user's extension, if any. |
| `emailAddress` | `string` | The user's email address, if any. |
| `department` | `string` | The user's department full path, if any. |
| `title` | `string` | The user's title, if any. |
| `mobilePhoneNumber` | `string` | The user's mobile phone number, if any. |
| `timeZone` | `string` | The user's time zone, if any. |
| `language` | `string` | The user's language, if any. |
| `callingLineIdFirstName` | `string` | The user's calling line id first name, if any. |
| `callingLineIdLastName` | `string` | The user's calling line id last name, if any. |
| `callingLineIdPhoneNumber` | `string` | The user's calling line id phone number, if any. |
| `address` | `object` | The physical (street) address, or `null` when absent. |

### `ServicePackSummary`

Summary of a service pack, as returned by the list operation.

| Field | Type | Description |
|---|---|---|
| `servicePackName` | `string` | The service pack name. |

### `ServicePackDetail`

| Field | Type | Description |
|---|---|---|
| `servicePackName` | `string` | The service pack name. |
| `description` | `string` | The service pack description, or `null` when absent. |
| `availableForUse` | `boolean` | Whether the pack is available for assignment, if reported. |
| `quantity` | [`ServiceQuantity`](#servicequantity) | The licensed quantity (finite or unlimited), if reported. |
| `assignedQuantity` | `integer` | The number currently assigned, if reported. |
| `allowedQuantity` | [`ServiceQuantity`](#servicequantity) | The maximum quantity that may be assigned, if reported. |
| `userServices` | `array` of `string` | The display names of the user services included in the pack. |

### `ServiceQuantity`

A BroadWorks service/service-pack quantity: either a finite count or an unlimited allocation.

| Field | Type | Description |
|---|---|---|
| `quantity` | `integer` | The finite count, or `null` when unlimited or unspecified. |
| `unlimited` | `boolean` | Whether the allocation is unlimited. |

### `ServiceAuthorization`

Authorization state for a single service or service pack.

| Field | Type | Description |
|---|---|---|
| `serviceName` | `string` | The service (or service pack) display name. |
| `authorized` | `boolean` | Whether the service is authorized (`false` means explicitly unauthorized). |
| `quantity` | [`ServiceQuantity`](#servicequantity) | The authorized quantity (finite or unlimited), or `null` when unauthorized or unspecified. |

### `ServiceAuthorizationSet`

A snapshot of the service authorization state at a service provider or group level.

| Field | Type | Description |
|---|---|---|
| `userServices` | `array` of [`ServiceAuthorization`](#serviceauthorization) | The user service authorizations. |
| `groupServices` | `array` of [`ServiceAuthorization`](#serviceauthorization) | The group service authorizations. |
| `servicePacks` | `array` of [`ServiceAuthorization`](#serviceauthorization) | The service pack authorizations (empty for a service-provider-level read). |

### `AssignedService`

A single service assigned to a group or user.

| Field | Type | Description |
|---|---|---|
| `serviceName` | `string` | The service display name. |
| `active` | `boolean` | Whether the service is currently active. |

### `AssignedServicesResult`

The set of services assigned to a user, split by the level that grants them.

| Field | Type | Description |
|---|---|---|
| `groupServices` | `array` of [`AssignedService`](#assignedservice) | The group services assigned to the user. |
| `userServices` | `array` of [`AssignedService`](#assignedservice) | The user services assigned to the user. |

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
