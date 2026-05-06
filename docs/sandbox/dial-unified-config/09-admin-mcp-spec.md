# 09 — DIAL MCP Server (Spec v0.4 — building blocks, embedded module, OQs resolved)

> **Status:** Draft v0.4 — all eight open questions resolved (§10). Single-surface design with 9 building-block tools, in-repo Java Gradle module embedded in DIAL Core as a Vert.x verticle, mounted on Core's existing port. Architectural discipline ensures the module is extractable to a standalone service later with minimal code change. Ready to kick off MCP-1 implementation.
> **Audience:** Product, DIAL Core dev team, MCP tooling team, DevOps leads, anyone building agents that talk to DIAL.
> **Prerequisites:** [`03-api-reference.md`](03-api-reference.md) (the API this wraps), [`04-security-and-audit.md`](04-security-and-audit.md) (auth model).

This document specifies a Model Context Protocol (MCP) server that exposes DIAL's REST API to AI agents as a small set of typed building-block tools. Both administrators and end-users (via DIAL QuickApps, Claude Code, Claude Desktop, IDE integrations, CI) call the **same** tool surface — DIAL Core enforces authorization based on the caller's identity, so the MCP itself stays small and stupid. The MCP is *not* a replacement for `dial-cli` or the DIAL Admin Backend (those remain canonical human interfaces); it is the canonical *agent* interface.

---

## 1. Summary

Build `dial-mcp`: a Java Gradle module (sibling to the existing `config/`, `storage/`, `credentials/`, `server/`, and `cli/` modules) inside the existing `ai-dial-core` repository, deployed as a Vert.x verticle alongside the rest of DIAL Core. The module exposes 9 building-block tools — describe-schema, list/get/create/update/delete resource, upload/download file, publish resource — against DIAL Core's REST API. Agents compose these into the higher-level workflows users actually want (promote a model, scaffold an app, integrate an external toolset, save resources from a chat). The MCP doesn't bake workflows in; agents are good at composition, the MCP makes composition cheap.

The embedded-module shape is chosen for v1 delivery speed (one repo, one build, one release; type-sharing with `config/` for free), with explicit architectural discipline (§7.1) that keeps extraction to a standalone sidecar a build-and-deploy change rather than a refactor. See §11 for the extraction trigger conditions.

A single tool surface serves both audiences:

- **Admins / operators** with admin credentials write to `public/` and `platform/` shared buckets and to their own private bucket; read across all three.
- **End-users / QuickApps** with user JWTs write to their own private bucket; read from `public/` and their own private bucket.

The MCP layer doesn't gate on caller role — it forwards credentials to Core, which evaluates `(caller, bucket, verb)` per [`04-security-and-audit.md`](04-security-and-audit.md) §1. The MCP only adds bucket-alias resolution (`private` / `public` / `platform`), response shaping, and agent-friendly affordances.

## 2. Problem & Motivation

### 2.1 Why MCP, not "just use the API"

`dial-cli` and direct REST both work for agents — but poorly. Agents parsing CLI output are fragile (column drift, YAML quirks, interleaved warnings). Agents hitting REST directly have to learn URL conventions, the ETag dance, and error taxonomy from scratch every session, and they can't discover what's available without reading docs.

MCP solves a small set of specific pain points:

| Problem | API today | MCP |
|---|---|---|
| Discoverability | Read `03-api-reference.md` | `tools/list` → structured catalog with descriptions |
| Return shape | Bare REST JSON, parse at your peril | Typed JSON, schema-validated, response-shape control (`format: summary | detailed`) |
| Errors | HTTP status + error body | Structured error with remediation hint |
| Dry-run | `?validate=true` query | First-class `validate_only: true` tool arg |
| Bucket discovery | Manually call `/v1/bucket`, remember the encrypted id | Server-side aliases (`private` / `public` / `platform`) |

### 2.2 Why building blocks, not workflow tools

A familiar trap when wrapping REST APIs in MCP: build one tool per workflow ("promote model", "scaffold app", "register OAuth toolset", "find best model for X"). This produces dozens of tools, each one baking in workflow choices the agent should make, and forces the MCP team to chase every new user scenario.

Industry guidance for 2026 ([Anthropic — Writing Effective Tools for Agents](https://www.anthropic.com/engineering/writing-tools-for-agents); [Phil Schmid — MCP Best Practices](https://www.philschmid.de/mcp-best-practices)) converged on the opposite: a small set of composable building blocks, with the agent doing workflow orchestration. A building-block tool earns its place when it eliminates a problem the agent would otherwise solve repeatedly — not when it represents a user-visible workflow.

The 9 tools in §6 are the smallest set that lets an agent compose any documented DIAL admin or user workflow. They are deliberately mostly thin REST wrappers, with a few intentional lifts (response-shape control on reads, transactional-feel on writes via `validate_only` + ETag, async lifecycle for publication). Discovery, recommendation, scoring, and external-source orchestration are explicitly the agent's job — the MCP exposes the *destination* tools (e.g. `dial_create_resource(type='toolsets', ...)`); the agent finds the third-party MCP, decides which model is best, fetches images of cats, etc.

### 2.3 Why now

Three converging trends:

1. Claude Code, Claude Desktop, and IDE MCP integrations are the de-facto runtime for engineers doing config and ops work.
2. DIAL QuickApps — agents hosted inside DIAL — increasingly need to author DIAL resources on a user's behalf without admin intervention.
3. End-user requests are increasingly conversational ("save these images to my folder", "share this conversation", "draft a reusable prompt from this thread").

### 2.4 Why not re-use the CLI internally via exec

Tempting ("MCP shells out to `dial-cli`") but wrong. Every call pays process startup cost, output parsing cost, and an argv injection surface. The CLI's `--set` ergonomics are also inverted for agents — an agent knows the full object and wants to PUT it, not assemble it field-by-field from flags. MCP → REST direct is simpler, faster, and typed.

---

## 3. Users & Scenarios

### 3.1 Personas

| Persona | Environment | Auth | Typical use |
|---|---|---|---|
| **DIAL operator / DevOps** | Claude Code / Claude Desktop with MCP wired against an env | Admin API key | Config inspection, model promotion, role/limit reasoning |
| **DIAL app developer** | Claude Code in dev | Admin API key (dev env) | Scaffolding apps + schemas + roles together |
| **DIAL QuickApp** | Agent hosted inside DIAL, acting as the signed-in user | User JWT | Authoring user-owned applications, prompts, files |
| **End user (Claude with DIAL MCP)** | Claude Desktop / Web with DIAL MCP wired in | User JWT | Save/share resources, organize files, draft prompts from chat history |
| **CI/CD agent** | GitHub Action or similar | Service-account API key / OIDC client creds | Apply-from-repo flows, drift checks |

### 3.2 Illustrative agent compositions

These are workflows users say in natural language. The MCP has *no* tool for any of them — the agent composes building blocks.

**Admin: "Promote Claude Sonnet 4.6 from uat to prod."**
1. `dial_get_resource(id="models/public/anthropic.claude-sonnet-4-6")` against the uat MCP.
2. Agent transforms upstream endpoints / region lists in-session.
3. `dial_create_resource(...)` (new in target) or `dial_update_resource(...)` (existing) against the prod MCP, with `validate_only: true` first.

**Admin: "Find and register the GitHub MCP toolset with OAuth."**
1. Agent discovers the GitHub MCP via web search (external — not the DIAL MCP's job).
2. `dial_describe_schema(type='toolsets')`.
3. Agent constructs the toolset spec including OAuth config.
4. `dial_create_resource(id='toolsets/public/github-mcp', spec=…)`.

**Admin: "Create an interceptor and make it global."**
1. `dial_describe_schema(type='interceptors')`.
2. `dial_create_resource(id='interceptors/platform/audit-logger', spec=…)`.
3. `dial_get_resource(id='settings/platform/global')`.
4. `dial_update_resource(id='settings/platform/global', spec={…audit-logger appended to globalInterceptors}, if_match=…)`.

**Admin: "Create a key with full-role access."**
1. `dial_describe_schema(type='keys')`.
2. `dial_create_resource(id='keys/platform/<name>', spec={role: 'full', …})`.

**User: "Find pictures of cats and save them to my /pets folder."**
1. Agent finds image URLs via external web/search MCP.
2. Per image: `dial_upload_file(id='files/private/pets/<name>.png', source_url='…')`.

**User: "Save this conversation as a reusable prompt."**
1. Agent composes prompt body from conversation context.
2. `dial_describe_schema(type='prompts')`.
3. `dial_create_resource(id='prompts/private/<name>', spec=…)`.

**User: "Share this conversation."**
1. `dial_publish_resource(id='conversations/private/<id>', target='public/conversations/')`. The `target` is a `public/`-prefixed folder path (trailing slash required), matching the `Publication.targetFolder` field validated by `PublicationService`. The call initiates a publication request in `PENDING` state that an admin must approve before the resource appears publicly — this is the "async lifecycle" the §6.1 tool table refers to.

**Admin: "What rate limits actually apply to user X on model Y?"** *(post-v1)*
- v1: agent fetches role list, model spec, and merges precedence in-context. Slow. Brittle.
- Post-v1 with `dial_get_effective_policy(subject, target)` (§11): one server-side call returns the merged answer with provenance.

---

## 4. Goals & Non-Goals

### Goals

- **G1.** A small set of building-block tools (≤10 in v1) that lets agents compose any documented DIAL admin or user workflow, with no MCP-side state required between calls.
- **G2.** Agent-optimized ergonomics: full-object PUT, `validate_only`, ETag returned on every read, `confirm: true` on destructive ops, structured remediation hints in errors.
- **G3.** Discovery & self-description: `describe_schema(type)`, MCP-resource catalog of supported types — an agent dropped into a fresh install can figure out what it can do.
- **G4.** Single tool surface for both admin and user audiences; authorization delegated entirely to DIAL Core.
- **G5.** Bucket aliases (`private` / `public` / `platform`) — an agent never has to learn the calling user's encrypted bucket id.

### Non-goals

- **N1.** Not a replacement for `dial-cli` (human workflows) or the DIAL Admin Backend (operators who prefer a GUI).
- **N2.** No business logic beyond what the API already enforces — MCP does not re-validate or re-author workflows.
- **N3.** No workflow tools for user scenarios — discovery, recommendation, scoring, and external-source orchestration belong to the agent.
- **N4.** No multi-DIAL-instance federation. Each MCP server instance is pinned to exactly one DIAL Core deployment / environment. Cross-env workflows are agent-side compositions across two MCP connections.
- **N5.** Not a hosting/tenancy layer. MCP delegates all auth and multi-tenancy to DIAL Core.
- **N6.** Not a config generator or template engine — agents author specs in-session, the MCP just persists them.

---

## 5. Functional Requirements

| ID | Requirement |
|---|---|
| **M1** | Building blocks compose. Any workflow documented in `03-api-reference.md` is reachable via a sequence of v1 tool calls, with no MCP-side state required between calls. (REST parity is *not* a release gate — the §11 future-work list is acknowledged scope.) |
| **M2** | All write tools accept `validate_only: true` to dry-run without mutating. |
| **M3** | Tool responses follow the API response schema by default; tool-specific projections (`format: summary \| detailed`, two-array list envelope) are documented per tool. |
| **M4** | Tool descriptions are concise and include 1–2 example invocations. Descriptions are loaded into every agent's context — keep them short; do not embed REST-equivalent details. |
| **M5** | Destructive tools (`dial_delete_resource`) require an explicit `confirm: true` argument. |
| **M6** | Auth is pluggable: admin API key, user JWT pass-through, and service-account OIDC (MCP-2 scope — not v1; see §7.4 and §8). The MCP does not store secrets long-term — it reads from env or per-session config. |
| **M7** | The MCP is stateless across tool calls — each call is an independent HTTP request to Core. The one cached value per session is the result of `GET /v1/bucket` (used to resolve the `private` alias) — refreshed at session start, no cross-call state otherwise. |
| **M8** | Every tool call carries a correlation ID forwarded to DIAL Core (§7.4). |
| **M9** | Schema embedding & evolution: in v1 the MCP module loads schemas at startup from the same in-process `config/` types and JSON Schema generators Core uses — `dial_describe_schema(type)` is a registry lookup, not an HTTP round-trip. Because MCP and Core ship in the same release, new entity types appear in lockstep with no MCP-side change beyond adding them to the `type` enum. The runtime-fetch path (`GET /v1/admin/schema/{type}`) is **intended as the canonical REST contract once the endpoint is implemented** (post-v1 — currently no `RouteTemplate` entry exists; tracked in §11); it would become the fallback used by the post-extraction model (§11). |
| **M10** | Rate limits: MCP respects DIAL Core's rate limits; additionally applies a client-side per-session token bucket to protect against runaway agent loops. Settings (defaults): `mcp.rateLimit.enabled = true`, `mcp.rateLimit.callsPerMinute = 60`, `mcp.rateLimit.burstCapacity = 10`. Buckets are keyed on the MCP session ID. Overflow returns a structured error carrying a `retry_after` hint (seconds) rather than dropping the connection. See §7.1 for the full settings table. |

---

## 6. Tool Surface

Single namespace `dial_*` (no admin/user prefix split — auth-driven scope, enforced by Core). Naming convention: `dial_<verb>_<noun>`, snake_case.

### 6.1 The 9 tools

| # | Tool | REST equivalent | Purpose |
|---|---|---|---|
| 1 | `dial_describe_schema(type)` | `GET /v1/admin/schema/{type}` *(not yet implemented — v1 is an in-process registry lookup; endpoint tracked in §11)* | JSON Schema for an entity type — agents read before writing |
| 2 | `dial_list_resources(path, recursive?, filter?, format?, cursor?)` | `GET /v1/{type}/{bucket}/[<sub>/]` | Paginated listing; two-array envelope for hierarchical types (§6.3) |
| 3 | `dial_get_resource(id, format?)` | `GET /v1/{type}/{bucket}/{name}` | Single read with ETag header |
| 4 | `dial_create_resource(id, spec, validate_only?)` | `POST /v1/{type}/{bucket}/{name}` | Create-only; `409` if exists |
| 5 | `dial_update_resource(id, spec, if_match?, validate_only?)` | `PUT /v1/{type}/{bucket}/{name}` | Update-only; `404` if missing; `412` on stale ETag |
| 6 | `dial_delete_resource(id, confirm, if_match?)` | `DELETE /v1/{type}/{bucket}/{name}` | Requires `confirm: true` |
| 7 | `dial_upload_file(id, content \| source_url, content_type?)` | `PUT /v1/files/{bucket}/{path}` (multipart) | File-shaped writes; binary content or server-fetched URL |
| 8 | `dial_download_file(id, max_bytes?)` | `GET /v1/files/{bucket}/{path}` | Raw bytes / MCP image-content; metadata via `dial_get_resource` |
| 9 | `dial_publish_resource(id, target)` | `POST /v1/ops/publication/create` | Async publication lifecycle |

Cross-cutting affordances on every relevant tool: `validate_only` on writes, `confirm` on delete, ETag header on reads/writes, structured errors with remediation hints, MCP correlation headers forwarded to Core.

> **Note on tool 9.** `dial_publish_resource` targets the existing **Resource Operations API** (`POST /v1/ops/publication/create`), not the Configuration API in [`03-api-reference.md`](03-api-reference.md). The `DialClient` wrapper posts a `Publication` request body containing `resources[]` and `targetFolder` — matching the fields validated by `PublicationService`. The §3.2 illustrative composition for "Share this conversation" exercises this path.

> See [`03-api-reference.md`](03-api-reference.md) §4 for the underlying REST pagination contract that `dial_list_resources` drains.

### 6.2 Bucket aliases

The `id` and `path` arguments accept three reserved tokens in the bucket position, resolved server-side by the MCP layer:

| Alias | Resolves to | Notes |
|---|---|---|
| `private` | The caller's encrypted bucket id | Looked up via `GET /v1/bucket` once per session and cached (M7). |
| `public` | The literal `public` bucket | Passthrough. |
| `platform` | The literal `platform` bucket | Passthrough. Core authz returns `403` for non-admin callers. |

Listings always return canonical (resolved) ids; aliases are accepted on input but never produced on output. Agents that prefer canonical ids exclusively can — aliases are convenience, not policy.

When a type doesn't live in the requested bucket (e.g. `models/private/...` — `models` only live in `public/`), Core returns `404` or `403` and the MCP surfaces a remediation hint pointing at `dial_describe_schema(type)` for the canonical bucket placement.

### 6.3 List response shape

The MCP layer transforms the REST listing response (`entityType` + `bucket` + `items` + `nextCursor` + `hasMore`) into the following agent-facing envelope, adding `path` (derived from `{type}/{bucket}/`), `folders` (extracted from items with hierarchical paths), and `truncated` / `truncation_reason` (MCP-only cap fields not present in the REST response).

`dial_list_resources` returns a two-array envelope that handles flat and hierarchical types uniformly:

```json
{
  "path": "files/<bucket>/photos/",
  "items": [
    { "kind": "resource", "id": "files/<bucket>/photos/cover.png", "name": "cover.png", "etag": "…", "…": "summary fields per type" }
  ],
  "folders": [
    { "kind": "folder", "path": "files/<bucket>/photos/cats/", "name": "cats" }
  ],
  "nextCursor": null,
  "hasMore": false,
  "truncated": false,
  "truncation_reason": null
}
```

For flat types (`models`, `roles`, `keys`, `interceptors`, `routes`, `schemas`, `settings`), `folders` is always empty. Tool arguments for the `schemas` entity type use the URL-segment form (`schemas`), not the blob group name (`app_type_schemas`); the MCP always uses the URL-segment form in `path` and `id` arguments, consistent with the canonical id format `schemas/{bucket}/{name}`. For hierarchical types (`files`, `prompts`, `conversations`), `folders` carries the immediate sub-prefixes; the agent navigates by re-listing with a deeper `path`. `recursive: true` flattens the tree under `path` (subject to the truncation cap).

`truncated: true` with `truncation_reason: "mcp_cap"` is distinct from `hasMore: true`:

- `truncated` means **the MCP refused to keep paging** — narrow the query.
- `hasMore` means **more pages exist in Core** — re-invoke with the returned `nextCursor`.

Combined-state rule: when `truncated: true` and `hasMore: true` simultaneously, the agent SHOULD narrow its query (reduce scope via `path` or `filter`) rather than re-invoking with `nextCursor` — paging further would hit the same cap. When `truncated: false` and `hasMore: true`, the agent MAY re-invoke with the returned `nextCursor` to fetch the next page.

Default page size matches Core's default (100) with a hard cap (500) per `03-api-reference.md` §4. The MCP applies a per-call ceiling of 5 pages = 2,500 items for `recursive: true` on potentially-unbounded types (`files`, `prompts`, `conversations`); reaching the cap triggers `truncated: true`.

### 6.4 Format projection

`format: summary | detailed`:

- **`dial_list_resources`** defaults to `summary`. Each item carries a small set of agent-relevant fields per type (table below).
- **`dial_get_resource`** defaults to `detailed` (full entity body, matching the API response).

| Type | `summary` fields (in addition to `id`, `name`, `etag`, `kind`) |
|---|---|
| `models` | `displayName`, `displayVersion`, `status`, `description` |
| `applications` | `displayName`, `status`, `description` |
| `toolsets` | `displayName`, `status`, `description` |
| `interceptors` | `displayName`, `status`, `description` |
| `roles` | `status`, `description` |
| `keys` | `role`, `status`, `description` |
| `routes` | `paths`, `methods`, `status`, `description` |
| `schemas` | `displayName`, `status`, `description` [^schemas-url-segment] |
| `settings` | (singleton — not listed) |
| `files` | `contentType`, `size`, `description` |
| `prompts` | `displayName`, `description` |
| `conversations` | `displayName`, `description` |

For the `settings` singleton type, `dial_list_resources(path='settings/platform/')` returns a structured error with a remediation hint directing the agent to use `dial_get_resource(id='settings/platform/global')` directly, matching Core's `405` response.

[^schemas-url-segment]: URL-segment form `schemas`; blob group name is `app_type_schemas` — see §6.3.

> Per MCP-OQ-2 (resolved §10): ship the table as-is. Revisit only if eval data shows a specific projection underserves agent decision-making.

Projections are applied server-side in the MCP layer, not in Core. Adding a new field to a type does not require an MCP release; the field just doesn't show in `summary` until the projection table is updated.

### 6.5 Create vs update split

`dial_create_resource` and `dial_update_resource` are intentionally non-overlapping (mirroring the REST split in [`03-api-reference.md`](03-api-reference.md) §1):

- `create` → `POST`, returns `409 Conflict` if the entity exists. An LLM that hallucinates a slightly-wrong "this is new" gets a clean error and self-corrects.
- `update` → `PUT`, returns `404 Not Found` if the entity is missing. Typo guard — no silent stub creation.

Bulk upsert (`apply_manifests`) is intentionally *not* in v1 (see §11). When it lands, it remains the only path where create-or-update is implicit.

### 6.6 Example tool definition

```json
{
  "name": "dial_update_resource",
  "description": "Update an existing DIAL resource (full-entity replace). Returns the persisted entity with its new ETag header. Returns a structured 404 error if the entity does not exist — call dial_create_resource instead. Set validate_only=true to dry-run. Authorization is enforced by DIAL Core based on the caller's identity.",
  "inputSchema": {
    "description": "Full-entity replace for an existing DIAL resource. Use dial_describe_schema(type) to get the entity's JSON schema before constructing the spec.",
    "type": "object",
    "required": ["id", "spec"],
    "properties": {
      "id":   { "type": "string", "description": "Canonical id `{type}/{bucket}/{name}`. Bucket may be the literal value or one of the aliases `private` / `public` / `platform`." },
      "spec": { "type": "object", "additionalProperties": true, "description": "Entity body matching the type's JSON schema (see dial_describe_schema)." },
      "if_match":      { "type": "string", "description": "ETag for optimistic concurrency. Optional. Returns 412 Precondition Failed if the stored ETag has moved." },
      "validate_only": { "type": "boolean", "default": false }
    }
  }
}
```

The peer `dial_create_resource` has the same shape minus `if_match`, returns `409 Conflict` if the entity already exists.

### 6.7 `dial_upload_file` input schema

`dial_upload_file` accepts exactly one of `content` (base64-encoded binary string) or `source_url` (URL string fetched server-side by the MCP); the two are mutually exclusive via `oneOf`. `max_bytes` mirrors `dial_download_file`'s ceiling.

```json
{
  "name": "dial_upload_file",
  "description": "Upload a file to a DIAL bucket. Provide exactly one of `content` (base64-encoded binary) or `source_url` (URL fetched by the MCP server). Returns the persisted file metadata with its ETag.",
  "inputSchema": {
    "type": "object",
    "required": ["id"],
    "properties": {
      "id":           { "type": "string", "description": "Canonical id `files/{bucket}/{path}`. Bucket may be the literal value or one of the aliases `private` / `public` / `platform`." },
      "content":      { "type": "string", "contentEncoding": "base64", "description": "Base64-encoded binary file content." },
      "source_url":   { "type": "string", "format": "uri", "description": "URL fetched server-side by the MCP. See SSRF note below." },
      "content_type": { "type": "string", "description": "MIME type. Inferred from `source_url` response or filename if omitted." },
      "max_bytes":    { "type": "integer", "minimum": 1, "description": "Upper bound on accepted payload size; matches `dial_download_file.max_bytes`." }
    },
    "oneOf": [
      { "required": ["content"],    "not": { "required": ["source_url"] } },
      { "required": ["source_url"], "not": { "required": ["content"] } }
    ]
  }
}
```

The `oneOf` constraint requires exactly one of `content` or `source_url`; calls providing neither, or both, are rejected.

> **SSRF note.** `source_url` is fetched server-side by the MCP, so unrestricted use can reach internal services (Redis, blob storage APIs, sidecars, cloud metadata IPs). Spec requirement: ship with `mcp.upload.sourceUrl.enabled = false` (feature opt-in), `mcp.upload.sourceUrl.allowedUrlPrefixes = []` (empty list = deny all), and `mcp.upload.sourceUrl.blockedCidrs` defaulting to RFC 1918 + link-local + `169.254.169.254`. See §7.1 for the full settings table.

### 6.8 `dial_download_file` input schema

`dial_download_file` returns raw bytes (or, for image content types, an MCP image-content block). Metadata is fetched separately via `dial_get_resource`.

```json
{
  "name": "dial_download_file",
  "description": "Download a file from a DIAL bucket. Returns raw bytes (or an MCP image-content block for image MIME types). Use dial_get_resource for metadata.",
  "inputSchema": {
    "type": "object",
    "required": ["id"],
    "properties": {
      "id":        { "type": "string", "description": "Canonical id `files/{bucket}/{path}`. Bucket may be the literal value or one of the aliases `private` / `public` / `platform`." },
      "max_bytes": { "type": "integer", "minimum": 1, "default": 10485760, "description": "Upper bound on response size (bytes). Default 10 MB. Hard ceiling — requests for files larger than this fail with a structured error pointing at chunked retrieval (post-v1)." },
      "format":    { "type": "string", "enum": ["bytes", "image"], "description": "Optional response shape hint; `image` packages the response as an MCP image-content block when the file's MIME type is image/*." }
    }
  }
}
```

---

## 7. Architecture

### 7.1 Module placement

v1 ships as a new Gradle module **`mcp/`** sibling to the existing `config/`, `storage/`, `credentials/`, `server/`, and `cli/` modules in the `ai-dial-core` repository. The module is deployed as a Vert.x verticle by `AiDial.java` at startup, sharing the Core JVM but isolated through its own thread pool and per-session concurrency caps (M10). The module is toggleable via `mcp.enabled = true|false` so operators who don't want MCP can disable it without rebuilding.

`mcp.enabled` lives in the static settings file (`aidial.settings.json`) under the `mcp` namespace, type boolean, default `true`. Example: `{ "mcp": { "enabled": false } }`. As a static setting, a change requires a pod restart — consistent with `mcp.enabled = false` being an operator escape hatch during incidents, not a runtime toggle.

The full `mcp` settings namespace:

| Key | Type | Default | Purpose |
|---|---|---|---|
| `mcp.enabled` | boolean | `true` | Master switch. `false` = the verticle is not deployed, the `/mcp` mount point returns `404`. |
| `mcp.rateLimit.enabled` | boolean | `true` | Toggle for the per-session token bucket (M10). Disable only in tests. |
| `mcp.rateLimit.callsPerMinute` | integer | `60` | Steady-state refill rate per MCP session. |
| `mcp.rateLimit.burstCapacity` | integer | `10` | Token-bucket capacity per MCP session; absorbs short bursts above the steady rate. |
| `mcp.concurrency.maxConcurrentCallsPerSession` | integer | `5` | Hard cap on simultaneous in-flight tool calls per MCP session; calls beyond the cap are rejected with a structured tool error. Complements the token-bucket rate limit by bounding parallelism, not just throughput. |
| `mcp.upload.sourceUrl.enabled` | boolean | `false` | Master switch for `dial_upload_file`'s `source_url` input. **Default-deny**: feature is opt-in. |
| `mcp.upload.sourceUrl.allowedUrlPrefixes` | list of strings | `[]` (deny all) | Allow-list of URL prefixes (`https://example.com/assets/`). Empty list = no `source_url` accepted even when `enabled = true`. |
| `mcp.upload.sourceUrl.blockedCidrs` | list of strings | RFC 1918 (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) + link-local (`169.254.0.0/16`, `fe80::/10`) + loopback (`127.0.0.0/8`, `::1/128`) + cloud-metadata (`169.254.169.254`) | After DNS resolution, the resolved IP is checked; matches are rejected before any HTTP request. |

Rate-limit overflow: the MCP returns a structured tool error with a `retry_after` field (seconds until the bucket has at least one token) rather than dropping the connection. Buckets are keyed on the MCP session id, so two sessions from the same agent runtime get independent budgets.

SSRF defaults are deliberately strict — operators must explicitly set `mcp.upload.sourceUrl.enabled = true` *and* populate `allowedUrlPrefixes` before agents can call `dial_upload_file(source_url=…)`. The `blockedCidrs` check runs after DNS resolution and rejects all RFC 1918 ranges, link-local, loopback, and the cloud-metadata IP `169.254.169.254` by default.

This shape was chosen for v1 over a separate-repo Python sidecar because:

- **Delivery speed.** One repo, one CI, one Helm artifact, one release. Halves time-to-first-deploy.
- **Type sharing for free.** The MCP module imports `Config`, `Application`, `Model`, etc. directly from `config/`, with the same Jackson serialization, JSON Schema generation, and field-encryption semantics Core uses for its REST controllers. No codegen, no drift.
- **Faster feedback.** Integration tests against the actual Core build in the same PR — no version-pinning dance.
- **L2 readiness.** If/when bidirectional MCP features (HITL elicitation, sampling, MCP-proxy in the chat-completion path) become a priority, the same module evolves; no re-platforming.

The deliberate cost is **release-cadence coupling**: every MCP iteration is a Core release. The mitigation is the architectural discipline below — the module is built to be extractable to a standalone service later (see §11) so coupling is a v1 cost, not a permanent commitment.

#### Extraction discipline (preserved through v1)

To keep future extraction a config-and-deploy change rather than a refactor, the `mcp/` module follows these rules:

1. **REST-only access to Core.** The module talks to Core *only* through Core's public REST API (loopback HTTP via `localhost`), even when running in-process. No direct injection of `ResourceService`, `PublicationService`, `ApplicationService`, etc. A thin `DialClient` HTTP wrapper is the single swap point if extracted.
2. **Minimal cross-module dependencies.** The Gradle module depends only on `config/` (for entity types) and a small set of `credentials/` constants (for auth-header conventions). It does **not** depend on `server/` internals. CI enforces this with a dependency-graph check.
3. **Config-driven Core URL.** `MCP_DIAL_TARGET_URL` env var, defaulting to `http://localhost:${server.port}`. Extraction = change the env var, not the code.
4. **Auth tokens forwarded verbatim.** Even when in-process, MCP passes the caller's JWT or API key to Core's REST surface; never bypasses authn/authz on the basis of "we're in the same JVM." Same trust boundary either way.
5. **Own verticle, own thread pool.** Operational isolation from chat hot path. Extraction = remove the verticle deployment from `AiDial.java`.
6. **Tests live in the module.** The module is testable standalone, against a staged Core via testcontainers or HTTP mocks.

Following this, extraction reduces to: copy the module to a new repo, replace the in-tree `config/` Gradle dependency with a published artifact (or inline the small slice used), drop the verticle deployment from `AiDial.java`, build a Docker image, point `MCP_DIAL_TARGET_URL` at the in-cluster Core service URL. Estimate: a couple of days of work — no controller refactoring, no auth re-platforming.

### 7.2 Stack

- **Language:** Java 21 (matches Core).
- **MCP framework:** the Java MCP SDK (`io.modelcontextprotocol.sdk:mcp`).
- **HTTP client:** Vert.x `WebClient` for loopback calls to Core; supports `localhost`-fast-path when both endpoints share an event loop.
- **Schema source:** in-process registry lookup against the `config/` module at startup (M9); `GET /v1/admin/schema/{type}` is intended as the canonical REST fallback once that endpoint is implemented (post-v1 — currently no `RouteTemplate` entry exists; tracked in §11). No build-time codegen.
- **Transport:** **Streamable HTTP (primary; HTTP/SSE retained for backward-compat)** mounted on Core's existing HTTP port at a dedicated path (e.g. `/mcp`) — no new port to open in ingress / TLS / firewall config (resolves MCP-OQ-7). The MCP specification update of 2025-03-26 deprecated HTTP+SSE in favour of Streamable HTTP; the Java MCP SDK supports both, so MCP-1 ships Streamable HTTP as the primary transport with SSE preserved as a backward-compatibility adapter for older clients. Stdio is not shipped in v1; laptop devs running `./gradlew :server:run` reach MCP at `http://localhost:8080/mcp` (resolves MCP-OQ-8).

**Threading bridge (Vert.x ↔ Reactor).** The Java MCP SDK dispatches tool handlers on Reactor scheduler threads, while Vert.x `WebClient` requires an active Vert.x context. To avoid blocking event-loop threads or hitting context-mismatch bugs, MCP-1 must commit to one of two bridge patterns and document the choice in `mcp/CONTRIBUTING.md`: (a) **captured-context dispatch** — the verticle captures its Vert.x context at startup and tool handlers re-enter it via `context.runOnContext(...)` before issuing `WebClient` calls, awaiting the resulting `Future` back into the Reactor handler; or (b) **worker-pool dispatch** — tool handlers run on a Vert.x `WorkerExecutor` pool and await `WebClient` futures on that pool. Either is acceptable; the spec does not prescribe which, but the choice is locked during MCP-1 and applied uniformly across all 9 tool handlers. **Recommendation for v1:** option (a) (captured-context dispatch via `context.runOnContext()`) — `WebClient` calls return Vert.x `Future` objects that can be awaited without blocking, so option (b) is preferred only if handlers contain non-Vert.x blocking I/O. Caveat: ensure the captured Vert.x context is re-entered before any `WebClient` invocation; never call `WebClient` directly from a Reactor scheduler thread.

Rationale for Java over Python (the v0.2 lean):

- The in-repo embedded model makes Java the natural choice — Python embedded in a JVM is overkill and a Python sidecar isn't really "embedded."
- Real code reuse with `config/` POJOs, Jackson serialization, JSON Schema generation, and `CredentialEncryptionService` — same pattern the `cli/` module uses.
- The Java MCP SDK is real and stable in 2026; less reference material than TS/Python ecosystems but functional and supported.
- L2 (elicitation, sampling, MCP-proxy in the gateway path) is Java-resident anyway; building L1 in Java warms up that expertise rather than re-platforming on the way to L2.

The Python ecosystem alignment argument (DIAL apps and interceptors are largely Python) is preserved for **post-extraction** — if/when extraction happens and the operating constraints shift, a Python rewrite remains an option. v1 prioritizes delivery speed and type-sharing.

### 7.3 Deployment

| Shape | Audience |
|---|---|
| Verticle inside DIAL Core, mounted on Core's HTTP port at `/mcp` (default — no extra deploy step) | Hosted environments — operators, QuickApps, CI agents reach MCP at the Core endpoint |
| Local Core (`./gradlew :server:run`) with MCP enabled | Laptop developers — Claude Desktop / Claude Code points at `http://localhost:8080/mcp` |

Stdio transport is not shipped in v1 (resolves MCP-OQ-8). Laptop demand can be reassessed once usage data exists; if real, a small launcher JAR (or GraalVM native-image binary, ~30ms cold-start) can ship from the same Gradle module without architectural change.

When/if the module is extracted to a standalone service (§11), the deployment table gains a Helm chart entry / Docker image and the in-Core verticle is removed; existing audiences keep their entrypoints (URL change only).

### 7.4 Auth

| Caller | Credential | How MCP handles it |
|---|---|---|
| Operator | Admin API key in env (`AIDIAL_MCP_API_KEY` or env-scoped variant) | Forward as `API-KEY: <value>` request header (DIAL Core's API key header — `Proxy.HEADER_API_KEY`) |
| CI agent *(MCP-2 — not in v1)* | Service-account OIDC client credentials | Exchange for short-lived token, forward — exchange mechanism to be specified when MCP-2 is scoped (no OQ assigned yet; tracked as MCP-2 scope item). v1 CI agents use a service-account API key (admin row above). |
| QuickApp | User JWT | Forward verbatim |
| End user via Claude Desktop | User JWT (provided by the agent runtime) | Forward verbatim |

User JWTs (QuickApp and end-user rows) are forwarded as `Authorization: Bearer <token>`.

All authorization decisions are made by Core's `ConfigAuthorizationService`. The MCP adds no authorization logic of its own.

### 7.5 Correlation

Every MCP tool call adds headers forwarded to DIAL Core:

```
X-DIAL-Client: dial-mcp/<version>
X-DIAL-Client-Session: <uuid>
X-DIAL-Client-Agent: claude-code | claude-desktop | quickapp | ci | other
```

Before Phase 7 lands these are echoed to Core's application logs (best-effort, not query-friendly). Once Phase 7 ships these headers are forwarded into audit-event metadata as supplemental `client_id` and `client_session_id` correlation fields — separate from `requestedBy` (which records the authenticated actor identity per [`04-security-and-audit.md`](04-security-and-audit.md) §3.3).

---

## 8. Phased Rollout

| Phase | Scope | Core prereq |
|---|---|---|
| **MCP-0** | Spec + design review | None — this doc |
| **MCP-1** | All 9 building-block tools (§6.1), Streamable HTTP transport (primary; HTTP/SSE retained for backward-compat) from the embedded verticle on Core's existing port at `/mcp`, admin API key + user JWT auth. One MCP server instance per environment. | Core Phase 1 (read-only API) for the read tools; Core Phase 2/3 (writes) for the write tools — ship in two increments alongside Core |
| **MCP-2** | Service-account OIDC for CI agents | None — additive auth |
| **MCP-future** | Tools listed in §11 — each scoped to its driving need and Core dependency | Per item |

Read-only MCP-1 ships as soon as Core Phase 1 deploys to any environment. Write tools follow Core's Phase 2/3 entity-by-entity rollout.

**MCP-1 kickoff checklist** (must clear before tool implementation begins):

- Add `include 'mcp'` to `settings.gradle` and create `mcp/build.gradle` with `implementation project(':config')` + the `io.modelcontextprotocol.sdk:mcp` dependency, plus the `io.freefair.lombok` plugin wiring used by sibling modules.
- Wire `/mcp` into the existing request path: add a path-prefix branch at the top of `Proxy.handleRequest()` that short-circuits requests beginning with `/mcp` to a new `McpRequestHandler` (delegating to the verticle), preserving the current `Proxy`-as-sole-handler model rather than refactoring `AiDial.start()` to a `Router`. Update `AiDial.start()` to deploy the MCP verticle when `mcp.enabled = true`.
- Select and document the Vert.x ↔ Reactor threading bridge (§7.2) in `mcp/CONTRIBUTING.md`.
- Wire the `mcp.*` settings (§7.1) into `aidial.settings.json` defaults and the static-config loader.

---

## 9. Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Agent loops / runaway tool calls | DoS on Core's admin surface; chat hot path degraded | Client-side token bucket (M10), Core rate limits, MCP-verticle dedicated thread pool + per-session concurrency cap (`mcp.concurrency.maxConcurrentCallsPerSession`, see §7.1), kill-switch via `mcp.enabled = false` |
| Agent-driven mass deletion | Data loss | `confirm: true` on `dial_delete_resource`; audit-based rollback (post-Phase-7, see §11 and [`04-security-and-audit.md`](04-security-and-audit.md) §3.3) |
| SSE deprecation — MCP-1 ships on a deprecated transport | Tooling drift; clients migrating off SSE-only servers stop interoperating | Ship Streamable HTTP as primary (§7.2); keep SSE adapter as a backward-compat path. Java MCP SDK supports both transports natively |
| Auth misconfiguration (over-scoped token) | Agent acts with more privilege than intended | Recommend env-specific keys with admin role only on lower envs; user-JWT passthrough has no such risk |
| Schema drift between MCP-declared inputs and Core | Agents write invalid specs | In-repo type sharing eliminates the v0.2 drift class; runtime fetch of dynamic schemas (M9) covers the rest; integration tests against the same Core build |
| MCP protocol churn | Breaking changes from Anthropic | Pin SDK major version; document protocol version in tool responses |
| Discipline erosion — MCP module starts calling Core internals directly | Extraction becomes expensive; module fuses with `server/` | Dependency-graph CI check (§7.1); architectural review on every MCP→Core call |
| Release-cadence coupling — MCP iteration tied to Core releases | Slow turnaround on tool-description / projection tweaks | Feature-flag MCP behaviors; treat MCP-internal changes as patch-level Core releases; if friction becomes blocking, pull the extraction trigger (§11) |

---

## 10. Open Questions

All eight MCP-OQs are resolved as of v0.4. Original questions kept (struck through) so the rationale remains traceable; resolutions inline.

| # | Question | Resolution |
|---|---|---|
| ~~MCP-OQ-1~~ | ~~**Module name**: `mcp/` (terse, matches `config/`/`storage/` style) or `dial-mcp/` (explicit prefix)?~~ | **Resolved:** `mcp/`. Matches the existing sibling-module naming convention (`config/`, `storage/`, `credentials/`, `server/`). |
| ~~MCP-OQ-2~~ | ~~**Per-type `summary` projections** (§6.4 table): are the listed fields the right ones, or revise based on first eval pass?~~ | **Resolved:** ship the §6.4 table as-is. Revisit only if eval data shows a specific projection underserves agent decision-making. |
| ~~MCP-OQ-3~~ | ~~**Multi-env in a single MCP session**: one tool call against `env=prod`, next against `env=uat` — safe, or pin each MCP server instance to one env?~~ | **Resolved:** **pin each MCP server instance to exactly one environment.** Cross-env workflows (promote, diff) are agent-side compositions across two MCP server connections — same pattern Claude Code already uses for any multi-target setup. |
| ~~MCP-OQ-4~~ | ~~**`describe_schema` caching**: M7 says stateless aside from `/v1/bucket`. Add a session-level TTL cache for schemas (~60s) to avoid round-trips on common writes?~~ | **Resolved:** no caching needed in v1. With the embedded-module model the MCP loads schemas at startup directly from the in-process `config/` Gradle module and JSON Schema generators Core already uses (M9). `dial_describe_schema(type)` is a registry lookup, not an HTTP round-trip — there is no cost to cache. The cache discussion only resurfaces if/when the module is extracted (§11), at which point a session-level TTL cache becomes part of the extraction-trigger checklist. |
| ~~MCP-OQ-5~~ | ~~**Confirmation UX for destructive ops**: is `confirm: true` enough, or should the server require a two-step flow (`prepare_delete` → `commit_delete`)?~~ | **Resolved:** `confirm: true` is enough for v1. A two-step flow is a candidate for the L2 capability set (§11) where elicitation lands natively. |
| ~~MCP-OQ-6~~ | ~~**MCP-internal observability**: expose tool latency / error rate via `/metrics`? Or rely on Core logs + agent traces?~~ | **Resolved:** rely on Core's existing application logs (§7.5) and agent-side traces. No `/metrics` exposed by the MCP module in v1; revisit if operator feedback shows blind spots. |
| ~~MCP-OQ-7~~ | ~~**Endpoint placement**: mount MCP on a dedicated path on Core's existing port (`/mcp/*`), or a separate port? Affects ingress / TLS / rate-limit configuration.~~ | **Resolved:** mount on Core's existing port at `/mcp`, transport = Streamable HTTP (primary; HTTP/SSE retained for backward-compat per the 2025-03-26 MCP spec deprecation). No new ingress / TLS / firewall configuration required; existing rate-limit and auth infrastructure applies uniformly. |
| ~~MCP-OQ-8~~ | ~~**Stdio laptop story**: ship the launcher JAR in v1, or punt until laptop demand is real (HTTP-only first cut, point Claude Desktop at local Core)?~~ | **Resolved:** punt. v1 is HTTP-only (Streamable HTTP primary, HTTP/SSE backward-compat); laptop devs point Claude Desktop / Claude Code at a local or remote Core's `/mcp` endpoint. Stdio launcher reassessed once real demand exists. |

---

## 11. Future Work (parked / out of scope for v1)

Items deliberately excluded from MCP-1, with a short note on what would unlock them:

| Item | Driving need | Unlocks when |
|---|---|---|
| `GET /v1/admin/schema/{type}` Core endpoint | Canonical REST surface for entity JSON schemas — currently absent (`RouteTemplate` has no entry, `ControllerSelector` has no handler). v1 MCP uses an in-process registry lookup instead (M9); this endpoint is the post-extraction fallback path. | Phase 3 of Core, or earlier if a non-MCP consumer (third-party agent / OpenAPI export) needs the surface |
| `dial_get_effective_policy(subject, target)` | Aggregate role / limit / key precedence into one server-side answer for "what limits actually apply to user X on model Y?" | Core exposes the merge as an endpoint |
| `dial_apply_manifests(...)` | Multi-resource transactional writes (e.g. interceptor + global-settings update in one call) | Real demand from operators / CI agents |
| `dial_diff_environments(source_env, target_env, ...)` | Cross-env drift inspection within a single tool call | Real demand from operators. With MCP-OQ-3 resolved as "one MCP per env," cross-env workflows are already viable as agent-side compositions across two MCP connections; a server-side diff tool only earns its place if that composition proves too cumbersome. |
| `dial_export(env, type?)` | Full-config snapshot | Real demand from operators / CI. Same composition-vs-tool tradeoff as `diff_environments`. |
| `dial_search_resources(query, types?)` | Cross-type / cross-bucket name search | Agents thrash on `list + filter` enough to justify a server-side index |
| Audit tools (`dial_query_audit`, `dial_get_entity_history`, `dial_snapshot_at_time`, `dial_rollback_entity`) | Root-cause + rollback workflows | Core Phase 7 audit subsystem ships |
| `dial_deploy_codeapp(name, code, runtime)` | Codeapp authoring lifecycle in one call | Codeapp service has a clean async readiness signal |
| **Extract `mcp/` module to a standalone service** | Independent release cadence; OSS contribution friction reduction; stack flexibility (e.g. Python rewrite for ecosystem alignment) | Release-cadence coupling becomes the bottleneck on MCP iteration; OR Core-team capacity shifts and an external owner takes over; OR Python ecosystem alignment becomes more valuable than in-repo type sharing. The §7.1 discipline keeps this a build-and-deploy change rather than a refactor. |
| **L2 — Core-embedded MCP capabilities** | HITL elicitation driven by Core policy; sampling rooted in Core data; live resource subscriptions backed by Core events; MCP-proxy in the chat-completion gateway path | HITL becomes a real product requirement, OR DIAL aspires to be an MCP control plane for upstream tools. Separate spec when triggered. |
| `dial-api` Python package adoption | Typed REST client for a post-extraction Python rewrite | Extraction triggered AND target stack is Python |
| OpenAPI spec from Core as a release artifact | Stack-agnostic third-party clients (Admin Backend reskin, third-party agent integrations) | Separate Core-side task |
| DIAL-app-with-MCP-endpoint deployment pattern | Eat-own-dogfood: MCP server hosted as a DIAL application, discoverable in the app catalog | DIAL codeapp infrastructure stabilizes; MCP-as-DIAL-app pattern proven in QA |
| Multi-tenancy awareness | Tenant-scoped tool calls | Core MT work (OQ-22 / OQ-26) lands |
| Agent prompt library / starter-prompt catalog | Lower time-to-first-tool-call | Post-MCP-1 once we have real usage data |
| MCP server marketplace / registry integration | Public Anthropic MCP registry vs private EPAM tap | Productization decision |
| DIAL Chat-embedded agent using this MCP | Let end users talk to their workspace in natural language | Product-side decision |
| Terraform / Pulumi providers | Declarative IaC audience | Different surface for a different audience; not blocked by MCP |

---

## 12. References

- [`03-api-reference.md`](03-api-reference.md) — the REST surface this wraps
- [`04-security-and-audit.md`](04-security-and-audit.md) — auth and audit integration
- [`05-cli-design.md`](05-cli-design.md) — the peer human-facing client
- [`07-migration-and-rollout.md`](07-migration-and-rollout.md) — phase dependencies
- [Model Context Protocol spec](https://modelcontextprotocol.io)
- [MCP transports — Streamable HTTP](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports) (the 2025-03-26 spec update that deprecated HTTP+SSE in favour of Streamable HTTP)
- [Writing Effective Tools for Agents — Anthropic](https://www.anthropic.com/engineering/writing-tools-for-agents)
- [MCP Best Practices — Phil Schmid](https://www.philschmid.de/mcp-best-practices)
- Anthropic MCP SDKs — `io.modelcontextprotocol.sdk:mcp` (Java — chosen for v1 embedded), `mcp` (Python — alternative for post-extraction), `@modelcontextprotocol/sdk` (TypeScript — alternative)

---

## Next

- All eight MCP-OQs resolved (§10). Spec is implementation-ready.
- Kick off MCP-1: create the `mcp/` Gradle module in `ai-dial-core`, write the §7.1 extraction discipline into `mcp/CONTRIBUTING.md`, target a 2-week first cut of read-only tools against the local Core build.
- Once Core Phase 1 deploys to a staging env, smoke-test the read tools end-to-end against the §3.2 illustrative compositions before scoping the write tools alongside Core Phase 2/3.
