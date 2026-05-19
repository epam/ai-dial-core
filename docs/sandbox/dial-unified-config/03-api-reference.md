# 03 — Configuration API Reference

> **Audience:** DIAL Core dev team implementing the API; integrators building against the unified Configuration surface (`/v1/{type}/{bucket}/*` for per-entity CRUD; `/v1/admin/*` for cross-entity ops).
> **Reading time:** ~15 minutes.
> **Prerequisites:** [`02-architecture.md`](02-architecture.md) §Path Format Reference and §Bucket Strategy.

This document specifies the new Configuration API surface exposed by DIAL Core. The CLI (`dial-cli`) and the DIAL Admin Backend are both clients of this API; nothing else in the system should bypass it for admin-managed configuration.

Authorization internals (the `ConfigAuthorizationService` interface), secret-field handling, and audit events are specified in [`04-security-and-audit.md`](04-security-and-audit.md). This document only describes the externally-visible API contract and validation rules.

---

## 1. Endpoint Structure

The Configuration API has two URL families:

- **Per-entity CRUD** at `/v1/{type}/{bucket}/{name}` — extended in two complementary places, **not** by adding new types into the existing `RouteTemplate.RESOURCE` regex (which today covers `applications`, `toolsets`, `prompts`, `conversations`). The genuinely new admin-config types (`models`, `interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`) get a sibling `RouteTemplate.CONFIG_RESOURCE` entry; `files` keeps its existing dedicated `RouteTemplate.FILES` entry (`/v1/files/{bucket}/{path}`) and reuses the existing files dispatch path with a thin admin-aware authz check on writes to `public/`; `conversations` and `prompts` keep their existing slot in `RESOURCE`. This avoids overlapping regex matches on `/v1/files/...` and respects the existing files controller's specific multipart / streaming handling. Authorization for all paths is bucket-aware: `ConfigAuthorizationService` dispatches from `(role, verb, type, bucket)` — admin role for writes to `public/` and reads/writes to `platform/`; bucket-owner for user buckets; read-public for `public/` reads. See [`04-security-and-audit.md`](04-security-and-audit.md) §1.
- **Cross-entity operator endpoints** at `/v1/admin/*` — `apply`, `validate`, `export`, `audit` (Phase 7), `health/config`, `schema`. These don't fit per-entity-CRUD (they span types, span buckets, or have no entity at all). Always admin-role-gated.

**Identifier format:** Full resource IDs `{type}/{bucket}/{name}` (matching `ResourceDescriptor.getUrl()` convention — see [`02-architecture.md`](02-architecture.md) §Path Format Reference). The URL path after `/v1/` is parsed as `{entityType}/{bucket}/{name...}`.

```
# Per-entity-type CRUD (full resource ID in URL — uniform with existing Resource API)
GET    /v1/{entityType}/{bucket}/                     # List entities in this bucket (admin enumerates per bucket)
GET    /v1/{entityType}/{bucket}/{name}               # Get entity details
POST   /v1/{entityType}/{bucket}/{name}               # Create-only — 409 Conflict if entity exists
PUT    /v1/{entityType}/{bucket}/{name}               # Update-only — 404 Not Found if missing
DELETE /v1/{entityType}/{bucket}/{name}               # Delete — 404 if missing

# Examples — bucket is always explicit:
GET    /v1/models/public/gpt-4                        # user-facing model (admin write, anyone read)
POST   /v1/roles/platform/viewer                      # create infrastructure role (admin only; 409 if exists)
PUT    /v1/applications/public/my-admin-app           # admin update of public application (404 if missing)
GET    /v1/interceptors/platform/guardrail            # infrastructure interceptor (admin only)
GET    /v1/settings/platform/global                   # singleton settings (admin only — see below)

# Singleton settings (uniform {type}/{bucket}/{name} shape; bucket=platform, name=global)
GET    /v1/settings/platform/global                   # Get effective global settings (blob | file | default projection)
PUT    /v1/settings/platform/global                   # Replace global settings (upsert — sets/updates the API blob)
DELETE /v1/settings/platform/global                   # Clear the API blob; revert to file-sourced (or default) projection (idempotent, 204)

# Cross-entity operator endpoints — every /v1/admin/* path requires admin role
# (gated by ConfigAuthorizationService.isAdmin(ctx); 403 otherwise)
POST   /v1/admin/apply                                # Apply set of resource manifests (declarative bulk)
POST   /v1/admin/validate                             # Validate manifests without applying

# State export (admin)
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

**Route layout — sibling regex, no overlap with `FILES`.** Phase 2 adds a new `RouteTemplate.CONFIG_RESOURCE` entry for the admin-config types only; `RouteTemplate.RESOURCE` and `RouteTemplate.FILES` are left unchanged so the existing `/v1/files/...` dispatch path keeps its dedicated controller. The current `RouteTemplate.RESOURCE` regex in `server/src/main/java/com/epam/aidial/core/server/data/RouteTemplate.java` is `"^/v1/(conversations|prompts|applications|toolsets)/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)$"` — confirming `applications` and `toolsets` are already matched, so admin writes to `public/applications/...` and `public/toolsets/...` flow through the existing `RESOURCE`-routed controllers without any regex change. Adding `files` into `RESOURCE` (or `CONFIG_RESOURCE`) would produce two patterns matching `/v1/files/{bucket}/...` — `ControllerSelector`'s evaluation order would silently determine which controller handles user file requests, a footgun avoided by keeping the existing slot:

```java
// New sibling entry — admin-config types only:
CONFIG_RESOURCE(
    "^/v1/(models|interceptors|roles|keys|routes|schemas|settings)/(?<bucket>[a-zA-Z0-9_-]+)(?:/(?<path>.*))?$",
    "/v1/{type}/{bucket}/{path}"
),
// Existing entries are unchanged:
//   RESOURCE  — (conversations|prompts|applications|toolsets)
//   FILES     — /v1/files/{bucket}/{path}
// Authorization branches in the controller / dispatcher on (verb, type, bucket).
```

**Trailing-slash listing — both forms accepted.** The trailing path group is **optional** in the `CONFIG_RESOURCE` regex (`(?:/(?<path>.*))?` — note the non-capturing group around the slash + path). Both `GET /v1/models/public/` and `GET /v1/models/public` resolve to the per-bucket listing route; the controller treats an empty or absent `path` capture identically. This intentionally diverges from the existing `FILES` template (which mandates the trailing slash and serves `301 Moved Permanently` for the no-slash form): listings on the new admin-config types are common enough that requiring a redirect on every `dial-cli get models` invocation would add a needless round-trip. For per-entity URLs (`GET /v1/models/public/gpt-4`) the path capture is non-empty and the controller dispatches to the get-single-entity branch. **Dispatch rule (explicit):** when the `path` capture is non-empty and not slash-terminated, the controller dispatches to the single-entity `GET`/`PUT`/`POST`/`DELETE` branch; an empty or absent `path` capture dispatches to listing.

**Bucket character class — `[a-zA-Z0-9_-]` is sufficient for Phase 1–5; MT scopes require regex update.** The bucket group accepts `[a-zA-Z0-9_-]` which covers every bucket value used in Phase 1–5 (`public`, `platform`, encrypted user-bucket IDs). Future multi-tenant scope IDs of the form `tenants/{id}`, `teams/{id}`, `channels/{id}` contain a `/` separator and therefore **will require the regex to be extended** (e.g. allowing one or more `/`-separated segments) when MT lands. The regex is not a "long-term constant" with respect to MT — it is sufficient for the single-tenant phases and an MT-aware regex update is part of the MT delivery scope.

Admin writes to `public/files/...`, `public/prompts/...`, `public/conversations/...` flow through their existing controllers (`FILES` and `RESOURCE`); `ConfigAuthorizationService` is consulted before the controller's existing per-bucket logic to gate the admin role on writes to the `public/` bucket and to deny admin reach into user buckets ([OQ-33](08-open-questions-and-references.md)). No new regex is needed for files/prompts/conversations — only a new authz check inside the existing controllers.

Validation is performed by `ResourceDescriptorFactory`, which enforces type, bucket, and name segment constraints automatically. No additional validation regex needed.

**Singleton settings rationale.** The `globalSettings` document doesn't have a natural per-entity name. Rather than carve out `/v1/admin/settings` as a one-off, the singleton uses `bucket=platform`, `name=global` to follow the uniform `{type}/{bucket}/{name}` pattern — keeps URL parsing, auth dispatch, and route regex uniform; future MT scopes plug in as `/v1/settings/{tenant-id}/...` without reshaping the route.

**Settings supports `GET`, `PUT`, and `DELETE`.** `POST` against the settings endpoint returns `405 Method Not Allowed` because the singleton conceptually always exists from the API's perspective (the `GET` projection always has a value — see "GET projection sources" below — so `POST`-create has nothing to create that isn't already addressable). `PUT` is **upsert** by nature (the one allowed exception to the strict create/update split — see paragraph below); it sets or replaces the API blob at `platform/settings/global`. `DELETE` **clears the API blob** and reverts the singleton to its file-sourced (or schema-default) projection — operators take API control via `PUT` and release it via `DELETE`. `DELETE` is idempotent: it returns `204 No Content` whether or not the blob was present, and after the call `GET` reflects the file-sourced (or default) values (`source: "file"` or `"default"`). Phase 2 implementation must validate `POST` ahead of the generic `CONFIG_RESOURCE` dispatch and emit `405` rather than letting it flow into the `POST`-creates branch of the generic controller. The `405 Method Not Allowed` response on `POST` MUST include `Allow: GET, PUT, DELETE` per RFC 9110 §15.5.6. `HEAD` is treated as `GET`. Concurrency: `PUT` and `DELETE` accept `If-Match` against the current `ETag`; mismatch returns `412 Precondition Failed`.

**GET projection sources.** The `GET` response is the **effective** projection of `globalSettings`, with the `source` field disclosing the origin (Owner-only):

| Blob present? | Config file defines `globalInterceptors` / `retriableErrorCodes`? | Body content | `source` |
|---|---|---|---|
| yes | (any) | API blob (whole-object replace per [OQ-10](08-open-questions-and-references.md)) | `"api"` |
| no | yes | file-sourced fields | `"file"` |
| no | no | schema defaults (empty `globalInterceptors`, default `retriableErrorCodes`) | `"default"` |

`"default"` is a singleton-only `source` value introduced for this projection — per-entity types do not produce it. The `ETag` returned via the HTTP header is computed over the projected body so clients can use `If-Match` consistently across the three states.

**Listing on the singleton — `GET /v1/settings/platform/` returns `405 Method Not Allowed`.** The listing path for the singleton type is not meaningful (there is exactly one entity at the fixed name `global`). Phase 2 returns `405 Method Not Allowed` with `Allow: GET, PUT, DELETE` (matching the per-entity surface above) so that callers are directed to `GET /v1/settings/platform/global`. Admin MCP and CLI clients should use the per-entity GET rather than listing — see [`09-admin-mcp-spec.md`](09-admin-mcp-spec.md) §6.1.

**Strict create/update split (no upsert at the single-entity surface).** `POST` creates and returns `409 Conflict` if the entity already exists; `PUT` updates and returns `404 Not Found` if the entity does not exist. The two methods are intentionally non-overlapping so a typo in an entity name surfaces as a clean 404/409 instead of a silent stub creation. *(When Phase 7 audit lands, the `operation` field — `create | update | delete` — is unambiguously derivable from the HTTP method without a pre-state probe.)* Bulk upsert (create-or-update by desired-state apply) lives only on `POST /v1/admin/apply` (§7) — that is the canonical declarative path. The singleton `PUT /v1/settings/platform/global` is the one allowed exception: the global-settings projection always has a value (blob, file, or default), so its endpoint is upsert by nature; `DELETE` on the same URL is supported and means "clear the API blob, revert to file/default" — see the *Settings supports `GET`, `PUT`, and `DELETE`* paragraph above.

**PATCH deferred to Phase 4+.** Phase 2–3 supports `POST` (create), `PUT` (full update), and `DELETE` only. `PUT` requires the complete entity body — absent fields revert to defaults (except write-only fields like `Key.key` which use preserve-on-omit). The CLI provides field-level update ergonomics via `--set` flags (internally: GET + local merge + PUT). PATCH (RFC 7396 JSON Merge Patch) is a Phase 4+ addition if operator feedback indicates full-entity PUT is insufficient.

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

**Response shape — Public vs Owner views.** Entity-intrinsic fields live at top level (matching today's user Resource API shape). On GET responses, two extra fields are projected per [`04-security-and-audit.md`](04-security-and-audit.md) §1.5: `status` (top-level, **Public** — visible to anyone who can read the entity) and `source` + `validationWarnings` (top-level, **Owner-only** — admin or bucket-owner). Public callers see the entity body + `status` only; Owner callers additionally see `source` and `validationWarnings`. The flat shape is enforced by Jackson `@JsonView` annotations on the response wrapper with `DEFAULT_VIEW_INCLUSION = false` — a forgotten annotation makes a field invisible everywhere (fail-closed at write time, never silently leaks). `etag` is returned via the HTTP `ETag` header, never in the body.

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

Uses the same ETag pattern as existing Resource API:

- `If-Match: <etag>` header on `PUT` / `DELETE` is **optional** for optimistic concurrency (matching today's user Resource API behavior — `ResourceService.put` accepts `EtagHeader.ANY` when no header is provided). When present, the server returns `412 Precondition Failed` if the stored ETag has moved; when absent, the write proceeds unconditionally (last-write-wins). This is a deliberate choice for parity with the existing Resource API and to keep simple `dial-cli update` flows ergonomic; CI pipelines and concurrency-sensitive callers should always pass `--if-match` (CLI flag) or `If-Match` (header). The CLI's `update` command exits `6` (412) when a passed-in ETag doesn't match (see `06-cli-user-guide.md` §2.8 — exit `5` is reserved for `409` Conflict on `add`).
- `POST` provides create-only semantics natively (`409 Conflict` if exists) — no `If-None-Match: *` header is needed or accepted at the single-entity surface.
- ETag returned in the HTTP `ETag` response header on `POST`, `PUT`, and `GET`. Never on `DELETE` (the resource has no representation post-deletion and `ResourceService.delete()` does not produce an ETag). Never in the response body. The ETag value is the one `ResourceService` already computes and stores in the Redis HASH `etag` attribute as part of the same `put()` call that created/updated the resource — controllers retrieve it from the `ResourceService` write result (or via `ResourceService.getResource(descriptor)` immediately after the put) rather than computing it independently. This guarantees the ETag returned in the response matches the value subsequent `If-Match` checks will compare against.

Error code mapping at a glance: `404` = entity missing on `PUT`/`DELETE`/`GET`; `409` = entity already exists on `POST`; `412` = `If-Match` mismatch on `PUT`/`DELETE`.

### 3.1 Secret field handling on PUT

`PUT` is full-replace by default — fields absent from the request body revert to the entity's defaults. Secret fields are the **explicit exception**: an absent / `null` / `"***"` secret field on `PUT` preserves the value already stored. The fields that follow this preserve-on-omit semantics are:

- `Key.key`
- `Upstream.key`
- `Upstream.extraData`

**All other fields follow standard full-replace semantics.** Clients that want to keep a non-secret field at its current value must include it in the `PUT` body explicitly — the server does not infer "preserve" for non-secret fields. `POST` does **not** participate in preserve-on-omit: a `POST` body with `field = "***"` is rejected as `400 Bad Request` (the mask sentinel is not a valid create-time secret). See [`04-security-and-audit.md`](04-security-and-audit.md) §2.5 for the full `POST` / `PUT` matrix and the `"***"` sentinel rules, and §2.4 for the `@EncryptedField` annotation that gates which fields participate.

## 4. Response Format for Lists

Listing is per-bucket — `GET /v1/{type}/{bucket}/`. Admin enumerates the relevant bucket(s); for admin-managed types each entity type has exactly one shared bucket (`public/` or `platform/`), so enumeration is unambiguous. There is no flat cross-bucket list route. Field projection follows the Public/Owner views in [`04-security-and-audit.md`](04-security-and-audit.md) §1.5 — the listing controller computes the projection once per bucket (caller's authz to that bucket determines the view) and applies it uniformly to all items.

**Pagination — `?limit=N&cursor=...` (default 100, max 500).** The listing endpoint accepts two query parameters: `limit` (page size; integer, default 100, hard cap 500 — values above 500 are clamped to 500) and `cursor` (opaque continuation token returned by a previous page; absent on the first request). The response carries two envelope fields alongside `items`:

```json
{
  "entityType": "models",
  "bucket": "public",
  "items": [ /* … up to `limit` items … */ ],
  "nextCursor": "opaque-base64-token",
  "hasMore": true
}
```

`hasMore` is **always present** (`true` or `false`) on every listing response; `nextCursor` is present **iff** `hasMore: true` and is omitted on the last page. The two fields are kept consistent — the two-field shape is convenient for clients that prefer either explicit `hasMore` checks or `nextCursor`-presence checks. The cursor is opaque and clients must not parse it. The Admin MCP's `dial_admin_list_entities` paginates the underlying listing endpoint (issuing `?limit=500` per page) until `hasMore: false` (for bounded entity types) or until its per-invocation ceiling of 2,500 items (5 pages) for potentially unbounded types (`files`, `prompts`, `conversations`) — see [`09-admin-mcp-spec.md`](09-admin-mcp-spec.md) §6.1 for the full draining and truncation semantics.

**`name` field synthesis.** The `name` value on each list item (and on `GET` of a single entity) is **always synthesized** by the controller — for API-managed entities the **full canonical ID** (the `Config` map key, e.g. `models/public/gpt-4`); for file-sourced entities the simple-name `Config` map key (e.g. `gpt-4`). It is never deserialized from the persisted JSON body. **Amendment 2026-05-08 (Polish.1):** prior to this round API-managed entries projected `simpleName(mapKey)` in the listing/GET; canonical IDs were exposed only on the legacy `/openai/...` listings. Operators copy-pasting an API entry's listing row into a per-entity URL needed to reconstruct the canonical prefix manually, and a file-vs-API simple-name collision silently lost one row in the listing. Polish.1 projects the full canonical ID for API entries so the row is copy-paste-friendly and the dedup keying on the full map key (see *Listing dedup* below) preserves both rows on collision. File-sourced entries are unchanged. Implementers wiring the listing controller must populate `name` from the canonical map key for API entries and from the simple map key for file entries — not expect it on the persisted body.

**Listing dedup.** The listing builder dedupes rows by the full `Config` map key — *not* by simple name. A file entry keyed `gpt-4` and an API entry keyed `models/public/gpt-4` therefore appear as **two distinct rows**, distinguished by the `name` field (simple vs canonical) and by the Owner-only `source` field (`"file"` vs `"api"`). Pre-Polish.1 the dedup was simple-name-keyed, so a file/API simple-name twin silently dropped one row.

**Owner view — admin or bucket-owner caller:**

```json
{
  "entityType": "models",
  "bucket": "public",
  "items": [
    {
      "name": "chat-gpt-35-turbo",
      "type": "chat",
      "endpoint": "...",
      "status": "valid",
      "source": "file"
    },
    {
      "name": "models/public/anthropic.claude-sonnet-4-6",
      "type": "chat",
      "endpoint": "...",
      "status": "valid",
      "source": "api"
    },
    {
      "name": "models/public/old-broken-model",
      "type": "chat",
      "endpoint": "...",
      "status": "invalid",
      "source": "api",
      "validationWarnings": [
        { "field": "applicationTypeSchemaId",
          "message": "Schema 'schemas/public/old-schema-v1' not found" },
        { "field": "interceptors[0]",
          "message": "Interceptor 'deprecated-guardrail' not found" }
      ]
    }
  ]
}
```

**Public view — anonymous reader of `public/` bucket:**

```json
{
  "entityType": "models",
  "bucket": "public",
  "items": [
    { "name": "chat-gpt-35-turbo", "type": "chat", "endpoint": "...", "status": "valid" },
    { "name": "models/public/anthropic.claude-sonnet-4-6", "type": "chat", "endpoint": "...", "status": "valid" },
    { "name": "models/public/old-broken-model", "type": "chat", "endpoint": "...", "status": "invalid" }
  ]
}
```

`source` and `validationWarnings` are absent in the Public view — they're omitted entirely, not nulled. `etag` is in the HTTP `ETag` header on per-item GET, not in the listing body.

> **Public-view exposure of `endpoint` and `upstreams[].endpoint`.** Both fields remain in the Public view for `public/`-bucket types (`models`, `applications`, `toolsets`, `schemas`, `files`, `prompts`, `conversations`), consistent with today's `/openai/models` and `/openai/deployments` behaviour. `platform/`-bucket types are admin-only at the `ConfigAuthorizationService` dispatch layer, so the Public view shape never reaches a non-admin caller for those. See [`04-security-and-audit.md`](04-security-and-audit.md) §1.5 for the full bucket-scope clarification and the operator-side mitigation if cluster-internal endpoints must stay private.

The `source` field (Owner-only) indicates whether the entity came from the config file (`"file"` — read-only via API, will be overridden once migrated) or was created via the API (`"api"` — full CRUD). This transparency helps operators understand the current state and plan migration.

The `status` field (Public — visible to all readers) indicates whether the entity passes current-version validation (`"valid"`) or fails it (`"invalid"`). Blob storage holds only the entity payload — `validationWarnings` are **always computed at runtime**, never persisted alongside the entity. Computation timing depends on the entity's storage model (see [`02-architecture.md`](02-architecture.md) §4.3): for MergedConfigStore-managed entities (models, roles, schemas, interceptors, routes, keys, settings) warnings are computed at `MergedConfigStore` rebuild and held in the in-memory `invalidEntities` sibling store; for blob-native entities (applications, toolsets) warnings are computed lazily by `BlobEntityValidator` on each Configuration API read. Either way, the listing/get response folds the current warnings into the payload — no warning state lives in blob.

Hot-path consequence also depends on storage model: invalid MergedConfigStore-managed entities are skipped from `Config` and never serve traffic; invalid blob-native entities still serve through `findDeployment` and fail at request time on the missing reference (today's behavior — unchanged). Causes of `invalid` are upstream changes (referenced interceptor or schema removed) and version drift after a Core upgrade introduces stricter validation. Direct creation of an invalid entity is rejected by write-time validation. See [`02-architecture.md`](02-architecture.md) §4.3 for the layered model and §4.1 for the three visibility channels (health endpoint, listing, Prometheus).

**Forward compatibility note.** The `status` field is **always present** on listing items from Phase 1 onwards. Phase 1 servers ship without `MergedConfigStore` (read-only API directly off the in-memory `Config` ref), so every Phase 1 item returns `"status": "valid"`. The `"invalid"` value first appears in Phase 2 once the invalid-entity sibling store is introduced. Clients written against Phase 1 servers therefore see a stable shape and do not need version-aware parsing. Phase 1 listing responses MUST also include the pagination envelope fields (`hasMore`, `nextCursor`, `entityType`, `bucket`) even when Phase 1 serves from the in-memory `Config` ref without blob pagination — `hasMore: false` is always present on Phase 1 (no cursor needed for a single in-memory snapshot), `nextCursor` is absent. Clients polling `hasMore: false` are stable across Phase 1 and Phase 2+.

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

**Cross-reference validation on per-entity writes — strict by default, opt-in soft.** Cross-references between entities (a model's `interceptors[]` naming an interceptor that doesn't exist yet, a role's `limits` map keyed by a deployment that doesn't exist yet, an application's `applicationTypeSchemaId` pointing at a not-yet-created schema) **block the per-entity write with `422 Unprocessable Entity`** by default. The 422 body uses the same `validationWarnings` shape as the listing response (§4). Operators who want gradual file→API migration where references temporarily dangle (see [`02-architecture.md`](02-architecture.md) §10) set the static setting `config.write.softValidation: true` (default `false`); the per-entity write controllers then accept the write and surface the dangling reference through the listing `status: "invalid"` + `validationWarnings` channel ([`02-architecture.md`](02-architecture.md) §4.3) instead of rejecting.

**`softValidation` governs per-entity acceptance during application across both surfaces** — per-entity `POST` / `PUT /v1/{type}/{bucket}/{name}` writes and bulk `POST /v1/admin/apply` (§7). It is a server-wide setting controlling whether a write that fails validation is rejected (strict) or accepted with `status: "invalid"` (soft).

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

> **Phase gate — bulk write/validate ships in [Phase 4](07-migration-and-rollout.md#phase-4-declarative-mode--environment-promotion).** Phases 2 and 3 deliver per-entity `POST` / `PUT` / `DELETE` only. The bulk-write endpoint `POST /v1/admin/apply` and the `dial-cli apply` / `diff` commands ship in Phase 4. The read-only snapshot `GET /v1/admin/export` (and `dial-cli export`) ships in **Phase 1** alongside the other read endpoints — it just serializes the current in-memory `Config`. `POST /v1/admin/validate` ships in two stages: a model-scoped validate in Phase 2 (covers the same single-entity types Phase 2 makes writable), and the full multi-entity / batch-aware validate in Phase 4 alongside `apply`. Until Phase 4, operators issue per-entity calls or use the [§6 migration workflow](#6-validation) to seed batches.

`POST /v1/admin/apply` accepts a set of entity manifests and applies them with the following behavior (see OQ-6 in [`08-open-questions-and-references.md`](08-open-questions-and-references.md)):

- **Validate-first gate (CLI-side).** The CLI calls `POST /v1/admin/validate` first. If any entity fails validation, nothing is sent to apply.
- **Server-side: apply sequentially.** The server processes manifests in dependency order and returns per-entity results — `{entityId, status, error?}` — with summary counts. The apply HTTP response is `200 OK` whenever the batch was accepted for processing (even if individual entities later failed); clients inspect the per-entity `status` array. The HTTP envelope is non-`200` only when the batch is rejected as a unit (precheck failure with `precheck: true`).
- **Cluster-wide serialization.** Every admin write surface — both this `POST /v1/admin/apply` and per-entity `POST`/`PUT`/`DELETE /v1/{type}/{bucket}/{name}` — acquires a single global admin-write lock around its write phase (see [`02-architecture.md`](02-architecture.md) §4.4). Concurrent admin writes from different pods therefore serialize at the entity-set level, not just per-resource-key, so an interleaved batch on another pod cannot land between the writes of an in-flight apply.
- **Dependency apply order (fixed):** `globalSettings → schemas → interceptors → roles → keys → routes → models → toolsets → applications`. The server-side apply loop special-cases `kind: Settings`: it always issues `PUT /v1/settings/platform/global` (the singleton upsert path) rather than attempting POST-create. POST to the settings endpoint returns 405 (the singleton's GET projection always has a value, so POST-create has nothing to create), so the generic create-then-update logic must not be used for this type. `apply` does not delete the singleton — operators wanting to revert the API blob to the file/default projection use the explicit `DELETE /v1/settings/platform/global` (or `dial-cli settings reset`) outside the apply path.
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
