# Review: plugin-close-all-kinds

## What was built

- **`Plugin`** (design D1). A new `plugin-api` interface with `default void close()` and the kind-neutral contract:
  once per instance, never on an event loop, an exception logged and dropped.
- **`OriginPlugin extends Plugin`.** Its own `close()` is gone; the origin-only sentence (after `close()`, Core only
  reads or cancels an answer it holds) is in its class Javadoc. A plugin built against the old API still matches (D2).
- **Spec.** "Closing an origin plugin" is now "Closing a plugin": every instance Core creates, whatever its kind.
- **Docs.** `docs/dynamic-settings/plugins.md`, "Settings changes", names `Plugin` as where `close()` is declared.

Server code is unchanged: `EmbeddedOrigins` closes the `OriginPlugin` it holds as before.

## Tests

- Checkstyle passes; `plugin-samples` builds against the new API.
- `EmbeddedOriginsTest` (13 tests) and `EmbeddedOriginApiTest` (43 tests), which hold the scenarios of "Closing a
  plugin", pass unchanged.

## Not done

- No second plugin kind exists, so the widened rule has no test of its own; the kind that comes next adds one.
- Closing providers and a deadline for `close()` stay out of scope (#2264).
