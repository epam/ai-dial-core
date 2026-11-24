# Design

## Context

With `spendingLimits.mode` set to `enforce`, a JWT call of a model on the Responses routes runs the budget check before its token and request limits. When no budget of the caller's cost object covers the model, the check rejects with a 429 and the DIAL error body; with `no-budget-should-retry-false` the 429 also carries `x-should-retry: false`. A pass answers this rejection where its controller handles request errors, after a router in decision mode has asked again while it has decisions left. A call of an alias runs two passes: the alias pass makes no limit check and forwards to Core for the target with a per-request key it makes, and the target pass checks the limits. That key lends the target to the call, and when the target is a router, the deployment the router chooses runs in that same target pass. The alias pass falls back on its target's 429 only while a router's decision has a candidate left; otherwise it relays the target's answer, streaming or not, with every header but the hop-by-hop and trace ones. A model's or application's own 429 never reaches its caller: Core retries the next upstream and, when none is left, answers its own 503 or 502. An interceptor's answer is relayed with every header.

## Decisions

### D1. The client gets 400 with the message as plain text, not 429 or another status

The table shows what Codex CLI printed for each answer. The answers came from a stub upstream, with Codex's default retry settings. The plain statuses print the same in 0.168.0 and 0.172.0.

| Answer | Codex prints | Requests |
|---|---|---|
| 429, DIAL JSON (today) | `exceeded retry limit, last status: 429 Too Many Requests` | 1 |
| **400, plain text** | **the message** | **1** |
| 400, DIAL JSON | the raw JSON body | 1 |
| 402, 403, 409 or 422, DIAL JSON or text | `Reconnecting... 1/5` to `5/5`, then `unexpected status 403 Forbidden: <message>, url: …` | 6 |
| 429, `usage_limit_reached` without `resets_at` | `You've hit your usage limit. Try again later.` | 1 |
| 429, `insufficient_quota` (0.168.0) | `Quota exceeded. Check your plan and billing details.` | 1 |
| 429, code `project_spend_limit_exceeded` (0.172.0) | `Your API project has reached its spend limit.` | 1 |
| 403, code `inference_hook_denied` (0.172.0) | the message | 1 |

For `project_spend_limit_exceeded`, the 0.168.0 source maps the code to the `Quota exceeded…` sentence; that run was not made.

Codex's source at `rust-v0.168.0` explains the table. `codex-api/src/api_bridge.rs` maps the status:
- a 400 becomes `InvalidRequest` with the body as its text;
- a 429 becomes `RetryLimit`, unless its type or code is one of Codex's own;
- any other status becomes `UnexpectedStatus`.

`protocol/src/error.rs` (`retry_delay`) never retries `InvalidRequest`. It retries `UnexpectedStatus` with backoff. It retries `RetryLimit` only on a server-sent `retry-after`.

**Rejected: 403, 402, 409 or 422** - Codex shows the message only after five reconnects.

**Rejected: 400 with the DIAL JSON body** - Codex prints the JSON as it is, so the user has to read it.

**Rejected: 429 with one of Codex's own types or codes** - Codex prints its own sentence and hides the message. Each sentence is also wrong here: "try again later" and "reached its spend limit" describe a limit that resets, and this caller has none.

**Rejected: 403 with `inference_hook_denied`** - it would tell Codex that a workspace hook blocked the request, which is not what happened, and Codex before 0.169.0 retries it as a plain 403.

### D2. Inside Core the refusal stays a 429; only the pass that answers the client turns it into the 400

The rejection that a pass's limit check raises keeps its status, body and headers. Only the pass that answers the client's own request on a Responses route (`ProxyContext.isOriginalRequest`), as its final answer, answers the 400. An alias pass falls back on its target's answer only when the status is one it retries, and 429 is one of them, so a 400 from the target pass would end the fallback to a router's next candidate. A router asking again after a candidate's refusal does it on any error. The status still names the reason: Core reports the refusal as `limit` for a 429 and as `unavailable` for a 400.

**Rejected: the budget check rejects with 400** - an alias candidate's fallback would stop on it, and a router would report the refusal as `unavailable`.

### D3. The alias pass flags the key it makes, in memory only, and the pass with a flagged key marks its 429 with `X-DIAL-REFUSAL: no_budget`

The alias pass makes no limit check, so it learns the reason only from its target's answer. From status and body alone, it cannot tell the no-budget 429 from a token or request limit's 429: both have the same DIAL body. So the target pass has to say so.

On the Responses routes, the alias pass sets a flag on the per-request key it makes for its target. Alias passes on the other routes do not, because nothing there reads it. A key Core later makes from the target pass does not copy the flag: one for an interceptor in front of the target, or for the target's callback. A pass whose own key carries the flag adds `X-DIAL-REFUSAL: no_budget` to its no-budget 429. That pass is the alias's target pass. When the target is a router in decision mode, it is also the deployment the router chose, which runs in the same pass with the same key.

When an alias pass answers its own caller with a 429 that carries the header, it answers as Core answers that caller on a direct call:
- To the client's own request, it answers the 400 of D1. The body is the 429's `error.message`, and the headers are the ones a direct 400 has: `Content-Type: text/plain` and `x-should-retry: false`, but none of the target's other headers.
- To a call whose key carries the flag, it passes the 429 and the header on. This is the alias a router chose inside another alias's target pass.
- To any other call, it passes the 429 on without the header.

The flag lives on the key object in memory and is never stored: the stored JSON of every key stays byte for byte as today. The target pass served in process takes the alias pass's key object itself, so it sees the flag. A target pass reached over HTTP reads the key from Redis without it, and answers as to any caller with a per-request key. Only a background request reaches its target over HTTP on the Responses routes, so a background request through an alias keeps the 429.

**Rejected: store the flag with the key** - a Core without the field rejects a stored key that has one, because `ProxyUtil.MAPPER` fails on unknown properties. During a rolling deploy, any hop of a flagged key to an old instance would fail: the background target pass, and the background job's poller, which presents the key of the pass that saved the job. After a rollback, every flagged key still in Redis, for up to the background job TTL, would fail too.

**Rejected: first ship `@JsonIgnoreProperties(ignoreUnknown = true)` on `ApiKeyData`, then store the flag** - it needs a release before this one, and a rollback past that release still fails. It would buy only the background requests, which Codex CLI does not send.

**Rejected: the context's existing `isAliasTargetPass`** - it is the tracing mark of an in-process target pass, and it is off when an origin plugin's call reached the alias, so it would decide the answer by how the call is traced.

**Rejected: the lending of the key (`DeploymentService.isLent`) as the test** - Core passes the lending on to an interceptor in front of the target and to the target's callback, so those calls would get the header. And it stops the lending at a router's decision, so a model or alias the router chose would not.

**Rejected: recognise `x-should-retry: false` without `retry-after`** - it guesses from headers that mean something else. `no-budget-should-retry-false` D2 rejected the same guess.

**Rejected: recognise the message text** - a rewording breaks it, and an upstream's text can match.

**Rejected: a request header from the alias pass instead of the key flag** - a client could send that header itself, and Core copies the client's headers onward.

**Rejected: the target pass answers its alias pass 400** - the alias pass would not fall back (D2).

### D4. Core removes `X-DIAL-REFUSAL` from what comes from outside Core

The header changes what an alias pass answers the client, so only Core may set it. On the Responses routes, Core removes it at two places:
- the model's or application's pass, where its controller copies the upstream's headers, next to `stripUpstreamCodexHeaders`;
- the interceptor relay.

A forged header could only matter on a 429, and a 429 can reach an alias pass from an interceptor only; a model's or application's 429 becomes Core's own 503 or 502. The strip in the model's pass therefore only keeps the header from leaking to a client on other statuses.

**Rejected: trust the header from any origin** - an interceptor could then make Core turn its own 429 into Core's 400.

### D5. The message changes on every route

Codex prints the 400 body word for word, and Claude Code prints `error.message`. "Budget" and "cost object" are admin words, and the message gives no next step. The new message is `No DIAL spend limit is assigned for <model> in project <cost object>. Ask your DIAL admin to assign one.` It keeps the model and the cost object, so the admin still finds the call. The budget check builds it once, so every route and the logs show the same text. `display_message` stays `You have no budget for this model`.

**Rejected: a new text only in the Responses 400** - the alias pass would need the cost object, and one refusal would have two texts in the logs.

**Rejected: `display_message` as the 400 body** - it names neither the model nor the project, and gives no next step.

## Differences from today

"Today" is `development` with `no-budget-should-retry-false` applied.

| What | Today | After | Requirement |
|---|---|---|---|
| Responses no-budget, the client's own request, streaming or not | 429, DIAL JSON, `x-should-retry: false` | 400, `text/plain` message, `x-should-retry: false` | No budget for the model |
| Responses no-budget through an alias, or a router with an alias as its target or candidate | 429, DIAL JSON | 400, `text/plain` message; a background request keeps the 429 | No budget for the model; The alias relays the target's answer; No limit check on an alias pass |
| Responses no-budget to an application's, interceptor's or translator's call | 429 | 429, new message, no `X-DIAL-REFUSAL` | No budget for the model |
| Responses no-budget, answered by the pass with a flagged key to its alias pass | 429 | 429 with `X-DIAL-REFUSAL: no_budget` | - (inside Core) |
| `X-DIAL-REFUSAL` from a model's upstream, an application or an interceptor | relayed | removed | The no-budget mark stays inside Core |
| The no-budget message on Messages and Responses | `No budget covers <model> for cost object <cost object>` | `No DIAL spend limit is assigned for <model> in project <cost object>. Ask your DIAL admin to assign one.` | No budget for the model |
| The no-budget message on chat completions, embeddings and the other routes | the same as above | the same as above | none: no requirement states it |
| The per-request key a Responses alias pass makes | no flag | carries the flag of D3 in memory; the stored JSON of every key is unchanged | - |
| Response status in the alias pass's analytics row for a relayed no-budget refusal | 429 | 400 | - (records) |
| Response status in the router row when the client called the router directly (a router inside an alias's target pass keeps 429) | 429 | 400 | - (records) |
| Response status of the client's request in the HTTP server span | 429 | 400 | - (records) |
| A direct refusal's analytics row | none | none (unchanged: a refusal before the upstream writes no row) | - |

## Effect per request

| Pass | Today | After | How it is measured |
|---|---|---|---|
| A pass that relays a model's, application's or interceptor's answer on Responses | copies the headers | also removes one header | the existing tests |
| A Responses alias pass | makes the per-request key | sets one boolean on it, in memory | the existing tests |
| An alias pass that relays a marked 429 to the client | pipes or copies the small error body | reads it into memory and parses it once for `error.message` | `BudgetSpendCapsApiTest` alias scenarios |

No Redis or blob round trip is added.

## Risks / Trade-offs

- Any client of the Responses routes gets this refusal as a 400 with a plain-text body, not only Codex. An OpenAI SDK reports it as a bad request with the text as its message. A DIAL client that reads `display_message` from the JSON body loses it on this answer, and a client that told a limit apart by the 429 no longer sees one.
- A model with an interceptor in front, global interceptors included, keeps the 429 for Codex. The refusal is made on the interceptor's call, which gets no header. With global interceptors configured, Codex users get only the reworded message, and only if the interceptor passes it on.
- With the default cost object, the message reads `in project ORG` (or whatever `defaultCostObject` is). That is still the cost object an admin assigns the spend limit to.
- A background request through an alias keeps the 429, because its target pass runs over HTTP and reads the key without the in-memory flag (D3). Codex CLI does not send background requests.
- A rolling deploy and a rollback change no stored record, because the flag is never stored. An old alias pass sets no flag, so a new target pass answers it 429 without the header; an old target pass sets no header, so a new alias pass relays its 429. Either way the client gets today's 429 during the window.

## Migration Plan

None. Deploy after `no-budget-should-retry-false`.

## Code map

- `BudgetEnforcer.java:rejectNoBudget`: builds the rejection and its message (D5), with `RateLimitResult.isNoBudget` set.
- `RateLimitResult.java:throwIfError`, and `isClientRetryDisabled` from `no-budget-should-retry-false`: the 429, its body and `x-should-retry: false`. With `isNoBudget` it throws `NoBudgetException`, an `HttpException` with the same status, body and headers whose `getReason()` is the message as text. Every route that handles an `HttpException` answers it as before.
- `ResponsesController.java:handleRequestBody`: where the Responses alias pass's key gets the D3 flag, right after `ApiKeyData.java:initFromContext`. `initFromContext` itself sets no flag and passes the lending on (the `isLentTo` branch) without one.
- Storage of the key:
  - `ApiKeyStore.java:assignPerRequestApiKey` stores it as JSON, and `ApiKeyStore.java:getApiKeyData` reads it on the HTTP hop with `ProxyUtil.MAPPER`, which rejects unknown fields. The flag is `@JsonIgnore`, so it is never written and an HTTP hop reads the key unflagged.
  - `VertxHostDelegate.java:call`: the in-process target pass takes the parent's per-request key object itself, with no Redis read.
- `UpstreamRouteProvider`, the alias pass's route with one attempt: why an alias candidate's failed fallback reaches the router as a 503 for the reason `upstream`.
- `ResponsesController.java:handleRequestError`, after `RouterDecisionFlow.java:nextCandidateOrError`: the final answer of a pass's own refusal. It answers the 400 to the client's own request, or the 429 with `X-DIAL-REFUSAL` when the pass's key is flagged (D2, D3).
- `ResponsesController.java:handleProxyResponse`, after `BaseDeploymentPostController.java:isRetriableError`: the alias pass reads its target's answer here. A marked 429 is answered as D3 says, before the streaming and non-streaming branches, ending through `completeProxyResponse` so that the analytics row and `finalizeRequest` stay as for a relayed answer.
- `ResponsesController.java:putLimitHeaders`, next to `CodexLimitResponses.java:stripUpstreamCodexHeaders`, on both the streaming and non-streaming branches: a model's or application's pass removes the upstream's `X-DIAL-REFUSAL` (D4), and so does an alias pass whose own key is not flagged, before its early return. An alias pass with a flagged key passes the header on.
- `BaseInterceptorController.java:handleProxyResponse` with `ProxyUtil.java:handleChunkedResponse`: the interceptor relay, which copies every header. It is shared with chat completions, so it calls the hook `removeCoreHeaders`, which `ResponsesInterceptorController` overrides to remove `X-DIAL-REFUSAL` (D4).
- `BaseDeploymentPostController.java:nextUpstream`, `RouterDecisionFlow.java:retryWithNextCandidate` and `TieredBalancer.java:createUpstreamUnavailableException`: why an origin's 429 never reaches the caller.
- `ProxyContext.java:isOriginalRequest`: whether the request is the client's own.
- `ProxyContext.java:respond(HttpException)` and `ProxyContext.java:respond(HttpStatus, String)`: the direct answer. `respond(HttpStatus, String)` sets no `Content-Type`, so the 400 sets `Content-Type: text/plain` itself.
- `ProxyUtil.java:copyResponse`, `ProxyUtil.java:handleChunkedResponse`: the alias relay, non-streaming and streaming.
- `Proxy.java`: the new header name constant, next to `HEADER_ROUTER_NOTICE`.
- OpenAPI: the 400 and 429 `@ApiResponse` descriptions on `ResponsesController.java:handle`.
- Tests: `BudgetSpendCapsApiTest` and its `dial-config/budget-spend-caps.json` (`gpt-spend`, `gpt-other`, `m1`, `alias-m1`, `r1`, `strong`, `app-chain`). The config gains an interceptor `icpt`, a model `m-icpt` behind it, and an alias `alias-icpt` of `m-icpt`.
  - `testBudgetForAnotherModelOnly`, `testBudgetForAnotherModelOnlyOnResponses`, `testNoBudgetThroughAliasAndRouterOnMessages`, `testNoBudgetThroughAliasAndRouterOnResponses`: they assert today's message and status.
  - `testApplicationCallToTheModelUsesInitiatorBudget`: the pattern of an application's per-request key call.
  - `testUpstreamHeadersRemovedBehindTheAlias`: the pattern of an upstream header behind the alias.
  - `postResponses` and `send`: the two Responses paths.
