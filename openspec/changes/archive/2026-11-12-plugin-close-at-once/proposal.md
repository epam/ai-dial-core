# Proposal

## Why

Core counts the attempts that hold each plugin instance (a lease per attempt, a use count and a closed mark per
instance) only so that an instance replaced after its entry changed, or whose entry was removed, is closed after the
requests in flight on it end. The review of #2216 asked for a simpler model: one instance per entry, created under a
lock and put in the map, with no leases and no instance state. Settings changes are rare admin edits, and a plugin
that must let its requests end can wait for them in its own `close()`.

## What Changes

- `PluginManager` keeps one map, entry name to (declaration, plugin). The plugin of a new or changed entry is created
  inside `ConcurrentHashMap.compute`, so racing requests share one instance and none is created to be closed at once.
- The instance replaced, or the instance of a removed entry, is closed at once, off the event loop, without waiting
  for the requests in flight on it.
- `Lease`, `acquire`, the per-instance use count, `VertxHostDelegate.whenClosed` and the attempt's give-back go.
  An attempt takes the plugin with `resolve` when it is sent.
- The `Plugin.close` and `OriginPlugin` Javadoc and `docs/dynamic-settings/plugins.md` say that Core does not wait
  for the requests in flight, and that a plugin that wants them to end first waits for them in `close()`.

## Capabilities

### Modified Capabilities

- `plugin-registry`: requirement "Closing a plugin" replaced by "Closing a plugin at once".

## Impact

- `server`: `PluginManager`, `ProxyClient`, `EmbeddedProxyRequest`, `VertxHostDelegate`.
- `plugin-api`: Javadoc only; no signature changes.
- A request in flight on a plugin whose entry an admin changes or removes may now fail when the plugin closes its
  clients under it, unless the plugin waits for its requests in `close()`.
