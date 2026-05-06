# dial-cli playground

A minimal, runnable playground for `dial-cli`. One profile, one manifest per
writable entity type, ~5-minute newcomer path against a local DIAL Core.

Sibling of `sample/aidial.config.json` (server config) and
`sample/aidial.settings.json` (server static settings) — those reference what
DIAL Core *serves*; this references what `dial-cli` *sends*.

## Layout

```
sample/dial-cli/
├── config.yaml          — single-environment profile (`local` → http://localhost:8080)
├── manifests/           — numbered by server's apply dependency order
│   ├── 01-schema.yaml
│   ├── 02-interceptor.yaml
│   ├── 03-role.yaml
│   ├── 04-key.yaml
│   ├── 05-route.yaml
│   ├── 06-model.yaml
│   ├── 07-toolset.yaml
│   ├── 08-application.yaml
│   └── 09-settings.yaml
└── README.md            — this file
```

## Prerequisites

1. **A running DIAL Core on `http://localhost:8080`.**
   Fastest route — the bundled image (per `06-cli-user-guide.md` §1.1.1):

   ```shell
   docker run --rm --name dial-core -p 8080:8080 -p 9464:9464 \
     ghcr.io/epam/ai-dial-core:<version>
   ```

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
     self-contained (no host env-var setup required for `DIAL_CLI_CONFIG`):

     ```shell
     alias dial-cli='docker run --rm --network host \
       -v "$PWD:/work:ro" -w /work \
       -e DIAL_CLI_CONFIG=/work/config.yaml \
       -e DIAL_LOCAL_API_KEY \
       ghcr.io/epam/ai-dial-core:<version> dial-cli'
     ```

     On macOS / Windows Docker Desktop swap `--network host` for
     `--add-host=host.docker.internal:host-gateway` and change
     `api_url` in `config.yaml` to `http://host.docker.internal:8080`.

   - **Standalone JAR** (after `./gradlew :cli:build`) — a function
     auto-resolves the profile from `$PWD/config.yaml` so the UX is
     symmetric with the docker alias:

     ```shell
     dial-cli() {
       DIAL_CLI_CONFIG="$PWD/config.yaml" \
         java -jar /abs/path/to/ai-dial-core/cli/build/cli-0.0.0-runner.jar "$@"
     }
     ```

## Quickstart

```shell
cd sample/dial-cli                      # both aliases above assume this
export DIAL_LOCAL_API_KEY=<your-admin-key>

# Inspect runtime state (file-sourced entities show source: file).
dial-cli env current                    # → local
dial-cli get models
dial-cli get roles

# Apply the whole playground in one shot.
cat manifests/*.yaml > /tmp/playground-all.yaml
dial-cli apply -f /tmp/playground-all.yaml

# Or one entity at a time.
dial-cli apply -f manifests/06-model.yaml

# Verify — the new entries show source: api.
dial-cli get models
dial-cli get roles
```

## Common commands

Once the playground is applied, these are the day-to-day verbs you'll reach
for. Full surface in `06-cli-user-guide.md` §2.

### Read

```shell
# kubectl-style alias (plural noun → list).
dial-cli get models
dial-cli get roles
dial-cli get keys

# Single entity, full body — secrets stay masked as "***".
dial-cli model get models/public/example-chat-model -o yaml
dial-cli role get roles/platform/example-user
dial-cli settings get                   # singleton — no name argument
```

### Update — `--set` flag (GET → local merge → PUT)

```shell
# Scalar fields.
dial-cli model update models/public/example-chat-model \
  --set 'displayName="Example Chat (renamed)"' \
  --set features.toolsSupported=true

# JSON-array values are passed quoted.
dial-cli model update models/public/example-chat-model \
  --set 'userRoles=["example-user","admin"]'

# Singleton update — upsert, no 404 path on first call.
dial-cli settings update --set 'retriableErrorCodes=[502,503,504]'

# Optional optimistic concurrency (412 / exit 6 on stale ETag).
dial-cli model update models/public/example-chat-model \
  --set maxTotalTokens=128000 --if-match "<etag-from-prior-get>"
```

### Validate / dry-run before mutating

```shell
# Validate one manifest against the server's evaluator (no write).
dial-cli model validate --name models/public/example-chat-model \
  --from-file manifests/06-model.yaml

# Preview an add or apply locally — exits 0, no HTTP, prints assembled JSON.
dial-cli model add --name models/public/another-model \
  --from-file manifests/06-model.yaml --dry-run
dial-cli apply -f /tmp/playground-all.yaml --dry-run
```

### Delete / tear down

```shell
dial-cli model delete models/public/example-chat-model       # 404 / exit 4 if absent
dial-cli model delete models/public/example-chat-model --if-match "<etag>"
dial-cli role delete roles/platform/example-user
dial-cli settings reset                  # release API control, fall back to file/default
```

### Promote / diff between environments

Add a second environment to `config.yaml` first (e.g. `dev` pointing at a
different DIAL Core), then:

```shell
dial-cli diff --source local --target dev
dial-cli model diff --source local --target dev --name models/public/example-chat-model

# Promote — as-is mode in MVP (template DSL deferred to 4C.1).
dial-cli model promote --from local --to dev --name models/public/example-chat-model
```

### Environment management

```shell
dial-cli env list
dial-cli env current
dial-cli env use local                   # persist defaults.env
dial-cli env check --env local           # config-only validation (no network probe in MVP)
```

## Exit codes

`0` success; `1` partial-batch / general failure; `2` validation; `3` auth;
`4` 404; `5` 409 (conflict on `add`); `6` 412 (stale ETag). Full contract:
`06-cli-user-guide.md` §2.8.

## Caveats

- This is a **config playground**, not a working LLM stack — upstreams in
  `06-model.yaml` and `08-application.yaml` point at non-existent hosts.
  Replace them with your real adapter / dev endpoint before chat-completion
  works against this model.
- Secret fields (`upstreams[].key`, `Key.key`) are placeholders. In real
  workflows source them from env / vault per `06-cli-user-guide.md` §2.1.
- Manifests are fully resolved YAML — **no template DSL, overlays, or
  bundles.** Those are deferred beyond MVP per `IMPLEMENTATION.md §5.5`
  (slices 4C.1–4C.5).
- `dial-cli apply -f <directory>` recursive walk is also deferred (4C.7);
  hence the explicit per-file or `cat`-into-temp-file pattern above.
- The docker alias mounts `$PWD` **read-only**, so `dial-cli env use` won't
  persist back to `config.yaml` from inside the container. Drop the `:ro`
  if you want to test that path; safer to leave it on for alpha CI.

## See also

- `docs/sandbox/dial-unified-config/06-cli-user-guide.md` — full operator guide
- `docs/sandbox/dial-unified-config/05-cli-design.md` — CLI internals
- `docs/sandbox/dial-unified-config/03-api-reference.md` — wire protocol
