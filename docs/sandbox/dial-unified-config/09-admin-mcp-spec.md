# 09 — DIAL Admin MCP Server (Spec v0.1 — raw)

> **Status:** Draft. Raw first pass — goals, tool surface, and phased rollout locked enough for review. Auth and deployment model open.
> **Audience:** Product, DIAL Core dev team, MCP tooling team, DevOps leads considering agent-native workflows.
> **Prerequisites:** [`03-api-reference.md`](03-api-reference.md) (the API this wraps), [`04-security-and-audit.md`](04-security-and-audit.md) (auth model).

This document specifies a Model Context Protocol (MCP) server that exposes DIAL's Configuration API to AI agents. It is a thin wrapper over the admin Configuration API (admin scope: `public/` and `platform/` buckets only — `/v1/{type}/{bucket}/{name}` for per-entity CRUD; `/v1/admin/*` for cross-entity ops — see [`03-api-reference.md`](03-api-reference.md) §1) and, in Phase 4, a separate user-scope surface (`dial_user_*` tools) for agents acting on behalf of users in their private buckets, adding typed tool signatures, discovery, and agent-friendly affordances. It is *not* a replacement for `dial-cli` or the Admin UI — those remain the canonical human interfaces. MCP is the canonical *agent* interface.

---

## 1. Summary

Build `dial-admin-mcp`: an MCP server that wraps the DIAL Configuration API as typed tools, so that AI agents running in Claude Code, Claude desktop, DIAL QuickApps, IDEs, or CI can read, validate, and mutate DIAL configuration without parsing CLI stdout or hand-rolling HTTP. Ships in phases — read-only admin scope first, then writes, then user-scope (private bucket) for QuickApp-hosted agents creating apps on behalf of users; audit tools follow once DIAL Core's audit subsystem lands in Phase 7.

## 2. Problem & Motivation

### 2.1 Why MCP, not "just use the CLI"

`dial-cli` and the REST API both work for agents — but poorly. Agents parsing CLI output are fragile: column drift, YAML quirks, interleaved warnings. Agents hitting REST directly have to learn the URL conventions, ETag dance, and error taxonomy from scratch every session, and they can't discover what's available without reading docs.

MCP solves three specific pain points:

| Problem | CLI today | MCP |
|---|---|---|
| Discoverability | `dial-cli --help` → human-only | `tools/list` → structured catalog |
| Return shape | stdout string, parse at your peril | Typed JSON result, schema-validated |
| Chaining | Agent must shell out per call, glue strings | Sequential tool calls with typed inputs/outputs |
| Errors | Exit codes + stderr text | Structured error with remediation hint |
| Dry-run | `--dry-run` flag on CLI | First-class `validate_only: true` tool arg |

### 2.2 Why now

Three converging trends:

1. **Claude Code, Claude desktop, and IDE integrations** are the de-facto runtime for engineers doing ops/config work. MCP is already how they talk to external systems (Slack, GitHub, Jira, filesystems).
2. **DIAL QuickApps** — agents hosted inside DIAL — need to create/configure DIAL entities on the user's behalf. Today that requires admin intervention. With user-scope MCP (Phase 4), a QuickApp can scaffold a user's own app in their private bucket.
3. **Operator workflows are increasingly agent-driven.** "Why isn't this model loading in uat?", "Promote Claude 4.6 to prod with the standard Bedrock template", "Audit every change to rate-limit roles in the last 7 days" — these read as prompts, not commands.

### 2.3 Why not re-use the CLI internally via exec

Tempting ("MCP server shells out to `dial-cli`") but wrong. Every call pays process startup cost, output parsing cost, and an argv injection surface. Worse, the CLI's `--set` ergonomics are inverted for agents — an agent knows the full object and wants to PUT it, not assemble it field-by-field from flags. MCP → REST API direct is simpler, faster, and typed.

---

## 3. Users & Scenarios

### 3.1 Personas

| Persona | Environment | Scope | Typical agent |
|---|---|---|---|
| **DIAL Env Operator** | Claude Code / Claude desktop with MCP configured against uat/prod | Admin | Diagnosing config drift, promoting models, auditing changes |
| **DIAL App Developer** | Claude Code, local dev loop | Admin (dev env) | Scaffolding an admin-managed app with its dependencies (schema, roles, interceptor) |
| **DIAL QuickApp** | Agent hosted inside DIAL, acting on behalf of the signed-in user | User (private bucket) | Creating/modifying the user's own applications and toolsets |
| **CI/CD Agent** | GitHub Action or equivalent running on PR | Admin (scoped service account) | Apply-from-repo with validation, diff commentary posted back to PR |

### 3.2 Top scenarios

**S1. "What's in prod right now?"** — Operator asks Claude "list the models currently loaded in prod and flag any with >1 week since last update." Claude calls `list_models(env=prod)` + `get_entity_history` per model. Output: structured table with provenance and last-modified.

**S2. "Promote Claude Sonnet 4.6 from uat to prod."** — Operator asks Claude. Claude calls `get_model(env=uat, name=...)`, `validate_manifests(env=prod, manifests=[…])` with env-translated upstream endpoints, then `put_model(env=prod, …)` after operator confirmation. *(Once DIAL Core Phase 7 audit lands, the resulting audit event will carry `requestedBy=operator@company.com`, `batch_id`.)*

**S3. "Scaffold a new admin app with a custom JSON schema + a rate-limit role."** — Developer describes the app. Claude calls `put_schema`, `put_application` (referencing the schema id), `put_role` (with limits keyed by the canonical model IDs), `precheck: true`, and reports success. Three entities, one conversation.

**S4. "User: 'Make me an email-summarizer agent.'"** (Phase 4, QuickApp) — QuickApp agent, authenticated as the user, calls `put_user_application` in the user's private bucket, sets up toolsets, and surfaces the resulting URL.

**S5. "Why did response latency jump at 14:30?"** *(MCP-3.5 — gated on DIAL Core Phase 7)* — Operator asks Claude in the middle of an incident. Claude calls `query_audit_log(since=14:00, until=14:40)`, finds an `interceptor` update, calls `snapshot_at_time(at=14:25)` and diffs against current. Result: a 2-sentence root cause with links.

**S6. "CI: apply this repo's config/ to the target env."** — GitHub Action invokes the MCP via a thin runner. Runner calls `validate_manifests` → posts dry-run diff to the PR → on merge, calls `apply_manifests` with `precheck: true`, reports per-entity status.

---

## 4. Goals & Non-Goals

### Goals
- **G1.** Expose every Configuration API capability — both per-entity CRUD on `/v1/{type}/{bucket}/{name}` and cross-entity ops on `/v1/admin/*` — as an MCP tool, with strong types derived from the same JSON schemas the REST API uses. Coverage spans all admin entity types (`models`, `applications`, `toolsets`, `interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`, `files`, `prompts`, `conversations` — see [OQ-21](08-open-questions-and-references.md)). Admin-scope MCP tools never target user buckets ([OQ-33](08-open-questions-and-references.md)) — user-owned files/prompts/conversations stay reachable through the Phase-4 `dial_user_*` surface.
- **G2.** Agent-optimized ergonomics: full-object PUT, `validate_only` flag, ETag returned on every read, actionable error remediation hints.
- **G3.** Discovery & self-description: `list_entity_types`, `describe_schema(type)`, `list_environments` — an agent dropped into a fresh install can figure out what it can do.
- **G4.** Safety rails for destructive ops: require explicit flag. *(Audit-first guarantee depends on DIAL Core Phase 7; until then, MCP relies on the explicit `confirm: true` flag and DIAL Core application logs.)*
- **G5.** Phase 4: user-scope support — same tool surface pivoting to the user's own resources, authenticated via the user's JWT.

### Non-goals
- **N1.** Not a replacement for the CLI (human workflows) or Admin UI (operators who prefer a GUI).
- **N2.** No business logic beyond what the API already enforces — MCP does not re-validate or re-author workflows.
- **N3.** No multi-DIAL-instance federation. Each MCP server talks to exactly one DIAL Core deployment. Multiple envs = multiple MCP servers (or one server with per-env config).
- **N4.** Not a hosting/tenancy layer. MCP delegates all auth and multi-tenancy to DIAL Core.
- **N5.** Not a config generator or template engine — agents can do that in-session; the MCP just applies what they produce.

---

## 5. Functional Requirements

| ID | Requirement |
|---|---|
| **M1** | Every admin-scope REST endpoint has a corresponding MCP tool. Parity is a release gate. |
| **M2** | All write tools accept `validate_only: true` to dry-run without mutating. |
| **M3** | All tools return structured JSON matching the REST response schema verbatim — no reshaping. |
| **M4** | Tool descriptions include example invocations and the corresponding REST call so agents can fall back to HTTP if a tool is unavailable. |
| **M5** | Destructive tools (`delete_*`, `apply_manifests` with removals) require an explicit `confirm: true` argument. No silent destructive default. |
| **M6** | Auth is pluggable: API key (Phase 1–2), user JWT (Phase 4). The MCP server itself does not store secrets long-term — it reads from env or per-session config. |
| **M7** | The MCP server is stateless across tool calls — each call is an independent HTTP request. No in-process state, no cache (DIAL Core has the cache). "Stateless across tool calls" means **no in-memory state retained across calls**, not "no DIAL Core read-side calls during a single tool invocation" — a single tool call may issue multiple reads against DIAL Core (e.g. the `confirm`-enforcement export read in §6.2) before responding. |
| **M8** | Every tool call carries a correlation ID forwarded to DIAL Core. Pre-Phase-7 it lands in DIAL Core application logs; post-Phase-7 it lands in the audit event metadata as `requestedBy` / `client_id`. |
| **M9** | Schema evolution: when a new entity type is added to DIAL Core, it is surfaced via `list_entity_types` without an MCP release — the MCP reads `GET /v1/admin/schema/{type}` at tool-call time for unknown types. |
| **M10** | Rate limits: MCP respects DIAL Core's rate limits; additionally applies a client-side token bucket to protect against runaway agent loops. |

---

## 6. Tool Surface (proposed)

Naming convention: `dial_admin_<verb>_<noun>`, snake_case. Grouped into five domains.

### 6.1 Read

| Tool | REST equivalent | Notes |
|---|---|---|
| `dial_admin_list_entity_types()` | (none — static) | Returns supported entity types and their canonical ID format. |
| `dial_admin_describe_schema(type)` | `GET /v1/admin/schema/{type}` | JSON Schema for the entity — agents read this before writing. |
| `dial_admin_list_environments()` | (MCP config) | Returns configured environments (dev, uat, prod). |
| `dial_admin_list_entities(type, bucket, env, filter?)` | `GET /v1/metadata/{type}/{bucket}/` | Per-bucket folder-metadata listing — returns the same `ResourceFolderMetadata` envelope with `ResourceItemMetadata` items the existing Resource API has used since launch (see [`03-api-reference.md`](03-api-reference.md) §4). Blob-managed entries only; file-sourced entries live in the runtime-config snapshot returned by `dial_admin_get_runtime_config`. For admin-managed types each entity type has exactly one shared bucket (`public/` or `platform/`); MCP defaults `bucket` for known types when the agent omits it (defaulting rule below). `filter` passes through as query params. |
| `dial_admin_get_entity(type, id, env)` | `GET /v1/{type}/{bucket}/{name}` | Returns entity + ETag + Owner-view fields (`source: file\|api`, `status`, `validationWarnings` if invalid) per [`04-security-and-audit.md`](04-security-and-audit.md) §1.5. |
| `dial_admin_get_runtime_config(env, type?)` *(deferred — Defer.1)* | `GET /v1/admin/export` | Full merged runtime config or single type. Includes both file-sourced and API-managed entries — the canonical answer to "what entities does this environment actually have" because the folder-metadata listing surface is blob-only. **Both the MCP tool and the underlying endpoint are deferred from MVP** — see [`IMPLEMENTATION.md`](IMPLEMENTATION.md) §5.5 Defer.1. Design preserved; until export ships, agents compose per-type listings + per-entity GETs (blob-only coverage; file-sourced entries are not reachable through the Configuration API surface during MVP). |
| `dial_admin_search_entities(query, env, type?)` | client-side composition over `dial_admin_list_entities` | Fuzzy/substring match over names. Useful for agents that don't know exact IDs. **Phase 1 implementation is client-side only** — the MCP server fetches metadata via the existing listing endpoint and filters in-process; no new DIAL Core endpoint. The "bounded by per-bucket counts" rationale holds for the infrastructure types (`models`, `roles`, `keys`, `interceptors`, `routes`, `schemas`, `settings` — typically <100 entities per type per environment). For `files`, `prompts`, `conversations` in `public/`, entity counts are potentially unbounded (icons, theme assets, prompt-template libraries can grow into the thousands). For those types the MCP server uses **token-based pagination** on the underlying `GET /v1/metadata/{type}/{bucket}/` endpoint (per [`03-api-reference.md`](03-api-reference.md) §4 — `limit` default 100, hard cap 1000): the MCP iterates pages via `?limit=1000&token=...` until either no `nextToken` is returned or a per-call MCP page cap is reached. For potentially unbounded types the MCP applies a hard ceiling (default **5 pages = 5,000 items per single tool invocation** — the MCP itself is stateless across calls). On overflow, the response carries a distinct **`truncated: true`** field with **`truncation_reason: "mcp_cap"`** — separate from the underlying `nextToken` (which means "more pages exist in DIAL Core"). Agents distinguish the two: `truncated: true` means narrow the query (the MCP refuses to keep paging); a surfaced `nextToken` without `truncated` means the agent should re-invoke with the returned token. Reaching the cap is an explicit signal that MCP-OQ-6 (server-side `?q=`) should be resolved for those types ahead of the others. The MCP itself does not perform client-side truncation of an individual page. |

**Bucket defaulting rule for `dial_admin_list_entities` and `dial_admin_get_entity`.** When the agent omits `bucket`, the MCP server fills in the default below before issuing the underlying REST call. Single-bucket admin-config types have one obvious target; for the dual-bucket types (`files`, `prompts`, `conversations`) the admin scope only manages **shared** instances in `public/` (admin has no access to user buckets — [OQ-33](08-open-questions-and-references.md)), so the default is `public/`.

| Type | Default bucket | Notes |
|---|---|---|
| `models` | `public/` | Single admin bucket. |
| `applications` | `public/` | Single admin bucket. |
| `toolsets` | `public/` | Single admin bucket. |
| `schemas` | `public/` | Single admin bucket — admin-managed application-type-schema entities sit alongside the apps that reference them. |
| `interceptors` | `platform/` | Single admin bucket. |
| `roles` | `platform/` | Single admin bucket. |
| `keys` | `platform/` | Single admin bucket. |
| `routes` | `platform/` | Single admin bucket. |
| `settings` | `platform/` | Singleton — `name` is fixed at `global`. Listing not meaningful — use `dial_admin_get_entity(type='settings', id='settings/platform/global', env=...)` instead. `dial_admin_update_entity` maps to `PUT` (upsert — no `404` on first use; `if_none_match='*'` returns `412` once an API blob is present). `dial_admin_delete_entity` maps to `DELETE` and **clears the API blob**, reverting the projection to file-sourced (or schema-default) values per [OQ-10](08-open-questions-and-references.md); idempotent. `dial_admin_create_entity` maps to `PUT … If-None-Match: *` and succeeds only when no API blob has been written yet. |
| `files` | `public/` | Dual-bucket type — admin manages shared assets here; user-bucket files are out of scope for admin MCP per [OQ-33](08-open-questions-and-references.md). |
| `prompts` | `public/` | Dual-bucket type — admin manages shared/default prompt templates; user prompts in user buckets are out of scope. |
| `conversations` | `public/` | Dual-bucket type — admin manages curated/example conversations; user conversations in user buckets are out of scope. |

User-scope entities (Phase 4 `dial_user_*` tools — §6.5) target the user's private bucket; defaulting on those tools is via JWT, not via the table above.

**Pagination semantics.** `dial_admin_list_entities` returns the same `ResourceFolderMetadata` envelope the underlying REST metadata endpoint produces — see [`03-api-reference.md`](03-api-reference.md) §4 for the canonical contract (`nextToken` present iff there is another page, omitted on the last page). The MCP server does not reshape the envelope; tools that paginate compose by calling `dial_admin_list_entities` with the prior response's `nextToken` until `nextToken` is absent.

### 6.2 Write

| Tool | REST equivalent | Notes |
|---|---|---|
| `dial_admin_create_entity(type, id, spec, env, validate_only?)` | `PUT /v1/{type}/{bucket}/{name} … If-None-Match: *` | Create-only via conditional `PUT`. Structured `412 Precondition Failed` (the MCP error code is `E_ALREADY_EXISTS`) if entity already exists — agents must call `dial_admin_update_entity` instead. Returns the new ETag on success. |
| `dial_admin_update_entity(type, id, spec, env, if_match?, validate_only?)` | `PUT /v1/{type}/{bucket}/{name}` (with `If-Match` when `if_match` is set) | Upsert by default — full-entity replace. `if_match` enables optimistic concurrency (`412 Precondition Failed` on stale ETag, MCP error code `E_STALE_ETAG`). When agents want "must already exist" semantics (typo guard) they perform a prior `dial_admin_get_entity` and pass the returned ETag as `if_match` — same pattern operators use from the CLI. Returns new ETag. |
| `dial_admin_delete_entity(type, id, env, confirm, if_match?)` | `DELETE /v1/{type}/{bucket}/{name}` | Requires `confirm: true`. `404` if missing. |
| `dial_admin_apply_manifests(manifests, env, validate_only?, precheck?, confirm?)` | `POST /v1/admin/apply` | Bulk upsert with dependency ordering — the only place create-or-update is implicit. `precheck` (default `true`) is the per-call batch-atomicity gate; composes orthogonally with the server-wide `softValidation`. `confirm` is structurally optional in the JSON Schema (so non-destructive applies don't need to pass it) but is **server-enforced**: the MCP server rejects with a structured `E_CONFIRM_REQUIRED` error when the manifest set causes a deletion (entity present in current state but absent from the apply set, or `state: absent` per the apply contract) and `confirm` is missing or `false`. Conditional-required-only-when-deletes is not expressible in JSON Schema's `required` array, so the contract is enforced server-side rather than at the type. **Detection mechanism — deferred alongside export ([Defer.1](IMPLEMENTATION.md)).** The original design issued `GET /v1/admin/export?type=<types_in_manifest>` to read live state and diff against the manifest. With export deferred, the `E_CONFIRM_REQUIRED` detection path also defers: until export ships, the MCP either (a) requires `confirm: true` whenever the manifest set is non-empty (conservative fallback), or (b) drains the per-type metadata listing (`GET /v1/metadata/{type}/public|platform/`) to enumerate blob-managed entities only — file-sourced entries cannot be diffed during MVP. Option (a) is the safer default; option (b) is implementer choice when MCP-1 lands. This is consistent with M7's per-call statelessness regardless. |
| `dial_admin_validate_manifests(manifests, env, precheck?)` | `POST /v1/admin/validate` | Pure dry-run — no audit event. |

**Why two tools instead of one upsert.** The wire is one `PUT` upsert per [`03-api-reference.md`](03-api-reference.md) §3, but the MCP exposes two tools because the LLM-correction story still matters: an LLM that hallucinates a slightly-wrong entity ID on an "update" should land on a structured error and self-correct, not silently create a stub the operator never asked for. `dial_admin_create_entity` sends `If-None-Match: *` (rejects if the entity exists — `E_ALREADY_EXISTS`); `dial_admin_update_entity` sends `If-Match: <etag>` when the agent supplies one (rejects on stale ETag — `E_STALE_ETAG`); bare `dial_admin_update_entity` is upsert (last-write-wins), matching the existing Resource API. Apply remains the only batch-upsert path and stays exactly as before.

### 6.3 Promote & Diff

| Tool | REST equivalent | Notes |
|---|---|---|
| `dial_admin_diff_environments(source_env, target_env, type?, name?)` | (CLI-equivalent, composed from 2×GET) | Structured diff: added/removed/changed per entity. |

**No dedicated `promote` tool.** Promotion is intentionally *not* a first-class MCP tool. The CLI's template DSL (`extends`/`includes`/`!if`/`!for` + function set — see [`05-cli-design.md`](05-cli-design.md) §3) is Java-resident, and re-implementing it in the MCP's TypeScript would guarantee semantic drift between the two clients. Agents promote by composing primitives directly:

1. `dial_admin_get_entity(type, id, env=source_env)` — fetch source entity.
2. Agent performs any needed field transformation in-session (LLM-native work: swap hostnames, re-resolve region lists, etc.). This is exactly the kind of string manipulation agents excel at; a typed template engine buys nothing here.
3. `dial_admin_validate_manifests(env=target_env, manifests=[transformed])` — dry-run against target.
4. `dial_admin_create_entity(...)` if the target env doesn't have it yet, otherwise `dial_admin_update_entity(type, id, spec=transformed, env=target_env, validate_only=false)` — after human confirmation. The agent picks based on the validate-step result rather than guessing.

Scenario S2 in §3.2 works unchanged with this composition. If operator feedback shows a native `promote` would pay off enough to justify library extraction, revisit in MCP-3+ — but the default is "MCP exposes primitives, agent composes workflows."

### 6.4 Audit

> **STATUS: WIP / DEFERRED.** Audit tools below are gated on DIAL Core's audit subsystem, which is **deferred to Phase 7** (see [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 7). They will not appear in MCP-1, MCP-2, or MCP-3 as currently planned — the §8 phasing table reflects this.

| Tool | REST equivalent | Notes |
|---|---|---|
| `dial_admin_query_audit(env, filters)` *(WIP — Phase 7)* | `GET /v1/admin/audit` | Filters: `entityType`, `entityId`, `bucket`, `batch_id`, `requestedBy`, `operation`, `status`, `since`, `until`. |
| `dial_admin_get_entity_history(type, id, env, since?, until?)` *(WIP — Phase 7)* | (composition over audit query) | Convenience: every event touching one entity. |
| `dial_admin_snapshot_at_time(env, at, type?)` *(WIP — Phase 7)* | (composition over audit snapshots + archival) | Reconstructs runtime config at a point in time for root-cause work. |
| `dial_admin_rollback_entity(type, id, env, to_event, confirm)` *(WIP — Phase 7)* | (composition over snapshot + PUT) | Requires `confirm: true`. |

### 6.5 User scope (Phase 4)

Parallel surface with `dial_user_*` prefix, accepting user JWT:

| Tool | Notes |
|---|---|
| `dial_user_list_applications()` | User's own apps in their private bucket. |
| `dial_user_put_application(spec, if_match?, validate_only?)` | Create/update user-owned app. |
| `dial_user_put_toolset(spec, …)` | Same for toolsets. |
| `dial_user_put_prompt(spec, …)` | Phase 4+ depending on prompt scope. |
| `dial_user_publish_application(id, target_bucket, message)` | Kicks off publication workflow (existing `PublicationService`). |

User-scope tools never touch `platform/` or anyone else's private bucket — authorization is enforced by DIAL Core, not by MCP.

### 6.6 Example tool definition (illustrative)

```json
{
  "name": "dial_admin_update_entity",
  "description": "Update an existing DIAL configuration entity (full-entity replace). Returns the persisted entity with its new ETag. Returns a structured 404 error if the entity does not exist — call dial_admin_create_entity instead. Set validate_only=true to dry-run. Authorization requires admin role.",
  "inputSchema": {
    "type": "object",
    "required": ["type", "id", "spec", "env"],
    "properties": {
      "type": { "enum": ["models", "applications", "toolsets", "interceptors", "roles", "keys", "routes", "schemas", "settings", "files", "prompts", "conversations"] },
      "id":   { "type": "string", "description": "Canonical ID: {type}/{bucket}/{name}" },
      "spec": { "type": "object", "description": "Entity body matching the type's JSON schema (see dial_admin_describe_schema)" },
      "env":  { "type": "string" },
      "if_match":       { "type": "string", "description": "ETag for optimistic concurrency. Optional. 412 Precondition Failed if the stored ETag has moved." },
      "validate_only":  { "type": "boolean", "default": false }
    }
  }
}
```

The peer `dial_admin_create_entity` tool has the same input shape minus `if_match`, returns `412 Precondition Failed` (MCP error code `E_ALREADY_EXISTS`) when the entity already exists — the create-only gate via `PUT … If-None-Match: *` per [`03-api-reference.md`](03-api-reference.md) §3.

---

## 7. Architecture

### 7.1 Deployment model (three options)

| Option | Description | Pros | Cons |
|---|---|---|---|
| **A. Standalone service next to DIAL Core** | A Node or Python MCP server running as its own pod in the DIAL Helm chart. | Isolated, independent versioning, easy to run local dev. | Another service to operate. |
| **B. Embedded in DIAL Core** | Vert.x verticle inside DIAL Core, exposing MCP over the same HTTP port. | No new deployment surface. | Couples MCP release cadence to DIAL Core. Language mismatch with MCP TypeScript ecosystem. |
| **C. Per-developer local proxy** | Each developer runs the MCP locally; it talks to a remote DIAL Core. | Zero server-side ops. | No audit correlation beyond what DIAL Core records. Harder to restrict to specific envs. |

**Recommendation: A for prod operators, C for local dev.** Shipping A gives DevOps a deployable they can restrict to a single env with scoped credentials. C is the fallback for the laptop Claude Code user. B is only attractive if MCP becomes a commodity — too early for that bet.

### 7.2 Implementation stack (proposed)

- **Language/framework:** TypeScript + Anthropic MCP SDK (`@modelcontextprotocol/sdk`). Reason: best-maintained SDK, matches Claude Code's native transport, aligns with Admin Frontend stack. Python is a reasonable alternative if the MCP tooling team prefers it — the tool surface is language-independent.
- **HTTP client:** native fetch + an ETag-aware wrapper.
- **Schema source:** at build time, pull `GET /v1/admin/schema/{type}` from a reference DIAL instance and generate TS types. At runtime, the MCP re-fetches for unknown types (M9).
- **Transport:** stdio (for Claude Code / Claude desktop) + HTTP (for QuickApp / remote use). Both modes from day one — the SDK supports this natively.

### 7.3 Auth model

Three phases:

| Phase | Auth | Notes |
|---|---|---|
| Phase 1–2 | API key in env var (`DIAL_ADMIN_MCP_API_KEY_<ENV>`), scoped to admin role in DIAL Core | Same auth as `dial-cli`. Operator configures once. |
| Phase 3 | Service account + OIDC client credentials (for CI/CD agents) | CI doesn't want long-lived API keys. |
| Phase 4 | User JWT pass-through (for DIAL QuickApp user scope) | QuickApp already has the user's JWT; MCP forwards it. Never stored. |

All modes flow through `ConfigAuthorizationService` on the DIAL Core side — MCP adds no authorization logic of its own.

### 7.4 Correlation with audit *(Phase 7)*

Every MCP tool call adds headers forwarded to DIAL Core:

```
X-DIAL-Client: dial-admin-mcp/0.1
X-DIAL-Client-Session: <uuid>
X-DIAL-Client-Agent: claude-code | claude-desktop | quickapp | ci
```

Pre-Phase-7 these headers are echoed into DIAL Core application logs (best-effort, not query-friendly). Post-Phase-7 they land in the audit event's metadata so "which agent did this?" becomes answerable from `dial-cli audit log`.

---

## 8. Phased Rollout

| Phase | Scope | DIAL Core prereq | Notes |
|---|---|---|---|
| **MCP-0** | Spec + design review | None | This doc. |
| **MCP-1** | Read-only admin scope: all §6.1 tools + `list_environments` | DIAL Core Phase 1 (read-only API) | Shippable in days once Phase 1 lands. |
| **MCP-2** | Write admin scope: §6.2 + `validate_only`, `confirm` safety | DIAL Core Phase 2 (writes for models), Phase 3 (writes for all entities) | Ship in two increments alongside Core. |
| **MCP-3** | Apply + diff: §6.3 (no dedicated `promote` tool — agents compose GET + transform + PUT) | DIAL Core Phase 4 (apply) | Audit tools (§6.4) split out — see MCP-3.5. |
| **MCP-3.5** *(deferred — gated on DIAL Core Phase 7)* | Audit query + snapshot + rollback tools: §6.4 | DIAL Core Phase 7 (audit subsystem) | `rollback` gated on DIAL Core snapshot archival landing. |
| **MCP-4** | User scope: §6.5 | User JWT auth flow; publication workflow audit gated on DIAL Core Phase 7+ | Requires QuickApp embed story. |
| **MCP-5** | Multi-tenancy awareness | DIAL Core MT work (OQ-22 / OQ-26) | Scope prefixes surface as tool arguments. |

MCP-1 can ship as soon as DIAL Core Phase 1 is deployed to any environment — it's value-positive even with only read tools.

---

## 9. Success Metrics

| Metric | MCP-1 target (first 90 days) | MCP-3 target (12 months) |
|---|---|---|
| Adoption: active environments with MCP enabled | ≥3 internal EPAM envs | ≥50% of DIAL production deployments |
| Median time-to-first-useful-tool-call after install | <5 min | <3 min |
| Tool calls / week (aggregate) | 100 | 5,000 |
| Agent-initiated configuration changes / week | 0 (read-only) | ≥30% of non-CI config changes |
| `validate_only` → real-apply conversion rate | — | >70% (indicates agents are pre-validating) |
| Rollback-triggered incidents per month | — | <1 |
| Operator CSAT for "resolved a config question via agent" | — | ≥4/5 |

---

## 10. Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Agent loops / runaway tool calls | DoS on DIAL Core admin surface | Client-side token bucket (M10), DIAL Core rate limits, MCP server per-env concurrency cap |
| Agent-driven mass deletion | Data loss | `confirm: true` required on all destructive ops; audit every PENDING; reconciliation job |
| Auth misconfiguration (over-scoped token) | Agent acts with more privilege than intended | Recommend env-specific API keys with admin role only on lower envs; Phase 3 service accounts with limited scope |
| Schema drift between MCP types and DIAL Core | Agents write invalid specs | Runtime fetch of schema (M9); integration tests against live DIAL Core pin canonical schemas per release |
| Duplicate maintenance (CLI + MCP) | Eng cost | Both wrap the same API; most of CLI's logic lives in DIAL Core (validation, apply semantics). CLI and MCP share no code — by design, each is a thin client. The one natural duplication point (template-based promote) is explicitly avoided by not shipping a `promote` tool in MCP — see §6.3. |
| QuickApp trust boundary | User-scope agent acts outside user's bucket | All auth delegated to DIAL Core's existing JWT model; MCP adds no bypass |
| MCP protocol churn | Breaking changes from Anthropic | Pin SDK major version; document protocol version in tool responses |

---

## 11. Open Questions

| # | Question | Needs to close |
|---|---|---|
| MCP-OQ-1 | **Deployment model**: ship A only, or A + C from day one? A is safer; C is faster for the Claude Code laptop user. | MCP-1 kickoff |
| MCP-OQ-2 | **Language**: TypeScript (recommended) or Python? | MCP-1 kickoff |
| MCP-OQ-3 | **Tool naming**: `dial_admin_*` vs `dial_*` with capability scopes as arguments? Kebab-case is also permitted by MCP. | Before MCP-1 ships |
| ~~MCP-OQ-4~~ | ~~**Promote as a tool** (§6.3) or **as a composition of read/write**?~~ **Resolved:** composition, no dedicated `promote` tool (see §6.3). Revisit in MCP-3+ only if operator feedback shows native promote pays off enough to justify template-engine extraction. | — |
| MCP-OQ-5 | **User-scope OAuth flow**: does the QuickApp pass the user's JWT, or does the MCP do its own OIDC dance? | MCP-4 design |
| MCP-OQ-6 | **Search tool** (§6.1 `dial_admin_search_entities`): scope this to client-side filter over `list_entities`, or add a DIAL Core endpoint? | MCP-1 scoping |
| MCP-OQ-7 | **Confirmation UX for destructive ops**: is `confirm: true` enough, or should the server require a two-step flow (`prepare_delete` → `commit_delete`)? | Before MCP-2 ships |
| MCP-OQ-8 | **Caching**: M7 says stateless. But `describe_schema` is rarely changing and every tool call costs a round-trip. Add a 60s TTL cache? | MCP-1 scoping |
| MCP-OQ-9 | **Multi-env in a single MCP session**: one tool call targets `env=prod`, next targets `env=uat` — is that safe, or should each MCP server instance be pinned to one env? | Before MCP-1 ships |
| MCP-OQ-10 | **Observability**: expose MCP-internal metrics (tool latency, error rate) via `/metrics`? Or rely on DIAL Core audit + agent traces? | MCP-2 scoping |

---

## 12. Out-of-Scope for This Spec (parked)

- **Agent prompt library** — "tell me how to use this MCP" starter prompts. Worth building in MCP-2, out of scope for v0.1.
- **MCP server marketplace / registry integration** — whether to publish to Anthropic's public registry, or keep private to EPAM tap.
- **DIAL Chat-embedded agent** using this MCP to let end users talk to config in natural language — interesting product follow-on, not in this spec.
- **Terraform / Pulumi providers** — a different surface for a different audience. MCP is for conversational agents; Terraform is for declarative IaC. They can coexist.

---

## 13. References

- [`03-api-reference.md`](03-api-reference.md) — the REST surface this wraps
- [`04-security-and-audit.md`](04-security-and-audit.md) — auth and audit integration
- [`05-cli-design.md`](05-cli-design.md) — the peer human-facing client
- [`07-migration-and-rollout.md`](07-migration-and-rollout.md) — phase dependencies
- [Model Context Protocol spec](https://modelcontextprotocol.io) — external reference
- Anthropic MCP SDK — `@modelcontextprotocol/sdk` (TypeScript), `mcp` (Python)

---

## Next

- Review and resolve MCP-OQ-1 through MCP-OQ-10.
- If spec is approved: kick off MCP-1 scoping with a tech lead, target 2-week delivery of read-only tools against a staging DIAL Core.
- Follow-up spec (separate doc): **DIAL QuickApp ↔ MCP integration** — how a QuickApp authenticates its MCP calls and presents results back to the user.
