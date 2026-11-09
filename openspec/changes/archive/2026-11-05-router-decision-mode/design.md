# Design

## Context

See proposal.md, Why. Line numbers are from `development` on 2026-11-04; change `router-entity` is applied first and
its design.md names what it adds (`Router`, `Config.routers`, the `isSubjectToLimits` exemption, the
`X-DIAL-USER-INFO` builder, the widened `validateDeploymentInterfaces`).

How the four POST paths resolve a deployment and when the body is in hand:

- Deployments-POST (`DeploymentPostController.handleDeployment`, 148-202): resolve on a virtual thread
  (`findDeployment` with the role check, consent), `accessibleByPerRequestKey`, `resolveServingEndpoint` (`503`),
  `setDeployment`, interceptors, `checkLimits` for a `Model` only (177), then `handleRateLimitSuccess` (229-244):
  a fresh `ApiKeyData`, `startSpan`, and only then `request.body()`, `handleRequestBody` (272-292) parses and runs
  `processChatCompletionsRequestBody` (`BaseChatCompletionController:86-105`). The body is not read before the
  deployment is known. The interceptor chain already re-enters this method with another id
  (`handleInterceptor`, 204-211, calls `handleDeployment(initialDeployment)`), so a re-entry with a chosen candidate
  is a pattern the controller has.
- `/openai/v1/chat/completions` (`ChatCompletionsController.handle:74`, `dispatch:95`), `/openai/v1/responses`
  (`ResponsesController.handle:180`, `dispatch:192`) and `/anthropic/v1/messages`
  (`MessagesBaseController.handle:76-93`): `request.body()`, `parseBody` (stores the bytes with
  `setRequestBodyWithTimestamp` and parses the tree), `setupDeployment(model)` on a virtual thread, `verifyLimit`,
  `startSpan`, `handleRequestBody(tree)`. The body is parsed before the deployment is known; the tree is the
  handler closure's, and `prepareUpstreamRoute` / `processChatCompletionsRequestBody` overwrite
  `context.requestBody` with the serialized chain output.
- The enhancement chain rewrites `model` to `overrideName` (`EnhanceDeploymentRequestFn.apply`) and lays the
  deployment's `defaultHeaders` onto the inbound request where the client sent none
  (`ApplyDefaultDeploymentSettingsFn.applyDefaultHeaders`, 71-85), so they reach the origin through the same copy as
  client headers. Both read `context.getDeployment()`.
- A call is given up on in two places of `BaseDeploymentPostController`: `nextUpstream()` (`route.next()` throws
  when the upstreams are exhausted, answered with `respond(e)`, 472-481) and `handleProxyConnectionError` (144-154,
  `502`). A retriable status goes back through `sendRequest()` to `nextUpstream()`
  (`BaseChatCompletionController.handleProxyResponse:161-165`, `MessagesBaseController.handleProxyResponse`,
  `ResponsesController` alike). Response headers reach the client only after a non-retriable answer
  (`handleChunkedResponse` / `copyResponse`), so "no byte has reached the client" is `!response.headWritten()`.
- The per-request key records `sourceDeployment = context.getDeployment().getName()` and `executionPath` = the
  parent key's path plus that name (`ApiKeyData.initFromContext`, 79-117). The analytics row takes `deployment`
  from the context, `execution_path` from the minted key and `parent_deployment` from
  `AnalyticsLogContext.getParentDeployment` (nearest preceding path entry that is not an interceptor);
  `GfLogStore` writes each field by hand (247-275) and JSON lists with `ProxyUtil.MAPPER`. The key of a pass is
  deleted in `finalizeRequest` (`BaseDeploymentPostController:132`).
- `DeploymentService.getInterceptors` (288-296) prepends the config's `globalInterceptors` to every deployment's
  own list, routers included; the deployments-POST, v1 chat and Responses flows hand the request to the chain
  before anything else (`DeploymentPostController:186-188`, `ChatCompletionsController.dispatch:115-118`,
  `ResponsesController:208`), and the callback pass (`apiKeyData.isInterceptor()`) resolves the initial deployment
  again. Messages has no interceptors.
- Consent for a root call reads the consent of the deployment itself; for a child, of the first `executionPath`
  entry (`ConsentService.getRootDeploymentId`, 111-120).
- Origin requests are created with `RequestOptions` carrying the client's connect and idle timeouts
  (`BaseDeploymentPostController.createProxyRequest(String)`, 380-391); the client idle timeout is 300 s
  (`aidial.settings.json` `client.idleTimeout`).
- The decide-side reference: today's Go proxy router walks all candidates on any connect failure or any non-2xx,
  `400` included (`dial-llm-router-optimized/internal/relay/relay.go`, `Transport.Open`, 113-163), and answers
  `400` for conflicting steering headers and `403` for a caller with no plan (`plan_based.go`, `Select`).

Reference: documentation MR 211, `design/router_resource.md`, "LLM routing protocol".

## Goals / Non-Goals

**Goals:**

- One Core pass per decided request, with everything the direct call does and nothing twice.
- The decision step is one shared method; each controller keeps its own flow and adds a re-entry with a candidate.
- A router in decision mode cannot be told apart from a direct call by the chosen deployment, except through the
  key's `executionPath` and the row's `decision` object.

**Non-Goals:**

- Caching or remembering verdicts, sending the router any outcome, a per-request key for the decide call.
- A candidate list shaped by Core (no reordering, no health memory): the walk is the verdict's order, once.

## Decisions

### D1. Config: a mode value, two entry fields, validation in the widened interfaces check

`InterfaceMode.DECISION("decision", true)`: the flag stays `true` because a router is already exempt through
`instanceof Router` (`router-entity` D5) and a `false` here would exempt any other deployment kind that managed to
declare the mode. The retarget is what moves the limit check to the chosen deployment, not the mode's flag.
`DeploymentInterface` gains `context` (a small class with one field, `Boolean body`) and `Long decisionTimeoutMs`,
both `@JsonInclude(NON_NULL)`. The default timeout is a constant in the decide client, `5000` ms; there is no static
setting for it, because the entry field is the knob and a second, global one would be a value nobody changes.

Validation lives in the per-entry loop every deployment kind and every write surface already runs,
`ConfigPostProcessor.validateOverridePaths(Deployment, List)` (657-668, called by `processApplications`,
`processInterceptors`, `processToolSets`, `processRouters`, the single-entity validators and, through
`validateModelInvariants`, by models), not in `validateDeploymentInterfaces`, which only models and routers run: a
decision entry on an application or interceptor must be rejected on the config file, on `PUT`, on
`/v1/admin/validate` and on `/v1/admin/apply` alike, and that loop is the one place all of them share. The per-entry
method gains the checks and a name that says so (`validateInterfaceEntry`), the public wrapper keeps its callers. A
`DECISION` entry warns `Mode 'decision' is allowed on a router only` unless
`deployment instanceof Router`, `Mode 'decision' requires base_url` when the entry has none (the deployment-level
`baseUrl` does not count: the decide URL is a different endpoint from the proxy one and must be written out),
`Mode 'decision' excludes translator` when one is declared, and `decisionTimeoutMs must be positive`.
`DeploymentEndpointUtil` is unchanged: `resolveMode` already returns the new value, and `resolveServingEndpoint`
resolves a decision entry to its `base_url` through `passthroughBaseUrl`, which is what lets the `503` pre-check in
every controller pass and keeps `isInterfaceDeclared` true for `/v1/routers`. No code forwards a client request to
that url, because the decision step runs before any forward (D3) and the spec says so
(deployment-interfaces-config, "Interface mode").

Rejected: a `decision` object on the entry holding url, body and timeout. The existing shape puts the url in
`base_url` and MR 211 writes it that way. Rejected: `subjectToLimits = false` on the mode, as `TRANSLATOR` has; see
above.

### D2. The decide client: one class, headers only, raw body optional

`RouterDecisionClient` (server module, next to the other origin-facing helpers) with one method:
`Future<Verdict> decide(ProxyContext context, Router router, DeploymentInterface entry, InterfaceType type,
@Nullable Buffer body)`. It runs on the event loop, no blocking:

1. The router's `defaultHeaders` for the interface (`router.resolveDefaultHeaders(type)`) go onto the decide request
   only, under every name the client did not send. They do not touch the inbound request, so the chosen
   deployment receives the client's headers and its own defaults, as a direct call would.
2. `proxy.getClient().request(new RequestOptions().setAbsoluteURI(entry.getBaseUrl()).setMethod(POST)
   .setConnectTimeout(min(client connect timeout, timeout)).setIdleTimeout(timeout).setTraceOperation("Decide ..."))`,
   with one deadline around the whole call: whichever timer fires, the answer is `504`.
3. Headers: `ProxyUtil.copyHeaders(request.headers(), decide.headers(), excludes)` with the same three exclusions
   `sendProxyRequest` makes (`Authorization` unless `forwardAuthToken`, the application headers, `X-DIAL-USER-INFO`),
   written out again rather than extracted: three lines, and the integration tests pin both lists. Then `X-DIAL-USER-INFO` from `router-entity`'s builder,
   `X-DIAL-ROUTER`, `X-DIAL-API`. No `API-KEY`: nothing is minted for this call.
4. `send(body)` with `Content-Type: application/json` and `Content-Length` when `body` is given, else `send()`.
5. `200`: `response.body()`, `ProxyUtil.MAPPER.readTree`, `candidates` (non-empty array of non-blank strings) or
   `deployment` (one string); anything else fails with `502` `Router answered an invalid decision`. `4xx`: fail
   with `DecisionRejectedException(status, body, contentType)`, relayed by the controller. `5xx`: `502`. Any
   timeout (connect or idle, both bounded by `decisionTimeoutMs`): `504`. Any other failure (connection refused,
   reset, closed): `502`. `decide_ms` is measured around the call.

Why headers and a raw body rather than MR 211's envelope wrapping the original request: the router reads steering
headers in proxy mode already, so the decide request is the proxy request without a body, and when a router wants
the body it gets the client's bytes with no serialization in Core. Why relay a router `4xx` rather than map it to
`502` as MR 211's table implies: in proxy mode the client receives the router's `400` (conflicting steering
headers) and `403` (no plan) today, and the decision mode should not turn a caller's mistake into a gateway error.

### D3. The decision step, and a re-entry per controller

One shared method on `BaseDeploymentPostController`:

```
protected Future<Void> decideIfRouter(InterfaceType type, Supplier<Future<Buffer>> bodyIfWanted)
```

It is a no-op unless `context.getDeployment()` is a `Router` and `resolveMode(router, type) == DECISION`. It runs
after the router's interceptors: the flows hand a deployment with interceptors to the chain first, and the
callback pass resolves the router again and reaches the step with `apiKeyData.isInterceptor()` set. Two guards
before the call: a candidate that is a `Router` fails with `502` `A router cannot be a routing candidate`, and a
router whose name is already in `apiKeyData.getExecutionPath()` fails with `502` `Router <name> re-entered`, unless
this is the router's own interceptor callback (`isInterceptor()` and `initialDeployment` equal to the router).
That second guard is what bounds an application candidate calling the router back, which no call-depth rule in
Core bounds today. Then it reads the body first only when `context.body` is set, calls the client, stores a
`RouterDecision` on the context (router name, interface, candidates, current index, `decideMs`, the attempts so
far, and a `resolving` flag) and completes. The controller then re-enters its own flow with `decision.current()`:

- `DeploymentPostController`: `handleDeployment(candidate)`. Its `.map` step runs the full resolve for the
  candidate (roles, consent, per-request-key access, `503`, interceptors) and `checkLimits` for a model, exactly
  as for a direct call. `handleRateLimitSuccess` reads `context.getOriginalRequestBody()` when set (the body was
  read for `context.body`, or this is a fallback) instead of `request.body()`, and stores the bytes there
  otherwise. No body `model` rewrite: the deployments-POST origin path carries the name.
- `ChatCompletionsController.dispatch`, `ResponsesController.dispatch`, `MessagesBaseController.handle`: after
  `setupDeployment(model)` completes and the interceptor branch did not take the request,
  `decideIfRouter(type, () -> succeeded(context.getOriginalRequestBody()))`, then `setupDeployment(candidate)` and
  `request.setModel(candidate)` (so `EnhanceDeploymentRequestFn` then applies the candidate's `overrideName`), then
  the same `hasNextInterceptor()` branch for the candidate, then the existing `verifyLimit`, `startSpan`,
  `handleRequestBody(request)`. `MessagesBaseController` also sets its `deploymentId` field to the candidate, a
  deliberate difference from today's "the id the client wrote" (`MessagesBaseController:203-211`): a translator
  behind the candidate must call the candidate back, not the router. `parseBody` in these three stores the client bytes in `context.originalRequestBody` as well as
  `requestBody`.
- `MessagesCountTokensController` inherits the step through `MessagesBaseController`: a `count_tokens` for a
  router in decision mode asks the router and counts on the chosen model. Consistent, and free.

Pre-flight failures during the candidate's resolve (a `PermissionDeniedException`, a `ResourceNotFoundException`,
an `HttpException` `503`, a `RateLimitResult` other than `OK`) reach each controller's `handleRequestError` /
`handleRateLimitHit`. Each of those gains one line at the top: `if (nextCandidateOrFalse(error)) return;`, a base
method that, while `decision.resolving` is set and a candidate is left, records the attempt (name, status, reason)
and re-enters with the next one, else maps a `ResourceNotFoundException` to `502` (an unknown candidate is the
router's fault, not the caller's) and lets the normal answer proceed. `resolving` is set when the step completes
and cleared by the controller once the candidate's resolve and limit check have passed (just before
`handleRateLimitSuccess` / `proceedToDeployment`), because the same error handlers also serve a body-read or
parse failure after that point, which must answer the client and never walk.

Why re-enter rather than a candidate loop of its own: the resolve is the access check, the consent check, the
`503` rule and the limit check in the order the spec already pins, and re-entering is how interceptors already
come back to the deployment.

### D4. Fallback after a failed call: hook the two give-up points

`nextUpstream()` exhaustion and `handleProxyConnectionError` first ask
`retryWithNextCandidate()` (base): when `context.getDecision()` has a next candidate and
`!context.getResponse().headWritten()`, it records the attempt with the status, calls
`proxy.getApiKeyStore().invalidatePerRequestApiKey(context.getProxyApiKeyData())` for the failed attempt's key (a
pass that gives up without answering would otherwise leave it to its TTL), and calls the controller's abstract
`reenterWithCandidate(String id)`:

- deployments-POST: `handleDeployment(id)` (the saved body is used, D3).
- the other three: re-parse `context.getOriginalRequestBody()` into a fresh request object (the chain mutated the
  previous one: defaults of the failed candidate, its override name), then the D3 sequence from `setupDeployment`.

`startSpan` runs again on re-entry and overwrites the span's `deploymentName` under the same span id: one span per
pass, one extra Redis write per fallback. The decision is closed (no further fallback) as soon as the chosen
deployment has interceptors (`context.hasNextInterceptor()` after its resolve, the branch the re-entry goes
through in every flow), because the model is then called from another pass. The saved body is released at the first response byte (`handleProxyResponse` once the answer is
non-retriable), so a decided request holds the client's bytes twice only until then.

Retry set, deliberate difference from the Go router: Core skips a candidate for a connect failure and for the
statuses `isRetriableError` names (after that candidate's own upstreams are exhausted), and relays everything
else. The Go router also moves on after a `400` or `404`. A model's `400` is the request's fault and the same
request would fail on the next model too; relaying it keeps the error honest and bounds the work at one model
call.

### D5. Accounting: the router rides in the key's path

`ApiKeyData.initFromContext` adds the router's name to `currentPath` before the receiver when
`context.getDecision()` is set and the pass is not an interceptor callback (`apiKeyData.isInterceptor()` false):
in the callback pass the parent path already holds the router (`[r1, i1]`), so the chosen deployment's path is
`[r1, i1, gpt-4]`. That gives, with no other change: `execution_path: [router, chosen]` in the row,
`parent_deployment: router` through `getParentDeployment`, `sourceDeployment: chosen`, and children of the chosen
deployment (an application's model calls) seeing the router as the root of their consent check, which is the
deployment the user called. The router gets no span, so no `<router>/aggregated-costs` record; that is MR 211's
"simulated hop" question, left for the analytics consumers.

`ProxyContext.decision` (`RouterDecision`: router, api, candidates, index, decideMs, attempts of
`{deployment, status, reason}`) is snapshotted into `AnalyticsLogContext.decision` and written by `GfLogStore` as
`"decision": {...}` next to `usage_per_model`, in the row shape the spec fixes: `router`, `api`, `candidates`,
`deployment` (`candidates[index]`), `decide_ms`, `attempts[{deployment, status, reason}]`, snake_case, written
through a small `@JsonNaming(SnakeCase)` record rather than the context class. A background Responses job's row
is built from the job record without the context (`AnalyticsLogContext.from(BackgroundJobRecord, ...)`, 131) and
carries no `decision` object; the spec scopes it out, and carrying the decision into the job record is a
follow-up. A decision failure answers from Core
itself and writes no row, as every other refusal (app-to-model-accounting, "Passes without an analytics row").

Rejected: a second row for the router. It is the cost this change removes, and the one-row shape is what the
A/B compares against the proxy router's two rows.

### D6. What stays untouched

`UpstreamRouteProvider` (a router in decision mode never gets a route), `ApplicationSchemaService`, the interceptor
controllers, `TokenStatsTracker` (one span, D4), `RateLimiter` (called with the chosen deployment as for a direct
call), `DeploymentEndpointUtil` (D1), `/v1/routers` (change `router-entity`), and `ResponseItemController`: the
item operations resolve the deployment from the response mapping, which `ResponsesController:506` saves as the
context's deployment after the retarget, so a decided response's items address the chosen model and never the
decide URL. `resolveServingEndpoint` callers (`DeploymentPostController:165`, `ChatCompletionsController:151`,
`ResponsesController:246, 342`, `MessagesBaseController:141, 175`, `BaseChatCompletionController:114`,
`ResponseItemController:161, 199`, `ConfigPostProcessor:772`) are all behind the step or behind the retarget.

## Per-request cost

Against the same request sent directly to the chosen model: one small HTTP request on the event loop (headers plus
an empty body, a few hundred bytes), one JSON parse of a verdict of a few dozen bytes, and on deployments-POST the
round trip overlaps the client's body upload; on the three body-addressed APIs it runs after the parse and before
the limits. No Redis command, no blob operation, no extra body copy or parse, one more `Buffer` reference to the
client's bytes until the first response byte. Against the proxy router: one whole Core pass fewer (its body read,
parse, serialize, key write and delete, span write, analytics row with bodies), and no `GET /v1/user/info` from
the router (one Core request and one Redis read per user request). A fallback costs one re-parse of the saved
body, one key invalidate and one mint, one span overwrite, one more origin call. With `context.body: true` the
decide request carries the body bytes once more on the wire, with no serialization.

Measured by the perf improvement initiative's same-day comparison on the router path (direct, proxy router,
decision mode): Core CPU per user request, user-info calls per second (must be zero in decision mode), Redis
commands per request, p50 overhead over the direct run. The measurement task is deferred to that environment.

## Risks / Trade-offs

- [A re-entry path that answers twice or never: a pre-flight failure both re-entering and responding] → the
  `nextCandidateOrFalse` return value is the only switch; the integration test "Forbidden first candidate skipped"
  and "Every candidate forbidden" cover both branches on all three body-addressed APIs and on deployments-POST.
- [The same `handleRequestError` serves a body or parse failure after the candidate is chosen] → the walk is armed
  by `decision.resolving` only between the verdict and the end of the candidate's resolve (D3); a test sends an
  unparsable body to a decided deployments-POST and asserts one `400` and no second decide call.
- [`request.body()` cannot be read twice] → the client bytes are kept in `context.originalRequestBody` from the
  first read; the fallback never calls `body()` again.
- [The chain mutated the tree before a fallback] → the fallback re-parses the saved bytes, never reuses the tree.
- [A decision entry's url reachable by some proxy path the step does not guard] → the step is in the four request
  flows (`MessagesCountTokensController` included), the item operations address the chosen model (D6), the spec
  scenario "Decision interface is not proxied" pins deployments-POST; the apply step re-checks the
  `resolveServingEndpoint` caller list in D6.
- [A background Responses request through a router: the retarget happens before `setBackgroundJob`, so it should
  work by construction, and it is not in the test plan] → stated as untested; the operator keeps background
  Responses off a router until a test exists.
- [The decide timeout fires while the client is still uploading on deployments-POST] → the answer is `504` and the
  upload is dropped with the response, as any early refusal is today.
- [`X-DIAL-USER-INFO` size with large role lists] → as in `router-entity`.
- [Older Core cannot load a config with `mode: decision` (unknown enum value)] → Migration Plan.

## Migration Plan

Deploy Core, then add `mode: decision` entries. Rolling back one release, to the `router-entity` release, while
such entries exist makes the whole config file fail to load (the enum value is unknown to Jackson, and the file is
bound as one tree, so `config.onInvalidEntity: skip` does not help), so that rollback reverts the decision entries
to proxy mode first, which the same router serves. Rolling back past `router-entity` loads the file with the
`routers` map ignored.

## Follow-ups (not in this change)

Conversation binding in Core (configured headers plus TTL, a Redis bucket, skipping the decide call on a hit),
outcome events to the router, a verdict `ttl`, a scoped per-request key for routers that call deployments while
deciding, body field projection. The `router` field on `RouterDecision` and the `decision` row object are the
points they attach to.

## Implementation notes

Compared with the code on `feat/router-deployment-type` after `router-entity` (43a5cbb5). The flows, line references
and helpers D1-D6 cite are where they say, give or take a few lines. Drift found while implementing, and what the
code does instead:

- **Mode flag (D1 vs task 1.1).** Task 1.1 said `DECISION("decision", false)`; D1 and task 1.3 say `true`. The code
  follows D1: `true`, with a unit test (`ConfigPostProcessorTest.testDecisionModeKeepsTheLimitFlag`).
- **Translator on a decision entry (D1).** `validateDeploymentInterfaces` already warned `A translator requires mode
  'translator'` for any non-translator entry with a translator, and the first warning is the one the config error
  shows. That warning now skips decision entries, so a router shows `Mode 'decision' excludes translator`, as an
  application does.
- **Verdict type (D2).** No `Verdict` or `DecisionRejectedException` class: `RouterDecisionClient.decide` returns the
  candidate list, and every failure is an `HttpException` carrying the status Core answers. A router `4xx` is an
  `HttpException` with the router's body as message and its `Content-Type` in the headers, which
  `respond(HttpException)` already writes. `DeploymentPostController.handleRequestError` now answers an
  `HttpException` with `respond(e)` (headers included) instead of `respond(status, message)`, as the three other
  controllers already did; no existing `HttpException` on that path carries headers. The client is a
  `@UtilityClass` reading the proxy from `ProxyContext.getProxy()`, so `Proxy` gains no field. The one deadline is a
  Vert.x timer of `decisionTimeoutMs`; connect and idle timeouts are set to the same bound.
- **The request is paused until the controller future completes (D3, deployments-POST).** `Proxy.handleRequest`
  pauses the client request and resumes it when the controller's future completes. A decide call waiting for the
  body inside that future never ends, and a decide call outside it lets the resumed body be dropped before
  `handleRateLimitSuccess` asks for it ("Request has already been read"). So for a router, `DeploymentPostController`
  registers the body read (one cached `Future<Buffer>` per controller) before the decision, runs the decision and the
  re-entry outside the returned future, and every later read (the decide body, the candidate, a fallback) uses that
  cached future. The three body-addressed APIs have the body before the step, so they need none of this.
- **Re-entry guard (D3).** The exception for "the router's own interceptor callback" is not needed and not coded: no
  pass of the router's own interceptors has the router in its key path (an interceptor's key path starts at the
  interceptor), so `path.contains(router)` is true only below the router's own decision.
- **Key path with global interceptors (D5).** An interceptor's key carries `[i1]`, not `[r1, i1]`, so "add the router
  only outside an interceptor callback" would lose it. The router is added before the receiver whenever a decision is
  set: `[r1, gpt-4]` for a root call, `[i1, r1, gpt-4]` after a global interceptor. The spec scenario "Global
  interceptor before the decision" is changed to that path. The row's `parent_deployment` is the router whenever a
  decision is set, read from the decision rather than from the path walk (on Messages the context has no interceptor
  list, which made the walk return the source deployment, null for a root call).
- **Global interceptors run once (D3).** In the callback pass the chain hands to the router, the chosen deployment's
  interceptor list would again start with the global ones, which `hasNextInterceptor` then sends to `i1` a second
  time. `BaseDeploymentPostController.resolveInterceptors` drops the interceptors that chain already ran when a
  decision is set in an interceptor callback pass; the chosen deployment's own interceptors still run.
- **Attempt status (D4, spec "Row of a decided request").** The status recorded for a candidate skipped after its call
  is the status Core would have answered for it: `502` for exhausted upstreams (`TieredBalancer.createUpstreamUnavailableException`
  answers `502` `No route`, or `503` when every upstream is busy with `429`), `502` for a connect failure. The upstream's own `503` is not kept on the route, so
  the scenario's `503` became `502` and the requirement says what `status` is.
- **Pre-flight walk re-parses (D3/D4 cost).** One `reenterWithCandidate` serves both the pre-flight walk and the
  fallback; on the three body-addressed APIs it re-parses the saved bytes in both cases. A pre-flight skip therefore
  costs one body parse, like a fallback.
- **Chat body model (D3).** `/openai/v1/chat/completions` re-serializes the body only when the chain changes the
  request, so `ChatCompletionsController` sets `model` to the candidate and writes the body itself only when it hands
  the request to an interceptor; otherwise it passes `changed = true` to `processChatCompletionsRequestBody`, which
  then serializes once whether or not the chain changed the request (one serialization per decided request).
  Responses writes it only when it hands the request to an interceptor; its chain always serializes. Messages always
  serializes.
- **Saved body release (D4).** Not done: the saved bytes stay referenced by the context until the request ends, not
  only until the first response byte. On deployments-POST they are the same buffer as the request body the chain
  starts from; elsewhere it is one more reference to the client's bytes for the request's lifetime. Add the release
  in each `handleProxyResponse` if the memory shows in the measurement.
- **Attempt reasons.** `forbidden`, `not_found`, `limit` (`429`), `unavailable` (any other `HttpException` in the
  resolve or limit check), `connect`, `upstream`.
- **Fallback state reset.** Besides the key invalidation, a fallback clears `proxyApiKeyData` and
  `cacheBreakpointContext` and puts the client's bytes back as the request body, so the next candidate's chain and
  interceptors start from the client's request.
- **No fallback after an accepted answer (D4).** `handleProxyConnectionError` is also the failure handler of the
  non-streaming body read (and of the Responses mapping save) after the origin's headers were accepted, so
  `retryWithNextCandidate` returns false once `context.getProxyResponse()` is set: such a failure answers `502` as
  before, and the candidate that answered is not retried (`RouterDecisionApiTest.bodyFailureAfterTheAnswerIsNotRetried`).
- **Decision row behind the chosen deployment's interceptors (D5).** The pass that decides hands the request to the
  interceptor chain and writes no row; the pass the last interceptor calls back serves the deployment without a
  decision on its context. So the interceptor key carries the decision's row (`ApiKeyData.decision`, set in
  `BaseInterceptorController` from the decision or from the previous interceptor's key, absent from the JSON
  otherwise), and `AnalyticsLogContext` writes it on the row of the pass whose deployment is the one the row names
  (`RouterDecisionApiTest.chosenDeploymentBehindAnInterceptorRecordsTheDecision`).
- **OpenAPI.** `docs/open_api_core.yaml` changes: `InterfaceMode` gains `decision` and `DeploymentInterface` gains
  `context` and `decisionTimeoutMs` (regenerated with `./gradlew :openapi-generator:replaceSpec`).
- **D6 caller list (task 8.1).** `resolveServingEndpoint` has the same callers as listed (lines shifted); no new one.
