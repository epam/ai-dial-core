# Design

## Context

`PluginManager` held, per entry, an `Instance` with an attempt count that doubled as a closed mark, a retired flag and
a closing promise, plus a set of the instances not closed yet. Each attempt took a `Lease` when it was sent and gave
it back through `VertxHostDelegate.whenClosed` once the attempt closed. Two event loops racing on a new declaration
could each create an instance, and the loser was retired.

## Decisions

### D1. One map, created under its lock

`origins` maps the entry name to `(declaration, plugin)`. A lookup whose declaration is the cached object returns the
plugin without a lock. Otherwise `ConcurrentHashMap.compute` runs under the lock of the name's bin: an equal
declaration (a rebuild's new object) keeps the plugin and stores the new object, so the next lookup is an identity
check again; a new one creates the plugin and closes the one it replaces. A create that throws leaves the mapping
unchanged. Plugin code runs under the bin lock, so a provider's `create` must stay quick, as its contract already says;
creating plugins when the config loads instead is #2268.

### D2. Close at once

A replaced instance, and the instance of a removed entry, are closed when the map drops them, through the task
executor as before. Core no longer waits for the requests in flight on them. The plugin knows its own requests: one that
must let them end waits for them in `close()`, which runs on a thread that may block.

### D3. Shutdown

`close()` sets the closed flag, then removes and closes every mapped instance. A request that creates a plugin while
`close()` runs leaves it open; Vert.x, which Core closes next, closes its clients and timers. Shutdown no longer waits
for a close that a replacement started earlier.

## Effect per request

The lookup is as before: a map read and an identity check. Each attempt no longer updates a use count twice or
registers a close action on its host.

## Migration Plan

None. Plugins compile unchanged; one that relied on Core waiting for its requests before `close()` waits in `close()`.
