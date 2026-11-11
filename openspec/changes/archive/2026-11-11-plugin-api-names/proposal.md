# Proposal

## Why

The review of #2216 asked to simplify the `plugin-api` names before it is published (#2261):

- Every type carried the `Origin` prefix, including the ones that are not specific to origin plugins: the request a
  plugin gets, its answer, the call it makes, the caller, and the host that serves its calls.
- `OriginHost` serves as Core's delegate to the plugin; Core's implementation is already `VertxHostDelegate`.
- `OriginPluginProvider` added only `create()` to `PluginProvider`, and was its only subtype.

## What Changes

- Renames: `OriginHost` to `HostDelegate`, `OriginRequest` to `Request`, `OriginResponse` to `Response`,
  `OriginCall` to `Call`, `OriginCaller` to `Caller`. `OriginPlugin` keeps its name: it names the kind.
- `OriginPluginProvider` is gone. `PluginProvider` declares `Plugin create(ObjectNode settings, Vertx vertx)`; a
  provider of origin plugins returns an `OriginPlugin` (a covariant return type).
- The kind comes from the instance. Where a deployment interface names an entry whose plugin is not an
  `OriginPlugin`, Core closes that instance and answers as it does for a plugin it cannot create (`502`).
- Javadoc: `PluginProvider.type()` says how a type differs from an entry's name; `Request.bodyTree` says it is the
  same JSON as `body` and why both are given.
- `docs/dynamic-settings/plugins.md` and `CLAUDE.md` use the new names.

Out of scope:

- New plugin kinds (#2262).
- Publishing `plugin-api` (#2261).

## Capabilities

### Modified Capabilities

- `plugin-registry`: "Origin plugins" names the kind by the plugin a provider creates, not by the provider, and says
  what happens to a plugin of another kind; "Plugin of another kind" is a new requirement.

## Impact

- `plugin-api`: every plugin recompiles against the new names. The provider adapter plugin
  (`ai-dial-model-provider-java`, module `plugin`) gets the same renames.
- `server`: `PluginManager` creates through `PluginProvider` and checks the instance's kind. Embedded proxy classes
  use the new names.
- No config, HTTP API or analytics record changes.
