# Review

## What shipped

- Static setting `analytics.errorRows` (env `aidial.analytics.errorRows`, default `false`). With it off, every row is unchanged.
- With it on, one row for every error answer Core gives itself through the `respond(...)` helpers on the LLM surfaces, feature
  calls, the MCP proxy and routes (the answers logged as `Responding with error`), and one row for a stream that breaks after its
  headers on the LLM surfaces. `response.status` is the client's; no new field, no tokens (a broken Messages stream keeps the
  usage already relayed, unpriced and uncharged), an empty `response.body`. An early refusal of a resolved deployment takes its
  `parent_deployment` from the path its key would have had (`AnalyticsLogContext.fromError`).
- No row for `401`, `413`, an interceptor call, a decision-mode router that fails before it decides or whose candidate does not
  resolve, a stream that breaks on a feature call, MCP or a route, WebSocket, or a recorded background job's stream.
- Docs: the settings table in `README.md` and "Error rows in the analytics log" in `docs/tracing.md`.

## Verification

- `./gradlew checkstyleMain checkstyleTest`: passed.
- `./gradlew :server:test` for `ErrorRowsApiTest`, `ErrorRowsDisabledApiTest`, `LogStoreTest`, `GfLogStoreTest`,
  `AnalyticsSettingsTest`, `ProxyTest`, `ResponsesControllerTest`, `ResponsesApiTest`, `ResponsesSpendLimitApiTest`,
  `ResponseItemControllerTest`, `BackgroundJobServiceTest`, `BudgetSpendCapsApiTest`, `RouterDecisionApiTest`,
  `EmbeddedDecisionApiTest`, `EmbeddedCallApiTest`, `AliasApiTest`, `AliasInProcessApiTest`, `DeploymentPostApiTest`,
  `RouteApiTest`: passed.
- `npx -y @fission-ai/openspec@1.14.3 validate analytics-error-rows --strict` and `validate --all --strict`: valid.

## Drift from design.md

- `ResponsesController.handleNonStreamingResponse` ends its chain with `transform`: a failed attachment scan answered `502` after
  the relay, which an in-process response does not reject, so the pass wrote a second row. A failed job save now ends the pass
  itself (Decision 6).
- `isErrorRowAllowed()` also matches a decided candidate by its decoded name, the form a router names a resource application by.
- The static response of a route is put on the context after the rate limit, so an earlier error row has no body.

## Not done

- Tasks 5.1 (Redis command count with the setting off and on) and 5.2 (the investigation's scenarios against a built Core):
  deferred, they need Docker.
- Covered by the rule and not exercised by a test: `500 Failed to process deployment`, `503 Upstream is missing required id`,
  `422 Failed to receive body`, and the `403 Forbidden route` of a route whose permissions the caller lacks.
- Older than this change, with the setting off too: a client that leaves a streamed background Responses request to a model
  gets a pass row and a usage charge, then the job's poller writes and charges again; a non-streamed background request whose job
  save fails writes no row.
