# 07 — DIAL Admin MCP

A Model Context Protocol server that exposes the Configuration API as typed tools for AI agents (Claude Code, Claude desktop, DIAL QuickApps, IDEs, CI). Same contract as the CLI and Admin Backend — just an agent-native surface on it.

## Why MCP (not "just use the CLI")

Agents parsing CLI stdout are fragile (column drift, YAML quirks, interleaved warnings); agents hitting REST directly have to learn URL conventions, the ETag dance, and the error taxonomy from scratch each session. MCP gives agents:

| Pain point | CLI today | MCP |
|---|---|---|
| Discoverability | `--help` is human-only | `tools/list` returns a structured catalog |
| Return shape | stdout string, parse at your peril | Typed JSON, schema-validated |
| Chaining | shell out per call, glue strings | Sequential tool calls with typed in/out |
| Errors | exit codes + stderr text | Structured error with remediation hint |
| Dry-run | `--dry-run` flag | First-class `validate_only: true` arg |

Shelling out to `dial-cli` from inside the MCP is rejected: process startup cost on every call, argv injection surface, and the `--set` field-assembly UX is wrong for agents (which know the full object and want to `PUT` it). MCP → REST direct is simpler, faster, typed.

## Personas

| Persona | Where | Scope |
|---|---|---|
| **DIAL env operator** | Claude Code / Claude desktop with MCP configured against uat/prod | Admin |
| **DIAL app developer** | Claude Code, local dev | Admin (dev env) |
| **DIAL QuickApp** | Agent hosted inside DIAL, acting on behalf of a user | User (private bucket) |
| **CI/CD agent** | GitHub Action or equivalent | Admin (scoped service account) |

Top scenarios (illustrative, not exhaustive): "List the models in prod and flag any with >1 week since last update", "Promote Claude Sonnet 4.6 from uat to prod", "Scaffold a new admin app with a custom schema + a rate-limit role", and the user-scope "User: 'Make me an email-summarizer agent'" (Phase 4).

## Goals / non-goals

**Goals.**

- Cover every Configuration API capability as a typed tool — per-entity CRUD on `/v1/{type}/{bucket}/{name}` and cross-entity ops on `/v1/admin/*`.
- Agent-optimized ergonomics: full-object PUT, `validate_only`, ETag returned on every read, actionable error remediation hints.
- Self-description: `list_entity_types`, `describe_schema(type)`, `list_environments` so an agent dropped into a fresh install can figure out what's possible.
- Safety rails for destructive ops — explicit `confirm: true` flag.
- Phase 4 — user-scope `dial_user_*` tools for agents acting on behalf of users.

**Non-goals.**

- Not a CLI replacement (humans) or Admin UI replacement (operators who want a GUI).
- No business logic beyond what the API enforces.
- No multi-instance federation — one MCP server talks to one DIAL Core deployment.
- Not a hosting/tenancy layer — auth and multi-tenancy delegate to DIAL Core.
- Not a config generator or template engine — agents compose in-session; MCP applies.

## Tool surface

Five named groups, all with prefix `dial_admin_<verb>_<noun>`:

- **Read** — `list_entity_types`, `describe_schema`, `list_environments`, `list_entities`, `get_entity`, `get_runtime_config`, `search_entities`.
- **Write** — `create_entity`, `update_entity`, `delete_entity` (`confirm: true` required), `apply_manifests`, `validate_manifests`.
- **Promote & diff** — `diff_environments`. *No first-class `promote` tool*: agents compose primitives (`get_entity` → transform in-session → `validate_manifests` → `create_entity` or `update_entity`), which keeps the MCP free of a TypeScript re-implementation of the CLI's template DSL.
- **Audit** — `query_audit`, `get_entity_history`, `snapshot_at_time`, `rollback_entity`. *Gated on DIAL Core Phase 7 — deferred.*
- **User scope** (Phase 4) — parallel `dial_user_*` surface accepting the user's JWT, restricted to the user's own private bucket. Never touches `platform/` or anyone else's bucket — DIAL Core enforces this, not the MCP.

One illustrative tool definition (every Write tool follows this shape):

```json
{
  "name": "dial_admin_update_entity",
  "description": "Update (or upsert) a DIAL configuration entity (full-entity replace). Wraps PUT /v1/{type}/{bucket}/{name} — bare is last-write-wins; pass if_match for CAS (structured 412 with error code E_STALE_ETAG on mismatch). Returns the persisted entity with its new ETag.",
  "inputSchema": {
    "type": "object",
    "required": ["type", "id", "spec", "env"],
    "properties": {
      "type": { "enum": ["models", "applications", "toolsets", "interceptors", "roles", "keys", "routes", "schemas", "settings", "files", "prompts", "conversations"] },
      "id":   { "type": "string", "description": "Canonical ID: {type}/{bucket}/{name}" },
      "spec": { "type": "object" },
      "env":  { "type": "string" },
      "if_match":      { "type": "string" },
      "validate_only": { "type": "boolean", "default": false }
    }
  }
}
```

The `create_entity` peer has the same shape minus `if_match`. It wraps `PUT … If-None-Match: *` and returns a structured `412 Precondition Failed` (MCP error code `E_ALREADY_EXISTS`) when the entity already exists — agents must then call `dial_admin_update_entity`. The two-tool split keeps the LLM-correction story explicit: a hallucinated entity ID on an "update" lands on a structured error instead of silently creating a stub. Apply remains the only batch-upsert path. `dial_admin_list_entities` wraps `GET /v1/metadata/{type}/{bucket}/` and returns `ResourceFolderMetadata` (the same shape the existing Resource API has used since launch).

## Deployment options

| Option | Description | When |
|---|---|---|
| **A. Standalone service** | A TypeScript/Python MCP server next to DIAL Core in the Helm chart. | **Recommended for prod operators** — isolated, independently versionable, scoped to a single env. |
| **B. Embedded in DIAL Core** | Vert.x verticle exposing MCP over the same HTTP port. | Avoid for now — couples release cadence and mismatches the TypeScript MCP ecosystem. |
| **C. Per-developer local proxy** | Each developer runs MCP locally against a remote DIAL. | **Fallback for laptop Claude Code use** — zero server-side ops, no audit correlation beyond DIAL logs. |

Implementation stack: TypeScript + Anthropic MCP SDK (`@modelcontextprotocol/sdk`), stdio + HTTP transport from day one, type generation from `GET /v1/admin/schema/{type}` at build time.

## Auth model

The MCP server adds **no authorization logic of its own** — every call flows through DIAL Core's `ConfigAuthorizationService`.

| Phase | Auth | Notes |
|---|---|---|
| 1–2 | API key in env var, scoped to admin role | Same as `dial-cli`. Operator configures once. |
| 3 | Service account + OIDC client credentials | CI agents don't want long-lived API keys. |
| 4 | User JWT pass-through | QuickApp already has the user's JWT — MCP forwards it. Never stored. |

Audit correlation: every tool call carries a correlation ID forwarded to DIAL Core. Pre-Phase-7 it lands in application logs; post-Phase-7 it appears in audit event metadata.

> See the full version: [`../dial-unified-config/09-admin-mcp-spec.md`](../dial-unified-config/09-admin-mcp-spec.md)
