# Design

## Context

With `spendingLimits.mode` `enforce`, a JWT call of a model on the Messages and Responses routes is checked against
the budgets of the caller's cost object first, before the token and request limits. When no budget there covers the
model, the budget check returns a 429 rejection with a message and a display message and no `retry-after`, and both
routes answer it with the DIAL error body and no other header. Every other model route runs the same budget check
without the spend caps, and answers the same rejection the same way. On Messages the other 429s already tell the
client what to do: the spend-cap and "could not read your spend limit" 429s send `x-should-retry: false`, and a token
or request limit sends `retry-after` and `x-should-retry`. On Responses no answer sends `x-should-retry`: the
spend-cap 429 has `retry-after` and the Codex `usage_limit_reached` type, a token or request limit has `retry-after`,
and the "could not read your spend limit" 429 has neither. An alias pass copies its target pass's answer to its own caller with every header but the hop-by-hop
and trace ones, and a router in decision mode asks again after a 429 while it has decisions left.

## Decisions

### D1. Keep 429 and add `x-should-retry: false`; do not change the status

Measured with Claude Code 2.1.330, started by the `dial` launcher, against a mock that returns this answer: today 11
requests (one and ten retries) and the error after about 3 minutes; with `x-should-retry: false`, one request and the
same error at once. The Anthropic and OpenAI SDKs retry 408, 409, 429 and 5xx unless the answer says
`x-should-retry: false`, and the Messages spend-cap 429 already relies on that. On Responses the effect is not
measured: it holds for a client built on an OpenAI SDK; whether Codex CLI reads the header was not checked.

**Rejected: 403 `permission_error`** - Claude Code reported `Failed to authenticate`, as for a credential problem,
and retried 13 times over about 4.5 minutes.

**Rejected: 402** - Claude Code failed fast, but the status would change for every client of the three routes.

**Rejected: also send `retry-after`** - no window resets. With `retry-after: 5` Claude Code still made 11 requests
over about 3.4 minutes, and a client that honours it would wait for a reset that never comes.

### D2. The budget check marks its no-budget rejection, and the rejection carries the header

`RateLimitResult.throwIfError` already turns `replyAfterSeconds` into `retry-after`. A new boolean component,
`isClientRetryDisabled`, becomes `x-should-retry: false` the same way. It is named for the client, because Core's
own retry rules (`isRetriableError`, a router's next decision) still treat the 429 as before. `BudgetEnforcer`
sets it on the no-budget rejection only when it runs as the spend-cap check (`checkWithSpendCaps`), which only the
Messages and Responses routes run, through `SpendCapCheck`. Both routes already pass that rejection to
`throwIfError`, so their `Answers` do not change, and the no-budget 429 of every other route stays as it is.

**Rejected: add the header in each route's `Answers.rejectOtherLimit`** - a route cannot tell a no-budget rejection
from another 429 without the same mark, and the header would then be built in two places.

**Rejected: treat every 429 without `retry-after` on these routes as final** - it guesses from a missing header; a
later rejection without one would get `x-should-retry: false` without anyone deciding it.

**Rejected: set the mark on every no-budget rejection** - chat completions, embeddings and the other model routes
would get the header too, which the proposal leaves out.

## Differences from today

| What | Today | After | Requirement |
|---|---|---|---|
| No-budget 429 on Messages and Responses (both paths), direct or through an alias | no `x-should-retry` | `x-should-retry: false`; status, body, no `retry-after` and no spend header as today | No budget for the model |

## Effect per request

None: one more header on a rejected request; no extra pass, parse, Redis or blob call.

## Risks / Trade-offs

- An admin who adds a budget while the client would still be retrying no longer rescues the call; the user resends
  it. The error message names the model and the cost object, which is what the admin needs.
- A router in decision mode still asks again after this 429 while it has decisions left, so through such a router
  the client gets the answer only after the last decision.
- A client that ignores `x-should-retry`, possibly Codex CLI, keeps retrying, as today.
- A translator's call back to Core on Messages or Responses is checked like any call of a model, so it gets the
  header too; whether the client of the outer deployment sees it is up to the translator.

## Migration Plan

None.

## Code map

- `BudgetEnforcer.java:checkBudgets`, `BudgetEnforcer.java:rejectNoBudget`: where the no-budget rejection is built;
  `isWithSpendCaps` is true only on the `checkWithSpendCaps` path.
- `RateLimitResult.java`: the record, its four- and six-argument constructors (keep them, with `isClientRetryDisabled` false),
  `withSpendCaps` (keeps the mark) and `throwIfError` (turns `replyAfterSeconds` into `retry-after`).
- `RateLimiter.java:limitWithSpendCaps`, `RateLimiter.java:checkBudgetsWithSpendCaps`: the only caller of
  `checkWithSpendCaps`; a budget rejection is returned before the token and request limits run.
- `SpendCapCheck.java:verify`: the only caller of `limitWithSpendCaps`; hands the rejection to `rejectOtherLimit`.
- `MessagesController.java:verifyLimit` (`rejectOtherLimit`: a 429 without a breach goes to `throwIfError`) and
  `ResponsesController.java:CODEX_ANSWERS` (`rejectOtherLimit`: always `throwIfError`).
- `RouterDecisionFlow.java:nextCandidateOrError`, `MessagesBaseController.java:handleRequestError`,
  `ResponsesController.java:handleRequestError`: a router in decision mode that chooses a model directly gets the
  rejection from the candidate's own limit check and answers it with `respond(HttpException)` after its last decision.
- `AnthropicLimitResponses.java:spendCapReached`: the existing `x-should-retry` header and its value;
  `CodexLimitResponses.java:spendCapReached` and `CodexLimitResponses.java:spendUnavailable`: the Responses 429s,
  without it.
- `ProxyContext.java:respond(HttpException)`: puts the rejection's headers on the target pass's answer.
- `ProxyUtil.java:copyResponse`, `ProxyUtil.java:handleChunkedResponse`, `BaseDeploymentPostController.java:isRetriableError`,
  `RouterDecisionFlow.java:retryWithNextCandidate`: the alias pass copies the target pass's headers, non-streaming and
  streaming; a router asks again after a 429 while decisions are left, then the last answer is relayed.
- OpenAPI: the 429 `@ApiResponse` descriptions on `MessagesController.java:handle` and `ResponsesController.java:handle`.
- Tests: `BudgetSpendCapsApiTest` (`testBudgetForAnotherModelOnly`, `testBudgetUsedUpThroughAliasAndRouter`, the
  helpers `budgetFor`, `decideAliasOfM1` (one counter per test server), `postMessages`, `postResponses` (only
  `/openai/v1/responses`; `send` for `/openai/responses`)) and `dial-config/budget-spend-caps.json`
  (`gpt-spend`, `gpt-other`, `m1`, `alias-m1`, `r1`, `strong`, and `gpt-translated` for a chat completions call).
  `SpendCapCheckTest`, `MessagesControllerLimitTest` and `ResponsesControllerTest` build `RateLimitResult` directly.
