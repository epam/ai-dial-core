# Design

## Context

For each attempt Core picks an upstream from the model's `upstreams` through the tiered, weighted balancer. A
deployment without `upstreams` gets one synthetic upstream whose `endpoint` and `id` are its serving endpoint. When
the pass ends, Core builds its analytics row and writes `response.upstream_uri` from the chosen upstream's
`endpoint`, or leaves it out when that is null. A router in decision mode gets a row of its own, copied from the
model's row with the upstream cleared. A pass in `translator` mode picks a model upstream too, but the translator
calls Core back and the call back picks again. A background Responses job saves the start request's row fields in a
record in Redis and writes its row from that record when the job ends. The upstream `id` already identifies an
upstream to `X-UPSTREAM-ID` pinning, prompt-cache pinning and Responses follow-ups, but no row carries it.

## Decisions

### D1. The row names the upstream by its `id`, not by its `extraData` or its position
`id` is the name Core already uses for the upstream wherever it must find it again, it stays the same when other
upstreams are added or removed, and it is short. A blank `id` counts as none, as it does for Responses and Messages.

**Rejected: `extraData`** - free-form JSON the adapter reads, of any size, and it can hold more than the region. It
says how the adapter should call the provider, not which config entry served the request.
**Rejected: the position in `upstreams`** - it moves when an upstream is added or removed, so old rows would point
at the wrong entry.

### D2. Only an entry of a model's `upstreams` counts; the synthetic upstream writes no `upstream_id`
The synthetic upstream's `id` is the serving endpoint, which `upstream_uri` already holds. Writing it would add the
same URL a second time on every application, alias and plugin row, under a name that reads as a config id. A route
row gets none for another reason: the pass has no model deployment.

**Rejected: the value `X-UPSTREAM-ID` matches (`id`, else `endpoint`)** - the same duplication, and for an upstream
with an `endpoint` but no `id` it would write the endpoint under a second name.
**Rejected: give the synthetic upstream no `id`** - Responses and Messages answer 503 "Upstream is missing required
id" without one, and the Responses id mapping stores it.

### D3. `upstream_id` sits in `response`, right after `upstream_uri`
The row reports the upstream under `response` today, so a consumer that reads `upstream_uri` finds the id next to it.

**Rejected: a flat top-level field, as `route_name` is** - it would split the upstream between two places in the
row.

### D4. A pass sent to a translator names the upstream Core chose for it, although that upstream does not answer
This needs no code, and the row stays consistent with `upstream_uri`, which already names that choice. The docs say
that the row of the translator's call back to Core is the one naming the upstream that answered.

**Rejected: no `upstream_id` on a pass in `translator` mode** - the row builder would have to know the pass's
interface and its mode, which the pass context does not hold, and the row would name the choice in `upstream_uri`
while leaving `upstream_id` out.

### D5. The background job record keeps the upstream id itself
The job's row then follows the rule as it applied when the request was served, whatever the config says at job end.

**Rejected: take it from the Responses id mapping at job end** - the mapping stores the upstream's `id`, synthetic
ids included, so telling them apart needs a look at the deployment's `upstreams` at job end. A config change during
the job would then decide the row. It would need no change to the stored record, though.

## Differences from today

| What | Today | After | Requirement |
|------|-------|-------|-------------|
| Row of a pass to which Core chose a model upstream with an `id` | no upstream id | `response.upstream_id` | Upstream in the analytics row |
| Row of a pass sent to a translator | no upstream id | the id of the upstream Core chose for it | Upstream of translator and feature passes |
| Row of a background Responses job | no upstream id | the start request's `response.upstream_id` | Upstream in a background job row |
| Background job record in Redis | no upstream id | `upstreamId` | Upstream in a background job row |

## Effect per request

None: Core reads one more string from the upstream it already chose and appends it to the row it already writes.
There is no new Core pass, body copy, parse or Redis/blob round trip. A background job record grows by the length of
the id.

## Risks / Trade-offs

- [Consumers do not read the field yet] -> a consumer that maps row fields to columns has to map
  `response.upstream_id` before it shows the field (the dependency named in the proposal).
- [Counts per upstream include translator passes] -> a translator pass and its call back both name an upstream. The
  docs say which row to count (D4).
- [Two upstreams of one model share an `id`] -> Core does not check that ids are unique. Their rows look the same,
  as the two upstreams already do to `X-UPSTREAM-ID`, which picks the first match.
- [An `id` is written in plain text] -> an `id` is a config label. `key` and `secretExtraData` stay out of the row,
  and the id goes to the analytics log only, not to spans.

## Migration Plan

None for config. During a rolling deploy, the job record ignores fields it does not know. An old pod that finishes a
job a new pod started writes its row without `upstream_id`, and a new pod that finishes an old pod's job writes none
either. Rollback works the same way.

## Code map

- `server/.../log/AnalyticsLogContext.java:resolveUpstreamId(ProxyContext)` - the one rule for the row and the job
  record: the chosen upstream's `id` when it is not blank and
  `EncryptedContentAffinityUtil.hasConfiguredUpstreams(context.getDeployment())`, else null.
- `server/.../log/AnalyticsLogContext.java:from(ProxyContext, String)` - builds the row. Add `upstreamId` next to
  `upstreamEndpoint`, from `resolveUpstreamId`.
- `server/.../log/AnalyticsLogContext.java:from(BackgroundJobRecord, ResponsesApiClient.TerminalResult, List)` - the
  job's row; map `record.upstreamId()`.
- `server/.../log/AnalyticsLogContext.java:routerRow` - clears `upstreamEndpoint` on the router row, live and from a
  job; clear `upstreamId` the same way.
- `server/.../log/AnalyticsLogContext.java:withoutBodies` - `toBuilder()` keeps the new field; no change.
- `server/.../log/GfLogStore.java:append(AnalyticsLogContext, LogEntry)` - writes `upstream_uri` after
  `response.status`; write `upstream_id` after it, escaped, with the same `"\",\"name\":\""` pattern.
- `server/.../data/BackgroundJobRecord.java:from(ProxyContext, UnaryOperator)` - add the `upstreamId` component next
  to `upstreamEndpoint`, from `AnalyticsLogContext.resolveUpstreamId`. The record is
  `@JsonIgnoreProperties(ignoreUnknown = true)`.
- `server/.../util/EncryptedContentAffinityUtil.java:hasConfiguredUpstreams` - the "model with `upstreams`" check to
  reuse; `UpstreamRouteProvider.java:getUpstreams` builds the synthetic upstream for everything else, and returns
  the model's `upstreams` whatever the interface's mode (D4).
- `server/.../controller/DeploymentFeatureController.java` - sends the first upstream's endpoint and key but sets no
  upstream route, so its rows carry neither field; no change.
- Tests: `GfLogStoreTest` (`logOf`, the `routerRow` and `...FromBackgroundJobRecord` tests),
  `BackgroundJobServiceTest:decidedJobWithoutUsageLeavesTheSpanRollUpToTheRouterRow` (runs `saveJob` to
  `logStore.save` with a deep-stubbed context), `ResourceBaseTest:awaitAnalyticsRows` and `analyticsRows`,
  `CostObjectAnalyticsLogApiTest` as the pattern for an API test with its own `dial-config/*.json`,
  `MessagesSpendLimitApiTest` with `dial-config/anthropic-spend-limits.json` as the pattern for a translator mock.
- Docs: `docs/tracing.md` (section "Route on log lines and analytics" is the pattern),
  `docs/dynamic-settings/models.md` (the `id` bullet under `models.<model_name>.upstreams`).
