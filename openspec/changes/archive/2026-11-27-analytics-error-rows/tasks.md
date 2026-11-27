# Tasks

Integration tests are in `ErrorRowsApiTest` (setting on) and `ErrorRowsDisabledApiTest` (setting absent), both on
`BaseErrorRowsApiTest extends ResourceBaseTest` (server module: full stack, embedded Redis, `TestWebServer`/`MockWebServer`). Rows
are read with `awaitAnalyticsRows(n)` and checked for an **exact** count, so a duplicate fails the test; "no row" is checked by
waiting and finding none. Every test uses **its own model name**, because the balancer's upstream states persist per model within a
test JVM and would couple tests. A two-upstream model uses a mock adapter that answers by the `X-UPSTREAM-ENDPOINT` header (Core calls
the model's endpoint and only names the chosen upstream in that header), with the failing upstream in tier 0. Spec scenario names are
in quotes; test names follow `#`. a2m is `app-to-model-accounting`.

Commit `9b6043c7` implements the first, narrower draft. Group 0 takes back what this revision supersedes (design.md, "Superseded").

Notes from implementing (each is also in design.md where it changes a decision):
- A decision-mode router that fails before it decides writes no row, but a router in proxy mode is the origin of its own pass and gets
  one like any pass: the exclusion tests `router.getMode() == Router.Mode.DECISION`, not `instanceof Router` alone
  (`#testRouterDecisionFails` uses `r-down`, `#testProxyModeRouterFails` uses `r2`, `#testDecidedRequestFails` uses `r-two`).
- A `403` row has no `deployment`: Core checks the role before it puts the deployment on the pass (`#testForbiddenDeployment`).
- `#testUnknownKeyWritesNoRow` and `#testBodyTooLargeWritesNoRow` need `resources.maxSize` 20000 in `BaseErrorRowsApiTest`.
- From review: a2m "Rate limit of a child pass" is `#testChildPassRateLimit` (before the pass has a key, the error row's parent
  comes from `ApiKeyData.pathOf`, in `AnalyticsLogContext.fromError`, so no other row changes), "Decided application with an
  encoded name" is `#testDecidedCandidateWithAnEncodedName` (`isErrorRowAllowed()` also compares the decoded name), and "Relayed
  error of a pass served in process" is `#testRelayedErrorOfAnInProcessPassHasOneRow` (`ResponsesController` no longer answers
  `502` after it relayed a non-streaming answer whose attachment scan failed: an in-process response does not reject it). When the
  job save of a non-streamed background request fails, that branch now calls `finalizeRequest()` itself, as the late helper did
  before, since no poller ends the pass (`ResponsesControllerTest#testBackgroundJobRecordNotSavedFinalizesThePass`). The deltas of
  `analytics-log` ("Rows without bodies for an alias pass", "Spending limits in the analytics row") and `router-deployment`
  ("Decision record") say "no row" only with the setting off.

## 0. Take back the narrow implementation

- [x] 0.1 Remove `rowMark` and `markRowWritten()` from `ProxyContext`, `lastUpstreamStatus` (the field and the six
  `setLastUpstreamStatus` calls in `BaseChatCompletionController`, `ResponsesController` and `MessagesBaseController`), and
  `writeExhaustedRow()` with its call in `nextUpstream`. Verify: a search of `server/src/main` for these names finds nothing, and
  `ChatCompletionApiTest`, `ResponsesApiTest` and `MessagesApiTest` pass.
- [x] 0.2 Remove the `error` object: `errorReason` from `AnalyticsLogContext` and its branch in `GfLogStore.append`. Delete
  `GfLogStoreTest#testErrorObjectWritten` and `#testErrorObjectOmittedWithoutReason`. Rewrite `#testErrorRowResponseBodyEmpty` without
  `errorReason` (analytics-log, "Refusal": a row with a request body and no response body has `request.body`, `response.status`,
  `"body":""`, no `token_usage` and no `error` member). Verify `GfLogStoreTest` passes and the line of a row with no response body
  ends exactly as the line of a row before this change did.
- [x] 0.3 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.log.GfLogStoreTest" --tests "com.epam.aidial.core.server.log.AnalyticsSettingsTest"`;
  both pass. (`ErrorRowsApiTest` fails until group 2: its tests assert the narrow behaviour.)

## 1. The setting (already in `9b6043c7`, stays)

- [x] 1.1 `AnalyticsSettingsTest#testErrorRowsDefaultsToFalse` and `#testErrorRowsRead` (analytics-log, "Setting absent").
- [x] 1.2 `errorRows` in `AnalyticsSettings`, the default method `LogStore.isErrorRowsEnabled()` and its override in `GfLogStore`.
- [x] 1.3 `ResourceBaseTest.createLogStore` delegates `isErrorRowsEnabled()`; `ErrorRowsApiTest#testSettingReachesTheStore` and
  `ErrorRowsDisabledApiTest#testSettingAbsentIsOff`.
- [x] 1.4 `"errorRows": false` in the `analytics` object of `server/src/main/resources/aidial.settings.json`; the test-resources file
  is not changed.
- [x] 1.5 Rewrite the `analytics.errorRows` row of the settings table in `README.md` for this scope: default `false`, one row for every
  error answer Core gives itself on the LLM surfaces and for a stream that breaks after its headers, `response.status` is the
  client's, no new field, `401` and `413` write none, env var `aidial.analytics.errorRows`. Verify the row has the table's four
  columns.

## 2. Answers Core gives itself (point A: the `respond(...)` helpers)

- [x] 2.1 Write the tests first, in `ErrorRowsApiTest` (setting on), and see them fail before 2.2. Change the existing ones that assert
  the narrow behaviour:
  - a2m "Every upstream busy": `#testEveryUpstreamBusy` (one row, `deployment` the model, `response.status "503"`);
    "Origin answers 502/503/504": `#testOrigin502`, `#testOrigin503`, `#testOrigin504` (client `502`, row status `"502"`);
    "Two upstreams, both busy": `#testTwoUpstreamsBothBusy`; "Last attempt busy, another upstream failed":
    `#testLastAttemptBusyAnotherFailed`; "Last attempt failed after a busy one": `#testLastAttemptFailedAfterBusy`. They stay, minus
    every assertion about `error`; `BaseErrorRowsApiTest.assertErrorRow` checks the deployment and the status only.
  - a2m "Connection refused": `#testConnectionRefused` (was `...WritesNoRow`; one row, status `"502"`); "Host that does not resolve":
    `#testHostThatDoesNotResolve` (an endpoint on a `.invalid` host); "No response headers": `#testNoResponseHeaders` (was
    `...WritesNoRow`; `client.idleTimeout` 1500 ms, `setHeadersDelay` longer; one row); `#testLastAttemptWithoutStatus` (502 then no
    headers; one row, as the filter on the last status is gone).
  - a2m "Rate limit": `#testRateLimit` (was `...WritesNoRow`; two rows in all, the first call's and the `429`'s, whose
    `response.status` is `"429"`); "Forbidden deployment": `#testForbiddenDeployment` (`userRoles` excludes the key's role; one row
    with `deployment` the model, status `"403"`); "Unknown deployment": `#testUnknownDeployment` (was `...WritesNoRow`; one row, status
    `"404"`, no `deployment`); "Wrong content type": `#testWrongContentType` (a `text/plain` body; `415`, one row); "Body that is not
    JSON": `#testBodyThatIsNotJson` (`400`, one row).
  - a2m "Other surfaces": `#testApplicationExhausted`, `#testV1ChatCompletionsExhausted`, `#testResponsesExhausted`,
    `#testMessagesExhausted` (an origin that answers `429` with one attempt; `503`, one row each, no `error`).
  - a2m "Decided request fails": `#testDecidedRequestFails` (was `...WritesNoErrorRow`; router `r1` chooses `gpt-4`, whose origin
    answers `502`: the router row as with the setting off, and one row for the candidate). Use `dial-config/router-decision.json` as
    `RouterDecisionApiTest` does.
  - a2m "No error row for what Monitoring does not log": `#testUnknownKeyWritesNoRow` (`401`) and `#testBodyTooLargeWritesNoRow` (`413`;
    `resources.maxSize` is 20000 in the base class); "Relayed origin error": `#testRelayedOriginError` (stays).
  - a2m "One row per pass": `#testRetryThatSucceeds` (stays), `#testInterceptorFailureWritesNoRow` (stays), and `#testRouterDecisionFails`
    ("Router decision fails": a router whose decide request answers `502`; the client gets `502`, no row).
  - analytics-log "Refusal" and "Successful pass with the setting on": `#testErrorRowFields` (the row's `trace.trace_id` equals the
    `X-DIAL-TRACE-ID` the client got, `response.body` is null in the context and empty in the line, no `token_usage`, no
    `upstream_uri` for an exhausted route) and `#testSuccessUnchanged` (stays).
  - In `ErrorRowsDisabledApiTest` (a2m "Setting off", analytics-log "Setting absent"): `#testSettingOffEveryUpstreamBusy`,
    `#testSettingOffNoRoute`, `#testSettingOffTwoUpstreamsFailed`, `#testSettingOffSuccessRow`, `#testModelBehindInterceptorsOneRow`
    stay; add `#testSettingOffRefusals` (`403`, `404`, `415` and `400` each write no row) and `#testSettingOffConnectionRefused` (no row).
- [x] 2.2 Add the default methods `LogStore.saveErrorRow(ProxyContext)` (saves the row when the setting is on) and
  `saveErrorResponse(ProxyContext)` (calls it when the status of the response is outside 200-299), with `LogStoreTest` for each branch. At the end of
  each of the four `respond(...)` helpers of `BaseDeploymentPostController`, after `context.respond(...)`, call
  `proxy.getLogStore().saveErrorResponse(context)` when `isErrorRowAllowed()` is true; no other method or class writes the row, and
  nothing is wrapped in a `try/catch` (design D1, D2, D7). `isErrorRowAllowed()` is false for a router in decision mode with no
  decision. Override `isErrorRowAllowed()` in
  `BaseInterceptorController` to return `false`. Verify 2.1 passes, with exact row counts.
- [x] 2.3 Add one test for each answer of the same helpers that a test can reach and 2.1 does not cover: `#testUnknownUpstreamId`
  (`400`, `X-UPSTREAM-ID` naming no upstream). List the ones not run (`500 Failed to process deployment`, `503 Upstream is missing
  required id`, `422 Failed to receive body`) in `review.md` as covered by the rule and not exercised.
- [x] 2.4 Rewrite the section "Error rows in the analytics log" of `docs/tracing.md` for this scope: the setting and its default; the
  rule (a row for every answer that logs `Responding with error`, through the LLM surfaces); `response.status` is the client's and
  how an origin `429`, `502`, `503` or `504` becomes `503` or `502`; no new field, empty `response.body`, no tokens; what writes no row
  (`401`, `413`, interceptor calls, a router that fails before it decides, routes, MCP, feature calls, a relayed origin error).
  Verify the section names the setting as `README.md` does.
- [x] 2.5 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.ErrorRowsApiTest" --tests "com.epam.aidial.core.server.ErrorRowsDisabledApiTest" --tests "com.epam.aidial.core.server.RouterDecisionApiTest" --tests "com.epam.aidial.core.server.ProxyTest"`;
  all pass.

## 3. A stream that breaks (point B: `handleResponseError`); can ship as its own PR (D1, D4)

- [x] 3.1 Write the tests first, in `ErrorRowsApiTest`, and see them fail before 3.2: a2m "Origin closes after the headers":
  `#testOriginClosesAfterHeaders` (a model's origin sends `200` and event-stream headers, then closes; one row, status `"200"`; the
  client gets no complete answer); "Origin breaks mid-stream": `#testOriginBreaksMidStream` (two events, then a close; one row, the
  client's stream has no `[DONE]`); "Client leaves a model": `#testClientLeavesModel` (one row, the one written with the setting off,
  not two); "Application's origin breaks": `#testApplicationOriginBreaks`; "Client leaves an application":
  `#testClientLeavesApplication` (one row; the key Core sent the application still exists in Redis). Use
  `SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY`, as `EmbeddedCallApiTest#testTargetFailsMidStream` does. In
  `ErrorRowsDisabledApiTest`: `#testSettingOffOriginBreaks` (a2m "Setting off, the origin breaks": no row).
- [x] 3.2 In `handleResponseError`, with the setting on (`logStore.isErrorRowsEnabled()`), save the row with `logStore.save(AnalyticsLogContext.from(context, null))`: when `responseStream.endStreamFuture()` of a `Model` fails, and
  after `context.getProxyRequest().reset()` for an application (design D1, D6). Verify 3.1 passes, and `#testClientLeavesModel` still
  finds one row.
  A Responses request with `background: true` writes no row here once its job is recorded (its terminal row is the job's), and
  writes it here when the stream breaks before that (`#testBrokenStreamOfRecordedBackgroundJobWritesNoRow`, `#testBrokenStreamOfBackgroundRequestBeforeItsJobIsRecorded`).
- [x] 3.3 Add the broken stream to `docs/tracing.md`: it is a row with the status Core had set, `200`, and no tokens (D4).
- [x] 3.4 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.ErrorRowsApiTest" --tests "com.epam.aidial.core.server.ErrorRowsDisabledApiTest" --tests "com.epam.aidial.core.server.EmbeddedCallApiTest"`;
  all pass.

## 4. Feature calls, MCP and routes (point A2); can ship as its own PR

- [x] 4.1 Write the tests first, in `ErrorRowsApiTest` (setting on), and see them fail before 4.2. Feature calls: a2m "Feature call whose
  origin refuses the connection": `#testFeatureCallConnectionError` (`POST /v1/deployments/chat-gpt-35-turbo/tokenize` with nothing on
  port 7001; `502`, one row); "Feature call to an unknown deployment": `#testFeatureCallUnknownDeployment` (`404`, one row, no
  `deployment`); "Feature call that needs no origin": `#testFeatureWithoutEndpointWritesOneRow` (a model with no rate endpoint, `POST /v1/{id}/rate`;
  `200`, one row). MCP: "MCP call to an unknown toolset": `#testMcpUnknownToolset` (`POST /v1/toolset/unknown/mcp`; `404`, one
  row); "MCP server that cannot be reached": `#testMcpServerUnreachable` (toolset `git`, nothing on port 9876; `502` or `503`, one row
  with the same status). Routes: "Route refused for a role": `#testRouteRefusedByRole` (`POST /v1/vector_store/1` as
  `vstore_user_key`; `403`, one row); "Route that is not found": `#testRouteNotFound` (`POST /unexpected`; `404`, one row); "Route
  whose only upstream is busy": `#testRouteUpstreamBusy` (`POST /tasks/task1`, origin on 9876 answers `429`; `503`, one row). Relays,
  "Origin's error relayed on these surfaces": `#testFeatureCallRelayedErrorHasOneRow` (origin on 7001 answers `500`),
  `#testMcpRelayedErrorHasOneRow` (toolset `git`, origin answers `500`) and `#testRouteRelayedErrorHasOneRow` (`/tasks/task1`, origin
  answers `500`): the client gets `500` and exactly one row each. In `ErrorRowsDisabledApiTest` (a2m "Setting off"):
  `#testSettingOffOtherSurfaces` (a feature call to an unknown deployment, an MCP call to an unknown toolset and a refused route each
  write no row).
- [x] 4.2 Add the same check of the store and the same `save`, written in the helper, after `context.respond(...)` in the two helpers of
  `DeploymentFeatureController`, in `McpProxyController.respond(HttpStatus, String)` and `respond(HttpException)` (a comment on
  `respond(int, Buffer)` says why it is not hooked), and in the three helpers of `BaseRouteController` and after the direct
  `context.respond` at `:75`: `logStore.saveErrorResponse(context)`, one line.
  No new method, no new class, nothing in `ProxyContext`. Verify 4.1 passes, and that the tests of group 2 still pass.
- [x] 4.3 Extend `README.md` and the "Error rows" section of `docs/tracing.md` to feature calls, MCP and routes, and say that the relayed
  error of these surfaces keeps its one row and that a stream that breaks on them writes none. Verify both name the surfaces as the spec.
- [x] 4.4 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.ErrorRowsApiTest" --tests "com.epam.aidial.core.server.ErrorRowsDisabledApiTest" --tests "com.epam.aidial.core.server.controller.*" --tests "com.epam.aidial.core.server.FeaturesApiTest" --tests "com.epam.aidial.core.server.ToolSetApiTest" --tests "com.epam.aidial.core.server.RouteApiTest"`;
  all pass.

## 5. Integration checks

- [ ] 5.1 Count Redis commands (`redis-cli MONITOR`) for one exhausted-`429` pass with the setting off and on, record both counts in
  `review.md`; they must be equal (design, per-request cost) (deferred: needs Docker and a Redis on this machine).
- [ ] 5.2 Re-run the investigation's scenarios (`REPORT.md`, scenarios 2 to 9 and x01 to x10) against a built Core with the setting off
  and on, and record in `review.md` which rows appear: off, the 0/1 counts of the report; on, one row for each event of
  `REPORT.md` section 7 (deferred: needs Docker, Node and a built Core).
- [x] 5.3 Run `npx -y @fission-ai/openspec@1.14.3 validate analytics-error-rows --strict` and
  `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`; the change passes (the `resource-subscription` Purpose warning is not
  this change's).
- [x] 5.4 Run `./gradlew checkstyleMain checkstyleTest` and the touched test classes: `ErrorRowsApiTest`, `ErrorRowsDisabledApiTest`,
  `GfLogStoreTest`, `AnalyticsSettingsTest`, `AnalyticsLogContextTest`, `ChatCompletionApiTest`, `ResponsesApiTest`, `MessagesApiTest`,
  `RouterDecisionApiTest`, `ProxyTest`, `EmbeddedCallApiTest`, and the `controller` package; all pass.

## Workflow follow-up

- `review.md` in the change folder, then `npx -y @fission-ai/openspec@1.14.3 archive analytics-error-rows -y`, then
  `validate --specs --strict`; one commit with explicit paths. The archive merges the two MODIFIED requirements and the ADDED ones into
  `app-to-model-accounting` and `analytics-log`.
- Not in this change, each its own: `401` and `413` (no Monitoring record), routes, MCP, feature calls, WebSocket and background jobs,
  a field or a mark in the row, the attempt count on a success row.
