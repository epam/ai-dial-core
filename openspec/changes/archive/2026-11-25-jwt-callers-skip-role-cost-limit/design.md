# Design

## Context

`RateLimiter.checkLimits` runs before a call goes upstream. Today it skips the role cost limit only when
`BudgetEnforcer.isEnforced` holds: `enforce` mode, a caller with user claims, and a model. Every other deployment gets
the full legacy check: token, request, and the role cost limit against the role cost record. Only the routes that check an application's own limits are affected: `POST /openai/v1/chat/completions` with the application in the body, Messages, Responses, routes and MCP; `POST /openai/deployments/{id}/chat/completions` never checks an application. `RateLimiter.increase`
charges every model call's cost to that record, also when a budget pays, so under `enforce` the record keeps growing
past the role limit while budgets still have room. Then a call to an application fails while calls to models pass. On
the Messages and Responses routes the same check also returns the spend caps the answer reports, read from the role
record.

## Decisions

### D1. A user's call to an application reports no spend caps
The role caps no longer limit the caller, so they would show 100% on a call that passes. The caller's budget is not
checked when the application is called, so reporting it would show a cap the call does not enforce. With no caps, the
client shows nothing for the agent call; `GET /anthropic/api/oauth/usage` still reports the budget.
**Rejected: report the caller's one budget, as translated deployments do** - it would also refuse the call once that
budget is used up, a check at application entry the issue leaves out, and it costs a budget read per call.
**Rejected: keep reporting the role caps** - they would read as reached on calls that pass.

## Differences from today

| What | Today | After | Requirement |
|------|-------|-------|-------------|
| `enforce`, user's call to an application, route or MCP server | role `costLimit` checked | not checked | budget-counters: Role cost limit is off for user callers in enforce mode |
| `enforce`, user's Messages or Responses call to an application | role caps reported | no caps reported | spend-limit-reporting: Budgets decide the caps in enforce mode |

## Effect per request

| Call | Today | After |
|------|-------|-------|
| `enforce`, user's call to anything but a model | token, request and role cost record reads | token and request record reads; the role cost record is not read |

Everything else is unchanged. Shown by `RateLimiterTest`, which counts the record reads.

## Risks / Trade-offs

- An application whose user has no budget left now starts, and fails on its first model call with the budget 429,
  instead of being refused at once by the role limit. An application that does work before calling a model does that
  work.

## Migration Plan

None.

## Code map

- `BudgetEnforcer.isApplicable` / `isEnforced`: today's rule (user claims and a `Model`); the budget check itself stays
  model-only.
- `BudgetEnforcer`: a new `isRoleCostLimitSkipped(ProxyContext)`: `enforce` mode and user claims, any deployment.
- `RateLimiter.checkLimits`: pass it as `isCostCheckSkipped` to `submitCheck`.
- `RateLimiter.checkLimit`: `isCostCheckSkipped` skips the role cost read and check, and now also the spend-cap branch
  (`checkLimitWithSpendCaps`), so the result carries no caps.
- `SpendCapCheck.verify`, `MessagesController`, `ResponsesController`: unchanged; null caps write no header and reject
  nothing.
- `RateLimiter.hasCostLimit`: unchanged; only read after a `SpendReadException`, which the skipped path cannot raise.
- Tests: `BudgetSpendCapsApiTest` (`budget-spend-caps.json`: role day cost limit $10, `app-chain`, `app-claude`,
  `proxyKey1`), `RateLimiterTest`.
