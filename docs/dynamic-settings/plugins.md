# Dynamic Setting for Plugins

A plugin is Java code that DIAL Core runs in process, instead of calling a service over HTTP. The code comes as a jar that Core loads at start; a `plugins` entry declares one instance of that code with its own settings.

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

An origin plugin answers the requests of a deployment interface inside DIAL Core, in place of the HTTP call to the deployment's endpoint. A model or an application names it in an interface entry:

```json
"interfaces": {
    "openaiChatCompletions": { "mode": "embedded", "plugin": "my-plugin" }
}
```

Any interface type can be embedded: chat completions, embeddings, the Responses API with its item operations, and Anthropic Messages. Refer to [models](models.md) and [applications](applications.md) for the interface fields.

* **What the plugin receives.** The request DIAL Core would send to an HTTP origin: the method, the path and query it would append to a base URL, the same headers and the body. The headers carry the per-request key in `API-KEY`, `X-DIAL-DEPLOYMENT-FEATURES`, and for a model with upstreams the `X-UPSTREAM-*` headers of the chosen upstream. DIAL Core forwards the client's headers too, as it does to an HTTP origin, so trust `X-UPSTREAM-*` only for a model with upstreams: elsewhere the client may have set them. There is no `traceparent` header.
* **More than an HTTP origin gets.** The body as a parsed JSON object as well (`bodyTree`), so the plugin needs no parse of its own; it is read-only unless the plugin passes it to a call. And the caller (`caller`): the user id of a caller with a JWT, the caller's roles, and its project. A routing plugin needs no `/v1/user/info` call.
* **What DIAL Core does with the answer.** The same as with an HTTP origin's: a retriable status moves to the next upstream, the body is relayed as it arrives, usage is parsed and charged, and one analytics row is written. The row's `response.upstream_uri` is `plugin://<name>` for a deployment without upstreams.
* **When the plugin cannot serve.** DIAL Core answers `502` `Failed to connect to upstream server`, without trying another upstream, when no `plugins` entry has the name, its plugin code is not loaded or serves no requests, or the plugin cannot be created from its settings. A Responses item operation answers `500` `Failed to process response operation` in these cases, as it does when its HTTP origin cannot be reached. A plugin that fails a call counts as an origin that failed to send its response headers.
* **When the client goes away first.** DIAL Core watches the client while the plugin works, and tells the plugin through `OriginHost.cancelled()` when the client leaves before the answer, so the plugin can stop its upstream work. A plugin that then fails its answer ends the request quietly: no other upstream is tried, and no analytics row is written. An answer it still gives is handled as an HTTP origin's answer after its client went away: a model's answer is read to the end and charged.
* **Settings changes.** DIAL Core creates the plugin from the entry's settings on the first request it serves, and again on the first request after the entry changes, handing it DIAL Core's Vert.x instance for the clients and timers it needs. A settings edit applies to the next request, with no restart. DIAL Core calls `OriginPlugin.close()` on a thread that may block, never on an event loop, so that the plugin releases its clients, caches and timers: on the replaced plugin once its last request is done, on the plugin of a removed entry at the next request that names the entry, and on every plugin when DIAL Core shuts down.
* **Log records.** A plugin logs through DIAL Core's SLF4J. The fields it attaches to a record as SLF4J key-value pairs (`log.atInfo().addKeyValue("model", m).log("routed")`) appear in the record's `Attributes` in DIAL Core's JSON log line, after DIAL Core's own attributes, which a field of the same name does not replace. They are not copied to the span.
* **Platform deployments only.** A plugin runs platform code, so only the platform config (the config files and the `platform` bucket) can name one. A custom application in any other bucket with an `embedded` interface is refused with `422`, and is never served by a plugin.
* **Rollout.** Deploy the DIAL Core image that carries the plugin before writing `embedded` config: an older DIAL Core does not know the mode. Roll back by switching the interface back to its `base_url`.

Plugin code runs on DIAL Core's event loop. It must not block, and must answer and stream its body on the thread it was called on.

### Calling another deployment

While it answers a request, an origin plugin can call another deployment through DIAL Core, in process, with the `OriginHost` its `handle` receives. DIAL Core serves the call as a request sent with the per-request key of the plugin's request, with no HTTP request into DIAL Core, no key lookup and no second parse of the body.

* **What a call reaches.** A `POST` to a create operation: a deployments action (`/openai/deployments/<name>/chat/completions`, `completions` or `embeddings`), `/openai/v1/chat/completions`, `/openai/v1/responses` (or `/openai/responses`) or `/anthropic/v1/messages`; the last four name the deployment in the body's `model`. Any other path, and a Responses call with `"background": true`, is refused: the call fails, and the plugin decides what to answer.
* **What the plugin passes.** The body as a parsed JSON object, which DIAL Core takes: the target's processing changes it in place, so a plugin that calls twice with one body passes a copy. The headers are copied. DIAL Core drops the ones it sets for an origin itself (`X-UPSTREAM-ENDPOINT`, `X-UPSTREAM-KEY`, `X-UPSTREAM-EXTRA-DATA`, `X-DIAL-CACHE-BREAKPOINT-PATH`, `X-DIAL-CACHE-EXTRA-METADATA`, `X-DIAL-OVERRIDE-NAME`, `X-DIAL-DEPLOYMENT-ID`, `X-DIAL-APPLICATION-ID`, `X-DIAL-APPLICATION-PROPERTIES` and `X-DIAL-DEPLOYMENT-FEATURES`), so a plugin may pass its own request's headers on.
* **What the plugin gets back.** The answer as a plugin's own answer looks: the status and headers, then the body as it arrives. Any status is an answer: an unknown deployment gives `404`, one the caller may not use `403`, a limit reached `429`.
* **How the call is checked, charged and logged.** As a request the plugin's deployment sends DIAL Core with its per-request key: the target's role, consent and access checks, its limits against the caller's, a token-stats span under the plugin's request, a per-request key for the target's origin, and an analytics row with `parent_deployment` the plugin's deployment. The row has no `request.body`, `response.body` or `assembled_response`, whatever the key: the plugin's own row logs the bodies once. A plugin that changes the body before the call sends the target a body no row carries.
* **When a call ends.** When DIAL Core cancels the plugin's answer (the client went away, or a retry moved past it), or the plugin cancels the call's answer, the target's request stops as one whose client went away does: a model target still reads its origin to the end and charges it. When the plugin's request ends, DIAL Core cancels the answers of its calls that are still open, and answers a later call `401` `Unknown api key`.

A plugin that calls its own deployment calls itself again and again: there is no depth limit.

### The forward sample

The module `plugin-samples` holds an origin plugin of type `forward`, with the settings `{"deployment": "<name>"}`. It sends each request on to that deployment through a call, and answers with the call's answer: a deployments action goes to the same action of that deployment, the other interfaces keep their path with `model` set to it. It is a worked example of a call and the vehicle for measuring one. The default Docker image does not carry it; build an image with `--build-arg SAMPLE_PLUGINS=true` to put its jar in `/app/plugins`.

## Plugin code

DIAL Core loads plugin code once, at start, from the jars in the directory named by the static setting `plugins.dir` (default `plugins`, relative to the working directory; `/app/plugins` in the Docker image). A missing directory loads nothing. A jar registers its code in `META-INF/services/com.epam.aidial.core.plugin.PluginProvider`, and compiles against the `plugin-api` module of DIAL Core.

DIAL Core does not start when a jar in the directory cannot be opened, when its plugin code cannot be created, when two jars register the same plugin class (an old jar left beside its new version), or when two plugin classes declare the same type. New plugin code needs a restart of DIAL Core; the `plugins` entries and their settings reload with the rest of the config.

## Configuration API

Plugins live in the `platform` bucket and need the admin role, like the other config entities:

* `GET`, `PUT` and `DELETE /v1/plugins/platform/{name}`, and `GET /v1/metadata/plugins/platform/` to list them.
* Manifest kind `Plugin` with name `plugins/platform/<name>` in `POST /v1/admin/validate` and `POST /v1/admin/apply`. A batch applies plugins after settings and schemas and before every other kind.
* `GET /v1/admin/config/file/plugins[/{name}]` shows the entries of the config files, and `POST /v1/admin/config/file/migrate` with type `plugins` copies them to the bucket.

An entry in the bucket replaces, as a whole, the file entry with the same name.
