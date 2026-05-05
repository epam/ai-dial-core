# 09 — DIAL MCP Server (Spec v0.2 — building blocks)

> **Status:** Draft v0.2 — single-surface design locked, building-block tool set sized down to 9 tools, stack & deployment reframed (external Python sidecar). Naming, summary-view projections per type, and a few session-model questions remain open.
> **Audience:** Product, DIAL Core dev team, MCP tooling team, DevOps leads, anyone building agents that talk to DIAL.
> **Prerequisites:** [`03-api-reference.md`](03-api-reference.md) (the API this wraps), [`04-security-and-audit.md`](04-security-and-audit.md) (auth model).

This document specifies a Model Context Protocol (MCP) server that exposes DIAL's REST API to AI agents as a small set of typed building-block tools. Both administrators and end-users (via DIAL QuickApps, Claude Code, Claude Desktop, IDE integrations, CI) call the **same** tool surface — DIAL Core enforces authorization based on the caller's identity, so the MCP itself stays small and stupid. The MCP is *not* a replacement for `dial-cli` or the DIAL Admin Backend (those remain canonical human interfaces); it is the canonical *agent* interface.

---

## 1. Summary

Build `ai-dial-mcp`: a standalone Python MCP server, distributed as a separate repository, that exposes 9 building-block tools — describe-schema, list/get/create/update/delete resource, upload/download file, publish resource — against DIAL Core's REST API. Agents compose these into the higher-level workflows users actually want (promote a model, scaffold an app, integrate an external toolset, save resources from a chat). The MCP doesn't bake workflows in; agents are good at composition, the MCP makes composition cheap.

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
1. `dial_publish_resource(id='conversations/private/<id>', target='public/<channel>', message='…')`.

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
- **N4.** No multi-DIAL-instance federation. Each MCP server talks to exactly one DIAL Core deployment. (Multi-env in a single session: see MCP-OQ-3.)
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
| **M6** | Auth is pluggable: admin API key, service-account OIDC, user JWT pass-through. The MCP does not store secrets long-term — it reads from env or per-session config. |
| **M7** | The MCP is stateless across tool calls — each call is an independent HTTP request to Core. The one cached value per session is the result of `GET /v1/bucket` (used to resolve the `private` alias) — refreshed at session start, no cross-call state otherwise. |
| **M8** | Every tool call carries a correlation ID forwarded to DIAL Core (§7.4). |
| **M9** | Schema evolution: when a new entity type is added to DIAL Core, it surfaces via `dial_describe_schema(type)` without an MCP release — the MCP fetches `GET /v1/admin/schema/{type}` at tool-call time for unknown types. |
| **M10** | Rate limits: MCP respects DIAL Core's rate limits; additionally applies a client-side token bucket per-session to protect against runaway agent loops. |

---

## 6. Tool Surface

Single namespace `dial_*` (no admin/user prefix split — auth-driven scope, enforced by Core). Naming convention: `dial_<verb>_<noun>`, snake_case.

### 6.1 The 9 tools

| # | Tool | REST equivalent | Purpose |
|---|---|---|---|
| 1 | `dial_describe_schema(type)` | `GET /v1/admin/schema/{type}` | JSON Schema for an entity type — agents read before writing |
| 2 | `dial_list_resources(path, recursive?, filter?, format?, cursor?)` | `GET /v1/{type}/{bucket}/[<sub>/]` | Paginated listing; two-array envelope for hierarchical types (§6.3) |
| 3 | `dial_get_resource(id, format?)` | `GET /v1/{type}/{bucket}/{name}` | Single read with ETag header |
| 4 | `dial_create_resource(id, spec, validate_only?)` | `POST /v1/{type}/{bucket}/{name}` | Create-only; `409` if exists |
| 5 | `dial_update_resource(id, spec, if_match?, validate_only?)` | `PUT /v1/{type}/{bucket}/{name}` | Update-only; `404` if missing; `412` on stale ETag |
| 6 | `dial_delete_resource(id, confirm, if_match?)` | `DELETE /v1/{type}/{bucket}/{name}` | Requires `confirm: true` |
| 7 | `dial_upload_file(id, content \| source_url, content_type?)` | `PUT /v1/files/{bucket}/{path}` (multipart) | File-shaped writes; binary content or server-fetched URL |
| 8 | `dial_download_file(id, max_bytes?)` | `GET /v1/files/{bucket}/{path}` | Raw bytes / MCP image-content; metadata via `dial_get_resource` |
| 9 | `dial_publish_resource(id, target, message?)` | wraps `PublicationService` | Async publication lifecycle |

Cross-cutting affordances on every relevant tool: `validate_only` on writes, `confirm` on delete, ETag header on reads/writes, structured errors with remediation hints, MCP correlation headers forwarded to Core.

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

For flat types (`models`, `roles`, `keys`, `interceptors`, `routes`, `schemas`, `settings`), `folders` is always empty. For hierarchical types (`files`, `prompts`, `conversations`), `folders` carries the immediate sub-prefixes; the agent navigates by re-listing with a deeper `path`. `recursive: true` flattens the tree under `path` (subject to the truncation cap).

`truncated: true` with `truncation_reason: "mcp_cap"` is distinct from `hasMore: true`:

- `truncated` means **the MCP refused to keep paging** — narrow the query.
- `hasMore` means **more pages exist in Core** — re-invoke with the returned `nextCursor`.

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
| `schemas` | `displayName`, `status`, `description` |
| `settings` | (singleton — not listed) |
| `files` | `contentType`, `size`, `description` |
| `prompts` | `displayName`, `description` |
| `conversations` | `displayName`, `description` |

> Field choices in the table are illustrative — confirm against actual entity shapes during MCP-1 evals (MCP-OQ-2).

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
    "type": "object",
    "required": ["id", "spec"],
    "properties": {
      "id":   { "type": "string", "description": "Canonical id `{type}/{bucket}/{name}`. Bucket may be the literal value or one of the aliases `private` / `public` / `platform`." },
      "spec": { "type": "object", "description": "Entity body matching the type's JSON schema (see dial_describe_schema)." },
      "if_match":      { "type": "string", "description": "ETag for optimistic concurrency. Optional. Returns 412 Precondition Failed if the stored ETag has moved." },
      "validate_only": { "type": "boolean", "default": false }
    }
  }
}
```

The peer `dial_create_resource` has the same shape minus `if_match`, returns `409 Conflict` if the entity already exists.

---

## 7. Architecture

### 7.1 Repository

Separate, standalone repository — e.g. `epam-edp/ai-dial-mcp`. **Not** part of `ai-dial-core`. Independent release cycle, own changelog, own CI, own version. This:

- Decouples MCP iteration from Core's release train.
- Lets the MCP team pick its own stack (Python — see §7.2) without coupling to Core's Java toolchain.
- Lowers the OSS contribution barrier (small focused repo).
- Mirrors how third-party agent integrations against DIAL would be structured anyway — the internal MCP becomes a reference implementation.

Drift between the MCP and Core's REST contract is mitigated the standard way: pin a Core version range in tests, run integration tests against a staging Core on every PR, surface contract changes as failing CI.

### 7.2 Stack

- **Language:** Python.
- **MCP framework:** Anthropic's `mcp` SDK / FastMCP.
- **HTTP client:** `httpx` (or equivalent) — direct REST calls to Core.
- **REST client typing:** for v1 the MCP does *not* depend on the `dial-api` Python package. Once `dial-api` is extended to cover the full Configuration API surface (see §11), the MCP can switch to it for typed REST clients without an architectural change.
- **Schema source:** runtime fetch of `GET /v1/admin/schema/{type}` (M9). No build-time codegen.
- **Transport:** HTTP/SSE (hosted) and stdio (laptop) — both supported by FastMCP from one entrypoint.

Rationale for Python:

- DIAL ecosystem is Python-heavy: `dial-api` Python package, most apps and interceptors, the analytics component. Same contributor pool that maintains those can hack on the MCP without context-switching.
- Python MCP SDK is first-class; FastMCP is the most idiomatic way to build a service-shaped MCP in 2026.
- Future code reuse via `dial-api` is loose-coupled (a published PyPI dependency), preserving the MCP's independent release cadence.
- Java was considered for code reuse with `dial-cli-core`; once the CLI's compile-time validators / template engine / single-binary distribution requirements were factored out, the case for Java weakened — the MCP's actual reuse needs are minimal at v1, and the Python ecosystem fit dominates.

### 7.3 Deployment

| Shape | Audience |
|---|---|
| Helm chart entry / Docker image as a sidecar to DIAL Core | Hosted environments (operators, QuickApps, CI agents) |
| `uvx ai-dial-mcp` for laptop install | Claude Code / Claude Desktop users |
| Stdio entrypoint of the same package | Claude Desktop instances that don't speak HTTP MCP |

The MCP is *not* embedded in DIAL Core. The deep-integration argument doesn't hold up for a building-block surface — every v1 tool is reachable through the public REST API, including bucket-alias resolution via the existing `GET /v1/bucket` endpoint. Embedding would couple MCP iteration to Core's release train without buying capability the surface needs.

### 7.4 Auth

| Caller | Credential | How MCP handles it |
|---|---|---|
| Operator | Admin API key in env (`DIAL_MCP_API_KEY` or env-scoped variant) | Forward as DIAL admin header |
| CI agent | Service-account OIDC client credentials | Exchange for short-lived token, forward |
| QuickApp | User JWT | Forward verbatim |
| End user via Claude Desktop | User JWT (provided by the agent runtime) | Forward verbatim |

All authorization decisions are made by Core's `ConfigAuthorizationService`. The MCP adds no authorization logic of its own.

### 7.5 Correlation

Every MCP tool call adds headers forwarded to DIAL Core:

```
X-DIAL-Client: ai-dial-mcp/<version>
X-DIAL-Client-Session: <uuid>
X-DIAL-Client-Agent: claude-code | claude-desktop | quickapp | ci | other
```

Pre-Phase-7 these are echoed to Core's application logs (best-effort, not query-friendly). Post-Phase-7 they land in audit-event metadata as `requestedBy` / `client_id`.

---

## 8. Phased Rollout

| Phase | Scope | Core prereq |
|---|---|---|
| **MCP-0** | Spec + design review | None — this doc |
| **MCP-1** | All 9 building-block tools (§6.1), HTTP/SSE + stdio transports, admin API key + user JWT auth | Core Phase 1 (read-only API) for the read tools; Core Phase 2/3 (writes) for the write tools — ship in two increments alongside Core |
| **MCP-2** | Service-account OIDC for CI agents | None — additive auth |
| **MCP-future** | Tools listed in §11 — each scoped to its driving need and Core dependency | Per item |

Read-only MCP-1 ships as soon as Core Phase 1 deploys to any environment. Write tools follow Core's Phase 2/3 entity-by-entity rollout.

---

## 9. Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Agent loops / runaway tool calls | DoS on Core's admin surface | Client-side token bucket (M10), Core rate limits, MCP per-session concurrency cap |
| Agent-driven mass deletion | Data loss | `confirm: true` on `dial_delete_resource`; reconciliation job; audit (post-Phase-7) |
| Auth misconfiguration (over-scoped token) | Agent acts with more privilege than intended | Recommend env-specific keys with admin role only on lower envs; user-JWT passthrough has no such risk |
| Schema drift between MCP and Core | Agents write invalid specs | Runtime fetch of schemas (M9); integration tests against staging Core on every PR |
| MCP protocol churn | Breaking changes from Anthropic | Pin SDK major version; document protocol version in tool responses |
| Drift between MCP-encoded knowledge and Core's REST contract (separate-repo cost) | Wrong tool descriptions, broken inputSchemas | Pinned Core version + integration tests; CI fail-loud on contract change |

---

## 10. Open Questions

| # | Question | Needs to close |
|---|---|---|
| MCP-OQ-1 | **Repo name & GitHub org**: `ai-dial-mcp` under `epam-edp`? Confirm. | MCP-1 kickoff |
| MCP-OQ-2 | **Per-type `summary` projections** (§6.4 table): are the listed fields the right ones, or revise based on first eval pass? | Before MCP-1 ships |
| MCP-OQ-3 | **Multi-env in a single MCP session**: one tool call against `env=prod`, next against `env=uat` — safe, or pin each MCP server instance to one env? | Before MCP-1 ships |
| MCP-OQ-4 | **`describe_schema` caching**: M7 says stateless aside from `/v1/bucket`. Add a session-level TTL cache for schemas (~60s) to avoid round-trips on common writes? | MCP-1 scoping |
| MCP-OQ-5 | **Confirmation UX for destructive ops**: is `confirm: true` enough, or should the server require a two-step flow (`prepare_delete` → `commit_delete`)? | Before MCP-1 destructive tools land |
| MCP-OQ-6 | **MCP-internal observability**: expose tool latency / error rate via `/metrics`? Or rely on Core logs + agent traces? | MCP-1 scoping |

---

## 11. Future Work (parked / out of scope for v1)

Items deliberately excluded from MCP-1, with a short note on what would unlock them:

| Item | Driving need | Unlocks when |
|---|---|---|
| `dial_get_effective_policy(subject, target)` | Aggregate role / limit / key precedence into one server-side answer for "what limits actually apply to user X on model Y?" | Core exposes the merge as an endpoint |
| `dial_apply_manifests(...)` | Multi-resource transactional writes (e.g. interceptor + global-settings update in one call) | Real demand from operators / CI agents |
| `dial_diff_environments(source_env, target_env, ...)` | Cross-env drift inspection | Multi-env MCP session model is locked (MCP-OQ-3) |
| `dial_export(env, type?)` | Full-config snapshot | Same as diff_environments |
| `dial_search_resources(query, types?)` | Cross-type / cross-bucket name search | Agents thrash on `list + filter` enough to justify a server-side index |
| Audit tools (`query_audit`, `get_entity_history`, `snapshot_at_time`, `rollback_entity`) | Root-cause + rollback workflows | Core Phase 7 audit subsystem ships |
| `dial_deploy_codeapp(name, code, runtime)` | Codeapp authoring lifecycle in one call | Codeapp service has a clean async readiness signal |
| `dial-api` Python package adoption | Typed REST client + future schema validation reuse | `dial-api` extended to cover the Configuration API |
| OpenAPI spec from Core as a release artifact | Stack-agnostic third-party clients | Separate Core-side task |
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
- [Writing Effective Tools for Agents — Anthropic](https://www.anthropic.com/engineering/writing-tools-for-agents)
- [MCP Best Practices — Phil Schmid](https://www.philschmid.de/mcp-best-practices)
- Anthropic MCP SDKs — `mcp` (Python — chosen), `@modelcontextprotocol/sdk` (TypeScript — alternative)

---

## Next

- Resolve MCP-OQ-1 through MCP-OQ-6.
- If approved: kick off MCP-1 scoping in the new repo, target a 2-week first cut of read-only tools against a staging DIAL Core.
- Follow-up: confirm whether MCP-OQ-3's resolution affects the §11 `dial_diff_environments` and `dial_export` framings.
