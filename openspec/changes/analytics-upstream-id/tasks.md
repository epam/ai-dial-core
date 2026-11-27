# Tasks

## 1. Upstream id in the row of a pass

- [ ] 1.1 Add `dial-config/upstream-analytics.json` with model `m-regions` (`maxRetryAttempts: 2`, upstreams
  `{id: a, tier: 0, extraData: {"region": "r1"}}` and `{id: b, tier: 1, extraData: {"region": "r2"}}`, no upstream
  `endpoint`), model `m-no-id` (one upstream `{extraData: {"region": "r1"}}`) and application `app` (an `endpoint`,
  no `upstreams`), all pointing at the test web server
- [ ] 1.2 Add `UpstreamAnalyticsLogApiTest` (spec: analytics-log, "Upstream in the analytics row"):
  `testUpstreamsToldApartOnlyById` (first attempt 503, second 200; row has `upstreamId` `b` and no
  `upstreamEndpoint`), `testUpstreamWithoutId` (neither field), `testDeploymentWithoutUpstreams` (`upstreamEndpoint`
  is `app`'s endpoint, no `upstreamId`)
- [ ] 1.3 Add to `GfLogStoreTest`: `testUpstreamIdIsWrittenAfterUpstreamUri` (scenario "Upstream with an endpoint and
  an id": `"response":{"status":"200","upstream_uri":"http://up-a/v1","upstream_id":"a",...}`),
  `testUpstreamIdIsWrittenAfterStatusWithoutUpstreamUri`, `testUpstreamIdIsOmittedWhenNull`, and
  `testRouterRowHasNoUpstreamId` (scenario "Router row": `routerRow` of a serving row with `upstreamId` `a`)
- [ ] 1.4 `AnalyticsLogContext`: add `upstreamId`, set in `from(ProxyContext, String)` from the chosen upstream's `id`
  only when `EncryptedContentAffinityUtil.hasConfiguredUpstreams(context.getDeployment())`; clear it in `routerRow`
- [ ] 1.5 `GfLogStore.append(AnalyticsLogContext, LogEntry)`: write `upstream_id`, escaped, after `upstream_uri`
- [ ] 1.6 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.UpstreamAnalyticsLogApiTest" --tests "com.epam.aidial.core.server.log.GfLogStoreTest"
  --tests "com.epam.aidial.core.server.RouterDecisionApiTest"`

## 2. Upstream id in the row of a background job

- [ ] 2.1 Extend `ResponsesControllerTest.testBackgroundJobRecordSaved` (scenario "Background job row"): the
  deployment is a model with the chosen upstream `{id: a, ...}` in its `upstreams`, and the saved record has
  `upstreamId` `a`
- [ ] 2.2 Add `GfLogStoreTest.testUpstreamIdFromBackgroundJobRecord` (the row built from a record with `upstreamId`
  `a` has `response.upstream_id` `a`) and extend `testBackgroundJobRecordStoredWithoutSessionLoadsWithNoSession` or
  add a sibling: a record stored without `upstreamId` loads with none
- [ ] 2.3 `BackgroundJobRecord`: add the `upstreamId` component and set it in `from(ProxyContext, UnaryOperator)`
  with the same rule as the row; map it in `AnalyticsLogContext.from(BackgroundJobRecord, ...)`
- [ ] 2.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.controller.ResponsesControllerTest" --tests
  "com.epam.aidial.core.server.log.GfLogStoreTest" --tests "com.epam.aidial.core.server.service.BackgroundJobServiceTest"`

## 3. Docs and checks

- [ ] 3.1 `docs/tracing.md`: a section "Upstream in analytics" next to "Route on log lines and analytics", naming
  `response.upstream_uri` (the upstream's `endpoint`) and `response.upstream_id` (the upstream's `id`, written only
  for a model's `upstreams` entry that has one)
- [ ] 3.2 `docs/dynamic-settings/models.md`: the `id` bullet under `models.<model_name>.upstreams` says the analytics
  row carries it as `response.upstream_id`, so upstreams without an `endpoint` should set it to be told apart
- [ ] 3.3 `./gradlew checkstyleMain checkstyleTest`, the test classes of groups 1 and 2 with `--tests`, and
  `npx -y @fission-ai/openspec@1.14.3 validate analytics-upstream-id --strict`

## Workflow follow-up

- ADAS ingest maps `response.upstream_id` to a `dial_usage_log` column (ADAS work, outside this PR).
- Separate fix PR: `X-UPSTREAM-ID` on a model whose upstream has neither `id` nor `endpoint` throws while matching.
- `review.md`, then `npx -y @fission-ai/openspec@1.14.3 archive analytics-upstream-id -y` and
  `validate --specs --strict`.
