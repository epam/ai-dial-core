# 03 — Configuration API Reference

> **Audience:** DIAL Core dev team implementing the API; integrators building against the unified Configuration surface (`/v1/{type}/{bucket}/*` for per-entity CRUD; `/v1/admin/*` for cross-entity ops).
> **Reading time:** ~15 minutes.
> **Prerequisites:** [`02-architecture.md`](02-architecture.md) §Path Format Reference and §Bucket Strategy.

This document specifies the new Configuration API surface exposed by DIAL Core. The CLI (`dial-cli`) and the DIAL Admin Backend are both clients of this API; nothing else in the system should bypass it for admin-managed configuration.

Authorization internals (the `ConfigAuthorizationService` interface), secret-field handling, and audit events are specified in [`04-security-and-audit.md`](04-security-and-audit.md). This document only describes the externally-visible API contract and validation rules.

---

## 1. Endpoint Structure

The Configuration API has three URL families, all sharing wire shape with the existing user Resource API:

- **Per-entity CRUD** at `/v1/{type}/{bucket}/{name}` — extended in one complementary place, **not** by adding new types into the existing `RouteTemplate.RESOURCE` regex (which today covers `applications`, `toolsets`, `prompts`, `conversations`). The genuinely new admin-config types (`models`, `interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`) get a sibling `RouteTemplate.CONFIG_RESOURCE` entry; `files` keeps its existing dedicated `RouteTemplate.FILES` entry (`/v1/files/{bucket}/{path}`); `conversations` and `prompts` keep their existing slot in `RESOURCE`. Methods follow the same `PUT`-upsert + `If-None-Match` / `If-Match` pattern the existing Resource API has used since DIAL Core launched — see §3 for the full concurrency surface and the existing `ResourceController` for the reference behaviour.
- **Per-entity / per-folder metadata listing** at `/v1/metadata/{type}/{bucket}/{path}` — sibling `RouteTemplate.CONFIG_RESOURCE_METADATA` regex, identical in shape to the existing `RESOURCE_METADATA` and `FILES_METADATA` templates. Returns `ResourceFolderMetadata` (folder GET) or `ResourceItemMetadata` (item GET) — see §4 for the wire shape.
- **Cross-entity operator endpoints** at `/v1/admin/*` — `apply`, `validate`, `export`, `audit` (Phase 7), `health/config`, `schema`. These don't fit per-entity-CRUD (they span types, span buckets, or have no entity at all). Always admin-role-gated.

Authorization for all paths is bucket-aware: `ConfigAuthorizationService` dispatches from `(role, verb, type, bucket)` — admin role for writes to `public/` and reads/writes to `platform/`; bucket-owner for user buckets; read-public for `public/` reads. See [`04-security-and-audit.md`](04-security-and-audit.md) §1.

**Identifier format:** Full resource IDs `{type}/{bucket}/{name}` (matching `ResourceDescriptor.getUrl()` convention — see [`02-architecture.md`](02-architecture.md) §Path Format Reference). The URL path after `/v1/` is parsed as `{entityType}/{bucket}/{name...}`.

```
# Per-entity-type CRUD (wire shape matches the existing user Resource API)
GET    /v1/{entityType}/{bucket}/{name}               # Get entity details (304 on If-None-Match match)
PUT    /v1/{entityType}/{bucket}/{name}               # Upsert; If-None-Match: * → 412 if exists (create-only gate);
                                                      #          If-Match: <etag> → 412 on mismatch (update guard)
DELETE /v1/{entityType}/{bucket}/{name}               # Delete; If-Match: <etag> → 412 on mismatch; 404 if missing

# Per-bucket folder listing + per-item metadata
GET    /v1/metadata/{entityType}/{bucket}/{name}      # If {name} is a folder path (or empty) → ResourceFolderMetadata
                                                      # If {name} addresses an existing item → ResourceItemMetadata

# Examples — bucket is always explicit:
GET    /v1/models/public/gpt-4                        # user-facing model (admin write, anyone read)
PUT    /v1/roles/platform/viewer                      # create/update infrastructure role (admin only)
PUT    /v1/applications/public/my-admin-app           # admin upsert of public application
GET    /v1/interceptors/platform/guardrail            # infrastructure interceptor (admin only)
GET    /v1/settings/platform/global                   # singleton settings (admin only — see below)
GET    /v1/metadata/models/public/                    # list blob-managed models in public/

# Singleton settings (uniform {type}/{bucket}/{name} shape; bucket=platform, name=global)
GET    /v1/settings/platform/global                   # Get effective global settings (blob | file | default projection)
PUT    /v1/settings/platform/global                   # Replace global settings (upsert — sets/updates the API blob)
DELETE /v1/settings/platform/global                   # Clear the API blob; revert to file-sourced (or default) projection (idempotent, 204)

# Cross-entity operator endpoints — every /v1/admin/* path requires admin role
# (gated by ConfigAuthorizationService.isAdmin(ctx); 403 otherwise)
POST   /v1/admin/apply                                # Apply set of resource manifests (declarative bulk)
POST   /v1/admin/validate                             # Validate manifests without applying

# State export (admin) — runtime-config snapshot including both file-sourced and API-managed entries
GET    /v1/admin/export                               # Export full config (YAML/JSON)
GET    /v1/admin/export?type=models                   # Export specific entity type

# Audit (WIP — Phase 7, deferred — see 07-migration-and-rollout.md §Phase 7)
GET    /v1/admin/audit                                # Query audit log

# Schema
GET    /v1/admin/schema/{entityType}                  # JSON Schema for entity type

# Reload health (skipped/invalid entities)
GET    /v1/admin/health/config                        # status: ok|degraded + skipped[] (admin role required)
```

**Authz on `/v1/admin/*` endpoints.** Every endpoint under the `/v1/admin/*` prefix requires the admin role (checked via `ConfigAuthorizationService.isAdmin(ctx)`); non-admin callers receive `403 Forbidden`. This is distinct from per-entity CRUD at `/v1/{type}/{bucket}/{name}`, which uses the bucket-aware dispatch on `(role, verb, type, bucket)` documented in `04-security-and-audit.md` §1.2. The unauthenticated `/health` liveness probe at the root is unrelated and unchanged.

Where `{entityType}` is one of: `models`, `applications`, `toolsets`, `interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`, `files`, `prompts`, `conversations`. Per [OQ-21](08-open-questions-and-references.md), admin scope covers all entity types — `files`, `prompts`, and `conversations` are first-class. Admin manages **shared** instances in `public/` (icons / theme assets / default prompt templates / curated example conversations) via the same per-entity URL; user-owned instances in user buckets remain owner-managed by the existing Resource API rule (admin has no access to user buckets — [OQ-33](08-open-questions-and-references.md)).

**Route layout — sibling regexes, no overlap with existing templates.** Phase 2 adds two new `RouteTemplate` entries for the admin-config types only — `CONFIG_RESOURCE` (per-entity CRUD, mirrors `RESOURCE`) and `CONFIG_RESOURCE_METADATA` (per-bucket listing / per-item metadata, mirrors `RESOURCE_METADATA`). `RouteTemplate.RESOURCE`, `RouteTemplate.RESOURCE_METADATA`, `RouteTemplate.FILES`, and `RouteTemplate.FILES_METADATA` are left unchanged. The current `RouteTemplate.RESOURCE` regex is `"^/v1/(conversations|prompts|applications|toolsets)/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)$"` — confirming `applications` and `toolsets` are already matched, so admin writes to `public/applications/...` and `public/toolsets/...` flow through the existing `RESOURCE`-routed controllers without any regex change.

```java
// New sibling entries — admin-config types only:
CONFIG_RESOURCE(
    "^/v1/(models|interceptors|roles|keys|routes|schemas|settings)/(?<bucket>[a-zA-Z0-9_-]+)/(?<path>.*)$",
    "/v1/{type}/{bucket}/{path}"
),
CONFIG_RESOURCE_METADATA(
    "^/v1/metadata/(models|interceptors|roles|keys|routes|schemas|settings)/(?<bucket>[a-zA-Z0-9_-]+)/(?<path>.*)$",
    "/v1/metadata/{type}/{bucket}/{path}"
),
// Existing entries are unchanged:
//   RESOURCE           — (conversations|prompts|applications|toolsets)
//   RESOURCE_METADATA  — /v1/metadata/(conversations|prompts|applications|toolsets)/...
//   FILES              — /v1/files/{bucket}/{path}
//   FILES_METADATA     — /v1/metadata/files/{bucket}/{path}
// Authorization branches in the controller / dispatcher on (verb, type, bucket).
```

**Listing path semantics — folder GET via `/v1/metadata/...`.** Per-bucket / per-folder enumeration is served by `GET /v1/metadata/{type}/{bucket}/{path}` exactly the way the existing Resource API serves `RESOURCE_METADATA` and `FILES_METADATA`. The non-metadata route `GET /v1/{type}/{bucket}/{name}` is single-entity only — there is no implicit listing on a trailing slash. This is the strictest possible alignment with the existing Resource API and matches what `dial-cli` / Admin Backend / Admin MCP clients already do for `applications` / `toolsets` / `conversations` / `prompts` / `files`.

**Bucket character class — `[a-zA-Z0-9_-]` is sufficient for Phase 1–5; MT scopes require regex update.** The bucket group accepts `[a-zA-Z0-9_-]` which covers every bucket value used in Phase 1–5 (`public`, `platform`, encrypted user-bucket IDs). Future multi-tenant scope IDs of the form `tenants/{id}`, `teams/{id}`, `channels/{id}` contain a `/` separator and therefore **will require the regex to be extended** (e.g. allowing one or more `/`-separated segments) when MT lands. The regex is not a "long-term constant" with respect to MT — it is sufficient for the single-tenant phases and an MT-aware regex update is part of the MT delivery scope.

Admin writes to `public/files/...`, `public/prompts/...`, `public/conversations/...` flow through their existing controllers (`FILES` and `RESOURCE`); `ConfigAuthorizationService` is consulted before the controller's existing per-bucket logic to gate the admin role on writes to the `public/` bucket and to deny admin reach into user buckets ([OQ-33](08-open-questions-and-references.md)). No new regex is needed for files/prompts/conversations — only a new authz check inside the existing controllers.

Validation is performed by `ResourceDescriptorFactory`, which enforces type, bucket, and name segment constraints automatically. No additional validation regex needed.

**Singleton settings rationale.** The `globalSettings` document doesn't have a natural per-entity name. Rather than carve out `/v1/admin/settings` as a one-off, the singleton uses `bucket=platform`, `name=global` to follow the uniform `{type}/{bucket}/{name}` pattern — keeps URL parsing, auth dispatch, and route regex uniform; future MT scopes plug in as `/v1/settings/{tenant-id}/...` without reshaping the route.

**Settings supports `GET`, `PUT`, and `DELETE`.** `PUT` is upsert (same as every other admin-config type — see §3); it sets or replaces the API blob at `platform/settings/global`. `DELETE` **clears the API blob** and reverts the singleton to its file-sourced (or schema-default) projection — operators take API control via `PUT` and release it via `DELETE`. `DELETE` is idempotent: it returns `204 No Content` whether or not the blob was present, and after the call `GET` reflects the file-sourced (or default) values (`source: "file"` or `"default"`). The `405 Method Not Allowed` response on `POST` (which the controller returns for every admin-config type, not just `settings`) MUST include `Allow: GET, PUT, DELETE` per RFC 9110 §15.5.6. `HEAD` is treated as `GET`. Concurrency: `PUT` and `DELETE` accept `If-Match` against the current `ETag`; mismatch returns `412 Precondition Failed`; `PUT` additionally accepts `If-None-Match: *` to gate create-only (singleton always exists once the API blob is written, so `If-None-Match: *` returns `412` whenever a blob is already present).

**GET projection sources.** The `GET` response is the **effective** projection of `globalSettings`, with the `source` field disclosing the origin (Owner-only):

| Blob present? | Config file defines `globalInterceptors` / `retriableErrorCodes`? | Body content | `source` |
|---|---|---|---|
| yes | (any) | API blob (whole-object replace per [OQ-10](08-open-questions-and-references.md)) | `"api"` |
| no | yes | file-sourced fields | `"file"` |
| no | no | schema defaults (empty `globalInterceptors`, default `retriableErrorCodes`) | `"default"` |

`"default"` is a singleton-only `source` value introduced for this projection — per-entity types do not produce it. The `ETag` returned via the HTTP header is computed over the projected body so clients can use `If-Match` consistently across the three states.

**Listing on the singleton — `GET /v1/metadata/settings/platform/` returns `405 Method Not Allowed`.** The listing path for the singleton type is not meaningful (there is exactly one entity at the fixed name `global`). The controller returns `405 Method Not Allowed` with `Allow: GET` — the metadata surface is read-only; the singleton's write verbs (`PUT`, `DELETE`) live on the entity URL `/v1/settings/platform/global`, not on the metadata URL. Per RFC 9110 §15.5.6 the `Allow` header lists verbs valid on the **requested** resource, so emitting `PUT`/`DELETE` here would mislead a caller. Admin MCP and CLI clients should use the per-entity GET rather than listing — see [`09-admin-mcp-spec.md`](09-admin-mcp-spec.md) §6.1.

**Wire shape aligned with the existing Resource API.** Admin-config types use the same `PUT`-upsert + `If-None-Match` / `If-Match` discipline that `applications`, `toolsets`, `conversations`, `prompts`, and `files` have used since DIAL Core launched. Concretely: `PUT` is upsert (create-or-update); `If-None-Match: *` on a `PUT` returns `412 Precondition Failed` if the entity already exists (the create-only gate the existing Resource API uses); `If-Match: <etag>` on a `PUT` or `DELETE` returns `412 Precondition Failed` if the stored ETag has moved. There is no `POST` at the single-entity surface — the controller returns `405 Method Not Allowed` with `Allow: GET, PUT, DELETE`. This intentionally aligns with the existing Resource API rather than introducing a separate POST-create / PUT-update split: operators, SDK authors, and `ResourceController` already understand one shape, and the unification keeps that one shape. Bulk upsert (declarative apply across many entities) still lives on `POST /v1/admin/apply` (§7) — that is the canonical declarative path. *(Phase 7 audit's `operation` field — `create | update | delete` — is derived inside the controller by reading the pre-state under the same lock as the write, the same way the existing `ResourceController`'s audit hooks would derive it.)*

**PATCH deferred to Phase 4+.** Phase 2–3 supports `GET`, `PUT` (upsert), and `DELETE` only. `PUT` requires the complete entity body — absent fields revert to defaults (except write-only fields like `Key.key` which use preserve-on-omit). The CLI provides field-level update ergonomics via `--set` flags (internally: GET + local merge + PUT). PATCH (RFC 7396 JSON Merge Patch) is a Phase 4+ addition if operator feedback indicates full-entity PUT is insufficient.

**Writes are validated.** Phase 3 writes go through the standard validation path — current-version structural and semantic validation is enforced. There is no validation-bypass flag. *(Audit rollback for incompatible payloads — when Phase 7 ships — works only when the historical snapshot still satisfies current validation; restoring a payload that has drifted out of compatibility (renamed field, removed schema reference, deprecated enum) is rejected with the same error a manual write of that payload would produce. A recovery mechanism for incompatible snapshots is tracked as OQ-31 in [`08-open-questions-and-references.md`](08-open-questions-and-references.md).)*

**Entity name validation.** The Configuration API validates entity names on write — returns HTTP 400 for names that don't match `^[A-Za-z0-9._-]+$` per path segment. `ConfigPostProcessor` retains its stripping of invalid toolset names as a safety net for file-sourced entities.

Global settings groups root-level Config fields that aren't per-entity: `globalInterceptors`, `retriableErrorCodes`, and future extensible fields. This is a single JSON object, not a collection of named entities (the `{bucket}/{name}` segments are fixed at `platform/global`).

```json
// GET /v1/settings/platform/global response (Owner view):
{
  "globalInterceptors": ["audit-logger", "pii-anonymizer"],
  "retriableErrorCodes": [429, 503, 504],
  "source": "api"
}
```

(Public callers of this endpoint see `403`, since `platform/` is admin-only — there is no Public-view shape for the singleton.)

**Entity-to-bucket mapping:**

| Entity Type | Bucket | Internal Routing | Rationale |
|---|---|---|---|
| `models` | `public/` | MergedConfigStore | User-facing deployment — hot-path reads from `config.getModels()` |
| `applications` | `public/` | ApplicationService (existing) | Already has ResourceService path — skip MergedConfigStore (see [`02-architecture.md`](02-architecture.md) §Entity Storage Strategy) |
| `toolsets` | `public/` | ToolSetService (existing) | Already has ResourceService path — skip MergedConfigStore |
| `schemas` | `public/` | MergedConfigStore | Referenced by apps users interact with |
| `interceptors` | `platform/` | MergedConfigStore | Infrastructure — interceptor chain resolution on hot path |
| `roles` | `platform/` | MergedConfigStore | Infrastructure — RateLimiter iterates all roles per request |
| `keys` | `platform/` | MergedConfigStore | Infrastructure — feeds ApiKeyStore for O(1) auth |
| `routes` | `platform/` | MergedConfigStore | Infrastructure — ordered iteration on every unmatched request |
| `settings` | `platform/` | MergedConfigStore | Singleton — globalInterceptors, retriableErrorCodes |
| `files` | `public/` (admin) / user buckets (owner) | ResourceService (existing) | Already has Resource API path. Admin manages shared assets (icons, themes, docs) in `public/`; user files in user buckets unchanged. Skip MergedConfigStore (see [`02-architecture.md`](02-architecture.md) §6). |
| `prompts` | `public/` (admin) / user buckets (owner) | ResourceService (existing) | Already has Resource API path. Admin manages shared/default prompt templates in `public/`; user prompts in user buckets unchanged. Skip MergedConfigStore. |
| `conversations` | `public/` (admin) / user buckets (owner) | ResourceService (existing) | Already has Resource API path. Admin manages curated/example conversations in `public/`; user conversations in user buckets unchanged. Skip MergedConfigStore. |

## 2. Entity Payloads

Entity JSON payloads match the existing `Config` data model exactly. The same Jackson-based serialization that reads `aidial.config.json` today is reused for API request/response bodies.

**Response shape — Public vs Owner views (per-entity GET only).** Entity-intrinsic fields live at top level (matching today's user Resource API shape). On per-entity `GET /v1/{type}/{bucket}/{name}` responses, two extra fields are projected per [`04-security-and-audit.md`](04-security-and-audit.md) §1.5: `status` (top-level, **Public** — visible to anyone who can read the entity) and `source` + `validationWarnings` (top-level, **Owner-only** — admin or bucket-owner). Public callers see the entity body + `status` only; Owner callers additionally see `source` and `validationWarnings`. The flat shape is enforced by Jackson `@JsonView` annotations on the response wrapper with `DEFAULT_VIEW_INCLUSION = false` — a forgotten annotation makes a field invisible everywhere (fail-closed at write time, never silently leaks). `etag` is returned via the HTTP `ETag` header, never in the body. **The projection does not apply to listings**: `GET /v1/metadata/{type}/{bucket}/{path}` returns `ResourceItemMetadata` items with no entity body, no `status`, no `source`, no `validationWarnings` — matching the existing `RESOURCE_METADATA` / `FILES_METADATA` shape. Operators who need entity-level validity or provenance use a per-entity `GET` after the listing, or `GET /v1/admin/export` for the runtime-config snapshot.

**Example — `PUT /v1/models/public/anthropic.claude-sonnet-4-6`:**

```json
{
  "type": "chat",
  "displayName": "Anthropic Claude Sonnet 4.6",
  "displayVersion": "v1",
  "iconUrl": "anthropic.svg",
  "endpoint": "http://dial-bedrock.dial.svc.cluster.local/openai/deployments/anthropic.claude-sonnet-4-6/chat/completions",
  "upstreams": [
    {
      "extraData": "{\"region\": \"us-east-1\"}"
    }
  ],
  "limits": {
    "maxTotalTokens": 200000
  },
  "pricing": {
    "unit": "token",
    "prompt": "0.000003",
    "completion": "0.000015"
  },
  "descriptionKeywords": ["Text Generation", "AWS", "Reasoning"],
  "features": {
    "systemPromptSupported": true,
    "toolsSupported": true
  }
}
```

## 3. Concurrency Control

Uses the same ETag pattern as the existing Resource API (RFC 7232 conditional headers via `EtagHeader.fromHeader(...)` in `server/util/ProxyUtil.etag(...)` → `ResourceService.putResource(..., EtagHeader, ...)`):

- **`PUT` is upsert.** A bare `PUT /v1/{type}/{bucket}/{name}` creates the entity if absent and replaces it if present — exactly the way `PUT /v1/applications/{bucket}/{name}` has worked since DIAL Core launched. The response is `200 OK` on both create and update; the new ETag is returned in the `ETag` response header.
- **Create-only gate via `If-None-Match: *`.** `PUT … If-None-Match: *` returns `412 Precondition Failed` if any entity already exists at that URL. This is the wire equivalent of the previous strict-POST behavior; CLI / Admin Backend / MCP clients that need create-only semantics send this header.
- **Update-only / CAS guard via `If-Match: <etag>`.** `PUT … If-Match: <etag>` and `DELETE … If-Match: <etag>` return `412 Precondition Failed` if the stored ETag has moved. `If-Match` is **optional** — when absent the write proceeds unconditionally (last-write-wins), matching the existing Resource API. CI pipelines and concurrency-sensitive callers should always pass `--if-match` (CLI flag) or `If-Match` (header).
- **GET caching via `If-None-Match: <etag>`.** `GET … If-None-Match: <etag>` returns `304 Not Modified` when the supplied ETag matches the stored value, otherwise serves the full body with the current `ETag` header — RFC 7232 §3.2 semantics, identical to the existing Resource API.
- **ETag transport.** Returned in the HTTP `ETag` response header on `GET`, `PUT`, and on per-item `GET /v1/metadata/...`. Never on `DELETE` (the resource has no representation post-deletion and `ResourceService.deleteResource()` does not produce an ETag). Never in the response body. The ETag value is the one `ResourceService` already computes and stores in the Redis HASH `etag` attribute as part of the same `putResource()` call that created/updated the resource — controllers retrieve it from the `ResourceService` write result rather than computing it independently. This guarantees the ETag returned in the response matches the value subsequent `If-Match` checks will compare against.

**Error code mapping at a glance** (matches existing Resource API):

| Status | Trigger |
|---|---|
| `200` | Successful `GET`, `PUT` (create or update), per-item `GET /v1/metadata/...`. |
| `304` | `GET … If-None-Match: <etag>` matches stored ETag. |
| `400` | Malformed request body / invalid name pattern / unknown bucket-type binding. |
| `404` | Entity missing on `GET` / `DELETE`; folder missing on `GET /v1/metadata/...`. |
| `405` | `POST` against a single-entity URL (no POST surface — `Allow: GET, PUT, DELETE`). |
| `412` | `If-Match` mismatch, or `If-None-Match: *` against an existing entity, or `If-None-Match: <etag>` matches stored value on `PUT` / `DELETE`. |

### 3.1 Secret field handling on PUT

`PUT` is full-replace by default — fields absent from the request body revert to the entity's defaults. Secret fields are the **explicit exception**: an absent / `null` / `"***"` secret field on `PUT` preserves the value already stored when the entity already exists. The fields that follow this preserve-on-omit semantics are:

- `Key.key`
- `Upstream.key`
- `Upstream.extraData`

**All other fields follow standard full-replace semantics.** Clients that want to keep a non-secret field at its current value must include it in the `PUT` body explicitly — the server does not infer "preserve" for non-secret fields. On a create (`PUT … If-None-Match: *` against a non-existent URL), there is no prior value to preserve — a body whose secret field is `"***"` is rejected as `400 Bad Request` (the mask sentinel is not a valid create-time secret). See [`04-security-and-audit.md`](04-security-and-audit.md) §2.5 for the full create-vs-update matrix and the `"***"` sentinel rules, and §2.4 for the `@EncryptedField` annotation that gates which fields participate.

## 4. Response Format for Lists

Listing is served by the metadata route — `GET /v1/metadata/{type}/{bucket}/{path}` — and returns the same `ResourceFolderMetadata` / `ResourceItemMetadata` shape the existing `RESOURCE_METADATA` and `FILES_METADATA` controllers return. There is no separate envelope for admin-config types: a folder GET returns `ResourceFolderMetadata`; an item GET returns `ResourceItemMetadata`. The response uses MIME `application/vnd.dial.metadata+json` when the client sets `Accept: application/vnd.dial.metadata+json`, else `application/json`. Admin enumerates the relevant bucket(s); for admin-managed types each entity type has exactly one shared bucket (`public/` or `platform/`), so enumeration is unambiguous.

**Source is blob-only.** The metadata listing enumerates **blob-managed entries only** (via `ResourceService.getMetadata(descriptor)` — the same primitive every existing Resource API listing uses). File-sourced entries (`aidial.config.json`) are **not surfaced** through the metadata listing — they live in `Config` only, have no `ResourceDescriptor`, no `ETag`, no `createdAt`. Operators who need the full runtime view (file + API entries together) use `GET /v1/admin/export` (or `dial-cli export`); that endpoint serializes the current in-memory `Config` and is the canonical answer to "what entities are effectively configured right now?". This is full alignment with the existing Resource API which has no file-source notion at all.

**Pagination — `?token=<opaque>&limit=N` (default 100, max 1000).** Same query parameters the existing Resource API uses: `limit` (page size; integer, default 100, hard cap 1000) and `token` (opaque continuation token returned by a previous page; absent on the first request). The response's `nextToken` field is **present iff there is another page** — it is omitted on the last page. The token is opaque and clients must not parse it. The Admin MCP's `dial_admin_list_entities` paginates the underlying listing endpoint until `nextToken` is absent (for bounded entity types) or until its per-invocation ceiling for potentially unbounded types (`files`, `prompts`, `conversations`) — see [`09-admin-mcp-spec.md`](09-admin-mcp-spec.md) §6.1 for the full draining and truncation semantics.

**Recursive listing — `?recursive=true`.** Mirrors the existing Resource API: when true, descends into child folders (when the type supports multi-segment paths — files / prompts / conversations). For admin-config types whose names are single-segment in Phase 1–4 (`models`, `interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`), `?recursive=true` is accepted and behaves identically to the default (the listing is already flat). The parameter is preserved for forward-compat with multi-tenant scopes — `tenants/{id}/...`, `teams/{id}/...`, `channels/{id}/...` may introduce nested paths.

**`ResourceFolderMetadata` (folder GET) — example for `GET /v1/metadata/models/public/`:**

```json
{
  "name": "",
  "parentPath": null,
  "bucket": "public",
  "url": "models/public/",
  "nodeType": "FOLDER",
  "resourceType": "MODEL",
  "items": [
    {
      "name": "anthropic.claude-sonnet-4-6",
      "parentPath": null,
      "bucket": "public",
      "url": "models/public/anthropic.claude-sonnet-4-6",
      "nodeType": "ITEM",
      "resourceType": "MODEL",
      "createdAt": 1715184000000,
      "updatedAt": 1715188000000,
      "etag": "\"a1b2c3d4e5f6\"",
      "author": "admin@company.com"
    },
    {
      "name": "old-broken-model",
      "parentPath": null,
      "bucket": "public",
      "url": "models/public/old-broken-model",
      "nodeType": "ITEM",
      "resourceType": "MODEL",
      "createdAt": 1714579200000,
      "updatedAt": 1714579200000,
      "etag": "\"99887766\"",
      "author": "admin@company.com"
    }
  ],
  "nextToken": "opaque-base64-token"
}
```

`nextToken` is omitted on the last page. The same shape applies to every admin-config type — only the `resourceType` value and `url` prefix change.

**`ResourceItemMetadata` (per-item GET) — example for `GET /v1/metadata/models/public/anthropic.claude-sonnet-4-6`:**

```json
{
  "name": "anthropic.claude-sonnet-4-6",
  "parentPath": null,
  "bucket": "public",
  "url": "models/public/anthropic.claude-sonnet-4-6",
  "nodeType": "ITEM",
  "resourceType": "MODEL",
  "createdAt": 1715184000000,
  "updatedAt": 1715188000000,
  "etag": "\"a1b2c3d4e5f6\"",
  "author": "admin@company.com"
}
```

**No entity body in listings.** The metadata listing carries **no entity payload** — operators who need the model's `endpoint`, `pricing`, `upstreams`, etc. follow up with a per-entity `GET /v1/models/public/anthropic.claude-sonnet-4-6`. Likewise, validity (`status`), provenance (`source`), and `validationWarnings` are **not surfaced through the listing** — they appear on the per-entity GET response per §2 (Public/Owner views), or operators query `GET /v1/admin/health/config` for the full degraded-entity report. This is full alignment with the existing Resource API which has never put entity bodies into folder listings.

**`name` synthesis.** Listing items derive `name` directly from the `ResourceDescriptor` (the simple name segment) — the same way the existing Resource API has always done it. The full canonical form lives in `url` (`models/public/anthropic.claude-sonnet-4-6`); operators copy-paste from `url` when constructing per-entity URLs. There is no separate dedup-by-key step because the listing is blob-only — there are no file-vs-API simple-name twins to disambiguate within a single response.

**Authorization on listings.** Same dispatch as the per-entity surface: `public/` buckets are listable by any authenticated reader (admin role required for `platform/`). Authorization is checked once when the listing controller starts; pagination on subsequent pages reuses the same authorization context via the opaque `token`.

**Forward compatibility note.** The `ResourceFolderMetadata` / `ResourceItemMetadata` shape is **already stable** in DIAL Core — it has been the existing Resource API's listing shape since launch. Clients written against today's `GET /v1/metadata/applications/public/` are already prepared for the same shape on `GET /v1/metadata/models/public/`. Phase 1 ships read-only listings against the in-memory `Config` ref **for API-managed entries already persisted in blob** (Phase 1's seed migration of file entries to blob is opt-in per [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 6 and not required by Phase 1 itself); a freshly-deployed DIAL Core that has no API-managed entries returns an empty `items[]` listing — operators rely on `GET /v1/admin/export` to see file-sourced entries during the gradual file→API migration.

## 5. Authorization

All endpoints are authorized through the **`ConfigAuthorizationService`** interface. The Phase 1–3 implementation (`AdminRoleAuthorizationService`) checks `access.admin.rules` from static settings:

```json
"access": {
    "admin": {
        "rules": [
            { "function": "CONTAIN", "source": "roles", "targets": ["admin"] }
        ]
    }
}
```

Both JWT-authenticated users and API keys with matching roles can access `/v1/admin/*` endpoints. The indirection through `ConfigAuthorizationService` ensures that when hierarchical authorization is introduced (multi-tenancy), only the service implementation changes — no endpoint code is modified.

> For the full authorization design, including the `ConfigAuthorizationService` interface, Phase 1–3 implementation, and the future Auth-MT hierarchical model, see [`04-security-and-audit.md`](04-security-and-audit.md) §Authorization.

## 6. Validation

On every write, the API validates the rules below. The `POST /v1/admin/validate` endpoint runs these checks without persisting, enabling dry-run and pre-commit validation. The endpoint ships in two stages — see the phase annotations on each rule.

**Phase 2 validate scope (single-entity, model type only) — covers structural/schema validation against the entity's JSON Schema:**

- JSON structure matches the entity's data model (Jackson deserialization) — *Phase 2 (model only)*
- Deployment name uniqueness across all types (`Config.selectDeployment()` check) — *Phase 2 (model only)*
- Upstream endpoints are valid URLs — *Phase 2 (model only)*

**Phase 4 validate scope (all entity types, batch-aware with proposed-config state) — adds cross-reference resolution, batch dependency ordering, and `precheck` semantics; the rules above also extend to all entity types:**

- `applicationTypeSchemaId` references an existing schema (for applications) — *Phase 4*
- `applicationProperties` conforms to the referenced schema (for schema-rich applications) — *Phase 4*
- Toolset names match pattern `^[A-Za-z0-9-_]+$` — *Phase 4 (toolset writes ship in Phase 3; bulk validate is Phase 4)*
- Keys have `project` and at least one role — *Phase 4*
- Route paths are valid regex — *Phase 4*
- Cross-reference resolution against the proposed-config state (current live config + not-yet-applied entities from the same batch) — *Phase 4 only*
- Batch dependency-order checks and `precheck: true` atomicity gate — *Phase 4 only*

**Cross-reference validation on per-entity writes — strict by default, opt-in soft.** Cross-references between entities (a model's `interceptors[]` naming an interceptor that doesn't exist yet, a role's `limits` map keyed by a deployment that doesn't exist yet, an application's `applicationTypeSchemaId` pointing at a not-yet-created schema) **block the per-entity `PUT` with `422 Unprocessable Entity`** by default. The 422 body uses the same `validationWarnings` shape that per-entity `GET` responses use (§2). Operators who want gradual file→API migration where references temporarily dangle (see [`02-architecture.md`](02-architecture.md) §10) set the static setting `config.write.softValidation: true` (default `false`); the per-entity write controllers then accept the write and surface the dangling reference through the per-entity `GET` `status: "invalid"` + `validationWarnings` channel ([`02-architecture.md`](02-architecture.md) §4.3) instead of rejecting. Listings do not carry validity (§4) — operators discover invalid entries via `GET /v1/admin/health/config` or a follow-up per-entity `GET`.

**`softValidation` governs per-entity acceptance during application across both surfaces** — per-entity `PUT /v1/{type}/{bucket}/{name}` writes and bulk `POST /v1/admin/apply` (§7). It is a server-wide setting controlling whether a write that fails validation is rejected (strict) or accepted with `status: "invalid"` (soft).

Bulk apply additionally evaluates references against the **proposed-config state** (current live config + not-yet-applied entities from the same batch) so that within-batch references resolve regardless of mode — that property is independent of `softValidation` and not configurable.

Bulk apply also takes a per-call **`precheck: true | false`** (default `true`) flag that controls **batch atomicity at the validation gate**. Under `precheck: true`, the server runs validation upfront for every entity in the batch and aborts on any error; under `precheck: false`, validation runs at each entity's write step and continues on per-entity failure. `precheck` is independent of `softValidation` — operators can pass `precheck: true` even under soft mode if they want fail-fast atomicity for that specific batch. The full matrix is in §7.

The recommended migration workflow under strict default is `dial-cli apply` of a manifest set that includes both the new entity and any references it depends on — within-batch resolution makes the per-entity 422 moot.

## 7. Bulk Apply Semantics

**Manifest `kind` taxonomy.** Every entry in an `apply` payload — and every YAML document in a CLI manifest file — carries a `kind` field. The valid values, the corresponding URL segment under `/v1/{type}/...`, and the overlay variant (used by `dial-cli` overlay manifests per [`05-cli-design.md`](05-cli-design.md) §5.2) are:

| `kind` | URL segment | Overlay variant | Server-consumed? |
|---|---|---|:---:|
| `Model` | `models` | `ModelOverlay` | Yes |
| `Application` | `applications` | `ApplicationOverlay` | Yes |
| `ToolSet` | `toolsets` | `ToolSetOverlay` | Yes |
| `Schema` | `schemas` | `SchemaOverlay` | Yes |
| `Interceptor` | `interceptors` | `InterceptorOverlay` | Yes |
| `Role` | `roles` | `RoleOverlay` | Yes |
| `Key` | `keys` | `KeyOverlay` | Yes |
| `Route` | `routes` | `RouteOverlay` | Yes |
| `Settings` | `settings` (singleton — `name` fixed at `global`) | `SettingsOverlay` | Yes |
| `File` | `files` | `FileOverlay` | Yes |
| `Prompt` | `prompts` | `PromptOverlay` | Yes |
| `Conversation` | `conversations` | `ConversationOverlay` | Yes |
| `Bundle` | (none — CLI-only sugar) | — | No (expanded client-side) |

Validation is **strict** — an unknown `kind` value on `POST /v1/admin/apply` returns `400 Bad Request` for the offending entry; the CLI rejects unknown `kind` at parse time before sending. Overlay variants (`*Overlay`) are CLI-only and never appear in the apply payload sent to the server — the CLI resolves base + overlay into a `kind: Model` / `kind: Role` / etc. before submission. The `Bundle` kind is also CLI-only — bundles expand into their constituent entity manifests client-side per [`05-cli-design.md`](05-cli-design.md) §5.3, so the server never sees `kind: Bundle`.

Apply-payload fields **server-consumed**: `kind`, `name`, `spec`, `etag` (when bulk apply gains per-entity ETag — out of scope today; see [`05-cli-design.md`](05-cli-design.md) §5.3 for the bundle `patch:` race contract). Apply-payload fields **CLI-only**: `template`, `params`, `patch` (overlay JSON Merge Patch), `target` (overlay target ID). The CLI resolves all CLI-only fields into a fully-expanded `spec:` before sending.

> **Phase gate — bulk write/validate ships in [Phase 4](07-migration-and-rollout.md#phase-4-declarative-mode--environment-promotion).** Phases 2 and 3 deliver per-entity `PUT` / `DELETE` only. The bulk-write endpoint `POST /v1/admin/apply` and the `dial-cli apply` / `diff` commands ship in Phase 4. The read-only snapshot `GET /v1/admin/export` (and `dial-cli export`) ships in **Phase 1** alongside the other read endpoints — it just serializes the current in-memory `Config`. `POST /v1/admin/validate` ships in two stages: a model-scoped validate in Phase 2 (covers the same single-entity types Phase 2 makes writable), and the full multi-entity / batch-aware validate in Phase 4 alongside `apply`. Until Phase 4, operators issue per-entity calls or use the [§6 migration workflow](#6-validation) to seed batches.

`POST /v1/admin/apply` accepts a set of entity manifests and applies them with the following behavior (see OQ-6 in [`08-open-questions-and-references.md`](08-open-questions-and-references.md)):

- **Validate-first gate (CLI-side).** The CLI calls `POST /v1/admin/validate` first. If any entity fails validation, nothing is sent to apply.
- **Server-side: apply sequentially.** The server processes manifests in dependency order and returns per-entity results — `{entityId, status, error?}` — with summary counts. The apply HTTP response is `200 OK` whenever the batch was accepted for processing (even if individual entities later failed); clients inspect the per-entity `status` array. The HTTP envelope is non-`200` only when the batch is rejected as a unit (precheck failure with `precheck: true`).
- **Cluster-wide serialization.** Every admin write surface — both this `POST /v1/admin/apply` and per-entity `PUT`/`DELETE /v1/{type}/{bucket}/{name}` — acquires a single global admin-write lock around its write phase (see [`02-architecture.md`](02-architecture.md) §4.4). Concurrent admin writes from different pods therefore serialize at the entity-set level, not just per-resource-key, so an interleaved batch on another pod cannot land between the writes of an in-flight apply.
- **Dependency apply order (fixed):** `globalSettings → schemas → interceptors → roles → keys → routes → models → toolsets → applications`. Every type uses the same PUT-upsert wire shape per §3, so the server-side apply loop is uniform — `kind: Settings` is no longer a special case at the wire level (the loop issues `PUT /v1/settings/platform/global` exactly the way it issues `PUT /v1/models/public/{name}`). `apply` does not delete the singleton — operators wanting to revert the API blob to the file/default projection use the explicit `DELETE /v1/settings/platform/global` (or `dial-cli settings reset`) outside the apply path.
- **No rollback.** Config entities are largely independent; partial application is acceptable.
- **Proposed-config validation is always-on.** Apply evaluates each entity's references against the **proposed-config state** (current live config + not-yet-applied entities from the same batch). A batch creating both an interceptor and a model referencing it validates successfully. This property is independent of `softValidation` and `precheck` — within-batch references always resolve.
- **Two flags govern apply behavior — `precheck` (per-call) and `softValidation` (server-wide):**

`precheck: true | false` (default `true`) controls **batch atomicity at the validation gate**. Under `precheck: true`, the server runs validation upfront for every entity in the batch and aborts on any error before any mutation. Under `precheck: false`, validation runs at each entity's write step during apply.

`softValidation` (server-wide static setting, default `false` — see §6) controls **per-entity acceptance during application** for any validation that runs at the per-entity step. Under `softValidation: false`, an entity that fails validation is rejected (per-entity `FAILED`). Under `softValidation: true`, the entity lands in blob with `status: "invalid"` and `validationWarnings`, surfaced via the listing channel ([`02-architecture.md`](02-architecture.md) §4.3) instead of being rejected.

The four cells:

| `softValidation` | `precheck` | Behavior |
|---|---|---|
| `false` (strict, default) | `true` *(default)* | Server pre-validates the whole batch. On any error the batch is **rejected before any mutation** — HTTP response carries the offending entities; **nothing applied**. |
| `false` (strict, default) | `false` | No upfront pre-check. Each entity validates at its own write step; per-entity validation failures become per-entity `FAILED`; subsequent entities continue. Apply response is `200 OK` with per-entity status. |
| `true` (soft) | `true` | Server pre-validates the whole batch. On any error the batch is **rejected before any mutation**, same as the strict + precheck cell — `precheck` is a per-call strict gate that operators can request even when the server is in soft mode. |
| `true` (soft) | `false` | No upfront pre-check. Each entity applies at its write step; per-entity validation failures **do not reject** — the entity is persisted to blob with `status: "invalid"` and `validationWarnings`. Apply response is `200 OK` with per-entity status (`"applied"` or `"applied_invalid"`).|

`precheck` is independent of `softValidation`. The mental model: `precheck` is the operator's per-call request for *batch atomicity*; `softValidation` is the server-wide policy for *whether broken entities are admitted at all*. They compose orthogonally.

**Per-entity status codes inside a bulk apply.** Bulk apply is upsert by design — the dependency-ordered sequential application performs create-or-update, never colliding-create — so a per-entity `409` cannot arise from a missing-create or duplicate-create case during apply. The only path that could surface a `409`-like state is a CAS / ETag check failure if the apply payload carried per-entity ETag metadata triggering it; the current payload schema has no per-entity ETag field (`etag` is reserved on the wire but not consumed by the server today — see [`05-cli-design.md`](05-cli-design.md) §5.3 for the `patch:` race contract), so this path is closed in practice. Any non-200 per-entity status that does appear inside a 200-batch (typically per-entity `FAILED` from validation under `precheck: false` + `softValidation: false`, or `500`-class server errors on a per-entity write) is mapped by the CLI to exit code `1` (partial-batch runtime failure) per the CLI exit-code contract.

Exit-code mapping for the CLI is in [`06-cli-user-guide.md`](06-cli-user-guide.md) §CI/CD Integration; the `1` (partial-batch) cell explicitly covers this case.

---

## Next

- Security, audit, and secret fields: [`04-security-and-audit.md`](04-security-and-audit.md)
- How the CLI consumes this API: [`05-cli-design.md`](05-cli-design.md)
- DevOps-facing reference: [`06-cli-user-guide.md`](06-cli-user-guide.md)
