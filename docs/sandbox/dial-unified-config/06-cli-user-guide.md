# 06 — `dial-cli` User Guide

> **Audience:** DevOps / Platform engineers (internal & external teams) who will use `dial-cli`.
> **Reading time:** ~20 minutes.
> **Prerequisites:** None — this is the operator-facing doc. Skim [`README.md`](README.md) for proposal context.

This is the hands-on guide: installation, configuration, daily commands, CI/CD, and audit queries. For the internal CLI design (command parser, template resolver, etc.) see [`05-cli-design.md`](05-cli-design.md).

Feedback questions Q1–Q13 and decisions D1–D9 from the review round are preserved inline — comment there or email the design team.

---

## Why This Tool?

Today, managing DIAL configuration means hand-editing `aidial.config.json`, pushing it via Helm/kubectl, and waiting 60–180+ seconds for DIAL Core to reload. There's no way to inspect the current runtime state from the command line, no diff between environments, and no automation-friendly interface for CI/CD.

`dial-cli` is a kubectl-inspired CLI tool that talks directly to the Configuration API in DIAL Core. It gives you:

- **Read the runtime state** — see exactly what DIAL Core has loaded right now.
- **Add/update/delete entities** — models, roles, keys, interceptors, routes, toolsets, applications, schemas, global settings.
- **Promote between environments** — move a model from dev → uat with template-based field re-resolution.
- **Diff environments** — see what's different between dev and prod in one command.
- **Declarative apply** — `kubectl apply -f` style workflow from YAML/JSON manifests.
- **Dry-run & validate** — preview any mutation before it hits the cluster.
- **Audit log** *(deferred — Phase 7)* — who changed what, when, with full state snapshots and rollback.
- **CI/CD native** — runs in pipelines, supports non-interactive auth, exit codes for scripting.

Changes take effect **immediately on the writer pod** (the replica that handled the write) — no file-watcher polling, no eventual consistency on that pod. Cross-replica propagation is ≤60s in Phase 1 and near-instant after Phase 1.5 (Redis pub/sub). This is materially different from today's Admin-Backend → file export → 60–180s poll chain; it is **not** the same as "every replica sees the change in the same tick".

> **What "immediate" covers, precisely.** The writer-pod's volatile `Config` reference (the in-memory map that backs routing, model resolution, role lookup, interceptor chains, and route matching) is swapped before the HTTP response returns — that is genuinely immediate. Mechanically, the API write path on the writer pod uses `MergedConfigStore.rebuildNow()`, the synchronous entry point that bypasses the debounce; the 500ms trailing-edge debounce applies to `requestRebuild()`, which is used by the file-poll callback, the pub/sub listener, and the safety-net poll timer ([`02-architecture.md`](02-architecture.md) §4 / §11.1). The `ApiKeyStore`, however, is updated inside the rebuild's `ConfigPostProcessor` step on rebuild paths driven by `requestRebuild()` (debounced ~500ms in Phase 1.5) — so a brand-new key created via `POST /v1/keys/...` would not authenticate any request for ~500ms+ on a stock implementation. Phase 2 wires a per-entity-type fast-path on the keys controller (`ApiKeyStore.addOrUpdateKey` invoked directly after `ResourceService.put` succeeds, before the HTTP response) so that newly created or rotated keys authenticate immediately on the writer pod. Without that fast-path, you would observe a brief authentication gap on freshly minted keys; with it, the "immediate" guarantee covers both routing/lookup (via `rebuildNow()`) and key authentication (via the fast-path) on the writer pod.

**Tech note:** `dial-cli` is distributed as a self-contained native binary (~30–50MB, ~3ms startup) — no JVM required. See [`05-cli-design.md`](05-cli-design.md) §6 for implementer details (language, framework, build pipeline).

> **FEEDBACK Q1:** Does this list cover the pain points you experience today? Is there anything critical missing from your daily DIAL operations workflow?

### What changes for you

| Before | After |
|--------|-------|
| Edit JSON files → Helm upgrade → wait 60s+ | `dial-cli model add --env uat ...` → immediate on the writer pod; ≤60s cross-replica in Phase 1; near-instant after Phase 1.5 (Redis pub/sub) [¹](#propagation-footnote) |
| Manual copy-paste between environments | `dial-cli promote --from dev --to uat --name models/public/...` |
| No visibility into runtime state | `dial-cli get models --env prod -o yaml` |
| No pre-flight validation | `dial-cli apply -f config/ --validate --dry-run` |
| No config diff between envs | `dial-cli diff --source dev --target uat` |
| CI/CD requires Helm values manipulation | `dial-cli apply -f config/ --env $TARGET` |
| No audit trail for config changes | `dial-cli audit history models/public/gpt-4` *(Phase 7)* |

<a id="propagation-footnote"></a>¹ "Immediate" means the writer pod — the replica that processes the write — updates its in-memory `Config` atomically as soon as the API call returns success. Other replicas catch up via the existing `FileConfigStore` poll (≤60s in Phase 1) or the existing `ResourceTopic` Redis pub/sub broadcast (near-instant in Phase 1.5 — `MergedConfigStore` adds one listener to the topic `ResourceService` already publishes on). This is already a large improvement over today's Admin-Backend → file export → 60–180s propagation chain.

**Backward compatibility.** Your existing config files keep working. The new API adds entities alongside file-based ones — config-file entities (simple names like `"gpt-4"`) and API-managed entities (canonical IDs like `"models/public/gpt-4"`) coexist in the same runtime config. There is no "big bang" migration. You migrate entities at your own pace, and the config file remains as a seed/fallback indefinitely. See [`07-migration-and-rollout.md`](07-migration-and-rollout.md) for the phased timeline, and [`02-architecture.md`](02-architecture.md) §10.1 for why we chose coexistence over a one-time migration.

---

## 1. Installation & Setup

### 1.1 Installation

```shell
# Option A: Native binary (no JVM needed) — GraalVM native-image, ~30-50MB
curl -sL https://github.com/epam/dial-cli/releases/latest/download/dial-cli-$(uname -s)-$(uname -m) \
  -o /usr/local/bin/dial-cli && chmod +x /usr/local/bin/dial-cli

# macOS — Homebrew
brew install epam/tap/dial-cli

# Option B: JBang (JVM fallback for platforms without native image)
jbang dial-cli@epam get models --env uat

# Option C: Docker (for CI or air-gapped environments)
docker run ghcr.io/epam/dial-cli get models --env prod

# Verify
dial-cli version
```

Shell completions:

```shell
dial-cli completion bash > /etc/bash_completion.d/dial-cli             # Bash
dial-cli completion zsh > "${fpath[1]}/_dial-cli"                      # Zsh
dial-cli completion fish > ~/.config/fish/completions/dial-cli.fish    # Fish
```

> **FEEDBACK Q2:** Which installation option fits best — native binary, JBang, or Docker? Do you need `deb`/`rpm`, `sdkman`, or anything else?

### 1.2 Configuration File

`dial-cli` uses a YAML config at `~/.dial-cli/config.yaml` (override with `--config <path>` or `DIAL_CLI_CONFIG` env var). The config separates **connection** (how to reach DIAL Core) from **variables** (environment-specific values) and **templates** (reusable entity field patterns).

> This file is operator-side input metadata — think `~/.kube/config` or Terraform `*.tfvars` — not a second source of truth that mirrors DIAL Core's runtime Config. There is nothing to synchronize between the two; templates resolve at write time and the rendered output is what lands in DIAL. See [`05-cli-design.md`](05-cli-design.md) §2 for the framing.

```yaml
# ~/.dial-cli/config.yaml
defaults:
  output: table
  env: dev

environments:
  dev:
    api_url: "https://dial-core.dev.dial.parts"
    auth:
      type: api_key
      key_env_var: DIAL_DEV_API_KEY
    vars:
      adapter_host_bedrock:  "http://dial-bedrock.dial.svc.cluster.local.:80"
      adapter_host_vertexai: "http://dial-vertexai.dial.svc.cluster.local.:80"
      adapter_host_openai:   "http://dial-openai.dial.svc.cluster.local.:80"
      icon_base_url: ""
      forward_auth_token: "false"

  uat:
    api_url: "https://dial-core.uat.dial.parts"
    auth:
      type: api_key
      key_env_var: DIAL_UAT_API_KEY
    vars:
      adapter_host_bedrock:  "http://dial-bedrock.dial.svc.cluster.local"
      adapter_host_vertexai: "http://dial-vertexai.dial.svc.cluster.local"
      adapter_host_openai:   "http://dial-openai.dial.svc.cluster.local"
      icon_base_url: "https://themes.eks.uat.dial.parts"
      forward_auth_token: "false"

  prod:
    api_url: "https://dial-core.prod.dial.parts"
    auth:
      type: api_key
      key_env_var: DIAL_PROD_API_KEY
    vars:
      adapter_host_bedrock:  "http://dial-bedrock.dial.svc.cluster.local"
      adapter_host_openai:   "http://dial-openai.dial.svc.cluster.local"
      icon_base_url: "https://themes.prod.dial.parts"
      forward_auth_token: "true"

# Templates — reusable field patterns for any entity type.
# Templates compose: `extends: <name>` for single-parent inheritance,
# `includes: [<name>, ...]` for mixins. See 05-cli-design.md §3.2 for merge order.
templates:
  # Base — common chat-model shape shared by every provider adapter.
  chat-base:
    description: "Common chat-model feature set"
    fields:
      type: chat
      features:
        systemPromptSupported: true
        toolsSupported: true
        streamingSupported: true

  # Mixin — forward the caller's auth token downstream when the env enables it.
  forward-auth-when-enabled:
    fields:
      !if "${vars.forward_auth_token} == 'true'":
        forwardAuthToken: true

  bedrock-chat:
    description: "AWS Bedrock model via dial-bedrock adapter"
    extends: chat-base
    includes: [forward-auth-when-enabled]
    fields:
      endpoint: "${vars.adapter_host_bedrock}/openai/deployments/${entity.name}/chat/completions"
      upstreams:
        !for { in: "${params.regions}", as: region }:
          - endpoint: "${vars.adapter_host_bedrock}/openai/deployments/${entity.name}/chat/completions"
            extraData:
              region: "${region}"

  vertexai-chat:
    description: "GCP Vertex AI model via dial-vertexai adapter"
    fields:
      endpoint: "${vars.adapter_host_vertexai}/openai/deployments/${entity.name}/chat/completions"
      forwardAuthToken: ${vars.forward_auth_token}
      upstreams:
        - endpoint: "${vars.adapter_host_vertexai}/openai/deployments/${entity.name}/chat/completions"
          extraData:
            region: "${params.region}"

  openai-chat:
    description: "OpenAI model via dial-openai adapter"
    fields:
      endpoint: "${vars.adapter_host_openai}/openai/deployments/${entity.name}/chat/completions"
      forwardAuthToken: ${vars.forward_auth_token}

  internal-interceptor:
    description: "Internal interceptor running alongside adapters"
    fields:
      endpoint: "${vars.adapter_host_openai}/interceptors/${entity.name}"
```

**Key concepts:**

- **`vars`** — generic key-value block for all environment-specific values. Adding a new variable is one line, no schema change.
- **`templates`** — entity-type-agnostic field patterns. The CLI deep-merges template `fields` into the entity spec, substituting placeholders. The server never sees templates — it receives fully resolved JSON.
- **Composition** — `extends:` inherits from a single parent, `includes:` layers in mixins. Effective merge order per template: extends chain → includes (in listed order) → own `fields` → entity `spec` at apply time. Cycles are rejected at parse.
- **Control flow** — `!if <expr>` and `!for { in: <list>, as: <var> }` YAML tags cover env-driven conditionals and per-region expansion without a full expression language. A small fixed function set is available inside `${...}`: `default`, `lower`, `upper`, `trim`, `join`, `base64`, `replace`. See [`05-cli-design.md`](05-cli-design.md) §3 (Template Resolution) for the full function set and tag semantics.
- **Variable namespaces:** `${vars.*}` (env profile), `${params.*}` (CLI `--param` or manifest), `${entity.*}` (auto-computed), `${SECRET:*}` (secret store), `${ENV_VAR}` (shell fallback).
- **Stamped, not live** — templates are resolved CLI-side at write time; the persisted entity contains concrete values, never placeholders or template references. Editing a template affects only future writes. See [`05-cli-design.md`](05-cli-design.md) §3.4.
- **Template sharing is an ops-team decision.** Because resolution is stamped, two operators with different copies of `~/.dial-cli/config.yaml` can stamp different values under the same template name — nothing in the CLI or server prevents this. Teams that want consistency across operators typically check `~/.dial-cli/config.yaml` (or an equivalent shared file) into a git repo and use it as the team source of truth; teams that prefer per-operator flexibility don't. Pick whichever matches your ops practice — the CLI works the same either way. `promote --template auto` (§2.5) is the best-effort mitigation when drift does occur.

> **FEEDBACK Q3 (config structure):**
>
> - Is the `vars` + `templates` approach clear? Better or worse than hard-coded `adapter_hosts` / `icon_base_url` as separate config keys?
> - Template composition (`extends` / `includes`) — do you see yourself using it, or is flat templating enough for your case? Any adapter families where a shared base would pay off?
> - Is the `!if` / `!for` control flow enough, or do you hit cases that would need arithmetic, regex, or custom functions (which would reopen OQ-30)?
> - Overlays vs. bundles — given the cross-env and multi-entity scenarios at the end of §2.7, which would you reach for first? Both? Neither?
> - Are the pre-defined templates sufficient? What other templates would you need?
> - Is `key_env_var` sufficient for auth, or do you need direct token/file/OIDC support?
> - Would you store this config in a shared repo or keep it strictly local?

---

## 2. Commands Reference

### 2.1 Global Flags

| Flag | Short | Description |
|------|-------|-------------|
| `--env <n>` | `-e` | Target environment (overrides `defaults.env`) |
| `--output <fmt>` | `-o` | Output format: `table`, `json`, `yaml` |
| `--config <path>` | | Config file path (default: `~/.dial-cli/config.yaml`) |
| `--api-url <url>` | | Override API URL |
| `--api-key-file <path>` | | Read the API key from a file (for CI secret mounts — GitHub/GitLab file secrets, K8s projected volumes, SOPS-decrypted files) |
| `--verbose` | `-v` | Verbose output |
| `--dry-run` | | Preview changes without applying |

**Credential handling — API keys are never accepted as a command-line flag.** A literal `--api-key <key>` flag would leak the secret to process listings (`ps auxf`, `/proc/<pid>/cmdline`), shell history, CI logs with `set -x`, and `docker ps` / `kubectl describe` output. The CLI only reads the key from (in priority order):

1. The env var named by the profile's `auth.key_env_var` (e.g. `DIAL_UAT_API_KEY`) — the CI default.
2. `--api-key-file <path>` — for CI secret mounts and SOPS-decrypted files.
3. The OS keystore, populated by `dial-cli auth login --env <n> --store` — for interactive developer workstations (macOS Keychain / libsecret / Windows Credential Manager).
4. Interactive no-echo prompt (`readPassword()`-style) when a TTY is attached and no credential was resolved above.

See [`04-security-and-audit.md`](04-security-and-audit.md) §1.6 for the full credential-handling contract.

### 2.2 Read Commands

```shell
dial-cli get models --env uat
dial-cli get roles --env prod
dial-cli get keys --env prod
dial-cli get interceptors
dial-cli get routes
dial-cli get toolsets --env uat
dial-cli get schemas
dial-cli get settings --env prod        # global settings (globalInterceptors, etc.)
```

> **Note on `dial-cli get schemas`.** This lists admin-managed application-type-schema **entities** stored at `public/app_type_schemas/...` and reachable via per-entity CRUD at `/v1/schemas/{bucket}/{name}` (the new Configuration API surface introduced by this proposal). It does **NOT** query the existing meta-endpoint at `/v1/application_type_schemas/(schemas|schema|meta_schema)`, which returns the JSON Schema definitions used to validate application-type bodies. The two endpoints are distinct, with distinct paths and distinct purposes — the new `/v1/schemas/...` route is per-entity CRUD; the existing `/v1/application_type_schemas/...` route is the validation meta-endpoint and remains unchanged. See [`02-architecture.md`](02-architecture.md) §5.3 for the full naming rationale.

Example — `dial-cli get models --env uat`:

```
NAME                                          TYPE   DISPLAY NAME                   SOURCE  STATUS    ENDPOINT
models/public/anthropic.claude-sonnet-4-6     chat   Anthropic Claude Sonnet 4.6    api     valid     http://dial-bedrock/openai/...
models/public/chat-gpt-35-turbo               chat   GPT-3.5 Turbo                  file    valid     http://dial-openai/openai/...
models/public/embedding-ada                   emb    Embedding Ada                  file    valid     http://dial-openai/openai/...
models/public/old-broken-model                chat   Legacy model                   api     invalid   (2 warnings — see `dial-cli model get`)
```

The `STATUS` column distinguishes valid entities from invalid ones. The `STATUS` column appears on listings for **every entity type** — models, roles, schemas, interceptors, routes, keys, applications, toolsets — with one underlying difference in how it's computed:

- For MergedConfigStore-managed entities (models, roles, schemas, interceptors, routes, keys, settings), invalid means *not in the runtime `Config` and not serving traffic* — pre-computed at reload, surfaced for visibility.
- For blob-native entities (applications, toolsets), invalid means *cross-references don't resolve against the current `Config`* — computed lazily on the admin-API read path. **The hot path is unchanged from today's behavior**: an invalid blob app still serves through `findDeployment` and fails at request time on the missing reference (`404` from the interceptor lookup, schema mismatch on schema-rich apps). The listing tells you about the broken state before users do.

Causes of `invalid` are upstream changes (referenced interceptor or schema removed) and version drift after a Core upgrade introduces stricter validation. Direct creation of an invalid entity is rejected by write-time validation. Inspect with `dial-cli model get <id>` (or `application get`, `toolset get`, etc.) or check the operator-facing health surface at `GET /v1/admin/health/config` (which covers MergedConfigStore-managed entities — invalid blob apps surface through listing, not health). See [`02-architecture.md`](02-architecture.md) §4.1–§4.3 for the full failure-and-recovery model.

**Get single entity** (canonical path):

```shell
dial-cli model get models/public/chat-gpt-35-turbo --env uat -o yaml
```

```yaml
name: models/public/chat-gpt-35-turbo
source: file             # "file" = from config file, "api" = from Configuration API
config:
  type: chat
  displayName: GPT-3.5 Turbo
  endpoint: http://dial-openai/openai/deployments/gpt-35-turbo/chat/completions
  upstreams:
    - endpoint: https://host1.openai.azure.com
      key: "***"         # secret fields always masked
      weight: 1
  features:
    systemPromptSupported: true
    toolsSupported: true
  userRoles: [basic, power-user]
```

**Export full environment:**

```shell
dial-cli export --env uat -o yaml > uat-full.yaml
dial-cli export --env uat --type models -o json > uat-models.json
```

> **FEEDBACK Q4 (read commands):**
>
> - Is the `get <plural>` / `<singular> get <path>` pattern intuitive? Or prefer `list` + `describe`?
> - The `source` column shows `file` vs `api` — is this useful?
> - Do you need filtering? e.g. `dial-cli get models --env prod --role power-user`

### 2.3 Diff Commands

```shell
dial-cli diff --source dev --target uat --type models
dial-cli diff --source dev --target uat --type models --name models/public/chat-gpt-35-turbo
dial-cli diff --source dev --target uat    # all entity types
```

```
NAME                                             STATUS
models/public/anthropic.claude-sonnet-4-6        only in dev
models/public/chat-gpt-35-turbo                  different
  ~ endpoint: http://dial-openai.dev.svc:80/... → http://dial-openai.uat.svc/...
  ~ upstreams[0].endpoint: https://dev-host... → https://uat-host...
models/public/embedding-ada                      same
models/public/gemini-2.5-flash                   only in uat
```

> **FEEDBACK Q5:** Is this diff format useful? Prefer unified-diff (`git diff` style), side-by-side, or this summary? Should diff ignore env-specific fields by default?

### 2.4 Write Commands (Imperative)

**Add a model using a template:**

```shell
dial-cli model add \
  --env uat \
  --name "models/public/anthropic.claude-sonnet-4-6" \
  --template bedrock-chat \
  --param region=us-east-1 \
  --set displayName="Anthropic Claude Sonnet 4.6" \
  --set displayVersion="v1" \
  --set iconUrl='${vars.icon_base_url}/icons/anthropic.svg' \
  --set maxTotalTokens=200000 \
  --set pricing.unit=token \
  --set pricing.prompt=0.000003 \
  --set pricing.completion=0.000015
```

`--template` resolves env-specific fields (endpoint, upstreams) from env `vars` + `--param` values. `--set` sets individual fields. Template fields + `--set` fields are deep-merged (explicit `--set` overrides template).

**Update / delete:**

```shell
dial-cli model update models/public/chat-gpt-35-turbo --env uat \
  --set maxTotalTokens=128000 --set 'userRoles=["basic","admin"]'

dial-cli model delete models/public/old-unused-model --env uat
```

**Singleton settings — `get` takes no name argument.** Because there is exactly one global-settings document (the singleton at `/v1/settings/platform/global`), `dial-cli settings get` is invoked without an entity name and is equivalent to `dial-cli get settings --env <env>` (the kubectl-style alias). Both forms hit `GET /v1/settings/platform/global` and return the singleton:

```shell
dial-cli settings get --env prod                  # explicit "get" verb, singleton
dial-cli get settings --env prod                  # alias, identical behavior
dial-cli get settings --env prod -o yaml          # YAML output
```

Pre-bootstrap behavior: until the first `PUT /v1/settings/platform/global` lands, `GET` returns the **default settings document** (empty `globalInterceptors`, default `retriableErrorCodes`) — not `404`. The singleton is conceptually always present; reads on a fresh environment surface the in-memory default rather than an error. Operators do not need to "create" the singleton before reading it.

**Note on `update --set`:** Since the API currently supports PUT (full entity replacement) only, `--set` works by fetching the current entity, merging your changes locally, and PUTting the full result back. ETag-based optimistic concurrency protects against conflicts — if someone else modified the entity between your GET and PUT, you'll get a `412 Precondition Failed` and the CLI exits `6`. **The CLI does not auto-retry on `412`** — a single GET → merge → PUT is one attempt; that's it. If you need retry-on-conflict semantics, wrap `update --if-match` in a shell loop, or use `dial-cli apply -f` with a full-spec manifest (which goes through the canonical `POST /v1/admin/apply` upsert path).

**Strict create vs update (no silent stub creation).** `add` and `update` are intentionally non-overlapping:

- `dial-cli model add ...` → fails with **409 Conflict** (exit `5`) if a model with that name already exists. Use `update` if you meant to modify it.
- `dial-cli model update models/public/gpt4 ...` → fails with **404 Not Found** (exit `4`) if no such model exists. A typo in the name surfaces here instead of silently creating a half-configured stub.
- `dial-cli model delete ...` → fails with **404 Not Found** if missing.

If you want create-or-update behavior in one shot (e.g. CI applying a manifest tree where some entities are new and some exist), use `dial-cli apply -f config/` — that's the canonical declarative path and the only place upsert lives. Optional `--if-match <etag>` on `update`/`delete` adds optimistic concurrency on top.

**Other entity types — same pattern:**

```shell
dial-cli role add --env uat --name roles/platform/power-user --from-file role-spec.yaml
dial-cli key add --env prod --name keys/platform/proxyKey3 --set project=Project2 --set 'roles=["basic"]'
dial-cli interceptor add --env uat --name interceptors/platform/guardrail-1 \
  --template internal-interceptor --set displayName="Content Filter"

# Global settings
dial-cli settings update --env prod --set 'globalInterceptors=["guardrail-1","audit-logger"]'
```

> `settings update` is upsert — it maps to `PUT /v1/settings/platform/global`, which is the one allowed exception to the strict create/update split (the singleton always exists post-bootstrap). It is therefore safe to run on a fresh environment without first calling `add` — there is no `404` path for the singleton and no exit `4` from `settings update`. See [`03-api-reference.md`](03-api-reference.md) §1.

> **FEEDBACK Q6 (write commands):**
>
> - Is `--template` + `--param` + `--set` natural? Or too many flags?
> - Would `dial-cli model clone <source> --name <new>` be useful?
> - For non-model entities — `--from-file` or `--set` flags preferred?
> - Is the canonical ID format (`models/public/...`, `roles/platform/...`) clear, or would you prefer short names?

### 2.5 Promote Between Environments

Three modes:

```shell
# Mode 1: Explicit template — re-resolves env-specific fields against target env vars
dial-cli promote --from dev --to uat \
  --name models/public/gemini-2.5-flash \
  --template vertexai-chat \
  --param region=us-central1

# Mode 2: As-is — no transformation (roles, keys, routes — no env-specific fields)
dial-cli promote --from dev --to uat \
  --name roles/platform/power-user

# Mode 3: Auto-detect — reverse-matches against source env templates
dial-cli promote --from dev --to uat \
  --name models/public/claude-sonnet \
  --template auto

# Always dry-run first
dial-cli promote --from dev --to uat --name models/public/gemini-2.5-flash \
  --template vertexai-chat --param region=us-central1 --dry-run
```

**How template promote works.** Fetch entity from source → strip template-generated fields → re-resolve template against target env's `vars` + `--param` → non-template fields (displayName, features, pricing) carry from source unchanged → validate and apply. See [`05-cli-design.md`](05-cli-design.md) §4 for the full promotion algorithm.

> **FEEDBACK Q7 (promote):**
>
> - Is three-mode promote (as-is / explicit / auto-detect) intuitive? Or overcomplicated?
> - Should `promote` support batch? e.g. `dial-cli promote --from dev --to uat --type models --template auto`
> - Would you trust `--template auto`, or always prefer explicit?

### 2.6 Validate & Dry-Run

```shell
dial-cli model validate --env uat --name "models/public/anthropic.claude-sonnet-4-6"
dial-cli model add --env uat --name "models/public/claude-sonnet" --template bedrock-chat --param region=us-east-1 --dry-run
dial-cli apply -f config/ --env uat --validate --dry-run
```

> **FEEDBACK Q8:** What validation checks matter most? Schema compliance? Endpoint reachability? Cross-reference integrity? Should validate be blocking or optional?

### 2.7 Declarative Apply (Manifest Files)

```shell
dial-cli apply -f dial-config.yaml --env uat
dial-cli apply -f config/ --env prod --validate --diff
```

**Manifest format** — entities with optional template references. The CLI uses a simplified `kind` + `name` + `spec` structure that aligns directly with the API `POST /v1/admin/apply` payload (no `apiVersion`, no `metadata` wrapper):

```yaml
kind: Model
name: models/public/anthropic.claude-sonnet-4-6
template: bedrock-chat
params:
  region: us-east-1
spec:
  type: chat
  displayName: "Anthropic Claude Sonnet 4.6"
  iconUrl: "${vars.icon_base_url}/icons/anthropic.svg"
  userRoles: ["basic", "power-user"]
  features:
    toolsSupported: true
---
kind: Role
name: roles/platform/basic
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

**Apply behavior (decided):**

- **Validate-first gate:** all manifests validated before anything is applied. If any fails, nothing is applied.
- **Continue on runtime failure:** if validation passes but an entity fails during apply, remaining entities still applied. Per-entity results reported.
- **Automatic dependency ordering:** `globalSettings → schemas → interceptors → roles → keys → routes → models → toolsets → applications`.
- **Idempotent summary:** every `apply` prints `created: N, updated: N, unchanged: N, failed: N` so a re-run against an already-applied manifest is visibly distinguishable from a fresh rollout — both exit `0` when nothing failed, but the counts tell you why.
- **Exit codes:** `0` if all entities succeeded (including the "nothing to apply" case); non-zero otherwise. The full code contract lives in §2.8 — apply uses `1` for partial-batch runtime failure, `2` for validation failure, `3/4/5/6` for auth / not-found / conflict / stale-ETag respectively.
- **Batch audit** *(Phase 7)*: when audit ships, all entities applied in one `apply` invocation will be linked by `batch_id` — `dial-cli audit log --batch <id>`. Until Phase 7, `apply` reports the per-entity summary (counts + per-entity status) but no persistent batch trail.

> **FEEDBACK Q9 (manifests):**
>
> - Is the `kind` / `name` / `spec` structure clear? Or prefer Kubernetes-style `apiVersion` + `metadata` wrapper?
> - Is `template` + `params` in manifests clear? Or prefer all fields always explicit?
> - For secrets — which secret stores matter? (Vault, KeyVault, AWS SM, SOPS, env vars only?)
> - Should `apply` delete entities not in manifests? (`--prune`) Or never auto-delete?

#### Template composition — onboarding a second Bedrock family

You already have `bedrock-chat` (§1.2). Now the team adds Bedrock **embedding** models and you notice the adapter host, auth-forward logic, and region expansion are identical — only the URL path and feature flags differ. `extends` / `includes` let you factor the common parts out instead of copy-pasting:

```yaml
templates:
  # … chat-base and forward-auth-when-enabled as in §1.2 …

  bedrock-embedding:
    description: "AWS Bedrock embedding model via dial-bedrock adapter"
    includes: [forward-auth-when-enabled]        # same auth handling
    fields:
      type: embedding
      endpoint: "${vars.adapter_host_bedrock}/openai/deployments/${entity.name}/embeddings"
      upstreams:
        !for { in: "${params.regions}", as: region }:
          - endpoint: "${vars.adapter_host_bedrock}/openai/deployments/${entity.name}/embeddings"
            extraData:
              region: "${region}"
```

A third Bedrock family (e.g. `bedrock-rerank`) becomes a five-line template — the shared bits are carried by `includes`, the new template adds only what's actually new. See [`05-cli-design.md`](05-cli-design.md) §3.2 for the merge order and cycle-rejection rules.

#### Environment overlays — UAT pricing differs from dev

`vars` handles values that differ per env and belong inside a template (adapter hosts, auth flags). It starts to creak when the per-env delta is an **entity field** outside the template — `pricing.prompt`, a role's rate-limit, a model that ships in dev/uat but is gated out of prod. Routing every such field through `${vars.*}` bloats the env profile and buries what's actually different.

Overlays split the manifest tree into a shared base and per-env overlay directories:

```
manifests/
├── base/
│   └── models/claude-sonnet.yaml              # default shape, used by every env
└── overlays/
    ├── uat/
    │   └── models/claude-sonnet.yaml          # only what differs in UAT
    └── prod/
        └── models/claude-sonnet.disable       # marker — not shipped in prod
```

An overlay uses `kind: <Entity>Overlay` with a `target` and a JSON Merge Patch (RFC 7396):

```yaml
# overlays/uat/models/claude-sonnet.yaml
kind: ModelOverlay
target: models/public/claude-sonnet
patch:
  pricing:
    prompt: 0.0000025
  params:
    regions: [us-west-2]                       # UAT pins to a single region
```

Apply base + overlay together:

```shell
dial-cli apply -f manifests/base/ --overlay manifests/overlays/uat/ --env uat
```

Entities with no overlay file in UAT are applied straight from `base/`. A `.disable` marker removes the targeted entity from that env's effective set. Because base is common by construction, `dial-cli diff --source dev --target uat` collapses to the diff between overlay trees — the declarative counterpart of the imperative `promote` workflow in §2.5. See [`05-cli-design.md`](05-cli-design.md) §5.2 for the full pipeline.

#### Bundle manifests — onboard-claude-sonnet in one command

Operational units rarely map to a single entity — "onboard a new model" usually means model + role rate-limits + key + (sometimes) route. A bundle groups these under a shared `params` scope so the whole unit is parameterised and applied atomically. Bundles are pure CLI-side sugar: the CLI expands a `Bundle` into its entities under the same dependency ordering as §2.7 and hands them to `POST /v1/admin/apply`. The server never sees the `Bundle` kind.

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
    patch:                                     # Merge Patch — don't clobber unrelated limits
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

```shell
dial-cli apply -f manifests/bundles/onboard-claude-sonnet.yaml --env uat \
  --param 'regions=[us-east-1,us-west-2]'
```

Two ergonomics to note: `patch:` (JSON Merge Patch — used above on the shared `basic` role so we don't overwrite unrelated limits) vs `spec:` (full replacement, same as a single-entity manifest); and a single command to apply the whole unit. See [`05-cli-design.md`](05-cli-design.md) §5.3 for parameter scoping and cross-reference validation.

> **`patch:` against a missing entity creates from scratch.** If the entity does not yet exist on the target environment (the underlying GET returns 404), the CLI treats the base as `{}` and applies the patch to the empty object. This means a `patch:` entry that provides only a subset of required fields will create an entity with missing required fields, which the server rejects under `precheck: true` (the default). Use `spec:` instead of `patch:` when the entity may not exist on the target environment.

### 2.8 CI/CD Integration

```yaml
# GitHub Actions
- name: Apply DIAL config to UAT
  env:
    DIAL_UAT_API_KEY: ${{ secrets.DIAL_UAT_API_KEY }}
  run: dial-cli apply -f config/ --env uat --validate --diff

# GitLab CI
deploy_dial_config:
  image: ghcr.io/epam/dial-cli:latest
  script:
    - dial-cli apply -f config/ --env $CI_ENVIRONMENT_NAME --validate
```

| Exit Code | Meaning |
|-----------|---------|
| `0` | Success — all entities applied, **or** nothing to apply (idempotent re-run). Check the `created/updated/unchanged/failed` summary printed by `apply` to tell the two apart. |
| `1` | Partial batch failure / general error — one or more entities failed at apply time after the validate-first gate passed. |
| `2` | Validation failed — the pre-apply gate rejected the manifest. Nothing was written. |
| `3` | Auth failed — invalid / missing API key, expired JWT, or insufficient role. |
| `4` | Entity not found — `404` from `update` / `delete` / `get` / `promote` against a non-existent entity. |
| `5` | Conflict — `409` on `add` (entity already exists; remediation: use `update` instead). |
| `6` | Precondition failed — `412` on `update` / `delete` with `If-Match` (stale ETag, concurrent modification; remediation: re-read and retry). |

Differentiated codes are preserved intentionally so pipelines can branch without parsing stderr — the same convention `kubectl`, `helm`, `terraform`, and `aws-cli` use. Exit `5` and exit `6` are split rather than collapsed because they require different remediation (use a different verb vs. re-read and retry); CI scripts that don't care about the distinction can match `5|6` as a single class. The per-entity outcomes inside an apply batch are still reported in the apply summary; the exit code is the pipeline-facing aggregate.

**Per-entity 409 inside bulk apply.** Bulk apply is upsert by design — the dependency-ordered sequential application performs create-or-update, never colliding-create — so a per-entity `409` cannot arise from a missing-create or duplicate-create case during apply. The only path that could surface a `409`-like state is a CAS / ETag check failure if the apply payload carried per-entity ETag metadata triggering it; the current payload schema has no per-entity ETag field, so this path is closed in practice. Any non-200 per-entity status appearing inside a 200-batch (typically per-entity `FAILED` from validation under `precheck: false` + `softValidation: false`) maps to exit `1` (partial-batch runtime failure) — exit `5` is reserved for the single-entity `add` case. See [`03-api-reference.md`](03-api-reference.md) §7 for the full per-entity status taxonomy.

> **FEEDBACK Q10:** Exit codes sufficient? Need `--non-interactive`/`--yes` flag? Need `dial-cli status` health-check? Any GitOps tool integration (ArgoCD, Flux)?

### 2.9 Environment Management

```shell
dial-cli env list
dial-cli env current
dial-cli env use uat                # set defaults.env to uat — subsequent commands omit --env
dial-cli env check --env uat
```

`env use` mutates `defaults.env` in `~/.dial-cli/config.yaml` (kubectl-`use-context` analog). After `env use uat`, commands like `dial-cli get models` run against UAT without re-typing `--env uat` every time. The `--env` flag still wins when supplied explicitly.

**Why no `dial-cli auth login` in Phase 2–3.** The CLI could mimic `kubectl`/`gcloud`/`aws`'s two-step "login-then-operate" flow, but with API-key-only authentication that command would be wallpaper over the same env-var resolution described in §2.1 — no session token to issue, no OIDC device code to exchange, no JWT refresh to orchestrate. `env use` covers the "pick an env and stop re-typing it" ergonomic today. A real `auth login` becomes first-class once OIDC/user-JWT is decided in D4 (see [`05-cli-design.md`](05-cli-design.md) §1 and OQ-19 in [`08-open-questions-and-references.md`](08-open-questions-and-references.md)).

```
NAME              API URL                                STATUS
dev (default)     https://dial-core.dev.dial.parts       connected
uat               https://dial-core.uat.dial.parts       connected
prod              https://dial-core.prod.dial.parts      auth failed
demo              https://dial-core.demo.dialx.ai        unreachable
```

### 2.10 Audit Log

> **STATUS: WIP / DEFERRED to Phase 7.** The CLI audit command group is **not delivered in Phases 1–6**. The shape below is the working design draft kept for reviewer feedback; commands marked `dial-cli audit *` will not exist until Phase 7 lands. See [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 7 for placement and rationale.

Today there is **no audit trail** for DIAL configuration changes. Phase 7 introduces one.

**Design decisions already made** (see [`04-security-and-audit.md`](04-security-and-audit.md) §Audit — also WIP):

- **Storage: Redis Streams (hot) + blob archival (cold).** Durable from the moment of write.
- **Criticality: Audit blocks the mutation.** If PENDING write fails, config change aborted. (Vault model.)
- **Intent log:** PENDING (before mutation) + APPLIED/FAILED (after). Never falsely claims a change was applied.
- **Scope:** `platform/` and `public/` buckets only. Personal user data excluded.
- **Retention:** 30 days default (configurable). Daily state snapshots for point-in-time reconstruction.

```shell
# Entity history
dial-cli audit history models/public/gpt-4 --from 2026-03-09 --to 2026-04-09

# Global changelog
dial-cli audit log --from 2026-04-02 --to 2026-04-09

# Batch apply operation
dial-cli audit log --batch batch_xyz789

# Point-in-time snapshot
dial-cli audit snapshot --at 2026-04-01 --entity-type models -o yaml

# Diff between two points in time
dial-cli audit diff --from 2026-04-01 --to 2026-04-09

# Rollback entity
dial-cli audit rollback models/public/gpt-4 --to-event evt_a1b2c3d4
dial-cli audit rollback models/public/gpt-4 --to-time 2026-04-01T00:00:00Z

# Reconcile orphaned PENDINGs
dial-cli audit reconcile --dry-run
```

**Rollback and current-version validation.** `dial-cli audit rollback` re-applies a historical snapshot through the standard write path, so it is subject to current-version validation. If the snapshot's payload no longer satisfies validation (a renamed field, a deprecated enum, a removed schema reference), the rollback is rejected with the same error a manual write of that payload would produce. A recovery mechanism for restoring snapshots that are incompatible with the current entity model is tracked as OQ-31 and is out of scope for Phase 7's MVP.

**Audit event structure** — matches the canonical schema in [`04-security-and-audit.md`](04-security-and-audit.md) §3.3; carries full post-mutation state snapshot:

```yaml
- id: evt-20260409-a1b2c3d4
  timestamp: "2026-04-09T10:15:03Z"
  requestedBy: "ci-pipeline@company.com"   # always admin JWT identity in Phase 3
  approvedBy: null                          # reserved for Phase 4+ publication workflow
  entityType: models
  entityId: models/public/chat-gpt-35-turbo
  bucket: public
  operation: update                         # create | update | delete
  status: APPLIED                           # PENDING → APPLIED | FAILED
  state:                                    # full entity snapshot AFTER change — enables rollback
    type: chat
    displayName: GPT-3.5 Turbo
    maxTotalTokens: 128000
    userRoles: ["basic", "admin"]
  diff: { maxTotalTokens: "changed", userRoles: "changed" }
  batch_id: null
  batch_index: null
  batch_size: null
```

**External tooling.** Blob layout designed for Athena, ELK, Loki, Datadog, or custom scripts (date-partitioned, one event per file, JSON).

> **FEEDBACK Q12 (audit):**
>
> - Are `audit history` / `audit log` / `audit snapshot` / `audit diff` / `audit rollback` the right subcommands?
> - Is 30-day retention sufficient? Different per environment?
> - Is rollback useful, or better handled through GitOps?
> - Which log analytics tools do you use? Would you connect them to audit blobs?
> - Need real-time config change notifications? (Slack, SIEM)

---

## 3. Supported Entity Types

| Entity Type | CLI name | Bucket | Canonical ID format | Examples |
|-------------|----------|--------|---------------------|----------|
| **Models** | `model` / `models` | `public/` | `models/public/<n>` | Add with templates, promote |
| **Applications** | `application` / `applications` | `public/` | `applications/public/<n>` | Admin apps, endpoints, features |
| **Toolsets** | `toolset` / `toolsets` | `public/` | `toolsets/public/<n>` | MCP toolsets, transport, auth |
| **App Type Schemas** | `schema` / `schemas` | `public/` | `schemas/public/<id>` | JSON schemas for typed apps |
| **Interceptors** | `interceptor` / `interceptors` | `platform/` | `interceptors/platform/<n>` | Endpoints, assign to models |
| **Roles** | `role` / `roles` | `platform/` | `roles/platform/<n>` | Rate limits, cost limits |
| **Keys** | `key` / `keys` | `platform/` | `keys/platform/<n>` | API keys, projects, roles |
| **Routes** | `route` / `routes` | `platform/` | `routes/platform/<n>` | URL routing, upstreams |
| **Global Settings** | `settings` | `platform/` | singleton | globalInterceptors, retriableErrorCodes |
| **Files** | `file` / `files` | `public/` | `files/public/<path>` | Admin-managed shared files — icons, theme assets, documentation. User-owned files in user buckets are unchanged and not addressed by `dial-cli` (admin has no access — [OQ-33](08-open-questions-and-references.md)). |
| **Prompts** | `prompt` / `prompts` | `public/` | `prompts/public/<n>` | Admin-managed shared/default prompt templates. User-owned prompts in user buckets unchanged. |
| **Conversations** | `conversation` / `conversations` | `public/` | `conversations/public/<n>` | Admin-managed curated example conversations. User-owned conversations in user buckets unchanged. |

**Bucket split.** `public/` = user-facing (things users see in the chat UI). `platform/` = infrastructure (things users never interact with — roles, keys, routes, interceptors, global settings). The `platform/` bucket name reflects the *tier* it serves; future multi-tenancy adds sibling tier-named scope mappings (tenant, team, channel) through `EntityLocationStrategy`. See [`02-architecture.md`](02-architecture.md) §Bucket Strategy.

**Identifier model — two formats coexist.** Config-file entities keep their simple names (`"gpt-4"`) and API-managed entities use canonical IDs (`"models/public/gpt-4"`). Both live in the same runtime config — no override, no collision, no forced migration. When you create an entity via the CLI, it gets a canonical ID. Your existing config-file entities keep working with simple names. The `source` field (`file` or `api`) tells you where each entity came from. You can migrate entities from file to API at your own pace — remove the config-file entry only after you've created the API version and updated all downstream references (rate limits, interceptor chains, etc.).

**API path format.** Per-entity CRUD uses the unified `/v1/{type}/{bucket}/{name}` URL — e.g. `GET /v1/models/public/gpt-4`, `PUT /v1/roles/platform/viewer` (extending the existing user Resource API regex; bucket-aware authz). Cross-entity operator endpoints stay under `/v1/admin/*` — `apply`, `validate`, `export`, `audit` (Phase 7), `health/config`, `schema`. The singleton settings resource sits at `/v1/settings/platform/global`. The bucket (`public/` or `platform/`) is always explicit on per-entity URLs.

**Identifiers — two forms coexist.** The CLI accepts both canonical IDs (`models/public/gpt-4`) and simple names (`gpt-4`), but these address **distinct entities** under the union model: a canonical ID refers to an API-managed entity, a simple name refers to a file-sourced entity. The CLI does not silently expand simple names into canonical IDs — doing so would conflate two different entries in the runtime config. Use the form that matches the entity you want to read or modify. Write commands (`add`, `update`, `delete`) only target API-managed entities, so they require canonical IDs. Listing commands (`dial-cli get models`) return every entity from both sources with a `source: file|api` column so you can tell them apart at a glance.

**Secrets.** Secret fields (Key.key, Upstream.key, OAuth clientSecret, etc.) are encrypted at rest in blob storage (AES-256-GCM + KMS). API responses always mask as `"***"`. Write-only — set but never read back. Export also masks secrets, so `export` + `apply` cannot round-trip secrets by design (each environment manages its own secrets). See [`04-security-and-audit.md`](04-security-and-audit.md) §Secrets at Rest.

> **FEEDBACK Q11 (entity coverage):**
>
> - Is this complete? Operations that don't map to any of these?
> - Which entity types do you manage most frequently? Which rarely?
> - For **external teams** — which entities would they manage vs. restrict?

> **FEEDBACK Q13 (files & prompts):** *(scope decided — see [OQ-21](08-open-questions-and-references.md): files / prompts / conversations are first-class admin types in `public/`. The questions below are still open as workflow refinements.)*
>
> - For files: which sub-paths matter most in CI/CD? (icons, themes, docs, all of `public/files/`?)
> - For prompts: do you treat default templates as code (managed in repo + applied) or content (managed in UI)?
> - For conversations: what's the realistic admin use case — onboarding examples, demo content, something else?
> - Promotion: do you promote files/prompts/conversations between envs (`promote --from dev --to uat`) or treat them as per-env content?

---

## 4. Summary & Open Questions

### What we're asking you to evaluate

1. **Feature completeness** — what's missing?
2. **Command ergonomics** — what would you rename?
3. **Config file** — is `vars` + `templates` clear?
4. **Templates & promote** — is template-based resolution better than hard-coded adapter presets?
5. **Manifests** — is `template` + `params` intuitive?
6. **CI/CD fit** — smooth integration?
7. **Audit** — useful subcommands and output?
8. **Files & prompts** — in scope or not?
9. **External teams** — hand this to a customer's DevOps team — productive?

### Decisions needing your input

| # | Decision | Status |
|---|----------|--------|
| D1 | CLI language: **Java** (Picocli + Quarkus + GraalVM). Shares DIAL Core data models. | Decided |
| D2 | Confirm prompt for destructive ops — always confirm `delete`? `--force` to skip? | Open |
| D3 | Default output — `table` vs `yaml` vs auto-detect (TTY→table, pipe→yaml) | Open |
| D4 | Secrets integration — env vars only? Vault? KeyVault? SOPS? | Open |
| D5 | `apply --prune` — delete entities not in manifest? Or never auto-delete? | Open |
| D6 | Promote scope — single entity only, or batch promote by type? | Open |
| D7 | Audit retention — **30 days default**. Different per environment? | Default decided *(Phase 7 — deferred)* |
| D8 | Audit criticality — **blocks mutation** if PENDING write fails. | Decided *(Phase 7 — deferred)* |
| D9 | Audit export — which external tools do you actually use? | Open *(Phase 7 — deferred)* |

### How to provide feedback

- Comment inline or at the bottom.
- Reply with structured feedback per section (Q1–Q13, D1–D9).
- Schedule a 30-min walkthrough — we'll demo the commands live.

---

## Next

- Internal CLI design (parser, template engine, promote algorithm): [`05-cli-design.md`](05-cli-design.md)
- API contract the CLI calls: [`03-api-reference.md`](03-api-reference.md)
- Phased rollout and what's available when: [`07-migration-and-rollout.md`](07-migration-and-rollout.md)

## References

- [`README.md`](README.md) — proposal overview and status
- Built with: Java 21, [Picocli](https://picocli.info/), Quarkus Command Mode, Jackson, GraalVM native-image
- Inspiration: `kubectl`, `helm`, `terraform`, `aws cli`
