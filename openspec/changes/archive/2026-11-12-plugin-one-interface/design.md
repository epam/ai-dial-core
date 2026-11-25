# Design

## Context

`PluginProvider.create` returns `Plugin`; `PluginManager` checked that the instance is an `OriginPlugin`, closed it
otherwise and failed the request with "serves no requests". Only the test provider `test-echo` created another kind.

## Decisions

### D1. One interface, named `Plugin`

`Plugin` takes `handle` and keeps `close()` as a default method, so a plugin with nothing to release is a lambda. The
name follows the other shared types, which lost the `Origin` prefix (`plugin-api-names`). When a second kind arrives
(#2262), a base with `close()` is split out again; its providers and plugins then change their declared types.

### D2. Shutdown re-check

`close()` sets the closed flag and then removes and closes every mapped instance. A `resolve` that passed the flag
check before may store its new plugin after that. `resolve` reads the flag again after `compute`; when it is set, it
removes the entry and closes the plugin, unless `close()` removed it first, and fails the request. Removing the entry
decides which of the two closes it, so it is closed once.

## Effect per request

None: one `instanceof` check fewer on the create path, one volatile read more on it.

## Migration Plan

Providers and plugins compiled against `OriginPlugin` change the type name to `Plugin`.
