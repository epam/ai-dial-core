# Proposal

## Why

`plugin-api` has two plugin interfaces: `Plugin`, a base with `close()` only, and `OriginPlugin`, which adds
`handle`. The base exists for kinds of plugin that are not built: today every plugin answers requests, and Core checks
the kind of each instance only to refuse one that does not. The review of #2216 agreed on one interface, to be split
again when a second kind of plugin needs it.

## What Changes

- `Plugin` declares `handle(Request, HostDelegate)` and the default `close()`; `OriginPlugin` is removed.
  `PluginProvider.create` returns `Plugin`, as it does today.
- Core no longer checks the kind of an instance; the "serves no requests" failure goes.
- `PluginManager.resolve` re-checks shutdown after creating a plugin, so one created while `close()` runs is closed
  (review of #2216).
- `ProxyClient` builds both embedded requests in one private method (review of #2216).

## Capabilities

### Modified Capabilities

- `plugin-registry`: "Origin plugins" no longer depends on the kind; "Closing a plugin at once" covers a plugin
  created while Core stops; "Plugin of another kind" is removed.

## Impact

- `plugin-api`: `OriginPlugin` removed. A provider or plugin naming it changes to `Plugin`; the provider adapter
  plugin follows on its branch.
- `server`: `PluginManager`, `ProxyClient`, `EmbeddedProxyRequest`. `plugin-samples`: `ForwardOriginPluginProvider`.
- No config, record or wire change.
