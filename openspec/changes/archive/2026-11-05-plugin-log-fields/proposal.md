# Proposal: plugin-log-fields

## Why

An origin plugin logs through Core's SLF4J, and a plugin that keeps the log contract of the service it replaces writes
its fields as SLF4J key-value pairs: the Java router writes `routed` with `interface`, `model`, `attempt`, `status`,
`plan`, `tier` and the rest, the fields existing log queries match on. Core's JSON log layout writes a record's message
and Core's own attributes but drops its key-value pairs, so inside Core such a record is only its message. The fields
must reach Core's log line from the first release that serves the plugin.

## What Changes

- **A record's key-value pairs in the log line.** Core's JSON layout adds each SLF4J key-value pair of a record to the
  record's `Attributes`, after Core's own attributes, under the pair's key, with its value as JSON (strings, numbers,
  booleans, lists, maps and JSON trees as themselves, null as null, anything else as its text).
- **Core's own attributes win.** A pair whose key is one of Core's attribute names (`loggerName`, `user.project`,
  `request.uri`, ...) is left out, so a plugin cannot overwrite what Core reports.
- **Not on the span.** The pairs go into the log line only; Core keeps copying only its own attributes to the current
  span.
- **Docs.** `docs/dynamic-settings/plugins.md`, origin plugins: a plugin's log records keep their fields.

**Out of scope:**
- MDC entries and SLF4J markers: no plugin uses them.
- A record format of the plugin's own (key order, level names): Core's layout stays Core's.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-origin`: a new requirement "Log records of an origin plugin".

## Impact

Motivated by the Java port of the router as an origin plugin, in a repository of its own, whose `routed`,
`plan_based decision` and transport records carry their fields as key-value pairs. Covers the fields of
those records in Core's log line; leaves the router's own envelope format, which only its standalone service writes.

Code: `server/.../log/layout/AutoEnrichedOtelJsonLayout.java` and its test; `docs/dynamic-settings/plugins.md`.
Depends on `embedded-origin` and `embedded-call`, archived on this branch.
