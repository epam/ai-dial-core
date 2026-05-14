# dial-cli playground

A runnable playground for `dial-cli`. Two CLI environments, one manifest per
writable entity type in both YAML and JSON, plus templates, overlays, and a
secrets demo — covering the full Phase-1–4 MVP surface that has landed.

Sibling of `sample/aidial.config.json` (server config) and
`sample/aidial.settings.json` (server static settings) — those reference what
DIAL Core *serves*; this references what `dial-cli` *sends*.

## Layout

```
sample/dial-cli/
├── config.yaml                       — two envs (local, staging) + 3 templates
├── manifests/
│   ├── base/                         — shared manifests (one per entity type)
│   │   ├── 01-schema.yaml
│   │   ├── 02-interceptor.yaml
│   │   ├── 03-role.yaml
│   │   ├── 04-key.yaml
│   │   ├── 05-route.yaml
│   │   ├── 06-model.yaml             — uses `template: bedrock-chat`
│   │   ├── 07-toolset.yaml
│   │   ├── 08-application.yaml
│   │   └── 09-settings.yaml
│   ├── overlays/
│   │   └── staging/                  — JSON Merge Patch overrides + .disable
│   │       ├── 03-role.yaml          — kind: RoleOverlay, looser limits
│   │       ├── 04-key.disable        — zero-byte marker, key not shipped
│   │       └── 06-model.yaml         — kind: ModelOverlay, +pricing, 1 region
│   └── secrets-demo.yaml             — ${SECRET:*} + ${ENV_VAR} demo (opt-in)
├── manifests-json/                   — same 9 entities in JSON + 1 array file
│   ├── 00-batch-array.json           — array-of-objects (JSON multi-doc)
│   ├── 01-schema.json
│   ├── … (02–09 mirror manifests/base/)
│   └── 09-settings.json
└── README.md                         — this file
```

## Prerequisites

1. **A running DIAL Core on `http://localhost:8080`.**
   Fastest route — the bundled image (per `06-cli-user-guide.md` §1.1.1):

   ```shell
   docker run --rm --name dial-core -p 8080:8080 -p 9464:9464 \
     ghcr.io/epam/ai-dial-core:<version>
   ```

   Both `local` and `staging` profile envs point at the same URL so the
   playground stays single-container while still exercising multi-env CLI
   features (`--env`, `--overlay`, `promote`, `diff`).

2. **An admin API key.** For an alpha test, the file-shipped keys in
   `sample/aidial.config.json` (`proxyKey1`, `proxyKey2`) work as long as
   `aidial.settings.json` `access.admin.rules` matches their role. Set:

   ```shell
   export DIAL_LOCAL_API_KEY=<your-admin-key>
   ```

3. **`dial-cli` on your shell.** Both forms below assume **you `cd` into
   `sample/dial-cli/` first**, so `$PWD` carries the profile + manifests.

   - **Bundled in the core image** — `$PWD` is mounted as `/work` and the
     profile is hardcoded to `/work/config.yaml`, so the alias is
     self-contained:

     ```shell
     alias dial-cli='docker run --rm --network host \
       -v "$PWD:/work:ro" -w /work \
       -e DIAL_CLI_CONFIG=/work/config.yaml \
       -e DIAL_LOCAL_API_KEY \
       ghcr.io/epam/ai-dial-core:<version> dial-cli'
     ```

     On macOS / Windows Docker Desktop swap `--network host` for
     `--add-host=host.docker.internal:host-gateway` and change `api_url`
     in `config.yaml` to `http://host.docker.internal:8080`.

   - **Standalone JAR** (after `./gradlew :cli:build`) — a function
     auto-resolves the profile from `$PWD/config.yaml`:

     ```shell
     dial-cli() {
       DIAL_CLI_CONFIG="$PWD/config.yaml" \
         java -jar /abs/path/to/ai-dial-core/cli/build/cli-0.0.0-runner.jar "$@"
     }
     ```

## Quickstart

```shell
cd sample/dial-cli                                      # both aliases above assume this
export DIAL_LOCAL_API_KEY=<your-admin-key>

# Inspect runtime state (file-sourced entities show source: file).
dial-cli env current                                    # → local
dial-cli get models
dial-cli get roles

# Apply the whole base/ tree in one shot — directory walk picks up every
# .yaml / .yml / .json file under the path.
dial-cli apply -f manifests/base/

# Or one entity at a time.
dial-cli apply -f manifests/base/06-model.yaml

# Verify — the new entries show source: api with the canonical id.
dial-cli get models
dial-cli get roles

# Preview without writing — fully-resolved manifests print to stdout, no HTTP.
dial-cli apply -f manifests/base/ --dry-run
```

The JSON tree is interchangeable with the YAML tree:

```shell
dial-cli apply -f manifests-json/ --env local --dry-run
```

## Templates (4C.1)

`config.yaml` ships three templates demonstrating composition + control flow:

| Template | Shape | Highlights |
|---|---|---|
| `chat-base` | own `fields` only | Common chat-model feature set |
| `forward-auth-when-enabled` | mixin (`!if` guard) | Conditional `forwardAuthToken` based on `${vars.forward_auth_token}` |
| `bedrock-chat` | `extends: chat-base` + `includes: [forward-auth-when-enabled]` | `!for` over `${params.regions}`, `${entity.name}` substitution, `default(…)` and `replace(…)` function calls |

`manifests/base/06-model.yaml` references the template:

```yaml
kind: Model
name: models/public/example-chat-model
template: bedrock-chat
params:
  regions: [us-east-1, us-west-2]
spec:
  displayName: "Example Chat Model"
  userRoles: ["example-user"]
```

The CLI deep-merges template `fields` into `spec` at write time (stamped, not
live — per OQ-29). The two envs resolve differently:

```shell
dial-cli apply -f manifests/base/06-model.yaml --env local   --dry-run   # → no forwardAuthToken
dial-cli apply -f manifests/base/06-model.yaml --env staging --dry-run   # → forwardAuthToken: true
```

Full DSL surface (functions, `!if` / `!for` semantics, merge order, cycle
detection) is in `05-cli-design.md §3` and `06-cli-user-guide.md §1.2`.

> **`!if` quoting gotcha.** Write `!if ${vars.x} == 'true':` (expression
> *unquoted*, embedded literals single-quoted). Wrapping the whole expression
> in double quotes turns it into a single string literal and the comparison
> stops working — placeholder substitution never runs.

## Environment overlays (4C.2)

Overlays split shared manifests from per-env deltas. `manifests/overlays/staging/`
contains three overlay files demonstrating the three available mechanisms:

| File | Kind | Effect |
|---|---|---|
| `03-role.yaml` | `RoleOverlay` | RFC 7396 JSON Merge Patch — bumps `limits["models/public/example-chat-model"]` |
| `06-model.yaml` | `ModelOverlay` | `patch:` adds a `pricing` block; `params:` overrides `regions` (cascades into template `!for`) |
| `04-key.disable` | zero-byte marker | Excludes `manifests/base/04-key.yaml` from the staging effective set |

Apply base + overlay:

```shell
dial-cli apply -f manifests/base/ --overlay manifests/overlays/staging/ --env staging --dry-run
```

Resulting batch: 8 manifests (the key is gone), the Model has one upstream
region instead of two, and `pricing.prompt` is set.

`.disable` marker matching is byte-equal stem + identical relative directory.
Format and the four-row truth table are documented in `05-cli-design.md §5.2`.

## Secrets & env vars (4C.4)

Both `${SECRET:NAME}` and bare `${NAME}` resolve via `System.getenv("NAME")` at
write time. Missing env vars fail loudly — no silent empty-string substitution.

`manifests/secrets-demo.yaml` is **not** part of the default `apply -f base/`
sweep; apply it explicitly with the env vars set:

```shell
export DIAL_UPSTREAM_HOST=http://localhost:7001
export DIAL_UPSTREAM_KEY=sk-...
dial-cli apply -f manifests/secrets-demo.yaml --env local --dry-run
```

Vault / OS-keychain extension stays deferred (OQ-19); env-var resolution is the
MVP path. See `05-cli-design.md §3.1` and `06-cli-user-guide.md §2.1`.

## JSON manifests

`manifests-json/` is a 1:1 mirror of `manifests/base/` in JSON form. The model
manifest is shipped fully-resolved (no template reference) so external
pipelines that generate JSON can ship self-contained specs. Both single-object
and array-of-objects forms are supported:

```json
[
  { "kind": "Role", "name": "roles/platform/example-batch-role", "spec": { "limits": { … } } },
  { "kind": "Key",  "name": "keys/platform/example-batch-key",   "spec": { "project": "BatchDemo", … } }
]
```

`00-batch-array.json` demonstrates the array form. Directory walk picks up both
shapes mixed in the same tree:

```shell
dial-cli apply -f manifests-json/                          # 11 entities (9 single + 2 from array)
dial-cli apply -f manifests-json/01-schema.json --env local
```

Overlay manifests in JSON form are supported by the same code path; the
playground only ships YAML overlays since `kind` / `target` / `patch` reads
more naturally in YAML.

## Common commands

Once a base or overlay sweep has been applied, these are the day-to-day verbs.
Full surface in `06-cli-user-guide.md §2`.

### Read

```shell
dial-cli get models                                         # kubectl-style alias
dial-cli get roles
dial-cli get keys

dial-cli model get models/public/example-chat-model -o yaml # full body, secrets masked
dial-cli role get roles/platform/example-user
dial-cli settings get                                       # singleton — no name argument
```

### Update — `--set` flag (GET → local merge → PUT)

```shell
dial-cli model update models/public/example-chat-model \
  --set 'displayName="Example Chat (renamed)"' \
  --set features.toolsSupported=true

dial-cli model update models/public/example-chat-model \
  --set 'userRoles=["example-user","admin"]'

dial-cli settings update --set 'retriableErrorCodes=[502,503,504]'

dial-cli model update models/public/example-chat-model \
  --set maxTotalTokens=128000 --if-match "<etag-from-prior-get>"
```

### Add via template — `--template` + `--param`

```shell
# Resolves bedrock-chat from config.yaml against --env local's vars
dial-cli model add --name models/public/another-chat-model \
  --template bedrock-chat \
  --param 'regions=[us-east-1]' \
  --set displayName="Another Model" \
  --set userRoles='["example-user"]'
```

### Validate / dry-run

```shell
dial-cli model validate --name models/public/example-chat-model \
  --from-file manifests/base/06-model.yaml

dial-cli model add --name models/public/another-model \
  --from-file manifests/base/06-model.yaml --dry-run
dial-cli apply -f manifests/base/ --dry-run
```

### Delete / tear down

```shell
dial-cli model delete models/public/example-chat-model
dial-cli model delete models/public/example-chat-model --if-match "<etag>"
dial-cli role delete roles/platform/example-user
dial-cli settings reset                                     # release API control
```

### Promote / diff between environments

```shell
dial-cli diff --source local --target staging
dial-cli model diff --source local --target staging \
  --name models/public/example-chat-model

# As-is promote — copies the entity verbatim
dial-cli model promote --from local --to staging \
  --name models/public/example-chat-model
```

### Environment management

```shell
dial-cli env list
dial-cli env current
dial-cli env use staging                                    # persist defaults.env
dial-cli env check --env local                              # config-only validation
```

## Exit codes

`0` success; `1` partial-batch / general failure; `2` validation; `3` auth;
`4` 404; `5` 409 (conflict on `add`); `6` 412 (stale ETag). Full contract:
`06-cli-user-guide.md §2.8`.

## Caveats

- This is a **config playground**, not a working LLM stack — upstreams in
  `06-model.yaml` and `08-application.yaml` point at non-existent hosts.
  Replace them with your real adapter / dev endpoint before chat-completion
  works against this model.
- Secret fields (`upstreams[].key`, `Key.key`) in the base manifests are
  placeholders. The `secrets-demo.yaml` shows the env-var-driven pattern; in
  real workflows source secrets from env / vault per `06-cli-user-guide.md §2.1`.
- **Bundles (`kind: Bundle`)** are not yet implemented (slice 4C.3 📋) and
  rejected by `ManifestLoader`. Use the directory-apply pattern for multi-entity
  batches.
- **`promote --template <name|auto>`** is not yet implemented (slice 4C.5 📋).
  Only as-is promote works; for template-driven multi-env rollouts use the
  overlay pattern above.
- The docker alias mounts `$PWD` **read-only**, so `dial-cli env use` won't
  persist back to `config.yaml` from inside the container. Drop the `:ro` if
  you want to test that path; safer to leave it on for alpha CI.

## See also

- `docs/sandbox/dial-unified-config/06-cli-user-guide.md` — full operator guide
- `docs/sandbox/dial-unified-config/05-cli-design.md` — CLI internals (templates, overlays, DSL)
- `docs/sandbox/dial-unified-config/03-api-reference.md` — wire protocol
- `docs/sandbox/dial-unified-config/IMPLEMENTATION.md` — slice register (which features have landed)
