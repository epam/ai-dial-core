# Tasks

## 1. Upstream id in the row of a pass

- [x] 1.1 Add `dial-config/upstream-analytics.json`, with the key and role the tests use, pointing at the test web
  server: model `m-regions` (`maxRetryAttempts: 2`, upstreams `{id: a, tier: 0, extraData: {"region": "r1"}}` and
  `{id: b, tier: 1, extraData: {"region": "r2"}}`, no upstream `endpoint`), model `m-endpoint-no-id` (one upstream
  `{endpoint: <test server>/up-a}`), model `m-no-id` (one upstream `{extraData: {"region": "r1"}}`), model `m-blank-id`
  (one upstream `{id: "", extraData: {"region": "r1"}}`), model `m-plain` (an `endpoint`, no `upstreams`), model
  `m-translated` (one upstream `{id: a}`, `anthropicMessages` in `translator` mode with an inline translator, as
  `dial-config/anthropic-spend-limits.json` has) and model `m-features` (one upstream `{endpoint: ..., id: a}` and
  `features.tokenizeEndpoint`)
- [x] 1.2 Add `UpstreamAnalyticsLogApiTest` (spec: analytics-log): `testUpstreamsToldApartOnlyById` (503 for the
  attempt whose `X-UPSTREAM-EXTRA-DATA` has region `r1`, 200 for `r2`; the row has `upstreamId` `b` and no
  `upstreamEndpoint`), `testUpstreamWithEndpointAndNoId` (`upstreamEndpoint` set, no `upstreamId`),
  `testUpstreamWithoutId` (`m-no-id` and `m-blank-id`: no `upstreamId`), `testModelWithoutUpstreams` (`upstreamEndpoint`
  is `m-plain`'s endpoint, no `upstreamId`), `testPassSentToTranslator` (the translator mock answers the Messages
  request; the row has `upstreamId` `a`) and `testFeatureCall` (tokenize to `m-features`; the row has neither field)
- [x] 1.3 Add to `GfLogStoreTest`: `testUpstreamIdIsWrittenAfterUpstreamUri` (scenario "Upstream with an endpoint and
  an id": `"response":{"status":"200","upstream_uri":"http://up-a/v1","upstream_id":"a",...}`),
  `testUpstreamIdIsWrittenAfterStatusWithoutUpstreamUri`, `testUpstreamIdIsOmittedWhenNull`,
  `testUpstreamIdIsEscaped` (an id with `"` and `\` still parses as JSON and reads back unchanged) and
  `testRouterRowHasNoUpstreamId` (scenario "Router row": `routerRow` of a serving row with `upstreamId` `a`)
- [x] 1.4 `AnalyticsLogContext`: add `upstreamId`, set in `from(ProxyContext, String)` from the chosen upstream's `id`
  when it is not blank and `EncryptedContentAffinityUtil.hasConfiguredUpstreams(context.getDeployment())`; clear it in
  `routerRow`
- [x] 1.5 `GfLogStore.append(AnalyticsLogContext, LogEntry)`: write `upstream_id`, escaped, after `upstream_uri`
- [x] 1.6 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.UpstreamAnalyticsLogApiTest" --tests "com.epam.aidial.core.server.log.GfLogStoreTest"
  --tests "com.epam.aidial.core.server.RouterDecisionApiTest"`

## 2. Upstream id in the row of a background job

- [x] 2.1 Add to `BackgroundJobServiceTest`, next to `decidedJobWithoutUsageLeavesTheSpanRollUpToTheRouterRow`
  (which runs the real `saveJob` to `logStore.save` chain with a deep-stubbed context): `jobRowHasTheUpstreamId`
  (scenario "Job on a model upstream": the context's deployment is a model with upstream `{id: a}` in `upstreams` and
  the route's chosen upstream is that one; the job's row has `upstreamId` `a`, and for a decided job the router row
  has none) and `jobRowWithoutUpstreamsHasNoUpstreamId` (scenario "Job on a deployment without upstreams": a model
  with no `upstreams` whose synthetic upstream has `id` = its endpoint; the row has no `upstreamId`)
- [x] 2.2 Add `GfLogStoreTest.testUpstreamIdFromBackgroundJobRecord` (the row built from a record with `upstreamId`
  `a` has `response.upstream_id` `a`) and `testBackgroundJobRecordStoredWithoutUpstreamIdLoadsWithNone` (a record
  JSON without `upstreamId` loads, and its row has no `upstream_id`)
- [x] 2.3 `BackgroundJobRecord`: add the `upstreamId` component and set it in `from(ProxyContext, UnaryOperator)`
  with the same rule as the row; map it in `AnalyticsLogContext.from(BackgroundJobRecord, ...)`
- [x] 2.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.service.BackgroundJobServiceTest" --tests
  "com.epam.aidial.core.server.log.GfLogStoreTest" --tests "com.epam.aidial.core.server.controller.ResponsesControllerTest"`

## 3. Docs and checks

- [x] 3.1 `docs/tracing.md`: a section "Upstream in analytics" next to "Route on log lines and analytics", naming
  `response.upstream_uri` (the upstream's `endpoint`) and `response.upstream_id` (the upstream's `id`, written only
  for an entry of a model's `upstreams` that has one). Say the id goes to the analytics log only, never to spans or
  log lines, and that a pass sent to a translator names the upstream Core chose for it, while the row of the
  translator's call back names the upstream that answered. Check in `testPassSentToTranslator`'s setup or an existing
  translator test which row field links the call back to the translator pass, and name it.
- [x] 3.2 `docs/dynamic-settings/models.md`: the `id` bullet under `models.<model_name>.upstreams` says the analytics
  row carries it as `response.upstream_id`, so upstreams without an `endpoint` should set it to be told apart
- [x] 3.3 `./gradlew checkstyleMain checkstyleTest`, the test classes of groups 1 and 2 with `--tests`, and
  `npx -y @fission-ai/openspec@1.14.3 validate analytics-upstream-id --strict`

## Workflow follow-up

- Analytics consumers that map row fields to columns add `response.upstream_id` (outside this PR).
- Separate fix PR: `X-UPSTREAM-ID` on a model whose upstream has neither `id` nor `endpoint` throws while matching.
- #2320 (analytics error rows): whichever PR merges second adds a scenario for `upstream_id` on an error row.
- `review.md`, then `npx -y @fission-ai/openspec@1.14.3 archive analytics-upstream-id -y` and
  `validate --specs --strict`.
