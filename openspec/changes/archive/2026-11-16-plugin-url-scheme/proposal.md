# Proposal

## Why

A plugin url is `plugin://<name>[/<path>]` (change `plugin-base-url`). `plugin` is a generic word: another product
or a future URI scheme may use it, and a reader of a config cannot tell it belongs to DIAL. The review of #2216 asked
for a DIAL-specific scheme, written there as `dial_plugin://`.

An underscore is not allowed in a URI scheme (RFC 3986, section 3.1: letters, digits, `+`, `-`, `.`). Core matches
the prefix as a string, so `dial_plugin://` would work in Core. But a Configuration API client that checks a
`base_url` with a URL parser would reject it or read it wrong: a browser's `new URL()` throws, Java's `URI` throws,
and Python's `urlsplit` returns no scheme. The Configuration API has clients the DIAL team does not know.
`dial-plugin://` is a valid scheme and is just as specific to DIAL.

## What Changes

- **BREAKING (preview).** The plugin url scheme is `dial-plugin://`. A plugin is named by `dial-plugin://<name>`,
  optionally followed by a path, wherever `plugin://<name>` was accepted: an interface's `base_url`, the
  deployment-level `baseUrl`, a translator's `baseUrl` and a decision router's interface or `baseUrl`.
- `plugin://<name>` is no longer a plugin url. Core reads it as any other base url: it is not rejected on write, and a
  request to it fails as one to an unreachable url does.
- The analytics row's `response.upstream_uri` for an interface served by a plugin is the `dial-plugin://` url.
- Every rule on plugin urls stays as it is: where they are rejected, the platform-only check, the `502` for a name no
  `plugins` entry has.

Out of scope: accepting both schemes. Plugins are in preview and no release has shipped `plugin://`, so there is no
stored config to migrate.

## Capabilities

### Modified Capabilities

- `deployment-interfaces-config`: the interface, translator, interceptor and platform-only requirements name the
  `dial-plugin://` scheme.
- `deployment-origin`: the embedded origin and translator plugin requests read the plugin from a `dial-plugin://` url.
- `router-deployment`: a decision router's plugin url uses the new scheme.
- `responses-api`: an item request reaches the plugin a `dial-plugin://` base url names.
- `app-to-model-accounting`: a scenario's translator names its plugin by a `dial-plugin://` `baseUrl`.

## Impact

- Review of #2216 (the base url proposal): covers the scheme name. The choice of `-` over `_` is recorded here.
- `server`: `DeploymentEndpointUtil.PLUGIN_URL_PREFIX`. Javadoc in `config`, `plugin-api` and `server`. Docs:
  plugins, models, applications, routers, translators, interceptors. Tests and the test config
  `router-decision-embedded.json`.
- Config: an entry with a `plugin://` url must be rewritten to `dial-plugin://`. The provider adapter's README
  follows on its branch.
- No cost effect: the same `startsWith` against a longer constant.
