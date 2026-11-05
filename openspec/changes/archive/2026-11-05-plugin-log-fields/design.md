# Design: plugin-log-fields

## Context

`server/src/main/java/com/epam/aidial/core/server/log/layout/AutoEnrichedOtelJsonLayout.java:buildEnrichedOtelLogRecord`
builds the line: `instant`, `loggerName`, `threadName`, then `enrichAttributesFromContext` (the pass's project, user,
request, response, route), `enrichExceptionAttributes`, `enrichOpenTelemetrySpan` (copies every attribute collected so
far to `Span.current()` as a string, skipped for a child pass), then the pass's tracing attributes. `OtelLogRecord`
serializes `Attributes` with Core's `ObjectMapper`, null map values included. `ILoggingEvent.getKeyValuePairs()`
(Logback 1.5, SLF4J 2 fluent API) holds a record's pairs in order, or null.

## Goals / Non-Goals

**Goals:** a plugin's record keeps its fields in Core's log line, and nothing Core reports changes.

**Non-Goals:** MDC and markers; the span; the plugin's own record format.

## Decisions

### D1. Pairs added last, Core's keys first

`enrichKeyValuePairs` runs after the tracing attributes and uses `putIfAbsent`, so every attribute Core sets keeps its
value and position. Rejected: pairs first and Core's attributes over them, which would move a plugin's field silently
when Core later adds an attribute of the same name; and a prefix such as `plugin.`, which would break the queries that
match the router's field names.

### D2. Values as JSON, unknown types as text

Strings, numbers, booleans, collections, maps and Jackson trees pass to the `ObjectMapper` as they are; null stays
null, as the router's envelope writes it. Any other object becomes `String.valueOf`, so a value Jackson cannot
serialize never sends the whole line to `buildFallbackJson`.

### D3. Not on the span

`enrichOpenTelemetrySpan` has already run when the pairs are added: a span keeps Core's attribute set, and a plugin's
fields (`status`, `model`) cannot collide with span attributes Core and the GenAI conventions own.

## Risks / Trade-offs

- A Core logger that starts using key-value pairs gets them in its line too: wanted, and none does today.

## Effect per request

None on passes, body copies, parses or Redis: one loop over a record's pairs, which only records that have them pay.

## Migration Plan

None.
