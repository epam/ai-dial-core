# 03 — API Surface

The shape of the Configuration API for reviewers. Verbs, URL patterns, concurrency, and authorization — no full payload tables.

## Two endpoint families

```
Per-entity CRUD              /v1/{type}/{bucket}/{name}
Per-bucket / folder listing  /v1/metadata/{type}/{bucket}/{path}
Cross-entity operator ops    /v1/admin/{apply | validate | schema | health/config}
                             # /v1/admin/export deferred — Defer.1
                             # /v1/admin/audit  deferred — Phase 7
```

Per-entity CRUD is uniform with the existing Resource API: a full resource identifier in the URL, type-first. Per-bucket and per-folder listings live on the sibling `/v1/metadata/{type}/{bucket}/{path}` route — identical shape to the existing `RESOURCE_METADATA` / `FILES_METADATA` routes for files, conversations, prompts, applications, and toolsets. Operator endpoints live under `/v1/admin/*` and are always admin-role-gated; non-admin callers get `403`.

## Per-entity CRUD

```
GET    /v1/{type}/{bucket}/{name}       # get entity (304 on If-None-Match match)
PUT    /v1/{type}/{bucket}/{name}       # upsert; If-None-Match: * → 412 if exists; If-Match: <etag> → 412 on mismatch
DELETE /v1/{type}/{bucket}/{name}       # delete; If-Match: <etag> → 412 on mismatch; 404 if missing
```

`POST` on the per-entity URL returns `405 Method Not Allowed` with `Allow: GET, PUT, DELETE`.

Canonical examples:

```
GET    /v1/models/public/gpt-4                                 # user-facing model
PUT    /v1/roles/platform/viewer    If-None-Match: *           # create-only (412 if exists)
PUT    /v1/applications/public/my-admin-app                    # bare upsert (last-write-wins)
GET    /v1/settings/platform/global                            # singleton settings
```

The bucket is always explicit. `public/` for user-facing types (models, applications, toolsets, schemas); `platform/` for infrastructure (roles, keys, routes, interceptors, settings). Admin scope also covers `public/` instances of `files`, `prompts`, and `conversations` (shared assets); user-owned instances in user buckets remain owner-managed by the existing Resource API rule.

### Wire shape — PUT-upsert with RFC 7232 conditional headers

Aligned with the existing Resource API since launch: one `PUT` upsert at the single-entity surface, with conditional headers covering create-only and CAS semantics.

- `PUT … If-None-Match: *` — create-only gate. Returns `412 Precondition Failed` if any entity already exists at that URL.
- `PUT … If-Match: <etag>` — CAS update guard. Returns `412 Precondition Failed` if the stored ETag has moved.
- `PUT` bare — last-write-wins upsert (creates if absent, replaces if present).
- `DELETE … If-Match: <etag>` — `412` on mismatch; `404` if missing.
- `GET … If-None-Match: <etag>` — `304 Not Modified` when the supplied ETag matches stored (RFC 7232 §3.2).

A typo on `PUT … If-None-Match: *` (entity exists) or on `DELETE` (entity missing) surfaces as a clean structured error instead of a silent stub creation. Bulk upsert lives only on `POST /v1/admin/apply` — that is the canonical declarative path.

The singleton `PUT /v1/settings/platform/global` follows the same shape — upsert by design (the projection always has a value: blob, file, or default). `DELETE` clears the API blob and reverts to the file-sourced or default projection (idempotent). `POST` returns `405` with `Allow: GET, PUT, DELETE`. On the metadata route (`GET /v1/metadata/settings/platform/`) the controller is read-only — `POST` / `PUT` / `DELETE` there return `405` with `Allow: GET`.

## Operator endpoints (`/v1/admin/*`)

```
POST  /v1/admin/apply                # apply a set of resource manifests
POST  /v1/admin/validate             # validate manifests without applying
GET   /v1/admin/schema/{type}        # JSON Schema for an entity type
GET   /v1/admin/health/config        # status: ok | degraded + skipped[]
GET   /v1/admin/audit                # Phase 7 — deferred
# GET /v1/admin/export               # deferred — Defer.1 (design preserved)
# GET /v1/admin/export?type=models   # deferred — Defer.1
```

`export` was originally the single source of truth for runtime state — it returns the effective merged config DIAL Core is actually serving (file + API entries). **Deferred from MVP at core-team request 2026-05-20** — see `../dial-unified-config/IMPLEMENTATION.md` §5.5 Defer.1; design preserved. Until it ships, operators consult `aidial.config.json` directly for file-sourced entries. `health/config` exposes invalid-entity skip state separately from the unauthenticated Kubernetes `/health` liveness probe, which is unchanged.

## Concurrency

ETag-based optimistic concurrency, matching the existing Resource API:

- `If-Match: <etag>` on `PUT` / `DELETE` is **optional**. Present → server returns `412 Precondition Failed` on mismatch. Absent → write proceeds (last-write-wins).
- `If-None-Match: *` on `PUT` is the **create-only gate** — `412 Precondition Failed` if the entity already exists.
- `If-None-Match: <etag>` on `GET` returns `304 Not Modified` when the supplied ETag matches stored.
- ETag is returned in the HTTP `ETag` response header on `PUT` and `GET` (and on per-item `GET /v1/metadata/...`). Never in the body, never on `DELETE`.

CI pipelines and concurrency-sensitive callers should always pass `If-Match`; ad-hoc CLI updates can omit it for ergonomics.

### Secret fields on `PUT` — preserve-on-omit

`PUT` is full-replace by default — absent fields revert to defaults. **Secret fields are the explicit exception**: an absent / `null` / `"***"` secret on `PUT` preserves the value already stored. This applies to `Key.key`, `Upstream.key`, `Upstream.extraData`, plus toolset OAuth credentials (`ResourceAuthSettings.clientSecret` and `codeVerifier`) — those use the existing toolset-encryption path but follow the same preserve-on-omit semantics. All other fields follow standard full-replace; clients that want to keep a non-secret value must include it in the body.

On a create (`PUT … If-None-Match: *` against a non-existent URL) there is no prior value to preserve — a `"***"` sentinel at create time is rejected as `400 Bad Request`.

## Authorization model

Two gates:

- **`/v1/admin/*`** — every endpoint requires the admin role, checked uniformly. `403` otherwise.
- **Per-entity `/v1/{type}/{bucket}/{name}`** — bucket-aware: `ConfigAuthorizationService` dispatches on `(role, verb, type, bucket)`. Admin for writes to `public/` and reads/writes to `platform/`; bucket-owner for user buckets; read-public for `public/` reads.

The full authorization spec is in [`04-security.md`](04-security.md).

## Validation

- **Entity name** — `^[A-Za-z0-9._%:-]+$` per path segment, checked on the URL-decoded value (literal `%` sent as `%25`). `400` on violation. Extendable on client request — see `02-architecture.md`.
- **Body** — current-version structural + semantic validation, no bypass flag. Soft-validation (accepting dangling cross-references) is opt-in via `config.write.softValidation: true`; default is strict `422`.
- **Status of stored entities** — invalid entities surface their `"status": "invalid"` + Owner-only `validationWarnings` on per-entity `GET` responses. The aggregate "which entities are invalid" view lives on `GET /v1/admin/health/config`. Listings do not carry this projection (see below).

## Listing & pagination

`GET /v1/metadata/{type}/{bucket}/{path}?limit=N&token=…` returns the same `ResourceFolderMetadata` / `ResourceItemMetadata` shape the existing `RESOURCE_METADATA` and `FILES_METADATA` controllers return. A folder GET returns `ResourceFolderMetadata`; a per-item GET returns `ResourceItemMetadata`. `limit` defaults to 100, hard cap 1000.

```json
{
  "name": null,
  "parentPath": null,
  "bucket": "public",
  "url": "models/public/",
  "nodeType": "FOLDER",
  "resourceType": "MODEL",
  "items": [
    { "name": "gpt-4", "url": "models/public/gpt-4", "nodeType": "ITEM", "resourceType": "MODEL", "etag": "…", "updatedAt": 1715800000000, "createdAt": 1715700000000 }
  ],
  "nextToken": "opaque-base64-token"
}
```

`nextToken` is **present iff there is another page** (omitted on the last page — no separate `hasMore` field). The token is opaque; clients must not parse it.

**Listings are blob-only and carry no Public/Owner projection.** No `status`, no `source`, no `validationWarnings`, no entity body. File-sourced entries (from `aidial.config.json`) are not surfaced through `/v1/metadata/...`. During MVP, operators consult `aidial.config.json` directly off the deployment to see file-sourced entries (`GET /v1/admin/export` / `dial-cli export` deferred — see `../dial-unified-config/IMPLEMENTATION.md` §5.5 Defer.1). Operators who need entity-level validity or provenance do a per-entity `GET` after the listing.

## Bulk apply semantics

`POST /v1/admin/apply` is the declarative path. The contract:

- **Validate first.** The CLI runs validation locally before submitting; the server re-validates server-side.
- **Dependency-ordered application** — the server applies entities in a fixed order (`settings → schemas → interceptors → roles → keys → routes → models → toolsets → applications`), not submission order.
- **Two orthogonal flags govern behaviour.** `precheck: true` (per-call, default) pre-validates the whole batch and aborts on any error — fail-fast batch atomicity. `precheck: false` validates at each entity's write step and continues on per-entity failure. The server-wide `config.write.softValidation` (see Validation) composes orthogonally — `precheck` controls atomicity, `softValidation` controls whether broken entities are admitted at all.
- **Continue on per-entity failure** (under `precheck: false`) — one failed entity does not roll back successful ones. The response carries a per-entity result list (`created` / `updated` / `unchanged` / `failed`, with error details for failures).
- **Cluster-wide serialization.** Every admin write surface — bulk apply and per-entity `PUT` / `DELETE` alike — runs under a single global admin-write lock (`AdminWriteLockService`; see `02-architecture.md`). Concurrent admin writes on different pods serialize at the entity-set level; an in-flight apply cannot have another batch's writes interleaved into it.

This is the locked failure-semantics decision: a partial-success report is more useful operationally than an all-or-nothing transaction across heterogeneous entity types.

> See the full version: [`../dial-unified-config/03-api-reference.md`](../dial-unified-config/03-api-reference.md)
