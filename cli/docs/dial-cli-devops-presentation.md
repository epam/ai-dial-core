# dial-cli — DevOps Walkthrough

**Audience:** DevOps engineers who manage DIAL Core environments.  
**Format:** Step-by-step tutorial / live demo script. Run each command, observe the output,
compare with the expected result shown below.  
**Prerequisites:** DIAL Core running on `http://reviewhost:8080`, an admin API key, the CLI
available as a JAR or shell alias (see [Setup](#0-setup)).

All commands are run from `sample/dial-cli/` so the profile (`config.yaml`) and manifests
are resolved automatically.

---

## Contents

1. [Profileless / no-config commands](#1-profileless--no-config-commands)
2. [Environment commands](#2-environment-commands)
3. [Inspect current config (read)](#3-inspect-current-config)
4. [Deploy from manifest files (apply)](#4-deploy-from-manifest-files)
5. [Update fields in-place (update --set)](#5-update-fields-in-place)
6. [Individual CRUD operations](#6-individual-crud-operations)
7. [Promote between environments](#7-promote-between-environments)
8. [Diff between environments](#8-diff-between-environments)
9. [Bundle: onboard a model end-to-end](#9-bundle-onboard-a-model-end-to-end)
10. [Overlays: environment-specific patches](#10-overlays-environment-specific-patches)

---

## 0. Setup

### What the CLI is

`dial-cli` is a kubectl-style command-line tool for managing DIAL Core configuration:
models, applications, API keys, roles, routes, interceptors, toolsets, schemas, and global
settings. It talks to the DIAL admin REST API, supports multi-environment profiles,
template-based manifest files, declarative bulk apply, and cross-environment promotion.

### 0.1 CLI profile

The profile lives at `~/.dial-cli/config.yaml` by default (or at any path pointed to by
`$DIAL_CLI_CONFIG`). The playground ships one at `sample/dial-cli/config.yaml`:

```yaml
defaults:
  output: table
  env: review

environments:
  review:
    api_url: "http://reviewhost:8080"
    auth:
      type: api_key
      key_env_var: DIAL_review_API_KEY
    vars:
      adapter_host: "http://reviewhost:7001"
      icon_base_url: ""
      forward_auth_token: "false"

  local:
    api_url: "http://reviewhost:8080"
    auth:
      type: api_key
      key_env_var: DIAL_review_API_KEY
    vars:
      adapter_host: "http://reviewhost:7001"
      icon_base_url: "https://example.com/icons"
      forward_auth_token: "true"
```

Both envs point at the same review DIAL Core for the playground — they differ in `vars`,
so template-driven commands produce different output for `review` vs `local`.

### 0.2 Shell setup

Works on Linux, macOS, and Windows (Git Bash / WSL).

```shell
cd sample/dial-cli
alias dial-cli='winpty bash dial-cli.sh'
```

---

## 1. Profileless / No-config Commands

The CLI does not require a profile file. You can pass `--api-url` and `--api-key-file`
directly on any command to connect to a DIAL Core instance without a `config.yaml`.

### Step 1.1 — Profileless mode (`--api-url` + `--api-key-file`)

When both flags are provided, no profile entry or `--env` flag is needed. The CLI
synthesises an ad-hoc context and proceeds. Useful for one-off commands or bootstrap
jobs that don't maintain a persistent `~/.dial-cli/config.yaml`.

**Input:**
```shell
dial-cli get models --api-url http://host.docker.internal:8080 --api-key-file api_key_local
```

**Expected result:**
```
model list from local env
```

---

### Step 1.2 — Interactive API key prompt (TTY fallback)

When no `--api-key-file` is provided and the profile's `key_env_var` is not set in the
environment, the CLI falls back to prompting for the key interactively — provided the
session is connected to a TTY.

**Input:**
```shell
dial-cli get models --api-url http://host.docker.internal:8080
```

**Expected result (interactive terminal):**
```
API key for env '<ad-hoc>':
```
Input is read with no echo (like a `sudo` password prompt). After entry the command
proceeds normally.

**In non-interactive contexts** (CI pipelines, piped commands, Docker without `-it`),
`System.console()` returns `null` — the prompt is skipped and the CLI fails immediately:
```
No API key resolved. Pass --api-key-file <path> or run from a TTY.
```

---

## 2. Environment Commands

### Step 2.1 — List configured environments

**Input:**
```shell
dial-cli env list
```

**Expected result:**
```
* review
  local
```
The `*` marks the active default. `review` is the default because of `defaults.env: review`
in `config.yaml`.

---

### Step 2.2 — Print the active environment

**Input:**
```shell
dial-cli env current
```

**Expected result:**
```
review
```

---

### Step 2.3 — Print environment details and credential source

**Input:**
```shell
dial-cli env check
```

**Expected result:**
```
Environment: review
API URL:     http://reviewhost:8080
Credentials: env-var ($DIAL_review_API_KEY)
```

> **Tip:** Use `--env local` to check the other environment:
> `dial-cli env check --env local`

---

### Step 2.4 — Switch the default environment

**Input:**
```shell
dial-cli env use local
```

**Expected result:**
```
Switched to environment 'local'.
```

Verify the switch:
```shell
dial-cli env current
```

**Expected result:**
```
local
```
`defaults.env` is updated in `config.yaml`. Switch back for the rest of this walkthrough:
```shell
dial-cli env use review
```

---

## 3. Inspect Current Config

### Step 3.1 — List all entities (merged API + file-config)

These are entities stored in blob storage via the DIAL admin API (as opposed to entries
loaded from the static config file).

**Input:**
```shell
dial-cli get models
dial-cli get roles
dial-cli get keys
```

**Expected result (fresh DIAL Core, nothing applied via API yet):**
```
# dial-cli get models
NAME              SOURCE
gpt-4o            file
claude-3-sonnet   file
...

# dial-cli get roles
NAME        SOURCE
default     file
...

# dial-cli get keys
NAME  SOURCE
```
`get models` and `get roles` merge API-managed and file-config entries — on a fresh
instance only the file-config entries appear (`source: file`). After running `apply` in
Step 4, API-managed entries appear alongside them with `source: api`.

`get keys` shows only API-managed keys. File-sourced key entries are silently omitted and
are not accessible via this surface.

File-sourced and API-managed entries coexist in the same running config under different
keys — file entries use simple names (`gpt-4`), API entries use canonical IDs
(`models/public/gpt-4`). They never collide.

---

### Step 3.2 — Read a single entity (full body)

After applying at least one model (Step 4), fetch its full spec.

**Input:**
```shell
dial-cli model get models/public/example-chat-model -o yaml
```

**Expected result:**
```yaml
name: models/public/example-chat-model
spec:
  displayName: "Example Chat Model"
  type: chat
  endpoint: "http://reviewhost:7001/openai/deployments/example-chat-model/chat/completions"
  features:
    systemPromptSupported: true
    toolsSupported: true
  upstreams:
    - endpoint: "http://reviewhost:7001/openai/deployments/example-chat-model/chat/completions"
      extraData:
        region: us-east-1
    - endpoint: "http://reviewhost:7001/openai/deployments/example-chat-model/chat/completions"
      extraData:
        region: us-west-2
  userRoles:
    - example-user
```
The ETag header is also returned — the CLI uses it automatically for optimistic concurrency
on subsequent writes.

---

### Step 3.3 — Read global settings (singleton)

**Input:**
```shell
dial-cli settings get -o yaml
```

**Expected result (nothing applied yet):**
Empty table — no API blob and no file-config settings entry present.
After applying `09-settings.yaml` in Step 4 the output shows the configured fields with `source: api`.

---

## 4. Deploy from Manifest Files

### Step 4.1 — Dry-run first (no writes)

Preview what would be sent without touching the API. The CLI resolves templates, overlays,
and params and prints the fully-expanded JSON envelope to stdout — no HTTP calls are made.

> **Prerequisite:** Template resolution runs before the dry-run check, so secrets must be
> available even for preview. Set the key secret first:
> ```shell
> export DIAL_CI_KEY=<your-api-key-value>
> ```

**Input:**
```shell
dial-cli apply -f manifests/base/ --dry-run
```

**Expected result:**
```
{"manifests":[{"kind":"Model","name":"example-chat-model","spec":{...}},...],"precheck":true}
```
The output is the raw JSON envelope the CLI would POST to `/v1/admin/apply`. Pipe it through
`jq` to inspect individual entries:
```shell
dial-cli apply -f manifests/base/ --dry-run | jq '.manifests[] | {kind, name}'
```
Verify that template fields (`endpoint`, `upstreams`, `forwardAuthToken`) look correct for
`review`.

---

### Step 4.2 — Apply a single manifest

**Input:**
```shell
dial-cli apply -f manifests/base/06-model.yaml
```

**Expected result:**
```
Created models/public/example-chat-model
```

---

### Step 4.3 — Apply the entire base directory

> **Prerequisite:** `Key.key` is required — the server rejects a blank value with `400`.
> Set the key secret before applying any Key manifest:
> ```shell
> export DIAL_CI_KEY=<your-api-key-value>
> ```

**Input:**
```shell
dial-cli apply -f manifests/base/
```

**Expected result:**
```
applied: 9, failed: 0
```
The model already existed from Step 4.2. `apply` is idempotent — existing entities are
re-applied without error (use `model update` or `apply` with an overlay patch to change
a specific field).

If `DIAL_CI_KEY` is not set the CLI fails loudly before sending:
```
Error: Required env var 'DIAL_CI_KEY' is not set (referenced by ${SECRET:DIAL_CI_KEY}).
Exit code: 1
```

---

### Step 4.4 — Verify

**Input:**
```shell
dial-cli get models
dial-cli get roles
dial-cli get keys
```

**Expected result:**
```
NAME                                SOURCE
models/public/example-chat-model    api

NAME                                SOURCE
roles/platform/example-user         api

NAME                                SOURCE
keys/platform/example-ci-key        api
```
All three are now API-managed.

---

### Step 4.5 — Apply JSON manifests (interchangeable)

The CLI accepts JSON single-objects, JSON arrays, or YAML — mixed in the same directory.

**Input:**
```shell
dial-cli apply -f manifests-json/ --env review --dry-run
```

**Expected result:**
```
{"manifests":[{"kind":"Model","name":"example-model","spec":{...}},...],"precheck":true}
```
Same JSON envelope format as Step 4.1 — pipe through `jq` to inspect individual entries.

---

## 5. Update Fields In-Place

`update` does a GET, applies `--set` patches, then PUTs back with the original
ETag — so concurrent writers are detected automatically.

Use single-quoted strings for `--set` values so the shell passes them verbatim to the
CLI. Escape commas inside JSON arrays as `\,` — picocli strips the backslash after
resolving the Map split, so the JSON converter receives valid input.

### Step 5.1 — Rename a model's display name

**Input:**
```shell
dial-cli model update models/public/example-chat-model --set 'displayName="Example Chat (v2)"'
```

**Expected result:**
```
Updated models/public/example-chat-model
```

Verify:
```shell
dial-cli model get models/public/example-chat-model -o yaml | grep displayName
```
```
  displayName: "Example Chat (v2)"
```

---

### Step 5.2 — Update a nested field and an array

**Input:**
```shell
dial-cli model update models/public/example-chat-model \
  --set features.toolsSupported=false \
  --set userRoles='["example-user","admin"]'
```

**Expected result:**
```
Updated models/public/example-chat-model
```
Multiple `--set` flags are merged into a single PUT. Dot-path notation addresses nested
fields; quoted JSON values set lists or strings.

---

### Step 5.3 — Update role rate limits

**Input:**
```shell
dial-cli role update roles/platform/example-user \
  --set 'limits.models/public/example-chat-model.minute="200000"'
```

**Expected result:**
```
Updated roles/platform/example-user
```

---

### Step 5.4 — Update with explicit ETag guard (optimistic lock)

The server returns the ETag in the HTTP `ETag` **response header** — it is not part of the JSON/YAML body, so `dial-cli model get … -o json` does not show it. For normal interactive use you never need `--if-match` because `model update` captures the ETag automatically from the GET it performs internally.

`--if-match` is useful in CI scripts that pin a write to a previously recorded ETag. Capture it via `curl`:

```shell
curl -si \
  -H "Authorization: Bearer $DIAL_review_API_KEY" \
  "http://host:8080/v1/models/public/example-chat-model" \
  | grep -i '^etag:'
# → etag: "abc123"
```

Then update guarded by that ETag:

**Input:**
```shell
dial-cli model update models/public/example-chat-model \
  --set limits.maxTotalTokens=128000 \
  --if-match '"abc123"'
```

**Expected result (ETag matches):**
```
Updated models/public/example-chat-model
```

**Expected result (ETag stale — another writer changed it first):**
```
Stale ETag: models/public/example-chat-model
```
Exit code: 6

---

### Step 5.5 — Update global settings

**Input:**
```shell
dial-cli settings update --set 'retriableErrorCodes=[502,503,504]'
```

**Expected result:**
```
Updated settings/platform/global
```
`settings update` is an upsert — it creates the entry if none exists (no `settings add`
is needed).

---

### Step 5.6 — Remove a field with `--set path=null`

Setting a path to `null` removes the field instead of writing a JSON null.

**Input:**
```shell
dial-cli model update models/public/example-chat-model \
  --set features.toolsSupported=null
```

**Expected result:**
```
Updated models/public/example-chat-model
```

Verify the field is gone:
```shell
dial-cli model get models/public/example-chat-model -o yaml | grep toolsSupported
# (no output — field removed)
```

To remove a rate-limit entry whose key contains slashes, use dot notation — slashes in a
path segment are treated as part of the key name, not as separators:

```shell
dial-cli role update roles/platform/example-user \
  --set limits.models/public/example-chat-model=null
```

**Expected result:**
```
Updated roles/platform/example-user
```

---

## 6. Individual CRUD Operations

### Step 6.1 — Add a new model via template

`add` fails if the entity already exists (exit code 5).

`--from-file` is required — it provides the spec body (displayName, userRoles, etc.).
The file may be a full manifest envelope (`kind`/`name`/`spec`) or a raw spec.
`--template` and `--param` from CLI flags override any values in the file.

**Input:**
```shell
dial-cli model add \
  --name models/public/another-chat-model \
  --from-file manifests/base/06-model.yaml \
  --template bedrock-chat \
  --param 'regions=[eu-west-1]'
```

**Expected result:**
```
Created models/public/another-chat-model
```

**If the model already exists:**
```
Already exists: models/public/another-chat-model
```
Exit code: 5

---

### Step 6.2 — Delete an entity

**Input:**
```shell
dial-cli model delete models/public/another-chat-model
```

**Expected result:**
```
Deleted models/public/another-chat-model
```

**If not found:**
```
Not found: models/public/another-chat-model
```
Exit code: 4

---

### Step 6.3 — Delete global settings (revert to file defaults)

**Input:**
```shell
dial-cli settings delete
```

**Expected result:**
```
Deleted settings/platform/global
```
DIAL Core reverts to file/default settings immediately.

> **Note:** `settings delete` is idempotent — it always returns 204, even when no API
> blob exists. It never exits with code 4 (unlike regular entity delete).

---

## 7. Promote Between Environments

Promotion copies a single entity from one environment to another via the admin apply API.
No file is touched — the CLI GETs from the source env and POSTs to the target env.

*Prerequisites for this section: the base sweep from Step 4.3 must be applied.*

### Step 7.1 — As-is promote (safe for roles and keys)

Entities with no environment-specific hostnames (roles, keys, schemas) can be copied
verbatim.

**Input:**
```shell
dial-cli role promote --from review --to local \
  --name roles/platform/example-user --dry-run
```

**Expected result:**
```
{"manifests":[{"kind":"Role","name":"roles/platform/example-user","spec":{...}}],"precheck":true}
```
The raw JSON apply envelope is printed (same format as `apply --dry-run`). Pipe through
`jq` to inspect the resolved spec. Without `--dry-run` the role is written to the `local` env.

---

### Step 7.2 — Template-driven promote (models with env-specific URLs)

`example-chat-model` was stamped from the `bedrock-chat` template. Its `endpoint` and
`iconUrl` contain `vars.adapter_host` / `vars.icon_base_url` from the `review` env.
Promoting as-is leaks review hostnames — use `--template` to re-resolve against `local`.

**Input:**
```shell
dial-cli model promote --from review --to local \
  --name models/public/example-chat-model \
  --template bedrock-chat \
  --param 'regions=[us-east-1]' \
  --dry-run
```

**Expected result:**
```
{"manifests":[{"kind":"Model","name":"models/public/example-chat-model","spec":{"displayName":"...","endpoint":"...","iconUrl":"https://example.com/icons/example-chat-model.svg","forwardAuthToken":true,"upstreams":[{"endpoint":"...","extraData":{"region":"us-east-1"}}],...}}],"precheck":true}
```
The raw JSON apply envelope is printed. The template re-resolved against `local` vars, so
`iconUrl` is set from `vars.icon_base_url`, `forwardAuthToken` is `true`, and only one
region is present (overridden by `--param`). Pipe through `jq` to inspect specific fields:
```shell
dial-cli model promote ... --dry-run | jq '.manifests[0].spec | {iconUrl, forwardAuthToken}'
```

---

### Step 7.3 — Auto-detect template

When you don't know which template was used, let the CLI reverse-match it.

**Input:**
```shell
dial-cli model promote --from review --to local \
  --name models/public/example-chat-model \
  --template auto --dry-run
```

**Expected result:**
```
{"manifests":[{"kind":"Model","name":"models/public/example-chat-model","spec":{...}}],"precheck":true}
```
The template is matched silently — no "auto-matched" message is printed. The resolved spec
in the envelope uses the matched template applied against `local` vars.

**If no template matches:**
```
No template matches the source entity. Available: bedrock-chat, chat-base, forward-auth-when-enabled. Use --template <name> explicitly.
```
Exit code: 2

**If multiple templates match:**
```
Multiple templates match: bedrock-chat, chat-base. Use --template <name> explicitly.
```
Exit code: 2

---

## 8. Diff Between Environments

### Step 8.1 — Diff a single entity

**Input:**
```shell
dial-cli model diff --source review --target local \
  --name models/public/example-chat-model
```

**Expected result (entity exists in both, local has `iconUrl` set):**
```
~ spec.forwardAuthToken: false → true
~ spec.iconUrl: "" → "https://example.com/icons/example-chat-model.svg"
~ spec.upstreams: [...] → [...]
```

**Expected result (entity exists only in review, not yet promoted to local):**
```
- name: "models/public/example-chat-model"
- spec: {...}
```

---

## 9. Bundle: Onboard a Model End-to-End

A **Bundle** is a single manifest that groups operationally-coherent entities — model,
role rate-limit patch, and CI key — under a shared `params` block. The CLI expands it
into individual apply entries before sending; the server never sees `kind: Bundle`.

`manifests/bundles/onboard-example.yaml`:
```yaml
kind: Bundle
name: onboard-rollout-model
params:
  model_name: example-rollout-model
  regions: [us-east-1, us-west-2]
  rate_limit_minute: "200000"
  rate_limit_day: "20000000"

entities:
  - kind: Model
    name: "models/public/${params.model_name}"
    template: bedrock-chat
    params:
      regions: "${params.regions}"
    spec:
      displayName: "Example Rollout Model"
      userRoles: ["example-user"]

  - kind: Role
    name: roles/platform/example-user
    patch:                              # RFC 7396 merge against existing role
      limits:
        "models/public/${params.model_name}":
          minute: "${params.rate_limit_minute}"
          day: "${params.rate_limit_day}"

  - kind: Key
    name: "keys/platform/${params.model_name}-ci"
    spec:
      project: "ExampleRollout"
      roles: ["example-user"]
      secured: false
      key: "${SECRET:DIAL_ROLLOUT_KEY}"   # required — server rejects blank Key.key
```

### Step 9.1 — Preview the expanded bundle

**Input:**
```shell
dial-cli apply -f manifests/bundles/onboard-example.yaml --env review --dry-run
```

**Expected result:**
```
{"manifests":[{"kind":"Model","name":"models/public/example-rollout-model","spec":{...}},{"kind":"Role","name":"roles/platform/example-user","spec":{...merged limits...}},{"kind":"Key","name":"keys/platform/example-rollout-model-ci","spec":{...}}],"precheck":true}
```
The bundle is expanded into individual manifest entries before printing. Inspect the
expanded result with `jq`:
```shell
dial-cli apply -f manifests/bundles/onboard-example.yaml --env review --dry-run \
  | jq '.manifests[] | {kind, name}'
```
Check that the Role entry's `spec.limits` contains both
`"models/public/example-chat-model"` (existing) and `"models/public/example-rollout-model"`
(added by bundle patch) — not replaced.

---

### Step 9.2 — Apply the bundle

> **Prerequisite:** The bundle creates a Key — `Key.key` is required.
> ```shell
> export DIAL_ROLLOUT_KEY=<your-api-key-value>
> ```

**Input:**
```shell
dial-cli apply -f manifests/bundles/onboard-example.yaml --env review
```

**Expected result:**
```
applied: 3, failed: 0
```

### Step 9.3 — Verify

**Input:**
```shell
dial-cli role get roles/platform/example-user -o yaml
```

**Expected result:**
```yaml
spec:
  limits:
    "models/public/example-chat-model":
      minute: "100000"
      day: "10000000"
    "models/public/example-rollout-model":     # ← added by bundle
      minute: "200000"
      day: "20000000"
```
Both limits are present — the bundle's `patch:` deep-merged without touching the original.

---

## 10. Overlays: Environment-specific Patches

Overlays split shared base manifests from per-environment deltas. Applied with
`--overlay <dir>`, the CLI merges overlay manifests on top of the base set before
sending — without modifying the base files.

`manifests/overlays/staging/` ships three mechanisms:

| File | Kind | Effect |
|------|------|--------|
| `03-role.yaml` | `RoleOverlay` | RFC 7396 patch — bumps rate limit for local |
| `06-model.yaml` | `ModelOverlay` | Adds `pricing` block; overrides `regions` to one region |
| `04-key.disable` | zero-byte marker | Removes `base/04-key.yaml` from the local set |

### Step 10.1 — Preview base + overlay for local

**Input:**
```shell
dial-cli apply -f manifests/base/ --overlay manifests/overlays/staging/ \
  --env local --dry-run
```

**Expected result:**
```
{"manifests":[{"kind":"Schema","name":"schemas/public/example-app-type","spec":{...}},{"kind":"Interceptor",...},{"kind":"Role",...},{"kind":"Route",...},{"kind":"Model",...},{"kind":"ToolSet",...},{"kind":"Application",...},{"kind":"Settings",...}],"precheck":true}
```
8 instead of 9 manifests — `04-key.disable` removes the CI key from the set before the
envelope is built. Inspect with `jq`:
```shell
dial-cli apply -f manifests/base/ --overlay manifests/overlays/staging/ --env local --dry-run \
  | jq '[.manifests[] | {kind, name}]'
```

---

### Step 10.2 — Apply base + overlay to local

> **Note:** The key is disabled for local via `04-key.disable`, so `DIAL_CI_KEY` is
> not needed for this apply.

**Input:**
```shell
dial-cli apply -f manifests/base/ --overlay manifests/overlays/staging/ \
  --env local
```

**Expected result:**
```
applied: 8, failed: 0
```

### Step 10.3 — Verify the overlay was applied

**Input:**
```shell
dial-cli model get models/public/example-chat-model --env local -o yaml | grep -A5 pricing
```

**Expected result:**
```yaml
  pricing:
    unit: token
    prompt: "0.0000025"
    completion: "0.0000150"
```
The `pricing` block came from the `ModelOverlay` — it is absent in the `review` copy.

**Input:**
```shell
dial-cli key get keys/platform/example-ci-key --env local
```

**Expected result:**
```
Error: 404 — Entity not found.
Exit code: 4
```
The key was suppressed by `04-key.disable`.

---

## Exit Codes Reference

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Network error, HTTP 5xx, or partial batch failure |
| 2 | Validation error or bad input (HTTP 400, 422) |
| 3 | Authentication failure |
| 4 | Entity not found (404) |
| 5 | Entity already exists — use `update` (412 on `add`) |
| 6 | Stale ETag — re-read and retry (412 on `update`/`delete`) |

Use exit codes in CI pipelines to distinguish "already configured" (5) from actual errors.

---

## Quick Reference

```shell
# Profileless / ad-hoc (no config.yaml required)
dial-cli get models --api-url http://host:8080 --api-key-file api_key_local
dial-cli get models --api-url http://host:8080   # prompts for key if TTY available

# Environments
dial-cli env list
dial-cli env current
dial-cli env use local
dial-cli env check [--env review]

# Read
dial-cli get models|applications|roles|keys|toolsets|routes|interceptors|schemas   # api + file merged
dial-cli model get models/public/<name> -o yaml|json|table    # canonical id -> API-managed
dial-cli model get <plain-name> -o yaml|json|table            # plain name -> file-config
dial-cli settings get

# Apply (bulk deploy)
dial-cli apply -f manifests/base/            # whole directory
dial-cli apply -f manifests/base/06-model.yaml
dial-cli apply -f manifests/base/ --dry-run  # preview only

# Apply with overlay (env-specific patches)
dial-cli apply -f manifests/base/ \
  --overlay manifests/overlays/staging/ \
  --env local

# Update in-place  (single-quoted strings pass values verbatim; \, escapes commas in arrays)
dial-cli model update models/public/<name> \
  --set 'displayName="New Name"' \
  --set features.toolsSupported=false
dial-cli settings update --set 'retriableErrorCodes=[502\,503\,504]'

# --set with keys containing slashes (dot separates segments; slash is part of the key)
dial-cli role update roles/platform/<name> \
  --set 'limits.models/public/<model>.minute="200000"'

# --set with a string array (use \, to escape commas from picocli's Map split)
dial-cli model update models/public/<name> \
  --set 'userRoles=["example-user"\,"admin"]'

# Remove a field (set to null)
dial-cli model update models/public/<name> --set features.toolsSupported=null
dial-cli role update roles/platform/<name> --set limits.models/public/<model>=null

# Validate (no write — POST /v1/admin/validate)
dial-cli model validate --name models/public/<name> --from-file <spec.yaml> \
  --template bedrock-chat --param 'regions=[us-east-1]'
dial-cli settings validate --from-file <spec.yaml>

# Add / delete
dial-cli model add --name models/public/<name> --from-file <spec.yaml> \
  --template bedrock-chat --param 'regions=[us-east-1]'
dial-cli model delete models/public/<name>
dial-cli settings delete

# Promote
dial-cli model promote --from review --to local \
  --name models/public/<name> \
  --template bedrock-chat --param 'regions=[us-east-1]'
dial-cli role promote --from review --to local \
  --name roles/platform/<name>                     # as-is (no template)

# Diff
dial-cli model diff --source review --target local --name models/public/<name>

# Shell completion
dial-cli completion bash >> ~/.bash_completion
dial-cli completion zsh  >> ~/.zshrc
```
