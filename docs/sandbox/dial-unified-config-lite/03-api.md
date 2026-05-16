# 03 — API Surface

The shape of the Configuration API for reviewers. Verbs, URL patterns, concurrency, and authorization — no full payload tables.

## Two endpoint families

```
Per-entity CRUD              /v1/{type}/{bucket}/{name}
Cross-entity operator ops    /v1/admin/{apply | validate | export | schema | audit | health/config}
```

Per-entity CRUD is uniform with the existing Resource API: a full resource identifier in the URL, type-first. Operator endpoints live under `/v1/admin/*` and are always admin-role-gated; non-admin callers get `403`.

## Per-entity CRUD

```
GET    /v1/{type}/{bucket}/             # list entities in this bucket
GET    /v1/{type}/{bucket}/{name}       # get entity
POST   /v1/{type}/{bucket}/{name}       # create-only — 409 if exists
PUT    /v1/{type}/{bucket}/{name}       # update-only — 404 if missing
DELETE /v1/{type}/{bucket}/{name}       # delete — 404 if missing
```

Canonical examples:

```
GET    /v1/models/public/gpt-4                   # user-facing model
POST   /v1/roles/platform/viewer                 # infrastructure role (409 if exists)
PUT    /v1/applications/public/my-admin-app      # admin update of public app
GET    /v1/settings/platform/global              # singleton settings
```

The bucket is always explicit. `public/` for user-facing types (models, applications, toolsets, schemas); `platform/` for infrastructure (roles, keys, routes, interceptors, settings). Admin scope also covers `public/` instances of `files`, `prompts`, and `conversations` (shared assets); user-owned instances in user buckets remain owner-managed by the existing Resource API rule.

### The strict create/update split

`POST` and `PUT` are deliberately non-overlapping:

- `POST` creates and returns `409 Conflict` if the entity exists.
- `PUT` updates and returns `404 Not Found` if it doesn't.

A typo in an entity name therefore surfaces as a clean `404` / `409` instead of a silent stub creation. Bulk upsert lives only on `POST /v1/admin/apply` — that is the canonical declarative path.

The only exception is the singleton `PUT /v1/settings/platform/global`, which is upsert by design (the projection always has a value — blob, file, or default). `DELETE` on that URL clears the API blob and reverts to the file-sourced or default projection.

## Operator endpoints (`/v1/admin/*`)

```
POST  /v1/admin/apply                # apply a set of resource manifests
POST  /v1/admin/validate             # validate manifests without applying
GET   /v1/admin/export               # full effective config (YAML/JSON)
GET   /v1/admin/export?type=models   # one entity type
GET   /v1/admin/schema/{type}        # JSON Schema for an entity type
GET   /v1/admin/health/config        # status: ok | degraded + skipped[]
GET   /v1/admin/audit                # Phase 7 — deferred
```

`export` is the single source of truth for runtime state — it returns the effective merged config DIAL Core is actually serving (file + API entries). `health/config` exposes invalid-entity skip state separately from the unauthenticated Kubernetes `/health` liveness probe, which is unchanged.

## Concurrency

ETag-based optimistic concurrency, matching the existing Resource API:

- `If-Match: <etag>` on `PUT` / `DELETE` is **optional**. Present → server returns `412 Precondition Failed` on mismatch. Absent → write proceeds (last-write-wins).
- ETag is returned in the HTTP `ETag` response header on `POST`, `PUT`, `GET`. Never in the body, never on `DELETE`.
- `POST` covers the create-only case natively (`409 Conflict` if exists). No `If-None-Match: *` is needed.

CI pipelines and concurrency-sensitive callers should always pass `If-Match`; ad-hoc CLI updates can omit it for ergonomics.

### Secret fields on `PUT` — preserve-on-omit

`PUT` is full-replace by default — absent fields revert to defaults. **Secret fields are the explicit exception**: an absent / `null` / `"***"` secret on `PUT` preserves the value already stored. This applies to `Key.key`, `Upstream.key`, `Upstream.extraData`. All other fields follow standard full-replace; clients that want to keep a non-secret value must include it in the body.

`POST` does **not** participate — a `"***"` sentinel at create time is rejected as `400`.

## Authorization model

Two gates:

- **`/v1/admin/*`** — every endpoint requires the admin role, checked uniformly. `403` otherwise.
- **Per-entity `/v1/{type}/{bucket}/{name}`** — bucket-aware: `ConfigAuthorizationService` dispatches on `(role, verb, type, bucket)`. Admin for writes to `public/` and reads/writes to `platform/`; bucket-owner for user buckets; read-public for `public/` reads.

The full authorization spec is in [`04-security.md`](04-security.md).

## Validation

- **Entity name** — `^[A-Za-z0-9._-]+$` per path segment. `400` on violation.
- **Body** — current-version structural + semantic validation, no bypass flag. Soft-validation (accepting dangling cross-references) is opt-in via `config.write.softValidation: true`; default is strict `422`.
- **Status of stored entities** — invalid entities are visible on the listing and `GET` surface with `"status": "invalid"` and an Owner-only `validationWarnings` array. They don't disappear silently.

## Listing & pagination

`GET /v1/{type}/{bucket}/` returns items in a paginated envelope:

```json
{
  "entityType": "models",
  "bucket": "public",
  "items": [ /* … */ ],
  "nextCursor": "opaque-base64-token",
  "hasMore": true
}
```

`limit` defaults to 100, capped at 500. `nextCursor` is opaque. `hasMore` is always present. Owner callers (admin or bucket-owner) also see `source` (`"file"` / `"api"` / `"default"`) and `validationWarnings` per item; Public callers see the body + `status` only.

File-sourced and API-managed entries appear as distinct rows: a file entry keyed `gpt-4` and an API entry keyed `models/public/gpt-4` are two rows, distinguished by `name` (simple vs canonical) and the Owner-only `source` field.

## Bulk apply semantics

`POST /v1/admin/apply` is the declarative path. The contract:

- **Validate first.** The CLI runs validation locally before submitting; the server re-validates server-side.
- **Sequential application** — the server processes entities in submission order.
- **Continue on per-entity failure** — one failed entity does not roll back successful ones. The response carries a per-entity result list (`created` / `updated` / `unchanged` / `failed`, with error details for failures).

This is the locked failure-semantics decision: a partial-success report is more useful operationally than an all-or-nothing transaction across heterogeneous entity types.

> See the full version: [`../dial-unified-config/03-api-reference.md`](../dial-unified-config/03-api-reference.md)
