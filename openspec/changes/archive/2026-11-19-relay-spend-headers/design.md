# Design

## Context

Every Messages and Responses pass runs a limit check before it forwards the request, and writes spend headers before
the first byte of the answer. The check reads the caller's caps from the budget when spending limits are enforced and
the pass's deployment is a model, and from the role cost limit otherwise; it refuses with the spend-cap 429 when the
cap is reached, and it also checks the token and request limits under the deployment's name. On the answer, the pass
removes the spend headers its upstream sent, on every status, and on a 200 adds the caps it read. An alias pass is an
application whose upstream is the target pass, in process or over HTTP, so it checks the role cost limit and replaces
the target pass's headers with it. A router in decision mode is asked inside the pass that named it, and that pass
then takes the chosen candidate's deployment and runs the candidate's limit check, so an alias candidate makes it an
alias pass too. In `strong` over `r1` choosing `alias-m1` over `m1`, the passes of `strong` and of `r1` are alias
passes, and only the pass of `m1` reads the budget, whose headers the client never sees.

## Decisions

### D1. The alias pass relays the target pass's headers; it does not compute them

The pass of the serving deployment is the only one that knows the budget that is charged, and its answer already
carries the right headers, in the client's format, on every status. Relaying them unchanged is less code than
recomputing them, and gives the same answer as a direct call of that deployment.

**Rejected: read the budget on the alias pass too** - the alias pass is not a model, and the model the chain ends at
is not known before a router behind it decides; it would also keep a limit check per extra pass.

### D2. The alias pass makes no limit check at all, not only no spend-cap check

On a non-model deployment, every check of `RateLimiter` reads the role cost limit and refuses when one of its windows
is reached, the spend-cap read or not; so skipping only the spend-cap part still refuses a call the budget allows. The
token and request limits it checks are under the alias's name, and the docs and a warning at config load already say
such limits have no effect. The target pass checks every limit before it calls its upstream, so the upstream is still never called
over a reached cap. `McpProxyController` already skips the whole limit check of an alias pass.

**Rejected: skip the spend-cap read and keep the other limits** - the remaining check still reads and enforces the role
cost limit, and still counts a request under the alias's name.

### D3. The rule is keyed on an alias pass, not on a router

`AliasPass` is the deployment whose upstream is Core itself. A router in decision mode is never the pass's deployment at
the limit check: the pass has taken the candidate's by then, so an alias candidate is an `AliasPass`, and a model
candidate makes the pass the serving one, which must keep its check. A router in proxy mode calls an origin outside
Core, whose answer may carry no spend headers.

**Rejected: also key on `Router`** - it would change only routers in proxy mode, and there it would drop the role caps
a client gets today (the right ones in `off` and `shadow` mode) whenever the router outside Core does not relay the
headers.

**Rejected: key on "the upstream is Core's own address"** - an in-process target pass has no address.

## Differences from today

| What | Today | After | Requirement |
|---|---|---|---|
| Spend headers on a 200 Messages or Responses answer through an alias | the alias pass's: role cost limit, or none | the serving pass's, unchanged | Spend headers through an alias |
| `anthropic-ratelimit-*` headers of a target pass's spend-cap 429 | removed by the alias pass (`retry-after` and `x-should-retry` kept) | kept | Spend headers through an alias |
| Upstream `x-codex-*` headers on an alias pass | removed | kept | Upstream Codex headers removed |
| Limit check on an alias pass | role cost limit, token and request limits under the alias's name | none; the target pass checks | No limit check on an alias pass |
| A role's request limit under an alias's name | counts and refuses on Messages and Responses | no effect, as on MCP | No limit check on an alias pass |

## Effect per request

| Per Messages or Responses call through one alias | Today | After |
|---|---|---|
| Limit checks (cost record read, token record read, request counter read and write) | one on the alias pass, one on the serving pass | one, on the serving pass |
| Core passes, body copies, parses | unchanged | unchanged |

Each alias pass in the chain saves one check: two for `strong` over `r1` choosing `alias-m1`. Not measured: the count
follows from the code paths in the Code map.

## Risks / Trade-offs

- A role that set a request limit under an alias's name loses it on Messages and Responses; the config load already
  warns that such a limit has no effect.
- In a decision chain, the target pass's spend-cap 429 is a failed candidate while the router has decisions left, so
  Core tries the next candidate, or asks the router again, before the client gets the 429. Today the alias pass's own
  refusal does the same. One case differs: when asking the router again fails (a timeout or an error of the router),
  the client gets the error of the alias pass's used-up upstream route, without spend headers, where today it gets
  the alias pass's own 429. A budget refusal of
  a model candidate behaves like this already; the change that ends a decision at a budget refusal covers both.
- A chain whose serving pass is an application or a translated deployment reports the caps that pass reads, as a
  direct call of it does.

## Migration Plan

None.

## Code map

- `SpendCapCheck.java:verify`: the check both routes call; the place to return no caps for an `AliasPass`.
- `MessagesController.java:verifyLimit` and `MessagesController.java:putLimitHeaders`: the check and the headers on
  Messages; `putLimitHeaders` leaves the headers of an alias pass's answer as they are.
- `ResponsesController.java:verifyLimit` and `ResponsesController.java:putLimitHeaders`: the same on Responses.
- `AnthropicLimitResponses.java:stripUpstreamRateLimitHeaders`, `CodexLimitResponses.java:stripUpstreamCodexHeaders`:
  the removal.
- `BaseDeploymentPostController.java:isSubjectToLimits`: true for an alias pass, which serves in passthrough mode.
- `RateLimiter.java:limitWithSpendCaps`, `RateLimiter.java:checkLimitWithSpendCaps`, `RateLimiter.java:checkLimit`,
  `RateLimiter.java:checkRequestLimit`, `BudgetEnforcer.java:isEnforced`: why an alias pass reads the role cost limit
  and counts a request under its name.
- `AliasPass.java`: the type to test for. `AliasPasses.java:build` makes every alias pass one:
  `AliasApplicationPass.java` for every presentation but `toolset`, `AliasToolSetPass.java` (MCP only) for `toolset`.
  `McpProxyController.java:limit` is the precedent.
- `MessagesBaseController.java:serveCandidate`, `ResponsesController.java:serveCandidate`,
  `RouterDecisionFlow.java:decideIfRouter`: the pass that asks a router takes the candidate's deployment before
  `verifyLimit`.
- `BaseDeploymentPostController.java:isRetriableError`, `RouterDecisionFlow.java:retryWithNextCandidate`: a 429 of an
  alias candidate's target asks the router again while decisions are left, then is relayed.
- Tests: `BudgetSpendCapsApiTest` and its config `dial-config/budget-spend-caps.json` (budgets in enforce mode),
  `MessagesSpendLimitApiTest`, `ResponsesSpendLimitApiTest`; `RouterDecisionApiTest` and `dial-config/router-decision.json`
  for the shape of an alias over a router and the relay after the last decision.
