# Proposal

## Why

`plugin-close` gave `OriginPlugin` a `close()` and left "closing other plugin kinds" out of scope, because origin
plugins were the only kind. The next kinds are planned (interceptors served by a plugin, #2262). With `close()` on
`OriginPlugin` alone, each new kind has to repeat the method and its contract, and nothing says it must be closed. The
PR review asked to close every plugin kind.

## What Changes

- A new `plugin-api` interface `Plugin`, with the `close()` contract `OriginPlugin` has today: a default method that
  does nothing, called once per instance, never on an event loop, an exception logged and dropped.
- `OriginPlugin` extends `Plugin` and loses its own `close()`. A plugin compiled against the old API keeps working:
  the method has the same name, signature and default.
- The plugin-registry requirement "Closing an origin plugin" becomes "Closing a plugin": every instance Core creates,
  whatever its kind, is closed by the same rules.
- `docs/dynamic-settings/plugins.md` says the same.

Out of scope:

- New plugin kinds. The rule applies to a kind when it is added.
- Closing the provider (`PluginProvider`): a provider lives as long as Core.
- A deadline for `close()` and for in-flight work (#2264).

## Capabilities

### Modified Capabilities

- `plugin-registry`: "Closing an origin plugin" renamed to "Closing a plugin" and widened to every kind.

## Impact

- Motivated by the review of #2216 (comment on the `plugin-close` proposal, "Closing other plugin kinds"). This change
  covers it as a contract; no second kind exists to close yet.
- `plugin-api`: `Plugin` (new), `OriginPlugin`. Server code is unchanged: `EmbeddedOrigins` calls `close()` on the
  `OriginPlugin` it holds, as before.
- No config, HTTP API or record changes.
