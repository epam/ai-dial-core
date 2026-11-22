# Design

## Context

Today an interface entry, a translator and a decision router's interface name a plugin in `plugin`
(`DeploymentInterface.plugin`, `Translator.plugin`). Core turns the name into a url in one place,
`DeploymentEndpointUtil.java:resolveBaseUrl` (`PLUGIN_URL_PREFIX + plugin`), and reads the name for dispatch in
another, `DeploymentEndpointUtil.java:resolvePlugin`, which every caller uses:
`BaseDeploymentPostController.java:createProxyRequest`, `ResponsesApiClient.java`, `RouterDecisionClient.java`,
`RouterDecisionFlow.java`. `ProxyClient.java:embeddedRequest` builds the request with the base url
`plugin://<name>`, and `EmbeddedProxyRequest.java:send` gives the plugin the absolute uri minus that base url.
`BaseInterceptorController.java:sendRequest` already refuses a resolved uri starting with `plugin://`.

Validation: `ConfigPostProcessor.java:validatePluginReference` (blank name, next to `base_url`, in translator mode,
on an interceptor), `validateOverridePaths` (no effect with a plugin), `validateDeploymentInterfaces` (a pass-through
entry needs a url or a plugin), `hasInlineTranslatorPlugin`; the `Translator` constructor (exactly one of `baseUrl`
and `plugin`); `ApplicationService.java:pluginOutsidePlatformError` (platform only, on PUT, validate and apply).
`AliasPasses.java:copyOperational` copies `plugin` into an alias view.

## Goals / Non-Goals

Goals: name a plugin by a base url, `plugin://<name>[/<path>]`, wherever a base url serves an interface; reject it
where nothing would serve it; keep every behaviour of a plugin-served interface.

Non-goals: a plugin url in the pre-`interfaces` `endpoint` fields; checking a toolset's MCP endpoint; the filter
plugins of #2262.

## Decisions

1. **The url is the reference.** `DeploymentInterface.plugin` and `Translator.plugin` are removed. `resolvePlugin`
   becomes the name read from the url `resolveBaseUrl` returns, so the interface's own `base_url`, the
   deployment-level `baseUrl` and a translator's `baseUrl` all name a plugin the same way, and dispatch, analytics and
   logs read one value. Rejected: keeping `plugin` as an alias of `base_url` — two spellings of one thing, and a client
   that drops the unknown field still loses it.
2. **Name and path.** The name is the text after `plugin://` up to the next `/`, or the end; it is cut from the
   string, not read as a URI host (`java.net.URI` gives no host for a name with `_`). The rest is a path Core keeps in
   front of the ingress path, as it does for an HTTP base url with a path: `EmbeddedProxyRequest` already hands the
   plugin everything after `plugin://<name>`. The scheme is matched as written, lowercase. Rejected: AS's
   `dial_plugin://`, which is not a valid scheme (RFC 3986 allows no `_`); `plugin://` is what analytics already
   records.
3. **Rejected where nothing serves it.** `endpoint` and `responsesEndpoint` name an origin, as a base url does, but
   no plugin serves them: a plugin url there would reach the HTTP client and fail at request time. A new
   `ConfigPostProcessor` check, called first in `validateInterfaces` and `validateDeploymentInterfaces`, before they
   return for a deployment without `interfaces`, so it runs on every write path and on reload, rejects it with a
   warning on the field. A router rejects both fields already (`validateRouterInvariants`), so the check skips it. On an interceptor, a plugin url in its own `baseUrl`, an interface `base_url` or an inline
   translator's `baseUrl` is rejected, as the `plugin` field is today; a named translator's url is still caught at
   request time by `BaseInterceptorController`. A plugin url with an empty name is rejected wherever it is allowed,
   the translator's included (`validateTranslator`). Rejected: checking every url field Core holds
   (the `features` endpoints, toolsets, MCP, upstreams): none of them names an origin, a plugin never serves them,
   and a plugin url there fails as any unreachable url does.
4. **Platform only.** `pluginOutsidePlatformError` checks the application's `baseUrl`, every interface `base_url` and
   every inline translator's `baseUrl` for a plugin url. `ProxyClient.embeddedRequest` keeps refusing a non-platform
   application at request time. Rejected: the request-time check alone, which would store a custom application that
   then fails every request, where the write is refused today.
5. **`overridePaths` stay without effect** on an entry served by a plugin url, as today with `plugin`: a plugin gets
   the path an HTTP origin at the base url gets before overrides. `findOverridePath` and `validateOverridePaths` read
   the entry's pass-through url instead of `plugin`. Rejected: applying overrides — a behaviour change no one asked
   for.
6. **Translator.** `baseUrl` becomes required and non-empty in the constructor; `in`, `out` checks stay. Rejected:
   keeping `plugin` as a second field read into `baseUrl`, two spellings again, which decision 1 rejects.

Deliberate differences from today: the plugin of an entry may come from the deployment-level `baseUrl`; a path may
follow the name; a plugin url in `endpoint` and `responsesEndpoint` is rejected (today it is an
unreachable url). An application's `translator`-mode entry with `plugin` was rejected; with a plugin url in `base_url`
it is accepted and the `base_url` ignored, as an HTTP `base_url` there is today (a model or router rejects both,
"Base url or translator, decided by mode"; an interceptor rejects a plugin url in any mode); a custom application
outside `platform` is still refused for it.

## Cost per request

None. The name is read from a url Core already resolves per request; no pass, body copy, parse or Redis/blob round
trip is added. Not measured: the code on the request path does one `startsWith` and one `indexOf` where it read a
field.

## Risks / Trade-offs

- A url is a string: `plugn://p` is an HTTP url to Core and fails at request time with `502`, as a typo in an HTTP
  base url does.
- A plugin name containing `/` cannot be written; a config file already drops such an entry.

## Migration Plan

Plugins are in preview, and #2216 is not released: only test environments built from its branch can hold config
naming `plugin`. Such an entry is rejected as one with an unknown field is: a write fails, and a stored `platform`
blob or a config file entry fails to load on reload as any unreadable entry does. Before upgrading such an
environment, replace `"plugin": "<name>"` with `"base_url": "plugin://<name>"` (a translator: `"baseUrl"`). The
provider adapter's README follows on its branch.
