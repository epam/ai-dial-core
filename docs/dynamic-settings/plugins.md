# Dynamic Setting for Plugins (preview)

A plugin is Java code that DIAL Core runs in process, instead of calling a service over HTTP. The code comes as a jar that Core loads at start; a `plugins` entry declares one instance of that code with its own settings.

> **Preview.** Plugins are in preview. Until they are declared stable, a DIAL Core release may change them in a backward-incompatible way: the `plugins` entries, the `dial-plugin://` base URLs that name them, the plugin endpoints of the Configuration API (`x-preview` in the OpenAPI specification), and the `aidial-core-plugin-api` module (`com.epam.aidial:aidial-core-plugin-api`) a plugin jar compiles against. A plugin jar is built for one DIAL Core version; rebuild it when you upgrade.

> In this version a plugin can answer a deployment interface in place of an HTTP origin: see [Origin plugins](#origin-plugins). Request and response filters come in a later version.

## plugins

A map of plugin instances.

* `<plugin_name>`: A unique name for this instance. Keep it URL-safe; a name containing `/` in a config file is dropped with a warning.

### plugins.<plugin_name>

* `type`: **Required.** The type of the plugin code that serves this instance, as its provider declares it. Which types exist depends on the jars loaded at start (see [Plugin code](#plugin-code)).
* `settings`: An object with the settings of this instance. Only the plugin code reads it; DIAL Core asks that code to check it. Optional, `{}` when absent.

No other field is accepted.

**Example**

```json
"plugins": {
    "my-plugin": {
        "type": "example",
        "settings": {
            "greeting": "hello"
        }
    }
}
```

Here `example` stands for the type of a jar you deploy; DIAL Core ships no plugin code of its own.

`settings` hold no secrets: DIAL Core stores them as written, in the config files and in the `platform` bucket, and returns them as written. A plugin that needs credentials gets them from the environment of the DIAL Core pod.

## How an entry is checked

DIAL Core checks each entry against the plugin code of its `type`, on every write and on every config reload. Two things can be wrong, and they are treated differently:

* **The settings are rejected by the plugin code.** The entry itself is wrong. Plugin code that fails while checking the settings counts as rejecting them. A write through the Configuration API is refused with `422` and a warning whose `field` is `settings` or `settings.<path>`, whatever the mode; in `/v1/admin/validate` and `/v1/admin/apply` the entry's result is `FAILED` with that warning. On a reload, the default `config.onInvalidEntity: abort` keeps the previous config live; with `skip`, the entry alone is dropped and recorded as invalid, visible in `GET /v1/admin/health/config` and, for an entry in the bucket, in the entry's `GET` (`status: "invalid"`).
* **No loaded plugin code declares the type.** This is treated like a model that names an interceptor which does not exist. A write is refused with `422` and a warning on `type`, unless `config.write.softValidation` is `true`; in that mode `/v1/admin/apply` reports the entry `APPLIED_INVALID`. On a reload the type is checked only with `config.onInvalidEntity: skip`, where the entry is dropped and recorded as invalid; with the default `abort` the entry stays. This lets you write a declaration before every DIAL Core pod runs the version that carries its jar.

## Origin plugins

An origin plugin answers the requests of a deployment interface inside DIAL Core, in place of the HTTP call to the deployment's endpoint. A model or an application names it by a base URL of the form `dial-plugin://<plugin_name>`, in an interface entry's `base_url`:

```json
"interfaces": {
    "openaiChatCompletions": { "base_url": "dial-plugin://my-plugin" }
}
```

or in the deployment-level `baseUrl`, which serves every interface entry that declares no `base_url` of its own. The name is the text after `dial-plugin://` up to the next `/`; a path may follow it (`dial-plugin://my-plugin/v2`), and DIAL Core keeps it in front of the path it appends, as for an HTTP base URL. The scheme is lowercase. A plugin URL with no name is rejected, and so is one in `endpoint` or `responsesEndpoint`, which no plugin serves.

Any interface type can be served by a plugin: chat completions, embeddings, the Responses API with its item operations, and Anthropic Messages. An entry served by a plugin URL is a pass-through entry: `mode` stays absent or `passthrough`. Refer to [models](models.md) and [applications](applications.md) for the interface fields.

* **What the plugin receives.** The request DIAL Core would send to an HTTP origin at the plugin URL: the method, the path and query it would append to the base URL (after any path that follows the plugin's name), the same headers and the body. The headers carry the per-request key in `API-KEY`, `X-DIAL-DEPLOYMENT-FEATURES`, and for a model with upstreams the `X-UPSTREAM-*` headers of the chosen upstream. DIAL Core forwards the client's headers too, as it does to an HTTP origin, so trust `X-UPSTREAM-*` only for a model with upstreams: elsewhere the client may have set them. There is no `traceparent` header.
* **More than an HTTP origin gets.** The body as a parsed JSON object as well (`bodyTree`), so the plugin needs no parse of its own; it is read-only unless the plugin passes it to a call. And the caller (`caller`): the user id of a caller with a JWT, the caller's roles, and its project. A routing plugin needs no `/v1/user/info` call.
* **What DIAL Core does with the answer.** The same as with an HTTP origin's: a retriable status moves to the next upstream, the body is relayed as it arrives, usage is parsed and charged, and one analytics row is written. The row's `response.upstream_uri` is the plugin URL, `dial-plugin://<name>` with any path after the name, for a deployment without upstreams.
* **When the plugin cannot serve.** DIAL Core answers `502` `Failed to connect to upstream server`, without trying another upstream, when no `plugins` entry has the name, its plugin code is not loaded, or the plugin cannot be created from its settings. A Responses item operation answers `500` `Failed to process response operation` in these cases, as it does when its HTTP origin cannot be reached. A plugin that fails a call counts as an origin that failed to send its response headers.
* **When the client goes away first.** DIAL Core watches the client while the plugin works, and tells the plugin through `HostDelegate.cancelled()` when the client leaves before the answer, so the plugin can stop its upstream work. A plugin that then fails its answer ends the request quietly: no other upstream is tried, and no analytics row is written. An answer it still gives is handled as an HTTP origin's answer after its client went away: a model's answer is read to the end and charged.
* **Settings changes.** DIAL Core creates the plugin from the entry's settings on the first request it serves, and again on the first request after the entry changes, handing it DIAL Core's Vert.x instance for the clients and timers it needs. A settings edit applies to the next request, with no restart. DIAL Core calls the plugin's `close()` on a thread that may block, never on an event loop, so that the plugin releases its clients, caches and timers: on the replaced plugin when the first request after the change creates its replacement, on the plugin of a removed entry at the next request that names the entry, and on every plugin when DIAL Core shuts down. DIAL Core does not wait for the requests still in flight on a plugin it closes: a plugin that wants them to end first waits for them in `close()`. Requests racing on a new or changed entry share one plugin.
* **Log records.** A plugin logs through DIAL Core's SLF4J. The fields it attaches to a record as SLF4J key-value pairs (`log.atInfo().addKeyValue("model", m).log("routed")`) appear in the record's `Attributes` in DIAL Core's JSON log line, after DIAL Core's own attributes, which a field of the same name does not replace. They are not copied to the span.
* **Platform deployments only.** A plugin runs platform code, so only the platform config (the config files and the `platform` bucket) can name one. A custom application in any other bucket with a plugin URL in its `baseUrl`, an interface's `base_url` or an inline translator's `baseUrl` is refused with `422`, and is never served by a plugin.
* **Rollout.** Deploy the DIAL Core image that carries the plugin before writing config that names it: an older DIAL Core does not serve an interface by a plugin. Roll back by writing the HTTP base URL back in place of the plugin URL.

Plugin code runs on DIAL Core's event loop. It must not block, and must answer and stream its body on the thread it was called on.

### Calling another deployment

While it answers a request, an origin plugin can call another deployment through DIAL Core, in process, with the `HostDelegate` its `handle` receives. DIAL Core serves the call as a request sent with the per-request key of the plugin's request, with no HTTP request into DIAL Core, no key lookup and no second parse of the body.

In process the host serves the create operations listed below. A plugin calls any other DIAL Core API (files, `GET /v1/user/info`, the resource and sharing APIs) over HTTP, with the per-request key its request carries in `API-KEY`, as an HTTP origin does; DIAL Core checks that key as for any client.

* **What a call reaches.** A `POST` to a create operation: a deployments action (`/openai/deployments/<name>/chat/completions`, `completions` or `embeddings`), `/openai/v1/chat/completions`, `/openai/v1/responses` (or `/openai/responses`) or `/anthropic/v1/messages`; the last four name the deployment in the body's `model`. Any other path, and a Responses call with `"background": true`, is refused: the call fails, and the plugin decides what to answer.
* **What the plugin passes.** The body as a parsed JSON object, which DIAL Core takes: the target's processing changes it in place, so a plugin that calls twice with one body passes a copy. The headers are copied. DIAL Core drops the ones it sets for an origin itself (`X-UPSTREAM-ENDPOINT`, `X-UPSTREAM-KEY`, `X-UPSTREAM-EXTRA-DATA`, `X-DIAL-CACHE-BREAKPOINT-PATH`, `X-DIAL-CACHE-EXTRA-METADATA`, `X-DIAL-OVERRIDE-NAME`, `X-DIAL-DEPLOYMENT-ID`, `X-DIAL-APPLICATION-ID`, `X-DIAL-APPLICATION-PROPERTIES` and `X-DIAL-DEPLOYMENT-FEATURES`), so a plugin may pass its own request's headers on.
* **What the plugin gets back.** The answer as a plugin's own answer looks: the status and headers, then the body as it arrives. Any status is an answer: an unknown deployment gives `404`, one the caller may not use `403`, a limit reached `429`.
* **How the call is checked, charged and logged.** As a request the plugin's deployment sends DIAL Core with its per-request key: the target's role, consent and access checks, its limits against the caller's, a token-stats span under the plugin's request, a per-request key for the target's origin, and an analytics row with `parent_deployment` the plugin's deployment. The row carries its bodies as a row of a request over HTTP does, so a plugin's call logs them twice: in the plugin's row and in the target's row, which holds the body the plugin passed.
* **When a call ends.** When DIAL Core cancels the plugin's answer (the client went away, or a retry moved past it), or the plugin cancels the call's answer, the target's request stops as one whose client went away does: a model target still reads its origin to the end and charges it. When the plugin's request ends, or a router in decision mode falls back from the plugin's deployment to another candidate, DIAL Core cancels the answers of its calls that are still open, and answers a later call `401` `Unknown api key`.

A plugin that calls its own deployment calls itself again and again. DIAL Core stops such a chain at 32 deployments: the call past that gets the answer `400` `Call depth limit reached: 32`.

### Translating for a deployment

A [translator](translators.md#translators-served-by-a-plugin) may name a plugin by its `baseUrl`, `dial-plugin://<plugin_name>`. The interface stays in `translator` mode, and the plugin receives the request DIAL Core would send to the translator service. It converts the request, calls the deployment's `out` interface through its host, and answers with the converted answer.

* **Call `out`, never your own interface.** DIAL Core checks that the deployment serves `out` pass-through; it cannot check what the plugin calls. A plugin that calls the interface it translates is called again and again.
* **Limits.** The client's request is neither limit-checked nor charged; the plugin's call is. A plugin that answers by itself, without the call, is charged nothing.
* **Responses.** A plugin translating `openaiResponses` receives the item operations of the responses it created (`GET`, `DELETE`, `cancel`) and answers them itself: a call through the host reaches create operations only, and refuses `"background": true`.
* **Not for interceptors.** An interceptor is always called over HTTP: one with a plugin URL in its `baseUrl`, an interface's `base_url` or an inline translator's `baseUrl` is rejected on write, and the requests of one whose named translator has a plugin URL fail as an unreachable interceptor.

### Deciding for a router

A [router in decision mode](routers.md#decision-mode) may name a plugin by a plugin URL in an interface's `base_url` or in its `baseUrl`. That plugin then receives the router's decide request in process, `POST /decide` (after any path that follows the plugin's name) with the headers and body DIAL Core would send to a router service, and answers it with the verdict, `{"candidates": [...]}`, as a router service does. DIAL Core serves the deployment the verdict names in the same pass; the plugin's answer is no deployment's answer.

* **The key.** The decide request's `API-KEY` is the decide request's key, and the plugin's calls through its host carry it, as a router service's calls with that header do. In process the host serves the create operations only: `GET /v1/user/info` and file reads go to DIAL Core over HTTP with that `API-KEY`. The caller is in the request, so a plugin rarely needs them.
* **When its calls end.** When the decide call ends, once DIAL Core has read the plugin's answer, DIAL Core cancels the plugin's calls still open and answers later ones `401`. A notice request (`X-DIAL-ROUTER-NOTICE: true`) carries no key: every call made while answering it is answered `401`.
* **Cancellation.** DIAL Core does not watch the client of a decide request. `HostDelegate.cancelled()` completes only when the plugin has not finished its answer, the status and the whole body, within the router's `decision.timeoutMs`; a plugin that finished its answer is never told.

### The forward sample

The module `plugin-samples` holds an origin plugin of type `forward`, with the settings `{"deployment": "<name>"}`. It sends each request on to that deployment through a call, and answers with the call's answer: a deployments action goes to the same action of that deployment, the other interfaces keep their path with `model` set to it. It forwards the create operations only: a Responses item operation or a Messages `count_tokens` request fails, as a call reaches nothing else. It is a worked example of a call and the vehicle for measuring one. The default Docker image does not carry it; build an image with `--build-arg SAMPLE_PLUGINS=true` to put its jar in the plugin directory `samples/forward`, that is `/app/plugins/samples/forward`.

## Plugin code

DIAL Core loads plugin code once, at start, from its own classpath and from the plugin directories under the directory named by the static setting `plugins.dir` (default `plugins`, relative to the working directory; `/app/plugins` in the Docker image). A missing directory loads nothing. A jar registers its code in `META-INF/services/com.epam.aidial.core.plugin.PluginProvider`, and compiles against the `aidial-core-plugin-api` module of DIAL Core (`com.epam.aidial:aidial-core-plugin-api`). See [Building a plugin](#building-a-plugin).

### Layout

Each plugin has a directory of its own, two levels below `plugins.dir`: `<plugins.dir>/<group>/<plugin>/`, which holds the plugin's jars, its libraries included. `<group>/<plugin>` is the plugin directory's id, which the logs and the settings below use.

```
/app/plugins/
├── builtin/           plugins the DIAL Core image carries
│   └── echo/
│       └── echo-1.0.jar
└── custom/            plugins an environment mounts
    └── translator/
        ├── translator-2.3.jar
        └── some-library.jar
```

DIAL Core treats every group alike. By convention the DIAL Core image fills `builtin`, and an environment mounts its own plugins under `custom`, one mount per plugin (for example `/app/plugins/custom/translator`, one Kubernetes volume each). A volume mounted over a whole group hides what the image put there, so mount into `custom`, never over `builtin` or over `plugins.dir`. An empty group is fine. Directories whose name starts with a dot, and `lost+found`, which a formatted volume carries, are ignored at both levels.

Each plugin directory gets its own class loader. A plugin sees its own jars and DIAL Core's classes, not the jars of another plugin, so two plugins may bundle different versions of one library. DIAL Core's classes win: when a plugin bundles a library DIAL Core carries (the plugin API, Vert.x, Jackson and the rest of DIAL Core's classpath), the plugin gets DIAL Core's copy. Do not bundle those.

DIAL Core does not start when:

* a jar lies directly in `plugins.dir` or directly in a group directory (the flat layout of earlier versions; the message names the jar);
* a jar of a loaded plugin directory cannot be opened, or its plugin code cannot be created;
* two jars of one plugin directory register the same plugin class (an old jar left beside its new version);
* a loaded plugin directory registers no plugin code (often a volume mounted one level off);
* two plugin classes declare the same type, in one plugin directory or in two.

At start DIAL Core logs each plugin directory, `Loaded plugin directory custom/translator: types [...]` or `Excluded plugin directory builtin/echo`. New plugin code needs a restart of DIAL Core; the `plugins` entries and their settings reload with the rest of the config.

Moving from an earlier version: move each plugin's jars into `<plugins.dir>/<group>/<plugin>/` in the rollout that brings this version. The earlier version reads only the flat directory, so a rollback takes the old layout back with it.

### Including and excluding plugins

The static settings `plugins.include` (default `["*"]`) and `plugins.exclude` (default `[]`) pick the plugin directories DIAL Core loads: one is loaded when its id matches a pattern of `include` and no pattern of `exclude`. A pattern is `*` or `<group>/<plugin>`, where either part may be `*`: `builtin/*` names a group, `*/echo` a plugin in any group. Any other pattern stops the start, and so does an empty `include`; to load no plugin directory, set `exclude` to `*`. A pattern that matches no directory is fine, so one setting can serve environments with different mounts.

Each setting is a JSON array or one string of comma-separated patterns, the form an environment variable takes:

```
aidial.plugins.exclude=builtin/echo,custom/old
```

DIAL Core does not open a plugin directory it does not load, so excluding a broken plugin gets DIAL Core started while the plugin is fixed. To replace a built-in plugin with another build of it, mount the build under `custom` and exclude the built-in one, for example `aidial.plugins.exclude=builtin/echo` with the new build in `custom/echo`; loading both stops the start, as both declare the same type.

### Building a plugin

The plugin API is published to the GitHub Packages Maven registry of this repository as `com.epam.aidial:aidial-core-plugin-api`:

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/epam/ai-dial-core")
        credentials {
            username = System.getenv("GPR_USERNAME")
            password = System.getenv("GPR_PASSWORD") // a token with the read:packages scope
        }
    }
}

dependencies {
    compileOnly("com.epam.aidial:aidial-core-plugin-api:<DIAL Core version>")
}
```

## Configuration API

Plugins live in the `platform` bucket and need the admin role, like the other config entities:

* `GET`, `PUT` and `DELETE /v1/plugins/platform/{name}`, and `GET /v1/metadata/plugins/platform/` to list them.
* Manifest kind `Plugin` with name `plugins/platform/<name>` in `POST /v1/admin/validate` and `POST /v1/admin/apply`. A batch applies plugins after settings and schemas and before every other kind.
* `GET /v1/admin/config/file/plugins[/{name}]` shows the entries of the config files, and `POST /v1/admin/config/file/migrate` with type `plugins` copies them to the bucket.

An entry in the bucket replaces, as a whole, the file entry with the same name.
