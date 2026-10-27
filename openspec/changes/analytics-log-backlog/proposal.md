# Proposal

## Why

Core writes analytics rows on one thread behind an unbounded queue, and each waiting row keeps its request and response
bodies in memory. When rows arrive faster than that thread writes them, the queue grows until the heap is full. In a
2,000-user perf run on 2 Core pods (router path, request bodies 665 KB on average, `server.instances=4`), the writer
topped out at about 100-120 MB/s per pod, about 70 user requests per second on the router path, where each user
request logs two bodies. The live heap grew 14 MB/s, and one pod died of `OutOfMemoryError`. The same load with every
key `secured`, so no bodies were logged, ran with 0 failures and a flat heap. Any pod that receives more than the
writer's rate with bodies logged reaches the same end. More server instances (#2184) only make that easier to reach.

## What Changes

- Core counts the bytes of the bodies held by rows waiting to be written. While that count is at or above
  `analytics.maxPendingBodyBytes`, Core queues new rows without their bodies, as it does for a `secured` key. Every
  other field of the row is still written, and no row is dropped. Once the writer has caught up, rows carry their
  bodies again.
- New setting `analytics.maxPendingBodyBytes` (env `aidial.analytics.maxPendingBodyBytes`): default one tenth of the
  JVM's maximum heap. A value below 1 stops Core at startup with an error naming the setting.
- A row for a `secured` key is queued without its bodies too. Core never writes them, so holding them while the row
  waits only costs memory. The row written is unchanged.
- Two metrics: `dial_analytics_log_pending_body_bytes` (the bytes held now) and
  `dial_analytics_log_bodies_skipped_total` (rows written without bodies because the writer was behind). Core also
  logs one warning when it starts leaving bodies out and one line when it stops.

## Capabilities

### New Capabilities
- `analytics-log`: what Core writes to the analytics log for a pass, starting with when a row carries the request and
  response bodies.

### Modified Capabilities

None. `deployment-origin`, the only capability in `openspec/specs` on `development`, says when a pass writes a row,
not what the row carries.

## Out of scope

- Making the writer faster (one copy of each body instead of two, faster JSON escaping). That raises the ceiling but
  leaves the queue unbounded. It may come as a later change.
- More writer threads. gflog keeps a buffer per thread, and more writers do not bound the queue.
- Not logging the body of the router's second pass, which repeats the client's body. That changes what analytics
  rows carry, so it is a product decision.
- A field in the row saying that its bodies were left out. The analytics consumer reads rows without bodies today
  (secured keys), and a new field changes its schema.
- Bounding the number of waiting rows. Without bodies a row is about a kilobyte and the writer handles far more of
  them per second than Core produces.

## Impact

- Motivation: the perf improvement initiative's runs of 2026-10-27 with #2184's image. This change covers the
  unbounded queue those runs exposed. It leaves the writer's speed alone.
- Code: `GfLogStore` (accounting, queueing, metrics), `AnalyticsLogContext` (a copy without bodies),
  `AnalyticsSettings` (the new setting). Docs: the settings table in `README.md`.
- Operators: no change while the writer keeps up. When it falls behind, some rows lack `request.body`,
  `response.body` and `assembled_response`; the metric counts them.
- Dependencies: none. #2184 and this change can merge in either order. Raising `server.instances` with bodies logged
  is safe only once this change is in.
