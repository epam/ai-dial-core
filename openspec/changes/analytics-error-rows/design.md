# Design

## Context

See proposal.md, Why and Out of scope. Two labels are used below: **Code** means read in the source (line numbers are those of
commit `9b6043c7`), and **Measured** means seen in the investigation run (`REPORT.md`: a running Core, a mock upstream, each
scenario joined by trace id). Anything not marked is expected, not run.

- Every error answer that `ChatCompletionsController`, `DeploymentPostController`, `ResponsesController`,
  `MessagesBaseController` and `BaseInterceptorController` give goes through one of the four `respond(...)` helpers of
  `BaseDeploymentPostController.java:109-127` (Code: a search for `context.respond(` and `respond(` in those files finds only
  helper calls, and no helper call with a 2xx status). Each helper calls `finalizeRequest()`, which saves nothing, and then
  `ProxyContext.java:respond(int, Buffer)`.
- `ProxyContext.respond` sets the status and ends the response (`response.setStatusCode(status).end(body)`), then logs
  `Responding with error. Body: {}` at WARN when the status is outside 200-299 (`ProxyContext.java:222`). It is the only place on
  these paths that knows the status. The other WARN lines of each event are written before it by the code that found the failure.
- A relay that breaks goes to `BaseDeploymentPostController.java:handleResponseError`, which logs `Can't send response to client`
  (`:218`). For a `Model` it reads the origin's stream to its end and saves the row only if that read succeeds; for an application it
  saves nothing. It does not use a helper.
- Feature calls (`DeploymentFeatureController`), the MCP proxy (`McpProxyController`) and routes (`BaseRouteController`) have their own
  `respond` helpers of the same shape, `finalizeRequest(); context.respond(...)`, and log the same WARN. Their existing `save` calls
  (`DeploymentFeatureController.java:145` and `:330`, `McpProxyController.java:363` and `:397`, `BaseRouteController.java:129`,
  `RouteRequestBodyHandler.java:220`) are on a success or on a relay of the origin's answer, not on an error answer.
- The 14 `LogStore.save` call sites are all on a relay that ended or on a pass-through. `AnalyticsLogContext.java:from` reads the
  status from `context.getResponse().getStatusCode()`, so a row built before `respond` shows the wrong status.

## Findings: the Monitoring events that have no Analytics record

The rule is the task's: an event counts when Core logs it in Monitoring and writes no Analytics record for it. `REPORT.md`
section 7 has the evidence. In short:

| # | Event | Monitoring record | Why no row | Integration point | Context at that point |
|---|---|---|---|---|---|
| E1 | no attempt left after a retriable origin status; client gets `503` or `502` | `No route` (`BaseDeploymentPostController.java:560`), `limit hit` (`UpstreamState.java:64`), `Responding with error` | `nextUpstream` answers through a helper (`:562`) | helper (A) | full `ProxyContext`; status just set |
| E2 | no response headers, counted as a 502 attempt | `Proxy failed to receive response header` (`BaseChatCompletionController.java:328`, `ResponsesController.java:622`, `MessagesBaseController.java:253`), then E1's | as E1 | helper (A) | as E1 |
| E3 | connection refused, DNS failure, failed body read | `Can't connect to origin` (`BaseDeploymentPostController.java:189`) | `handleProxyConnectionError` answers through a helper (`:188`) | helper (A) | as E1; current upstream still set |
| E4 | Core's own rate limit `429` | `Rate limit error` (`DeploymentPostController.java:310`), `Deployment error` on the other surfaces | answered through a helper | helper (A) | deployment resolved |
| E5 | `403` forbidden deployment | `Forbidden deployment` (four controllers) | helper | helper (A) | no deployment yet: Core checks the role before it puts the deployment on the pass (Measured by the tests) |
| E6 | `404` unknown deployment | `Deployment not found` (four controllers) | helper | helper (A) | no deployment |
| E7 | `415` wrong content type | `Responding with error` only | helper, before any deployment | helper (A) | no deployment |
| E8 | `400` bad JSON, `400 model is required` | `Can't process JSON request body` (`DeploymentPostController.java:347`) | helper | helper (A) | no deployment |
| E9 | origin breaks a stream after its headers; an application's relay breaks | `Can't send response to client` (`BaseDeploymentPostController.java:218`) | `handleResponseError` saves only after a good `Model` stream | `handleResponseError` (B) | full `ProxyContext`; response reset; status already set (200) |

| E10 | error answers of feature calls, the MCP proxy and routes | `Responding with error`; `Can't connect to origin` (`DeploymentFeatureController.java:347`, `McpProxyController.java:519`), `Rate limit error` (`McpProxyController.java:485`, `BaseRouteController.java:231`), `Forbidden route` (`BaseRouteController.java:67`) | their `respond` helpers, which save nothing (`DeploymentFeatureController.java:368`, `:373`; `McpProxyController.java:558`, `:563`; `BaseRouteController.java:274`, `:279`, `:284`) and one direct `context.respond` (`BaseRouteController.java:75`) | those helpers (A2) | full `ProxyContext`; a route adds its name and type |

E1 to E8 were seen in the run. E10 is read from the code, and group 4 of tasks.md tests it. E9 was seen for a `Model` whose origin broke (scenarios 7 and 8a); an application's break and a client that
left an application are code and the existing spec. Answers from the same helpers that the run did not exercise are covered by the
same rule: `500 Failed to process deployment`, `503 Upstream is missing required id`, `400` for an unknown upstream id,
`422 Failed to receive body`, enhancement-chain failures, router decision failures and the answers of an interceptor call. Not events of
this task: a relayed origin `500` or `501` (a row, no WARN), a retried attempt that a later attempt answered (the pass has its row),
and `401` and `413` (no Monitoring record).

## Goals / Non-Goals

Goals:
- one Analytics row for each event above, behind one boolean, off by default;
- the integration point chosen where the event happens and the request context is still there;
- an exact one-row guarantee per pass, with no marker on the request;
- no change with the setting off, and none to a successful or relayed row with it on.

Non-Goals:
- a new field, a reason, the origin's status or an attempt count in the row;
- WebSocket, background jobs, and a stream that breaks on a feature call, an MCP request or a route (`Can't send response to client` at
  `DeploymentFeatureController.java:362`, `McpProxyController.java:547`, `RouteRequestBodyHandler.java:265`): other code paths, not covered;
- changing the client's status or body, or any WARN line.

## Decisions

### 1. Two integration points, chosen by where the Monitoring event occurs

**A: the four `respond(...)` helpers**, after `context.respond(...)` returns. They are where E1 to E8 are answered, so one point
covers all of them, including the failures `nextUpstream` and `handleProxyConnectionError` end in. After `context.respond(...)` each
helper hands the answer to the store, in one line. The store holds the rule, so the controllers repeat nothing but that line:

```java
protected Future<?> respond(HttpStatus status, String errorMessage) {
    finalizeRequest();
    Future<?> answered = context.respond(status, errorMessage);
    if (isErrorRowAllowed()) {
        proxy.getLogStore().saveErrorResponse(context);
    }
    return answered;
}
```

`LogStore` has two default methods. `saveErrorRow(context)` saves `AnalyticsLogContext.from(context, null)` when the setting is on
(`isErrorRowsEnabled()`) and the request is a POST, checked before the row is built. `saveErrorResponse(context)`
calls it when the status of the response is outside 200-299, the condition under which `ProxyContext.respond` logs its WARN. The status
is the one the helper just answered with, read from the response, so the row carries what the client got.
`ProxyContext` is not touched, and no class or method outside `LogStore` writes the row. The two exclusions of Decision 2 are
`isErrorRowAllowed()`; the three other controllers have none and call `saveErrorResponse` on its own.

**B: `handleResponseError`**, where E9 occurs. For a `Model`, the row is saved when `responseStream.endStreamFuture()` fails (the
existing code saves when it succeeds, so the two never both happen); for an application, after `context.getProxyRequest().reset()`.
Both are `proxy.getLogStore().saveErrorRow(context)`, through one private method that applies `isErrorRowAllowed()` (Decision 2) and skips a background Responses job: the poller
(`BackgroundJobService`) writes the terminal row of that job, and a second row for the same span would break the one-row guarantee.
Both only with the setting on, and neither needs a status check: the row carries the status Core had set (200).

**A2: the helpers of the other three controllers**, with the same call after `context.respond(...)`:
- `DeploymentFeatureController`: `respond(HttpStatus, String)` and `respond(HttpStatus)`. Its `respond(HttpStatus.OK)` before the save at
  `:145` has a 2xx status, so the hook skips it and the pass keeps its one row.
- `McpProxyController`: `respond(HttpStatus, String)` and `respond(HttpException)`, **not `respond(int, Buffer)`**. That one relays the
  origin's answer (`:396`) and `:397` saves the row right after, so a hook there would write two rows for a relayed `500`. Its WARN is
  the origin's status, and the pass already has its row.
- `BaseRouteController`: `respond(HttpStatus, String)`, `respond(HttpException)` and `respond(HttpStatus)`, and the call after the direct
  `context.respond(HttpStatus.FORBIDDEN, "Forbidden route")` at `:75` (a route whose permissions the caller lacks). That call skips
  `finalizeRequest()` today; this change leaves it so, and adds only the row. `RouteRequestBodyHandler` answers through
  `controller.respond(...)`, so it is covered, and it relays the origin's answer through `responseStream.end(response)`, so a relayed
  route error keeps its one row from `:220`.
None of the three has an interceptor or a decision, so none needs an exclusion.

Rejected alternatives:
- A hook in `nextUpstream` only. It covers E1 and E2 and nothing else: E3 to E8 never pass through it. This was the first draft.
- A hook in `ProxyContext.respond(int, Buffer)`. It is the single funnel, but every controller reaches it, including the `Proxy`-level
  answers and the MCP relay of an origin's answer, which already has its row.
- A hook in `finalizeRequest()`. It runs before the status is set, so the row would carry the wrong `response.status`.
- A row at each of the roughly 40 call sites that answer an error. Easy to miss one, impossible to review.

### 2. The rule is the Monitoring rule, with no filter on the kind of failure

A row is written for every helper answer outside 200-299, which is every answer that logs `Responding with error`. There is no list of
statuses, no condition on the last origin status, and no condition on whether the pass has a decision. Because the rule is the WARN's
own, a new error answer added to a helper later is covered without a change here.

Two passes stay without a row because existing specs say they write none (review decisions D2 and D3):
- the call to an interceptor: `BaseInterceptorController` overrides the protected `isErrorRowAllowed()` to return `false`;
- the pass of a router in decision mode that has not decided yet: the base `isErrorRowAllowed()` returns false for a pass whose
  deployment is a `Router` with `getMode() == Router.Mode.DECISION` and whose `getDecision()` is null. A **decided** request is not
  skipped (Decision 3).

### 3. A decided request gets its row

The router row of a decided request is written by `finalizeRequest()` today, and stays. With the setting on, the error answer of the
chosen candidate also gets one row, beside it. The row is built by `from`, which already sets `parent_deployment` and the parent span
for a decided request. Nothing in the two rows can collide: they are different rows of one trace.

### 4. No change to the schema

The row has the fields every row has. A refusal has no tokens and an empty `response.body`, which is what `GfLogStore.java:append`
writes today for a pass whose context holds no response body (it writes `"body":"` and `appendBody` returns on a null buffer; Code).
The earlier drafts added an `error` object (`reason`, then `upstream_status`); both are gone, and so is the in-memory
`lastUpstreamStatus` that fed the first filter.

`response.status` tells these rows apart as it does for a relayed `500`. For a broken stream it is the `200` Core had set, which is
what the existing row of a client that left a `Model` carries (Measured: x10). Review decision D4 asks whether that is enough.

### 5. The flag is `LogStore.isErrorRowsEnabled()`, a default method that returns `false`

`GfLogStore` returns `settings.errorRows()`. `AnalyticsSettings.from` reads it with `getBoolean("errorRows", false)`, the code
default; the bundled `aidial.settings.json` states it too. The settings are static, so a change needs a restart, as every `analytics`
setting does. (This is already in the code and stays.)

Rejected alternatives: give `Proxy` the `AnalyticsSettings` (changes the constructor and every test that builds one); derive it
from `AIDIAL_LOG_LEVEL` (that level also sets the gflog console logger); a dynamic-config flag (every other `analytics` setting is
static). Test side: `ResourceBaseTest.java:createLogStore` wraps the real store and delegates `isErrorRowsEnabled()`.

### 6. One row per pass, with no marker

There is no `rowMark`. The guarantee comes from how a pass ends:
- **Helpers.** A helper answers by `response.setStatusCode(status).end(body)`. Vert.x rejects both on a response whose head is already
  written, with an `IllegalStateException`, so a second helper call in one pass would already fail today. The row follows the call
  and runs at most once per pass. The router fallbacks (`retryWithNextCandidate(..., () -> respond(...))`) run the helper once, when
  they give up. (Expected from the Vert.x contract; the tests below would fail if it did not hold.)
- **Success saves** run after a relay ended. A pass that relayed has its head written and cannot answer through a helper.
- **B** writes on the failure of `endStreamFuture()`, where the success save writes on its success: two outcomes of one future.

The limit of this argument: a later change that answered twice would double-write. The exact row counts in the tests (every scenario
reads the count with `awaitAnalyticsRows`) are what would catch it.

### 7. The write is not wrapped

The helper hook runs after the response has ended, with the call every other row uses (`LogStore.save`, `AnalyticsLogContext.from`).
No `try/catch` is added around it, as in the success paths, which have none either. The formatting and the file write run on the
writer thread and are caught there (`GfLogStore.java:doSave`). The synchronous part (`AnalyticsLogContext.from`, and in
`GfLogStore.save` the `admit` and `executor.submit`) could throw; if it did, the exception would reach the code that called the helper
and be logged, and the client already has its answer.

Rejected alternative: a `try/catch (RuntimeException)` that logs a WARN and returns, in `saveErrorRow`. `docs/CODE_STYLE.md` forbids
error handling for an exception that the code is not known to throw, and the success paths have none.

## Status semantics

A row's only status is `response.status`: what the client got. For an exhausted upstream Core never relays a retriable status. When no
attempt is left it answers `503 Service is not available` if every upstream of the model is at `429` and `502 No route` otherwise
(`TieredBalancer.java:createUpstreamUnavailableException`, from the stored state of every upstream, not from the last answer), so an
origin `502`, `503` or `504` gives the client `502`, and an origin `429` gives `503` only when no other upstream of the model failed with
something else. The origin's own status is not in the row; it stays in the INFO line `Received header from origin`.

## Test matrix

Each "rows" cell is an exact count read with `awaitAnalyticsRows`. Tests are named in tasks.md.

| Scenario | Setting off | Setting on |
|---|---|---|
| Success `200` | 1 | 1, unchanged |
| Origin `429` (1 attempt); `502`, `503`, `504`; two upstreams; mixed pair | 0 | 1, status as the client got |
| Origin `500`, relayed | 1 | 1, unchanged |
| Connection refused; DNS failure | 0 | 1, status 502 |
| No response headers | 0 | 1, status 502 |
| Core's `429`; `403`; `404`; `415`; `400` | 0 | 1 each |
| Application, `/openai/v1/chat/completions`, Responses, Messages, `429` | 0 | 1 each |
| Decided request, candidate answers `502` | router row | router row and 1 |
| Retry that succeeds | 1 (200) | 1 (200) |
| `401`, `413` | 0 | 0 |
| Interceptor answers `502`; router decision fails | 0 | 0 |
| Model origin closes after headers; breaks mid-stream | 0 | 1, status 200 |
| Application origin breaks; client leaves an application | 0 | 1, status 200 |
| Client leaves a `Model` | 1 | 1, the same row |
| Feature call, MCP request, route: refused, not found, origin unreachable, upstream busy | 0 | 1 each |
| Origin answers `500` to a feature call, an MCP request, a route | 1 | 1, unchanged |
| Feature call that needs no origin (`200`) | 1 | 1, unchanged |

## Per-request cost

- Off: the success and relay-success paths are not touched. An error answer reads one boolean (`isErrorRowsEnabled()`); B reads it only on its
  failure paths. No allocation, no I/O.
- On, success path: the same as off, nothing else.
- On, an error answer or a broken relay: one `AnalyticsLogContext.from` (a builder and a copy of the request header map, as for every
  row) and one queued row. No Redis or blob round trip, no body copy and no body parse: the request body is the buffer the pass
  already holds, counted by `GfLogStore.java:admit`.
- Effect on the writer: one more row per error answer. During an outage of every upstream that is one row per failed request, and
  `analytics.maxPendingBodyHeapPercent` still bounds the bodies waiting.

Measured by: the row counts of the test matrix, and a task that counts Redis commands for one exhausted pass with the setting off and
on, expecting the same count (deferred: needs Docker and Redis, as in the investigation).

## Risks / Trade-offs

- **A consumer that sums requests from the rows counts these too.** → Documented in `README.md` and `docs/tracing.md`; the setting is
  off by default; a consumer already handles a relayed `500` row.
- **A broken stream is a row with status `200`** and no tokens, like the row of a client that left. → Review decision D4.
- **The rule covers helper answers the run did not exercise** (`500`, `503` for a missing id, an unknown upstream id, `422`, router
  failures). → They log the same WARN, so the task covers them; one test for each that a test can reach, and the rest are listed.
- **MCP `respond(int, Buffer)` is not hooked.** A relayed MCP error has its row from `McpProxyController.java:397`; hooking it would
  write two. → `#testMcpRelayedErrorHasOneRow` fails if that changes. The same goes for the route and feature-call relays.
- **A route whose permissions the caller lacks** (`hasRequiredPermissions`, which application routes use) **answers through
  `context.respond` directly** (`BaseRouteController.java:75`), which skips `finalizeRequest()`. → Not changed here, so that nothing
  changes with the setting off; the row is added after it. No test reaches that `403`: the test config has no route with permissions, and an application route needs an application
  the caller may not use. It is covered by the code and listed in `review.md`, not exercised. The role check at `:66` answers through a
  helper, and `#testRouteRefusedByRole` covers that one.
- **A feature call, MCP or route request that is not a POST** writes no row: `GfLogStore.save` drops it, as for every row. The tests
  read the rows before that filter, so they see it.
- **The helper hook applies to every surface that extends `BaseDeploymentPostController`.** The run used a model on
  `/openai/deployments/{id}/chat/completions`. → One test per other surface; a surface whose test fails is reported and fixed or cut
  before archive.
- **No marker.** → Decision 6; the row counts of the tests.
- **A row for a decided request has `deployment` of the last candidate.** → It is the pass that answered.
- **Volume during an outage.** → Same as any row; the setting is off by default.

## Migration Plan

None. The setting is off by default and every existing row is unchanged. To enable it, set `analytics.errorRows: true` (or
`aidial.analytics.errorRows=true`) and restart Core. To roll back, unset it and restart; rows already written stay.

## Decisions for review

- **D1. The broken stream (E9) in this change, or as its own PR?** It is a confirmed Monitoring event with no row, so it is in scope
  by the task's rule, and the tasks keep it as its own group (3) that can ship apart. An earlier review asked for it to be separate.
- **D2. The call to an interceptor.** Existing specs say it writes no row. Default here: keep that. Covering it means modifying that
  requirement, and the run did not exercise an interceptor's failure.
- **D3. The pass of a router in decision mode that fails before it decides.** `router-deployment` says it writes no row. Default here: keep that.
  Covering it modifies that requirement; the run did not exercise a router.
- **D4. A mark on the row of a broken stream.** Its `response.status` is `200`, which looks like a delivered answer. With no new field
  the only difference is that it has no tokens. A marker would add a field to the schema. Default here: no marker.
- **D5. `response.body` of a Core answer.** Empty today; the text of Core's own answer is in the WARN line. Default here: leave it empty.

## Superseded by this revision

Recorded so that the narrow draft is not read as the design. Commit `9b6043c7` implements it and tasks.md group 0 takes it back:
the hook in `nextUpstream` (`writeExhaustedRow()`), the `rowMark` and `markRowWritten()` on `ProxyContext`, `lastUpstreamStatus` and
its six one-line calls in the three controllers, the filters (the last origin status in 429, 502, 503, 504; no decision), and the
`error` object with `errorReason` in `AnalyticsLogContext` and `GfLogStore`. The setting, `LogStore.isErrorRowsEnabled()`, the bundled default and
the `ResourceBaseTest` delegate stay.

## Drift found while implementing

Recorded, as `openspec/config.yaml` asks, instead of diverging silently.

- The router exclusion tests `router.getMode() == Router.Mode.DECISION`, not `instanceof Router` alone. A router in proxy mode is the
  origin of its own pass and never decides, so it gets a row like any pass; only a router that decides and fails before it has a
  verdict writes none. The first version of the check, `instanceof Router && getDecision() == null`, skipped the proxy-mode router too;
  `#testProxyModeRouterFails` found it.
- A `403` row has no `deployment`: Core checks the role before it puts the deployment on the pass, as for a `404`. The spec scenario
  said otherwise and was corrected; moving the deployment earlier would change the controllers' flow and is not part of this change.
- The integration tests need `resources.maxSize` 20000 (for the `413` test) in the base class, and a plain-socket origin
  (`RawOrigin`) for the broken streams, because `MockWebServer` cannot close a connection right after the headers.
- `finalizeRequest()` runs before `context.respond(...)` in each helper and the row after it, so for a decided request the router row
  is written first and the candidate's row second; they do not collide.
- The three other surfaces (feature calls, MCP, routes) were added after the first implementation. Their hook is the same call after
  `context.respond(...)`: one line, `saveErrorResponse(context)` on the store. Three earlier versions put the write on
  `ProxyContext`, in a class `ErrorRows` with a `writeErrorRow`, and as the same four-line block written in each helper; the first two
  were dropped for adding code outside the store, the last as duplication. Now the rule lives in `LogStore` once, and `ProxyContext`
  does not change. The `try/catch` they carried went with them (Decision 7).
- `McpProxyController.respond(int, Buffer)` is not hooked, on purpose (Decision 1, A2): it relays the origin's answer and the row is saved
  right after it. `#testMcpRelayedErrorHasOneRow` guards it.
- The rate feature is called as `POST /v1/{id}/rate`, not under `/v1/deployments/`; the test for a feature that needs no origin uses it.
