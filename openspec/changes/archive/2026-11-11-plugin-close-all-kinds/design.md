# Design

## Context

`OriginPlugin.close()` (`plugin-api`, `OriginPlugin.java`) is the only close contract. `EmbeddedOrigins` closes an
instance through `Instance.close` on a virtual-thread task (`EmbeddedOrigins.java:Instance.close`), on replacement,
removal and shutdown (`EmbeddedOrigins.java:close`, called from `AiDial.java:stop`).

## Decisions

### D1. A base interface `Plugin` with `close()`

`Plugin` declares `default void close()` with the kind-neutral part of the contract. `OriginPlugin extends Plugin`.
The origin-only sentence (after `close()`, Core calls the instance only to read or cancel an answer it holds) moves to
`OriginPlugin`'s class Javadoc.

Rejected: `Plugin extends AutoCloseable`. `AutoCloseable.close()` throws `Exception`, so every caller needs a
`try`/`catch` for checked exceptions, and IDEs warn about instances not used in try-with-resources, which Core never
does with a plugin.

Rejected: a generic instance cache for every kind now. `EmbeddedOrigins` stays origin-only until a second kind
exists; that kind decides whether to share it.

### D2. Binary compatibility

A plugin compiled against the old `OriginPlugin` implements `handle` and maybe overrides `close()`. Both still match:
`close()` is inherited with the same signature and a default body. No plugin needs a rebuild.

## Effect per request

None: no request path changes.

## Migration Plan

none
