# Proposal

## Why

Core starts one HTTP server, so Vert.x runs every client connection, and every upstream connection opened for it, on
one event loop (an event loop is one Vert.x I/O thread; a pod has 2 per CPU). In the 2,000-user perf run of
2026-10-27 (2 Core pods, 8 CPUs each) that loop had 86-108 tasks waiting on average (peak 227) while the other 15
loops had none, the pods used 45-48% of their CPU, and Core added 3.9 s to the median request. That one loop caps a
pod near 46 user requests per second at 665 KB bodies, so a pod cannot serve 1,000 users whatever its CPU limit.

## What Changes

- New setting `server.instances` (env `aidial.server.instances`): the number of HTTP servers Core starts on
  `server.port`. Vert.x shares the port between them and hands each new connection to the next one in turn, so
  connections spread over that many event loops. Default 1: one server, as today. A value below 1 stops Core at
  startup with an error naming the setting.
- The HTTP API, the request Core sends to an origin and the records Core writes do not depend on the number of
  instances.
- `IdentityProvider` stops sharing one SHA-256 digest object across requests. Today that object is safe only because
  every request runs on the same event loop; with several loops, concurrent JWT validations could corrupt the user
  hash Core writes to the analytics row (`user.id`) or fail the request.

## Capabilities

### New Capabilities
- `http-server`: how Core accepts client connections: the port and the number of HTTP server instances that share
  it, and what stays the same whatever that number is. What each request then gets is the other capabilities.

### Modified Capabilities

None. No existing capability covers how Core listens; `request-routing` starts once a request has arrived.

## Out of scope

- Changing the default to one server per event loop. That is a later change, after the perf run confirms the effect.
- One upstream HTTP client per event loop. Core keeps its one shared client, so the connection cap (`client.maxPoolSize`,
  4,096 per upstream host and port) stays a per-pod total. design.md records the cost this leaves.
- Moving body parsing (`ObjectMapper.readTree` of the request body) off the event loop.
- The Vert.x production JVM flags PP-28 also lists (`-Dvertx.disableContextTimings=true` and others): deployment
  settings, not Core code.
- `CostRateLimit.CURRENCY_FORMAT`, a static `NumberFormat` (not thread-safe) used for the cost-limit error message. It
  already runs on virtual threads today, so the race exists without this change; it is reported, not fixed here.

## Impact

- Motivation: MGDA perf journal, PF-26 ("one event loop carries all HTTP") and PP-28 ("HTTP on every event loop"),
  and the perf runs of 2026-10-27 (statgpt-perf-analysis pipelines 951707, 2 pods, and 951601, 45 pods). This change
  covers PP-28's server part. It leaves out PP-28's JVM flags.
- Code: `AiDial` (start and stop of the HTTP server), `IdentityProvider` (user hash), `ResourceBaseTest` (how tests
  read the port). Docs: the settings table in `README.md`.
- Operators: no change unless `aidial.server.instances` is set. With N > 1, upstream connections to one host may be
  opened from any of the N loops; their total count still follows the number of requests in flight.
- Dependencies: none. A later change that raises the default depends on this one and on the perf result.
