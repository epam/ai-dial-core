# Proposal

## Why

A deployment interface, a translator and a decision router's interface name the plugin that serves them in a field of
their own, `plugin`, next to `base_url` and exclusive of it. Core turns that name into the url `plugin://<name>` at
once and routes, logs and charges by that url. The review of #2216 proposed writing the url itself in the base url
field: one field says where an interface is served, whether by an HTTP origin or by a plugin.

The Configuration API is used by clients the DIAL team does not know. A client that reads an entity into a model
without the new `plugin` field and writes it back drops the field without an error. A plugin url in `base_url`
survives in a field every client already keeps.

## What Changes

- **BREAKING (preview).** `interfaces.<type>.plugin` and the translator's `plugin` are removed. A plugin is named by a
  base url `plugin://<name>`, optionally followed by a path: in `interfaces.<type>.base_url`, in the deployment-level
  `baseUrl` (which serves the entries that declare no `base_url`), and in a translator's `baseUrl`.
- The plugin receives the path and query Core appends to the base url: any path written after the name, then the
  ingress path, as an HTTP origin at that base url receives them.
- Core rejects a plugin url where no plugin serves: in a deployment's `endpoint` and `responsesEndpoint`, anywhere
  in an interceptor (its `baseUrl` included), and with no name. A custom application outside the `platform`
  bucket with a plugin url in `baseUrl`, an interface `base_url` or an inline translator's `baseUrl` is refused, as one
  naming a plugin is today.
- Everything else stays: how a plugin is called and answered, the platform check at request time, `overridePaths`
  having no effect on a plugin-served entry, the `502` for a name no `plugins` entry has.

Out of scope: a plugin url in the pre-`interfaces` `endpoint` fields (rejected here, a possible follow-up), and a
plugin url in the url fields a plugin never serves, which are not checked: the `features` endpoints, a toolset's MCP
endpoint, an upstream's endpoint. A plugin url there fails at request time as any unreachable url does.

## Capabilities

### Modified Capabilities

- `deployment-interfaces-config`: the interface, translator, interceptor and platform-only requirements name a plugin
  by a plugin url in place of the `plugin` field; a new requirement rejects a plugin url where no plugin serves.
- `deployment-origin`: the embedded origin and translator plugin requests take the plugin and the path from the url.
- `router-deployment`: a decision router's plugin is named by its interface's base url.
- `responses-api`: an item request reaches the plugin its interface's base url names.
- `plugin-registry`: "Origin plugins" names the plugin by a plugin url.
- `app-to-model-accounting`: a scenario's translator names its plugin by `baseUrl`.

## Impact

- Review of #2216 (decision recorded in the PR thread): covers the plugin reference. The filter plugins of #2262 are
  not origins and keep a reference of their own, out of scope here.
- `config`: `DeploymentInterface` and `Translator` lose `plugin`. `server`: `DeploymentEndpointUtil`,
  `ConfigPostProcessor`, `ApplicationService`, `AliasPasses`. Docs: plugins, models, applications, routers,
  translators, interceptors; the generated OpenAPI.
- Config: an entry carrying the removed `plugin` field is rejected as one with any unknown field is; write
  `"base_url": "plugin://<name>"` instead.
  Plugins are in preview, which allows this. The provider adapter's README follows on its branch.
- No change to records, analytics or the wire: the analytics row's `upstream_uri` is the base url, `plugin://<name>`
  as today when no path follows the name.
