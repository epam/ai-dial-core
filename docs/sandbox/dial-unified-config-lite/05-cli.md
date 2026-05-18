# 05 — `dial-cli`

The shape and feel of the CLI for reviewers. Commands, profile concept, templates, promotion — not the full flag reference.

## Command structure

```
dial-cli [global-flags] <resource-type> <command> [command-flags]
```

The CLI follows kubectl-like ergonomics. Per-resource-type commands:

| Command | HTTP | What it does |
|---|---|---|
| `list` (or `dial-cli get <plural>`) | `GET` | List entities of a type |
| `get <name>` | `GET` | Get one entity |
| `add` | `POST` | Create — exits `5` (409) if it already exists |
| `update <name> [--set k=v…]` | `PUT` | Update — exits `4` (404) if missing. Field-level `--set` runs GET → local merge → PUT |
| `delete <name>` | `DELETE` | Delete — exits `4` if missing |
| `validate` | `POST /v1/admin/validate` | Validate without applying |
| `promote --from <env> --to <env>` | `GET` + `POST /v1/admin/apply` | Promote between environments |
| `diff --source <env> --target <env>` | `GET` × 2 | Diff between environments |

Top-level commands:

| Command | What it does |
|---|---|
| `dial-cli apply -f <path>` | Declarative bulk apply against `POST /v1/admin/apply` |
| `dial-cli export --env <env>` | Full export of effective config |
| `dial-cli env list \| current \| use <name> \| check` | Environment management (kubectl `use-context` analog) |
| `dial-cli audit` *(Phase 7 — deferred)* | Query audit log |
| `dial-cli completion {bash\|zsh\|fish}` | Shell completion |

Three illustrative invocations:

```bash
# Read
dial-cli get models --env dev
dial-cli model get models/public/gpt-4 --env dev

# Apply a manifest (declarative)
dial-cli apply -f manifests/ --env uat --dry-run
dial-cli apply -f manifests/ --env uat

# Promote one entity from dev to uat with a named template
dial-cli promote --from dev --to uat --name models/public/gpt-4 --template bedrock-chat
```

### Strict create vs update — no implicit upsert

`add` maps to `POST` (create-only, `409` on conflict) and `update` maps to `PUT` (update-only, `404` on missing). The CLI never silently falls back between them — a typo in an entity name surfaces as a clean error instead of a stub creation. Bulk upsert is the explicit role of `dial-cli apply -f`.

The singleton `settings update` is the documented exception (the global-settings projection always has a value — `PUT` is upsert by nature). `settings reset` (mapping to `DELETE`) clears the API blob and reverts to the file-sourced or default projection — the explicit "release API control" mechanism.

## Profile config

`~/.dial-cli/config.yaml` is **operator-side input** — the kubeconfig / Terraform tfvars analogy. It is *not* configuration data DIAL Core serves. Three concerns separated:

- **Connection** — `api_url`, `auth` per environment. Credentials come from the env var named in the profile, `--api-key-file <path>`, the OS keystore, or an interactive no-echo prompt — the CLI **never** accepts an API key as a command-line flag (would leak into `ps`, shell history, CI logs under `set -x`, and `kubectl describe pod`).
- **Variables** — environment-specific values to substitute into templates and manifests.
- **Templates** — reusable field patterns, entity-type-agnostic.

Minimal shape:

```yaml
defaults:
  env: dev
  output: table

environments:
  dev:
    api_url: "https://dial-core.dev.dial.parts"
    auth: { type: api_key, key_env_var: DIAL_DEV_API_KEY }
    vars:
      adapter_host_bedrock: "http://dial-bedrock.dial.svc.cluster.local:80"
      forward_auth_token: "false"

templates:
  bedrock-chat:
    fields:
      endpoint: "${vars.adapter_host_bedrock}/openai/deployments/${entity.name}/chat/completions"
      forwardAuthToken: "${vars.forward_auth_token}"
      upstreams:
        - endpoint: "${vars.adapter_host_bedrock}/openai/deployments/${entity.name}/chat/completions"
          extraData: { region: "${params.region}" }
```

## Templates & overlays

Five substitution namespaces drive template resolution:

- `${vars.*}` — from the environment profile.
- `${params.*}` — from `--param k=v` flags or a manifest `params` block.
- `${entity.*}` — from entity metadata (`name`, `type`).
- `${SECRET:<name>}` — opaque secret references resolved from the OS keystore or env var (never echoed or logged).
- `${ENV_VAR}` — shell environment passthrough.

Templates compose along two axes:

- **`extends: <name>`** — single-parent inheritance (parent first, child wins on conflict).
- **`includes: [<name>, …]`** — mixin composition (later includes win over earlier; the template's own `fields` win over all).

**Resolution is stamped, not live.** Templates are resolved by the CLI at write time; the server never sees a template, only a fully-resolved entity. Editing a template later does *not* retroactively change anything DIAL Core is serving.

Manifests support a **base + env overlay** pattern: a base manifest under `manifests/`, environment-specific deltas under `overlays/<env>/`. The CLI deep-merges base + overlay before submission and `--dry-run` shows the fully merged YAML.

**Bundle manifests** group multiple related entities (a new LLM = model + role-limits + interceptor) into a single `kind: Bundle` apply payload. The server processes the bundle as one dependency-ordered, `precheck`-atomic batch — "onboard a new LLM" lands as one operation rather than three coordinated CLI calls.

## Promotion between environments

`dial-cli promote --from <src> --to <dst>` has three modes:

- **Explicit template** (`--template <name>`) — re-resolves env-specific fields against the target env's `vars`.
- **As-is** — no transformation. Right for entity types with no env-specific fields (roles, schemas, keys, routes).
- **Auto-detect** — reverse-matches the source entity against source-env templates and re-applies the same template at the target.

Always dry-run first. Promotions submit a single-entity manifest to `POST /v1/admin/apply` so the canonical upsert path is used — there is no client-side POST/PUT split that could TOCTOU between source-fetch and target-write.

Secrets are deliberately *skipped* by `promote` — set them per environment.

## Tech stack

**Java (Picocli + Quarkus + GraalVM native image).** The CLI reuses DIAL Core's `config/` Gradle module directly — zero reimplementation of `Model`, `Role`, `Key`, etc. data classes. Distribution is a single ~30–50 MB native binary with no JVM dependency; a JBang fallback exists for platforms without a native image.

> See the full versions: [`../dial-unified-config/05-cli-design.md`](../dial-unified-config/05-cli-design.md) and [`../dial-unified-config/06-cli-user-guide.md`](../dial-unified-config/06-cli-user-guide.md)
