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
* **What DIAL Core does with the answer.** The same as with an HTTP origin's: a retriable status moves to the next upstream, the body is relayed as it arrives, usage is parsed and charged, and one analytics row is written. The row's `response.upstream_uri` is `plugin://<name>` for a deployment without upstreams.
* **When the plugin cannot serve.** DIAL Core answers `502` `Failed to connect to upstream server`, without trying another upstream, when no `plugins` entry has the name, its plugin code is not loaded or serves no requests, or the plugin cannot be created from its settings. A Responses item operation answers `500` `Failed to process response operation` in these cases, as it does when its HTTP origin cannot be reached. A plugin that fails a call counts as an origin that failed to send its response headers.
* **Settings changes.** DIAL Core creates the plugin from the entry's settings on the first request it serves, and again on the first request after the entry changes. A settings edit applies to the next request, with no restart.
* **Platform deployments only.** A plugin runs platform code, so only the platform config (the config files and the `platform` bucket) can name one. A custom application in any other bucket with an `embedded` interface is refused with `422`, and is never served by a plugin.
* **Rollout.** Deploy the DIAL Core image that carries the plugin before writing `embedded` config: an older DIAL Core does not know the mode. Roll back by switching the interface back to its `base_url`.

Plugin code runs on DIAL Core's event loop. It must not block, and must answer and stream its body on the thread it was called on.

## Plugin code

DIAL Core loads plugin code once, at start, from the jars in the directory named by the static setting `plugins.dir` (default `plugins`, relative to the working directory; `/app/plugins` in the Docker image). A missing directory loads nothing. A jar registers its code in `META-INF/services/com.epam.aidial.core.plugin.PluginProvider`, and compiles against the `plugin-api` module of DIAL Core.

DIAL Core does not start when a jar in the directory cannot be opened, when its plugin code cannot be created, when two jars register the same plugin class (an old jar left beside its new version), or when two plugin classes declare the same type. New plugin code needs a restart of DIAL Core; the `plugins` entries and their settings reload with the rest of the config.

## Configuration API

Plugins live in the `platform` bucket and need the admin role, like the other config entities:

* `GET`, `PUT` and `DELETE /v1/plugins/platform/{name}`, and `GET /v1/metadata/plugins/platform/` to list them.
* Manifest kind `Plugin` with name `plugins/platform/<name>` in `POST /v1/admin/validate` and `POST /v1/admin/apply`. A batch applies plugins after settings and schemas and before every other kind.
* `GET /v1/admin/config/file/plugins[/{name}]` shows the entries of the config files, and `POST /v1/admin/config/file/migrate` with type `plugins` copies them to the bucket.

An entry in the bucket replaces, as a whole, the file entry with the same name.
