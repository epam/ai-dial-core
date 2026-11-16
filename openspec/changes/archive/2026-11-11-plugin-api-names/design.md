# Design

## Context

`plugin-api` (`com.epam.aidial.core.plugin`) has `Plugin` (close), `PluginProvider` (type, validate),
`OriginPluginProvider` (create), `OriginPlugin` (handle) and the data types `OriginRequest`, `OriginResponse`,
`OriginCall`, `OriginCaller`, `OriginHost`. `PluginManager.instance` rejects a provider that is not an
`OriginPluginProvider` ("serves no requests") before creating anything; config validation does not check the kind.

## Decisions

### D1. Drop the prefix on the types every kind can share

A request, an answer, a call into Core, the caller and the host are not specific to origin plugins; a later kind
(#2262) can take them as they are. `OriginPlugin` keeps the prefix because it is the kind.

The host becomes `HostDelegate`, as the reviewer named it and as Core's `VertxHostDelegate` already is.

A plugin class that also uses another library's `Request` or `Response` (or extends a class with a member type of
that name, as Core's API tests do with `ResourceBaseTest.Response`) writes one of them with its full name.

### D2. One provider interface; the kind is the plugin's

`PluginProvider.create` returns a `Plugin`. An implementation narrows the return type (`OriginPlugin create(...)`), so
plugin code stays typed. Core checks the kind on the instance: `PluginManager` closes an instance that is not an
`OriginPlugin` (Core closes every instance it creates, "Closing a plugin") and fails the request with the same `502`
as before.

Rejected: a type parameter `PluginProvider<P extends Plugin>`. `ServiceLoader` loads raw types, so Core would still
check the instance, and every provider would carry a type argument for nothing.

Lost: none. The kind was checked only when a request first needed the instance, before and after.

## Effect per request

None: one `instanceof` moves from the provider to the instance, once per created instance.

## Migration Plan

Plugins rebuild against the new names; there is no compatibility shim, as `plugin-api` is not published yet (#2261).
