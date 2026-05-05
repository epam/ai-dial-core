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

3. **`dial-cli` available.** Either:
   - **Bundled in the core image** — alias the docker invocation:

     ```shell
     alias dial-cli='docker run --rm --network host \
       -e DIAL_CLI_CONFIG -e DIAL_LOCAL_API_KEY \
       -v "$PWD/sample/dial-cli:/work:ro" -w /work \
       ghcr.io/epam/ai-dial-core:<version> dial-cli'
     ```

   - **Standalone JAR** after `./gradlew :cli:build`:

     ```shell
     alias dial-cli='java -jar /abs/path/to/cli/build/cli-0.0.0-runner.jar'
     ```

## Quickstart

```shell
cd sample/dial-cli
export DIAL_CLI_CONFIG="$PWD/config.yaml"
export DIAL_LOCAL_API_KEY=<your-admin-key>

# Inspect runtime state (file-sourced entities show source: file).
dial-cli env current               # → local
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

# Tear down.
dial-cli model delete models/public/example-chat-model
dial-cli role delete roles/platform/example-user
# … or `dial-cli settings reset` for the singleton.
```

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
  hence the explicit per-file or `cat | tee` pattern above.

## See also

- `docs/sandbox/dial-unified-config/06-cli-user-guide.md` — full operator guide
- `docs/sandbox/dial-unified-config/05-cli-design.md` — CLI internals
- `docs/sandbox/dial-unified-config/03-api-reference.md` — wire protocol
