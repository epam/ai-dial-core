# 05 — `dial-cli` Design

> **Audience:** Dev team building `dial-cli`; reviewers evaluating the CLI contract.
> **Reading time:** ~12 minutes.
> **Prerequisites:** [`03-api-reference.md`](03-api-reference.md) — the CLI is a client of this API.

This document specifies the internal design of `dial-cli`: command surface, configuration profile, template resolution, promotion logic, manifest format, and technology stack. For the DevOps-facing user guide (installation, worked workflows, troubleshooting) see [`06-cli-user-guide.md`](06-cli-user-guide.md).

---

## 1. Command Structure

```
dial-cli [global-flags] <resource-type> <command> [command-flags]
```

**Global flags:**

| Flag | Description |
|------|-------------|
| `--env <name>` | Target environment from profile config |
| `--config <path>` | CLI config file (default: `~/.dial-cli/config.yaml`) |
| `--api-url <url>` | Override API URL |
| `--api-key-file <path>` | Read API key from file (CI secret mounts, SOPS-decrypted files). The key is otherwise resolved from the profile's `auth.key_env_var`, the OS keystore, or an interactive no-echo prompt — never from a `--api-key` flag. See [`06-cli-user-guide.md`](06-cli-user-guide.md) §2.1 and [`04-security-and-audit.md`](04-security-and-audit.md) §1.6. |
| `-o, --output <fmt>` | Output format: `table` (default), `json`, `yaml` |
| `-v, --verbose` | Verbose output |
| `--dry-run` | Preview changes without applying |

**Resource types:** `model`, `application`, `toolset`, `interceptor`, `role`, `key`, `route`, `schema`, `settings`, `file`, `prompt`, `conversation`. Per [OQ-21](08-open-questions-and-references.md), `file` / `prompt` / `conversation` are first-class admin types — they target **shared** admin-managed instances in the `public/` bucket (icons / theme assets, default prompt templates, curated example conversations). User-owned files/prompts/conversations in user buckets remain accessed via the existing user Resource API and are not addressed by `dial-cli` admin commands (admin has no access to user buckets — [OQ-33](08-open-questions-and-references.md)).

**Identifiers.** The CLI accepts both canonical IDs (`{type}/{bucket}/{name}`, matching the API) and simple names inherited from the config file. Under the union model ([`02-architecture.md`](02-architecture.md) §MergedConfigStore), these address **distinct entities** — a canonical ID resolves to an API-managed entity, a simple name resolves to a file-sourced entity. The CLI forwards whichever identifier the operator supplies to the API verbatim; there is no silent expansion from simple to canonical (that would break the union by conflating two different entries).

```bash
# Canonical ID — API-managed entity
dial-cli model get models/public/gpt-4

# Simple name — file-sourced entity with the same short name, a different entity
dial-cli model get gpt-4

dial-cli role get roles/platform/viewer
dial-cli interceptor get interceptors/platform/guardrail
dial-cli application get applications/public/my-admin-app
```

Write commands (`add`, `update`, `delete`) target API-managed entities only, so they require canonical IDs. `get`, `list`, and `diff` work with either form because both exist in the runtime config.

**Strict create vs update — no upsert at the single-entity surface.** `add` maps to `POST` (create-only, 409 on conflict) and `update` maps to `PUT` (update-only, 404 on missing). The CLI never falls back from one to the other automatically — a `dial-cli model update models/public/gpt4` with a missing dash exits `4` instead of silently creating a stub. Bulk upsert (create-or-update by desired-state) lives only on `dial-cli apply -f` (which the server processes via `POST /v1/admin/apply`); use `apply` when you want kubectl-style "I don't care if it exists, make the world look like this" semantics. See [`03-api-reference.md`](03-api-reference.md) §1 for the wire-protocol contract and §3 for the 404 / 409 / 412 error mapping.

**Exit codes.** The CLI's complete exit-code contract for CI/CD pipelines lives in [`06-cli-user-guide.md`](06-cli-user-guide.md) §2.8 — `0` (success / nothing to apply), `1` (partial-batch runtime failure), `2` (validation failure), `3` (auth failure), `4` (404 — entity not found), `5` (409 — entity exists), `6` (412 — stale ETag). Per-resource-type commands and `apply` share the same code mapping.

**Commands per resource type:**

| Command | HTTP | Description |
|---------|------|-------------|
| `list` | `GET` | List all resources of this type. Alias: `dial-cli get <plural>` (kubectl-style; e.g., `dial-cli get models` ≡ `dial-cli model list`). The user guide ([`06-cli-user-guide.md`](06-cli-user-guide.md) §2.2) prefers the alias for listing because it reads more naturally; both are accepted. |
| `get <name>` | `GET` | Get details of a specific resource |
| `add [flags] [--template <t>] [--param k=v]` | `POST` | Create-only — exits `5` (409 Conflict) if entity already exists. No silent overwrite. |
| `update <name> [flags] [--if-match <etag>]` | `PUT` | Update-only — exits `4` (404 Not Found) if entity does not exist. Optional `--if-match <etag>` for optimistic concurrency (exit `6` on 412 Precondition Failed). No silent stub creation on a typo. **Retry semantics.** `update --set` performs a single GET → local merge → PUT and exits `6` on `412` without automatic retries — the CLI never retries-on-conflict implicitly. Operators who need retry-on-conflict should either pass `--if-match` inside an explicit shell loop or use `apply -f` with a full spec, which goes through the `POST /v1/admin/apply` upsert path. |
| `delete <name> [--if-match <etag>]` | `DELETE` | Delete — exits `4` if entity does not exist. |

> **`settings update` exception to the strict-update contract.** `dial-cli settings update` maps to `PUT /v1/settings/platform/global`, which is upsert by nature — see [`03-api-reference.md`](03-api-reference.md) §1. Unlike per-entity `update`, `settings update` cannot return `404` on first-time use; the exit-`4` mapping in this row does not apply to the singleton. All other exit codes (`0`, `2`, `3`, `6`) apply unchanged.
>
> **`settings get` takes no name argument.** Because the singleton has exactly one instance, `dial-cli settings get` is invoked without a name and is equivalent to `dial-cli get settings --env <env>` (kubectl-style alias). Both forms hit `GET /v1/settings/platform/global`. The server returns the **effective projection** — API blob if present, else file-sourced fields, else schema defaults — never `404`, so `settings get` always succeeds with exit `0` on a healthy environment. The `source` field on the response (`"api"` | `"file"` | `"default"`) discloses which projection won. See [`06-cli-user-guide.md`](06-cli-user-guide.md) §2.4 for the worked example.
>
> **`settings reset` releases API control.** `dial-cli settings reset --env <env>` maps to `DELETE /v1/settings/platform/global` and clears the API blob; subsequent `settings get` returns the file-sourced (or default) projection. Idempotent — exits `0` whether or not a blob was present. Optional `--if-match <etag>` for concurrent-edit protection (exit `6` on `412`). `settings delete` is a synonym; the table row above's exit-`4` mapping does not apply to the singleton — the URL conceptually always exists.
| `validate [--name <n>]` | `POST /v1/admin/validate` | Validate resource configuration |
| `promote --from <env> --to <env> --name <n> [--template <t>\|auto] [--param k=v]` | `GET` (source) + `POST /v1/admin/apply` (target) | Promote between environments. The CLI fetches the source entity, transforms env-specific fields, and submits a single-entity manifest to `POST /v1/admin/apply` — the canonical upsert path — which avoids the GET-then-decide TOCTOU race a client-side POST/PUT split would re-introduce. |
| `diff --source <env> --target <env> [--name <n>]` | `GET` × 2 | Diff between environments |

**Top-level commands:**

| Command | Phase | Description |
|---------|-------|-------------|
| `dial-cli apply -f <path>` | Phase 4 | Apply resource manifests (declarative) — uses `POST /v1/admin/apply`, see [`03-api-reference.md`](03-api-reference.md) §7 |
| `dial-cli export --env <env>` | Phase 1 | Export full environment to files — uses read-only `GET /v1/admin/export` |
| `dial-cli diff --source <env> --target <env>` | Phase 1 | Diff all resources between environments — read-only, uses `GET` on both envs |
| `dial-cli audit --env <env> [filters]` | Phase 7 — deferred | Query audit log |
| `dial-cli env list` | Phase 1 | List configured environments |
| `dial-cli env current` | Phase 1 | Print the currently selected environment |
| `dial-cli env use <name>` | Phase 1 | Persist `defaults.env` in `~/.dial-cli/config.yaml` (kubectl `use-context` analog). Subsequent commands omit `--env` unless overridden. |
| `dial-cli env check --env <name>` | Phase 1 | Probe API URL + credential resolution for a profile |
| `dial-cli completion [bash\|zsh\|fish]` | Phase 1 | Shell completion |

**Per-resource-type commands by phase.** `list` / `get` ship in Phase 1 (read-only). `add` / `update` / `delete` / `validate` / `promote` / `diff` ship in Phase 2 for `model` and in Phase 3 for the remaining types (`application`, `toolset`, `interceptor`, `role`, `key`, `route`, `schema`, `settings`, `file`, `prompt`, `conversation`). The Phase column above tracks delivery for the top-level commands only.

**Why no `dial-cli auth login` in Phase 2–3.** With API-key-only authentication, a `login` command would be a wrapper over the env-var/keystore precedence chain in §1 credential resolution — no session token to issue, no OIDC device flow to exchange, no JWT refresh to orchestrate. `env use` covers "pick an env and stop re-typing it". `auth login` becomes first-class once OIDC/user-JWT lands (D4, OQ-19). Until then the CLI deliberately avoids a ceremonial command that cannot do anything real.

**Update ergonomics without PATCH.** Phase 2–3 API exposes `POST` (create), `PUT` (full update), and `DELETE` only — see [`03-api-reference.md`](03-api-reference.md) §1. The CLI provides field-level update UX via `--set` flags:

```bash
dial-cli model update models/public/gpt-4 --set pricing.prompt=0.0000025
# CLI internally: GET → local merge → PUT
```

This keeps the wire protocol simple while preserving operator ergonomics.

## 2. Environment Profile Configuration

> **Framing — what this file is.** `~/.dial-cli/config.yaml` is operator-side input metadata, not configuration data DIAL Core serves. The kubeconfig (`~/.kube/config`) and Terraform `*.tfvars` analogy applies — there is nothing to synchronize between this file and DIAL Core's runtime Config because the two sides hold different kinds of data: this file holds *how to talk to DIAL and how to compose manifests*; DIAL holds *the entities the API serves*. Templates are resolved at write time (stamped, see §3.4 and OQ-29 in [`08-open-questions-and-references.md`](08-open-questions-and-references.md)) — the rendered output lands in DIAL, the template definition stays operator-side. Editing a template later does not retroactively change anything DIAL serves. The only synchronization concern is operator-to-operator (two operators with diverging local copies), addressed in [`06-cli-user-guide.md`](06-cli-user-guide.md) §1.2 and by `promote --template auto` (§4) — *not* CLI-to-DIAL.

The CLI config (`~/.dial-cli/config.yaml`) separates three concerns:

- **Connection** — how to reach DIAL Core in each environment (`api_url`, `auth`).
- **Variables** (`vars`) — environment-specific values that get substituted into templates and manifests.
- **Templates** — reusable field patterns, entity-type-agnostic, that the CLI deep-merges into entity specs.

For the full operator-facing walkthrough and a complete multi-environment example, see [`06-cli-user-guide.md`](06-cli-user-guide.md) §1.2. The minimal shape relevant to the design is:

```yaml
# ~/.dial-cli/config.yaml (excerpt — one env, one template)
defaults:
  output: table
  env: dev

environments:
  dev:
    api_url: "https://dial-core.dev.dial.parts"
    auth: { type: api_key, key_env_var: DIAL_DEV_API_KEY }
    vars:
      adapter_host_bedrock: "http://dial-bedrock.dial.svc.cluster.local.:80"
      icon_base_url: ""
      forward_auth_token: "false"

templates:
  bedrock-chat:
    description: "AWS Bedrock model via dial-bedrock adapter"
    fields:
      endpoint: "${vars.adapter_host_bedrock}/openai/deployments/${entity.name}/chat/completions"
      forwardAuthToken: "${vars.forward_auth_token}"
      upstreams:
        - endpoint: "${vars.adapter_host_bedrock}/openai/deployments/${entity.name}/chat/completions"
          extraData:
            region: "${params.region}"
```

**Design principles (these drive the implementation):**
- `vars` block holds **all** environment-specific values — adding a new variable is one line, no schema change.
- `templates` are **entity-type-agnostic** — the same mechanism works for models, interceptors, applications, toolsets, or any entity with environment-specific fields.
- `fields` is a **generic overlay** — any JSON fields can be templated, not a fixed schema. The CLI deep-merges template `fields` into the entity spec.
- Three substitution namespaces: `${vars.*}` (from environment), `${params.*}` (from manifest/CLI args), `${entity.*}` (from entity metadata — `name`, `type`).

## 3. Template Resolution

Templates are resolved by the CLI at write time. The server never sees templates — it receives fully resolved entity JSON. This keeps the API surface unchanged and makes templates a pure CLI-side ergonomic.

### 3.1 Substitution namespaces

| Namespace | Source | Example |
|-----------|--------|---------|
| `${vars.*}` | Environment profile `vars` block | `${vars.adapter_host_bedrock}` → `http://dial-bedrock.dev.svc...` |
| `${params.*}` | `--param` CLI flags or manifest `params` block | `${params.region}` → `us-east-1` |
| `${entity.*}` | Entity metadata | `${entity.name}` → `anthropic.claude-sonnet-4-6` |
| `${SECRET:*}` | Secret store (env var, vault — see OQ-19) | `${SECRET:openai-key}` → resolved at apply time |

**Resolution example.** `dial-cli model add --template bedrock-chat --param region=us-east-1 --env dev`:

```
Template fields.endpoint:
  "${vars.adapter_host_bedrock}/openai/deployments/${entity.name}/chat/completions"
Resolved:
  "http://dial-bedrock.dial.svc.cluster.local.:80/openai/deployments/anthropic.claude-sonnet-4-6/chat/completions"
```

### 3.2 Composition: `extends` and `includes`

A template can build on other templates. Two axes of reuse:

- **`extends: <name>`** — single-parent inheritance. The parent's `fields` block is evaluated first; the child merges on top (deep-merge, child wins).
- **`includes: [<name>, ...]`** — mixin composition. Each listed template's `fields` is merged in order; later mixins win over earlier ones; the current template's own `fields` wins over all includes.

Effective merge order per template, top to bottom (each step deep-merges and overrides the previous):

1. `extends` chain, resolved outer-most first.
2. `includes`, in listed order.
3. The template's own `fields` block.
4. At apply time, the entity `spec` block (see §3.5 for per-entity merge).

Cycles are rejected at parse time with a named error (`A extends B extends A`).

```yaml
templates:
  # Base for any chat model — common features, no env-specific bits
  chat-base:
    description: "Common chat-model feature set"
    fields:
      type: chat
      features:
        systemPromptSupported: true
        toolsSupported: true
        streamingSupported: true

  # Mixin — forward auth header when the env enables it
  forward-auth-when-enabled:
    fields:
      !if "${vars.forward_auth_token} == 'true'":
        forwardAuthToken: true

  # Concrete adapter template — inherits chat defaults, mixes in auth forwarding
  bedrock-chat:
    extends: chat-base
    includes: [forward-auth-when-enabled]
    fields:
      endpoint: "${vars.adapter_host_bedrock}/openai/deployments/${entity.name}/chat/completions"
      upstreams:
        !for { in: "${params.regions}", as: region }:
          - endpoint: "${vars.adapter_host_bedrock}/openai/deployments/${entity.name}/chat/completions"
            extraData:
              region: "${region}"
```

### 3.3 Control flow and functions

Two YAML tags give the template language enough control flow to cover realistic env-driven config without becoming a full programming language.

> **Implementation note — custom YAML tag handlers required, two strategies.** `!if` and `!for` are non-standard YAML — they appear as mapping keys in the template DSL, which standard SnakeYAML treats as opaque tagged keys. The CLI uses one of two concrete strategies (Phase 4 implementation decision): **(i) pre-parse rewrite** — the template loader rewrites `!if <expr>:` and `!for { ... }:` lines into sentinel string keys (e.g. `__if__: <expr>`, `__for__: { ... }`) before handing the document to standard SnakeYAML, then post-processes the parsed tree to expand the sentinels into structured records during template resolution; **(ii) custom SnakeYAML `Constructor`** — register a `Constructor` subclass that recognises the tagged-key nodes during parse and emits structured records directly into the parsed tree. Strategy (i) is simpler (a string-level pre-processor + a tree-walk post-processor; standard parser unchanged) but loses YAML source position information for the rewritten lines. Strategy (ii) is cleaner (no source-text rewriting; positions preserved) but requires more SnakeYAML internals knowledge and tighter library coupling. Pick one in the Phase 4 ADR and document the choice + handler in the Phase 4 implementation notes.

**`!if <expr>`** — conditional field inclusion. Attached as a mapping whose child fields are only emitted when the expression evaluates to truthy. Supported operators: `==`, `!=`, `&&`, `||`, `!`; operands are literals or `${...}` placeholders.

```yaml
fields:
  !if "${vars.icon_base_url} != ''":
    iconUrl: "${vars.icon_base_url}/icons/${entity.name}.svg"
```

**`!for { in: <list>, as: <var> }`** — array comprehension. Expands the child value (scalar, map, or list) once per element of the input list, binding `${<var>}`. Nested `!for`/`!if` are allowed.

```yaml
fields:
  upstreams:
    !for { in: "${params.regions}", as: region }:
      - endpoint: "${vars.adapter_host_bedrock}/openai/deployments/${entity.name}/chat/completions"
        extraData:
          region: "${region}"
```

**Function set (small and fixed).** Inside any `${...}` placeholder, a restricted set of functions is available. The set is deliberately small so the grammar stays easy to validate and port between Java (CLI) and the Admin MCP if ever needed:

| Function | Purpose | Example |
|----------|---------|---------|
| `default(value, fallback)` | Return fallback when value is missing/empty | `${default(params.region, 'us-east-1')}` |
| `lower(s)` / `upper(s)` | Case conversion | `${lower(entity.name)}` |
| `trim(s)` | Strip surrounding whitespace | `${trim(vars.icon_base_url)}` |
| `join(list, sep)` | Join list into string | `${join(params.regions, ',')}` |
| `base64(s)` | Base64-encode a string | `${base64(SECRET:openai-key)}` |
| `replace(s, from, to)` | Literal string replace | `${replace(entity.name, '.', '-')}` |

Custom functions are explicitly out of scope — any richer expression need is the signal to reopen OQ-30 (expression-language templating) rather than widen this set.

### 3.4 Resolution semantics: stamped, not live

Template resolution is **stamped at write time**, not a live link (see [OQ-29 in `08-open-questions-and-references.md`](08-open-questions-and-references.md) for the decision record and the rejected live-link alternative). The implications are:

- The persisted entity in DIAL Core is fully self-contained. Inspecting the runtime config from the API or Admin UI shows concrete values, never `${...}` placeholders or template references.
- Editing a template in `~/.dial-cli/config.yaml` affects only **future** writes. Existing entities continue to serve whatever was stamped into them. To propagate a template change to all consumers, re-apply the manifests (or re-`promote`).
- Audit records *(once Phase 7 ships)* are snapshots of the stamped entity, not the template. There is no "blast radius" query of the form "which entities are affected if I change `bedrock-chat`?" — that is intentionally out of scope for Phase 2–4.
- `promote --template auto` is the only mechanism that tries to recover the template linkage, and it remains best-effort (reverse-match; fails on manual edits, as documented in §4).

### 3.5 Merge order (entity apply)

Template `fields` are deep-merged with the entity `spec`. Explicit spec values override template values — the template provides defaults, the spec provides overrides:

```yaml
kind: Model
name: models/public/special-model
template: bedrock-chat
params:
  regions: [us-west-2]
spec:
  displayName: "Special Model"
  endpoint: "http://custom-endpoint/v1/chat"  # ← overrides template endpoint
  # upstreams come from template (not overridden)
```

The full per-entity resolution pipeline is therefore: `extends` chain → `includes` → template's own `fields` → `!if`/`!for` expansion with `${...}` substitution → deep-merge into `spec` (spec wins).

## 4. Promotion Logic

`dial-cli promote --from dev --to uat --name models/public/claude-sonnet`:

**Three `--template` modes:**

| `--template` value | Behavior | Use case |
|---|---|---|
| *(omitted)* | Copy entity as-is — no field transformation. Warn if entity contains hostnames matching source env's `vars`. | Roles, schemas, keys, routes — entities without env-specific fields. Or operator knows fields are already correct. |
| `bedrock-chat` *(explicit)* | Fetch entity spec from source. Re-resolve template fields using target env's `vars` + `--param` values. Non-template fields (displayName, features, pricing) carried from source unchanged. | Standard model promote — operator specifies the template. |
| `auto` | Fetch entity from source. Reverse-match field values against all templates resolved with source env's `vars`. If exactly one template matches → use it. If zero or multiple → fail with suggestions. | Convenience — best-effort auto-detection. May fail for manually edited entities. |

**Promote workflow:**

1. **Fetch** entity from source environment via `GET /v1/{type}/{resourcePath}`.
2. **Determine template:**
   - If `--template` omitted → skip to step 5 (as-is copy with warning check).
   - If `--template auto` → reverse-match against templates (see below).
   - If `--template <name>` → use specified template.
3. **Resolve** template `fields` against target environment's `vars` + `--param` values.
4. **Merge** resolved template fields into source entity spec (template fields replace, non-template fields preserved).
5. **Warn** if any field value contains a hostname from source env's `vars` that wasn't transformed:
   ```
   WARN: Entity 'models/public/claude-sonnet' field 'endpoint' contains hostname
   'dial-bedrock.dev.svc.cluster.local' matching source environment 'dev'.
   Consider using --template to transform env-specific fields.
   Proceeding with as-is copy.
   ```
6. **Validate** transformed entity via `POST /v1/admin/validate` on target environment.
7. If `--dry-run` → output transformed JSON/YAML and exit.
8. **Apply** to target environment via `POST /v1/admin/apply` with a single-entity manifest. This is the canonical upsert path (see [`03-api-reference.md`](03-api-reference.md) §1, §7) and is used here deliberately rather than a client-side GET-then-POST/PUT split — picking `POST` vs `PUT` from a prior read introduces a TOCTOU race (the entity could be created or deleted between the read and the write) that the strict per-entity create/update split exists to avoid.

**`auto` reverse-match algorithm:**

```
For each template in config:
  1. Resolve template.fields against SOURCE env vars (with entity.name from the fetched entity)
  2. Compare resolved field values against actual entity field values
  3. If all template fields match → candidate
If exactly one candidate → use it, extract params by reverse-substitution
If zero candidates → ERROR: "No template matches. Use --template <name> explicitly."
If multiple candidates → ERROR: "Multiple templates match: [list]. Use --template <name> explicitly."
```

## 5. Manifest File Format

Manifests declare entities with optional template references. Templates are resolved by the CLI before sending to the server. The format is intentionally simpler than Kubernetes-style YAML — no `apiVersion`, no `metadata` wrapper. The `kind` + `name` + `spec` structure aligns directly with the API `POST /v1/admin/apply` payload, eliminating transformation between CLI and API formats.

### 5.1 Single-entity manifests

The full set of valid `kind` values, their URL-segment mapping, and the corresponding overlay variants (used in §5.2) are tabled in [`03-api-reference.md`](03-api-reference.md) §7. Unknown `kind` is a strict validation failure — the CLI rejects at parse time, the server returns `400` on `POST /v1/admin/apply`.

```yaml
# manifests/models/claude-sonnet.yaml
kind: Model
name: models/public/anthropic.claude-sonnet-4-6
template: bedrock-chat             # ← links to template from config.yaml
params:                            # ← template-specific parameters
  region: us-east-1
spec:
  type: chat
  displayName: "Anthropic Claude Sonnet 4.6"
  iconUrl: "${vars.icon_base_url}/icons/anthropic.svg"
  # endpoint and upstreams come from template — no need to specify here
  userRoles: ["basic", "power-user"]
  features:
    toolsSupported: true
---
kind: Interceptor
name: interceptors/platform/content-filter
template: internal-interceptor     # ← same template mechanism, different entity type
spec:
  displayName: "Content Filter"
  # endpoint comes from template
---
kind: Role
name: roles/platform/basic
# No template — roles don't have env-specific fields
spec:
  limits:
    anthropic.claude-sonnet-4-6:
      minute: "100000"
      day: "10000000"
  costLimit:
    day: 50.00
---
kind: Key
name: keys/platform/proxyKey1
spec:
  project: "Project1"
  roles: ["basic"]
  secured: false
```

**Variable resolution order:**

1. `${vars.*}` → from environment profile `vars` block in `config.yaml`
2. `${params.*}` → from manifest `params` block or CLI `--param` flags
3. `${entity.*}` → from entity metadata (`name`, computed by CLI)
4. `${SECRET:key-name}` → from shell environment variables (extensible to vault in later phases — see OQ-19 in [`08-open-questions-and-references.md`](08-open-questions-and-references.md))
5. `${ENV_VAR}` → fallback to shell environment variables (for CI/CD pipelines)

### 5.2 Environment overlays (base + overlay)

Templates handle **intra-environment** patterns (how a bedrock-chat model looks in *this* env). They do not cleanly handle **cross-environment** differences that are outside the template's scope — e.g. a model whose `pricing.prompt` is lower in prod than in dev, a role whose rate-limits differ per env, or an entity that is enabled in uat but disabled in prod. Templating every such field through `${vars.*}` inflates the `vars` block and buries what is actually different behind a layer of indirection.

Overlays split the manifest tree into a shared **base** and per-env **overlay** trees:

```
manifests/
├── base/
│   ├── models/claude-sonnet.yaml
│   └── roles/basic.yaml
└── overlays/
    ├── dev/
    │   └── roles/basic.yaml          # looser rate-limits in dev
    ├── uat/
    │   └── models/claude-sonnet.yaml # different pricing / region in uat
    └── prod/
        ├── models/claude-sonnet.yaml
        └── models/claude-sonnet.disable  # marker — exclude this entity in prod
```

**Overlay manifest format:**

```yaml
# overlays/uat/models/claude-sonnet.yaml
kind: ModelOverlay
target: models/public/claude-sonnet   # canonical ID of entity in the base manifest
patch:                                # JSON Merge Patch (RFC 7396) applied on top of base.spec
  pricing:
    prompt: 0.0000025
  params:                             # optional — override template params for this env
    regions: [us-west-2]
```

**Apply semantics:**

```bash
# Resolve base+overlay into effective manifests, then apply
dial-cli apply -f manifests/base/ --overlay manifests/overlays/uat/ --env uat

# Dry-run shows the fully merged YAML before it hits the server
dial-cli apply -f manifests/base/ --overlay manifests/overlays/uat/ --env uat --dry-run
```

Resolution pipeline per entity:

1. Load the base manifest (`kind`, `name`, `template`, `params`, `spec`).
2. If an overlay targets the same `name`, apply its `patch` as a JSON Merge Patch over `spec` (and merge its `params` over the base `params`).
3. Resolve template `extends`/`includes`/`fields` against target env `vars` + (possibly overlay-modified) `params` — as §3.
4. Deep-merge resolved template fields into the patched `spec` (spec wins).
5. Hand the resulting entity to `POST /v1/admin/apply`.

An overlay file with the suffix `.disable` (e.g. `models/claude-sonnet.disable`) removes the targeted entity from the effective set for that environment — useful when a model ships in dev/uat but is gated out of prod. **Marker file format and matching algorithm:** the `.disable` suffix replaces the source manifest's normal extension (`.yaml` / `.yml` / `.json`). The matching algorithm is mechanical and deterministic: (1) take the base file's filename, strip its **last `.`-separated suffix** to get the base stem; (2) strip `.disable` from the marker filename to get the marker stem; (3) the two stems must be byte-for-byte equal; (4) the relative directory path from the overlay root must equal the relative directory path from the base root. Examples:

| Base file (under `base/`) | Disable marker (under `overlays/<env>/`) | Match? |
|---|---|---|
| `models/claude-sonnet.yaml` | `models/claude-sonnet.disable` | yes |
| `models/anthropic.claude-sonnet-4-6.yaml` | `models/anthropic.claude-sonnet-4-6.disable` | yes — base stem after stripping last suffix is `anthropic.claude-sonnet-4-6`; marker stem before `.disable` is `anthropic.claude-sonnet-4-6` |
| `models/anthropic.claude-sonnet-4-6.yaml` | `models/anthropic.claude-sonnet-4-6.yaml.disable` | no — marker stem is `anthropic.claude-sonnet-4-6.yaml`, does not match base stem |
| `models/claude-sonnet.yaml` | `applications/claude-sonnet.disable` | no — relative directory paths differ |

The marker file is **always empty** (zero bytes); any non-empty content is rejected by `dial-cli` with a clear error before resolution begins. There is no other recognized disable mechanism (no `disabled: true` field on the entity, no special directory) — the marker convention is the single, mechanical signal so dry-run and diff outputs can name it precisely.

**Edge case — base filename with no `.`-separated suffix.** When the base file's filename contains no `.` at all (e.g. an extension-less file under `base/`), the "strip last `.`-separated suffix" step in (1) above is a no-op: the base stem equals the full filename. The corresponding disable marker therefore appends `.disable` directly to that filename. Example: a base file `models/my-model` (no extension) has stem `my-model` and matches a marker `models/my-model.disable`.

**Why this pays off for promote and diff.** Because the base is common by construction, `dial-cli diff --source dev --target uat` can be rendered as the diff between the two overlay trees (plus any drift between live envs and the rendered base). Promoting an entity is then "apply source base + target overlay" — the symmetric, declarative equivalent of the imperative `promote` command in §4. Entities that have identical behaviour in both envs live only in the base and require no overlay file at all, which is the best ergonomics we can offer for the common case.

**Relationship to `promote`.** `promote` (§4) remains the imperative, single-entity, ad-hoc path. Overlays are the declarative, multi-entity, repo-tracked path. Both are first-class; teams will likely use `promote` for exploratory work and overlays for CI-driven rollouts.

### 5.3 Bundle manifests

A bundle groups several entities that share a `params` scope so that operationally-coherent units (e.g. "onboard a new LLM" = model + role rate-limits + key + route) can be parameterised once and applied atomically. Bundles are pure CLI-side sugar: the CLI expands a bundle into its constituent entity manifests before sending to `POST /v1/admin/apply`. The server never sees the `Bundle` kind. Dependency ordering from OQ-6 (`globalSettings → schemas → interceptors → roles → keys → routes → models → toolsets → applications`) applies to the expanded set.

```yaml
# manifests/bundles/onboard-claude-sonnet.yaml
kind: Bundle
name: onboard-claude-sonnet
params:
  model_name: anthropic.claude-sonnet-4-6
  regions: [us-east-1, us-west-2]
  rate_limit_minute: "100000"

entities:
  - kind: Model
    name: "models/public/${params.model_name}"
    template: bedrock-chat
    params:
      regions: "${params.regions}"
    spec:
      displayName: "Claude Sonnet 4.6"
      features: { toolsSupported: true }

  - kind: Role
    name: roles/platform/basic
    # `patch` semantics: apply JSON Merge Patch rather than full replacement —
    # a bundle should not clobber unrelated fields on a shared role.
    patch:
      limits:
        "${params.model_name}":
          minute: "${params.rate_limit_minute}"

  - kind: Key
    name: "keys/platform/${params.model_name}-ci"
    spec:
      project: "CI"
      roles: [basic]
      secured: false
```

Apply:

```bash
dial-cli apply -f manifests/bundles/onboard-claude-sonnet.yaml --env uat \
  --param model_name=anthropic.claude-sonnet-4-6 \
  --param 'regions=[us-east-1,us-west-2]'
```

**Parameter scoping.** A bundle's `params` are visible to every entity inside it and override any same-named `params` an entity declares. Entities inside a bundle may still declare their own `params` for template resolution (e.g. `regions: "${params.regions}"` above — the outer bundle param is re-exposed as the model-level param).

**`patch` vs `spec` inside bundles.** A bundle entity can declare either `spec:` (full replacement, same as a single-entity manifest) or `patch:` (JSON Merge Patch applied to the entity's current state on the target env — CLI internally does GET → merge → expand into a full `spec:` before the apply payload is sent; the server's `POST /v1/admin/apply` only accepts full `spec:` entries). `patch:` is the common case when a bundle adjusts shared entities like roles or global settings without wanting to overwrite unrelated fields.

**`patch:` GET → 404 fallback semantics.** When the GET step against the target environment returns `404` (the entity does not yet exist on that environment), the CLI treats the missing base as `{}` and applies the patch to that empty object, producing a `spec:` with only the patched fields populated. The expanded entry is handed to `POST /v1/admin/apply` as a regular entity entry. The apply endpoint uses upsert semantics — it creates the entity when absent and updates it when present, regardless of how the CLI generated the spec. The bundle's GET-then-merge step is a CLI-side concern; the server sees only a fully resolved spec (apply payload entries do not carry HTTP-method directives). This lets a bundle initialize a new shared entity in a single apply on a fresh environment without a separate "create-then-patch" two-step. Note: an entity created from an empty base may fail server-side validation if required fields are absent — `precheck: true` (default) surfaces this before any mutation, so the bundle either lands cleanly or aborts at the precheck gate without partial application. Operators who need a non-empty base on first-time apply should use `spec:` rather than `patch:` for that entity. Under `softValidation: true` + `precheck: false`, an entity created from an empty base and missing required fields is persisted to blob with `status: 'invalid'`. Operators using soft validation should use `spec:` rather than `patch:` for entities that may not exist on the target environment.

**Race contract for `patch:` on shared entities — known sharp edge, no detection.** Because the GET → merge → full-`spec:` expansion happens client-side and `POST /v1/admin/apply` does not accept `If-Match` per entity, two bundles patching the same entity (e.g. two different rollouts both touching `roles/platform/basic`) race silently with last-write-wins: the second bundle's GET observes a state that may already be stale by the time its full-`spec:` apply lands, and the merged value overwrites the first bundle's change with no error returned to either caller. The ETag captured during the GET is captured for client-side display only — the CLI does not pass it through on the apply call (the apply payload schema has no per-entity ETag field), so even when the stored ETag has moved between the GET and the apply, the apply still succeeds. **Hard contract: concurrent bundle `patch:` on the same entity silently overwrites; the system does not detect the collision.** Use `patch:` **only** for entities a single bundle owns. For any entity shared across bundles (a common shared role, `globalSettings`, an interceptor referenced from multiple rollouts) write a full `spec:` entry instead — the bundle then declares its full intended state for that entity, and a typo or out-of-date duplicate surfaces directly through the apply per-entity result rather than as silent data loss on a different team's rollout. This is a deliberate trade-off: the simpler apply-payload schema (no per-entity ETag plumbing) in exchange for an operator-discipline rule that is enforced by docs, not by the wire.

**Validation.** `dial-cli validate -f <bundle>.yaml` expands the bundle locally, runs CLI-side schema checks, then calls `POST /v1/admin/validate` with the expanded set under OQ-28's `precheck: true` option so that cross-references inside the bundle (model → interceptor, role → model, etc.) are checked against the proposed-config state rather than live config.

## 6. Technology Stack

**Decided: Java** (Picocli + Quarkus Command Mode + GraalVM native image).

| Component | Role | License |
|-----------|------|---------|
| **Picocli** | CLI framework — annotation-based commands, options, shell completion, ANSI output | Apache 2.0 |
| **Quarkus** (Command Mode) | Lightweight DI, config, HTTP client — no web server, just CLI entry point | Apache 2.0 |
| **GraalVM / Mandrel** | Native image compilation — ~3ms startup, single static binary, ~30–50MB | GPL v2 + Classpath |
| **DIAL Core `config/` module** | Direct Gradle dependency — reuses Config, Model, Deployment, Role, Key, Route classes and Jackson serialization | Apache 2.0 |

**Key benefit — code sharing with DIAL Core:**
- The `config/` module contains every entity data class (Config, Model, Application, ToolSet, Interceptor, Role, Key, Route) with all Jackson annotations.
- The CLI uses these classes directly — no reimplementation, no serialization mismatches.
- Validation logic can be shared between DIAL Core's Configuration API and the CLI's `--validate` / `--dry-run`.
- The team already knows Java — no new language to learn.

**Distribution:**

| Channel | Method |
|---------|--------|
| GitHub Releases | Pre-built native binaries for linux/amd64, linux/arm64, darwin/amd64, darwin/arm64, windows/amd64 |
| Homebrew | `brew install epam/tap/dial-cli` |
| Docker | `docker run ghcr.io/epam/dial-cli get models --env prod` |
| JBang | `jbang dial-cli@epam get models` (JVM fallback for platforms without native image) |

See `dial-cli-technology-analysis.md` for the full technology comparison and rationale.

---

## Next

- DevOps-facing user guide with worked examples: [`06-cli-user-guide.md`](06-cli-user-guide.md)
- API contract the CLI calls: [`03-api-reference.md`](03-api-reference.md)
- Rollout phases and scope: [`07-migration-and-rollout.md`](07-migration-and-rollout.md)
