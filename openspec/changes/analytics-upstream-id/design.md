# Design

## Context

For each attempt Core picks an upstream from the model's `upstreams` through the tiered, weighted balancer. A
deployment without `upstreams` gets one synthetic upstream whose `endpoint` and `id` are its serving endpoint. When
the pass ends, Core builds its analytics row and writes `response.upstream_uri` from the chosen upstream's
`endpoint`, or leaves it out when that is null. A router in decision mode gets a row of its own, copied from the
model's row with the upstream cleared. A background Responses job saves the start request's row fields in a record
in Redis and writes its row from that record when the job ends. The upstream `id` already identifies an upstream to
`X-UPSTREAM-ID` pinning, prompt-cache pinning and Responses follow-ups, but no row carries it.

## Decisions

### D1. The row names the upstream by its `id`, not by its `extraData` or its position
`id` is the name Core already uses for the upstream wherever it must find it again, it stays the same when other
upstreams are added or removed, and it is short.

**Rejected: `extraData`** - free-form JSON the adapter reads, of any size, and it can hold more than the region. It
says how the adapter should call the provider, not which config entry served the request.
**Rejected: the position in `upstreams`** - it moves when an upstream is added or removed, so old rows would point
at the wrong entry.

### D2. Only an entry of a model's `upstreams` counts; the synthetic upstream writes no `upstream_id`
The synthetic upstream's `id` is the serving endpoint, which `upstream_uri` already holds. Writing it would add the
same URL a second time on every application, alias, plugin and route row, under a name that reads as a config id.

**Rejected: the value `X-UPSTREAM-ID` matches (`id`, else `endpoint`)** - the same duplication, and for an upstream
with an `endpoint` but no `id` it would write the endpoint under a second name.

### D3. `upstream_id` sits in `response`, right after `upstream_uri`
The row reports the upstream under `response` today, and the analytics store reads `upstream_uri` from there.

**Rejected: a flat top-level field, as `route_name` is** - it would split the upstream between two places in the
row.

## Differences from today

| What | Today | After | Requirement |
|------|-------|-------|-------------|
| Row of a pass served by a model upstream with an `id` | no upstream id | `response.upstream_id` | Upstream in the analytics row |
| Row of a background Responses job | no upstream id | the start request's `response.upstream_id` | Upstream in the analytics row |
| Background job record in Redis | no upstream id | `upstreamId` | Upstream in the analytics row |

## Effect per request

None: Core reads one more string from the upstream it already chose and appends it to the row it already writes.
There is no new Core pass, body copy, parse or Redis/blob round trip. A background job record grows by the length of
the id.

## Risks / Trade-offs

- [The analytics store does not read the field yet] -> it is in the log files only until the ADAS ingest maps
  `response.upstream_id` to a column (the dependency named in the proposal).
- [Two upstreams of one model share an `id`] -> Core does not check that ids are unique. Their rows look the same,
  as the two upstreams already do to `X-UPSTREAM-ID`, which picks the first match.
- [An `id` is written in plain text] -> an `id` is a config label. `key` and `secretExtraData` stay out of the row.

## Migration Plan

None for config. During a rolling deploy, the job record ignores fields it does not know. An old pod that finishes a
job a new pod started writes its row without `upstream_id`, and a new pod that finishes an old pod's job writes none
either. Rollback works the same way.

## Code map

- `server/.../log/AnalyticsLogContext.java:from(ProxyContext, String)` - builds the row. Add `upstreamId` next to
  `upstreamEndpoint`, set only when `EncryptedContentAffinityUtil.hasConfiguredUpstreams(context.getDeployment())`.
- `server/.../log/AnalyticsLogContext.java:from(BackgroundJobRecord, ResponsesApiClient.TerminalResult, List)` - the
  job's row; map `record.upstreamId()`.
- `server/.../log/AnalyticsLogContext.java:routerRow` - clears `upstreamEndpoint` on the router row; clear
  `upstreamId` the same way.
- `server/.../log/AnalyticsLogContext.java:withoutBodies` - `toBuilder()` keeps the new field; no change.
- `server/.../log/GfLogStore.java:append(AnalyticsLogContext, LogEntry)` - writes `upstream_uri` after
  `response.status`; write `upstream_id` after it, escaped, with the same `"\",\"name\":\""` pattern.
- `server/.../data/BackgroundJobRecord.java:from(ProxyContext, UnaryOperator)` - add the `upstreamId` component next
  to `upstreamEndpoint`, with the same rule as the row. The record is `@JsonIgnoreProperties(ignoreUnknown = true)`.
- `server/.../util/EncryptedContentAffinityUtil.java:hasConfiguredUpstreams` - the "model with `upstreams`" check to
  reuse; `UpstreamRouteProvider.java:getUpstreams` builds the synthetic upstream for everything else.
- Tests: `GfLogStoreTest` (`logOf`, the `routerRow` and `...FromBackgroundJobRecord` tests),
  `ResponsesControllerTest:testBackgroundJobRecordSaved`, `ResourceBaseTest:awaitAnalyticsRows` and
  `analyticsRows`, `CostObjectAnalyticsLogApiTest` as the pattern for an API test with its own `dial-config/*.json`.
- Docs: `docs/tracing.md` (section "Route on log lines and analytics" is the pattern),
  `docs/dynamic-settings/models.md` (the `id` bullet under `models.<model_name>.upstreams`).
