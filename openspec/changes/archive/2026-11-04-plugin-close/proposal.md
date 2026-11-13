# Proposal: plugin-close

## Why

Core keeps one origin plugin instance per `plugins` entry. When the entry's settings change, Core creates a new
instance and drops the old one, and it never tells any instance it is no longer used, not even at shutdown. A plugin
that holds HTTP client pools, token caches or timers leaks them on every settings change. A Java port of a model
adapter as an origin plugin, which holds exactly those, asked for a way to release them (`embedded-call` design.md,
"Open Questions").

## What Changes

- **`OriginPlugin.close()`.** A default method that does nothing, so no existing plugin needs a change. Core calls it
  once per instance, never on an event loop, so it may block while a pool shuts down. An exception from it is logged
  and dropped.
- **When Core closes an instance.**
  - **Replaced:** after its entry's settings changed and a request created the new instance.
  - **Removed:** after its entry was removed, at the next request that names the entry, which answers `502`.
  - **At shutdown:** every instance, after Core stops serving requests and before it closes Vert.x.
- **Not while it works.** A replaced or removed instance is closed only once no request is in flight on it. A request
  is in flight from the moment Core hands it to the plugin until the plugin has answered and Core is done with that
  attempt: the attempt cancelled, or the request it serves finished. A request never reaches a closed instance.
- **Docs.** `docs/dynamic-settings/plugins.md` gains the lifecycle; the `OriginPlugin` Javadoc gains `close()`.

**Out of scope:**
- Closing other plugin kinds: only origin plugins exist so far.
- A timeout on in-flight requests. An answer that never ends keeps its instance open until shutdown.
- Closing a removed entry's instance that no request names any more: it stays open until shutdown.
- Closing the provider (`PluginProvider`): a provider lives as long as Core.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `plugin-registry`: a new requirement "Closing an origin plugin": a replaced or removed instance is closed once it
  is idle, and every instance at shutdown.

## Impact

- **Motivation:** `openspec/changes/archive/2026-11-03-embedded-call/design.md`, "Open Questions", "Closing a
  replaced instance". This change covers the whole point, as the question proposed it: `close()` as a default method,
  called once the replaced instance has no attempt in flight, counted from the send to the attempt's in-process calls
  closing, and at shutdown. It adds closing a removed entry's instance.
- **Code:**
  - `plugin-api`: `OriginPlugin.close()`.
  - `server`: `EmbeddedOrigins` (instances with an in-flight count, retiring, closing), `EmbeddedProxyRequest` (an
    attempt takes its instance and hands back the release), `ChildPass` (runs the release when it closes),
    `OriginClient`, and `AiDial` (closing at shutdown).
  - Test fixtures: `TestOriginPluginProvider` records closed instances.
- **APIs:** a default method on a plugin interface; no HTTP API change and no config change.
- **Dependencies:** none. It ships on the branch of PR #2216 after `embedded-call`, whose `ChildPass` and
  `EmbeddedProxyRequest` it changes.
