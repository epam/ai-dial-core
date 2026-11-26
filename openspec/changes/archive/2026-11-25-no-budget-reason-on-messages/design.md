# Design

## Context

With `spendingLimits.mode` `enforce`, a JWT call of a model on Messages runs the budget check in the spend-cap check
before its token and request limits. When no budget of the caller's cost object covers the model, the check rejects
with a 429, the DIAL error body, `x-should-retry: false` and no other header, and Messages answers it as it is, to
every caller. A call of an alias runs two passes: the alias pass makes no limit check and calls Core for the target
in process, with a per-request key it makes; on Messages there is no background request and no interceptor, so the
target pass always runs in process. The alias pass and a router in decision mode fall back on a 429 while a decision
has a candidate left; otherwise the alias pass relays its target's answer. On Responses, `no-budget-400-on-responses`
already keeps the refusal a 429 inside Core and turns it into the client's answer only at the pass that answers the
client.

## Decisions

### D1. The client's own request gets 402 `billing_error`, not the 429

The table shows what Claude Code 2.1.332 printed for each answer, from a stub upstream, run with `claude -p`. Every
answer had `x-should-retry: false` and the no-budget message. "Gateway mode" is Claude Code's own: its
`ANTHROPIC_AUTH_TOKEN` is a JWT whose `aud` includes `claude-gateway`, or its provider is a gateway sign-in.

| Answer | Gateway mode | Other | Requests |
|---|---|---|---|
| 429, DIAL body (today) | `API Error: Server is temporarily limiting requests (not your usage limit) · <message>` | `API Error: Request rejected (429) · <message>` | 1 |
| 429, `rate_limit_error`, `error.details.error_code` | the same as today | the same as today | 1 |
| 429, any body, `anthropic-ratelimit-unified-overage-disabled-reason` | `<message>` | `API Error: Request rejected (429) · <message>` | 1 |
| **402, `billing_error`** | **`API Error: 402 <message>`** | **the same** | **1** |
| 403, `permission_error` | `Failed to authenticate. API Error: 403 <message>` | the same | 1 |
| 400, `invalid_request_error` | `API Error: 400 <message>` | the same | 3 |

In gateway mode, Claude Code puts the "temporarily limiting" text in front of every 429 without its usage-limit
headers, unless the 429 names a disabled-overage reason. That header is not in Anthropic's error reference
(platform.claude.com/docs/en/api/errors); it is one of the headers Claude Code reads on its own usage limits. The 402
is the one answer that Claude Code prints with its message in both modes, at once, from documented behaviour only: the
reference lists 402 `billing_error`, and the Anthropic SDK does not retry a 402.

**Rejected: 429 `rate_limit_error` with `error.details.error_code`, as the reference describes a spend-limit
refusal** - Claude Code reads the code only to classify the error; the text stays "temporarily limiting" in gateway
mode.

**Rejected: 429 with `anthropic-ratelimit-unified-overage-disabled-reason`** - it fixes the text through a header
Anthropic does not document for this, which a later Claude Code may read differently.

**Rejected: 403 `permission_error`** - Claude Code reports `Failed to authenticate`, as for a credential problem.

**Rejected: 400 `invalid_request_error`** - Claude Code sent it three times.

### D2. Inside Core the refusal stays a 429, marked for the alias pass as on Responses

This is `no-budget-400-on-responses` D2 and D3 on Messages. Only the pass that answers the client's own request
(`ProxyContext.isOriginalRequest`), as its final answer, answers the 402. The Messages alias pass flags the
per-request key it makes for its target, in memory only; the pass whose own key carries the flag adds
`X-DIAL-REFUSAL: no_budget` to its no-budget 429. An alias pass that answers the client's own request turns a marked
429 into the 402, with the 429's `error.message` and none of the target's other headers. Core removes the mark from
what a model's upstream answers, and from every answer but the one an alias pass with a flagged key relays, as on
Responses (D4 there).

**Rejected: the budget check rejects with 402** - 402 is not a status Core retries, so an alias pass would stop on it
instead of falling back to a router's next candidate.

### D3. The no-budget code that both routes share moves to the base controller

`BaseDeploymentPostController` takes the final answer of the refusal (`respondNoBudget`, with the route's answer to the
client's own request as a callback), the test for a marked 429 (`isNoBudgetOfTarget`), the message of a marked 429
(`readNoBudgetMessage`) and the removal of the mark (`removeRefusalMark`). The Responses controller keeps its 400 and
the Messages controllers their 402.

**Rejected: a copy of the Responses code in the Messages controllers** - one rule, two copies to keep in step.

### D4. The 402 is written by its number; `HttpStatus` stays as it is

`HttpStatus` has no 402. Adding it to `HttpStatus.fromStatusCode` would change how Core treats a 402 from a model's
upstream, a router's decide endpoint or an authorization service, which today maps to 500 or 400. The Messages code
writes the status through `ProxyContext.respond(int, Buffer)` and `HttpServerResponse.setStatusCode`.

**Rejected: add `PAYMENT_REQUIRED` to `HttpStatus`** - it touches the storage module and the callers above.

## Differences from today

"Today" is `development` with `no-budget-400-on-responses` applied.

| What | Today | After | Requirement |
|---|---|---|---|
| Messages no-budget, the client's own request, streaming or not | 429, DIAL JSON, `x-should-retry: false` | 402, Anthropic `billing_error` JSON, `x-should-retry: false` | No budget on the Messages route |
| Messages no-budget through an alias, or a router with an alias as its target or candidate | 429, DIAL JSON | 402, Anthropic `billing_error` JSON | No budget through an alias; The alias relays the target's answer |
| Messages no-budget to an application's or translator's call | 429, DIAL JSON | the same, no `X-DIAL-REFUSAL` | No budget on the Messages route |
| Messages no-budget, answered by the pass with a flagged key to its alias pass | 429 | 429 with `X-DIAL-REFUSAL: no_budget` | - (inside Core) |
| `X-DIAL-REFUSAL` from a model's or application's upstream on Messages and `count_tokens` | relayed | removed | The no-budget mark stays inside Core |
| The per-request key a Messages alias pass makes | no flag | the flag, in memory; the stored JSON of every key is unchanged | - |
| Response status in the alias pass's analytics row, the router row and the HTTP server span for a no-budget refusal on Messages | 429 | 402 | - (records) |

## Effect per request

| Pass | Today | After | How it is measured |
|---|---|---|---|
| A pass that relays a model's answer on Messages | copies the headers | also removes one header | the existing tests |
| A Messages alias pass | makes the per-request key | sets one boolean on it, in memory | the existing tests |
| A Messages alias pass that relays a marked 429 to the client | pipes or copies the small error body | reads it into memory and parses it once for `error.message` | `BudgetSpendCapsApiTest` alias scenarios |

No Redis or blob round trip is added.

## Risks / Trade-offs

- Any client of Messages gets this refusal as a 402, not only Claude Code. An Anthropic SDK reports it as a billing
  error, whose reference text speaks of payment information; the message says what to do instead. Monitoring that
  counted the refusal as a 429 no longer sees one.
- The route's spend-cap refusal stays a 429 `billing_error`, so two spend refusals on one route have two statuses.
- A rolling deploy and a rollback change no stored record. An old alias pass sets no flag, so a new target pass
  answers it 429 without the mark; an old target pass sets no mark, so a new alias pass relays its 429. Either way the
  client gets today's 429 during the window.

## Migration Plan

None. Deploy after `no-budget-400-on-responses`.

## Code map

- `BudgetEnforcer.java:rejectNoBudget` and `RateLimitResult.java:throwIfError`: the rejection, thrown as
  `NoBudgetException` with the DIAL body; `getReason()` is the message as text.
- `MessagesController.java:verifyLimit`: hands the rejection on unchanged.
- `MessagesBaseController.java:handleRequestError`, after `RouterDecisionFlow.java:nextCandidateOrError`: the final
  answer of a pass's own refusal, through `respondNoBudget`, with the 402 as the client's answer.
- `MessagesBaseController.java:prepareUpstreamRoute`, after `ApiKeyData.java:initFromContext`: where the Messages alias
  pass's key gets the flag.
- `MessagesController.java:processResponse`: the alias pass reads its target's answer here; a marked 429 is answered
  as D2 says, ending through `completeProxyResponse`.
- `MessagesController.java:putLimitHeaders`: removes the mark through `removeRefusalMark`, before an alias pass's
  early return; `MessagesCountTokensController.java:forwardResponse` removes it after copying the upstream's headers.
- `AnthropicLimitResponses.java:putNoBudget` and `PAYMENT_REQUIRED`: the 402's body and headers.
- `BaseDeploymentPostController.java`: `respondNoBudget`, `isNoBudgetOfTarget`, `readNoBudgetMessage`,
  `removeRefusalMark`, moved from `ResponsesController.java`, and `isRetriableError`.
- `ResponsesController.java:handleRequestError`, `answerNoBudgetOfTarget` and `putLimitHeaders`: now call the shared
  code; `buildNoBudgetTextAnswer` is its 400.
- `ProxyContext.java:respond(int, Buffer)`: the direct 402.
- OpenAPI: the 402 and 429 `@ApiResponse` descriptions on `MessagesController.java:handle`.
- Tests: `BudgetSpendCapsApiTest`: `testBudgetForAnotherModelOnly`, `testNoBudgetOnStreamingMessages`,
  `testNoBudgetRouterCalledDirectlyOnMessages`, `testNoBudgetRouterCalledDirectlyChoosesAliasOnMessages`,
  `testNoBudgetThroughAliasOnMessages`,
  `testNoBudgetThroughAliasAndRouterOnMessages`, `testApplicationCallWithoutBudgetGets429` and
  `testUpstreamRefusalHeaderRemovedOnMessages`, with `assertNoBudgetMessagesAnswer`.
