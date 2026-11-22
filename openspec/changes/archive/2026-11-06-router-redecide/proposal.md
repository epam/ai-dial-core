# Proposal

## Why

In decision mode the router never sees the response. When Core falls back from the first candidate to the second,
the router still believes the first one served. A sticky-session router then binds the conversation to the wrong
deployment, and a router that wants to adapt its fallback (another provider after a `429`, a lower tier) gets no
chance to. Review of PR #2232 asked how the router can learn which candidate served. The agreed answer is the "list
plus re-decide" shape: a failure that ends the list goes back to the router with the attempt history, and a failure
after bytes reached the client is reported to it.

## What Changes

- **Re-decide.** When every candidate of a verdict has failed and the interface's `maxDecisions` (new, default `3`,
  first decision included) allows another, Core sends the router a new decide request. It carries
  `X-DIAL-ROUTER-ATTEMPTS`, the JSON list of every attempt so far. The new verdict's candidates are tried as the first
  ones were. Core does not restrict what the router names, a deployment that already failed included. When the
  re-decide fails, answers no usable candidate or no decision is left, Core answers the last candidate's failure.
- **Retry after the upstream answered, before the client got a byte.** A candidate whose answer fails after its
  headers arrived (a buffered non-streamed body that breaks, a stream that breaks before its first chunk reaches the
  client) is now skipped like a connect failure. Its attempt carries `received: true`, because the upstream may have
  done billable work.
- **Mid-stream notice.** When a candidate's answer fails after bytes reached the client, Core cannot fall back. It
  ends the client's response as today, and it sends the router a notice: a decide request with
  `X-DIAL-ROUTER-NOTICE: true` and the attempt history, the last attempt marked `partial: true`. Core does not wait
  for the answer, ignores any verdict, and does not count the notice as a decision.
- **Decision record.** The analytics `decision` object gains `decisions` (how many decide requests the request
  needed). `candidates` lists every verdict's candidates in order, `decide_ms` is their sum, and attempts carry
  `received` and `partial` when true.
- **Docs.** `docs/dynamic-settings/routers.md` describes the loop with three short use cases: sticky conversation,
  cheap local fallback, adaptive fallback.

Out of scope: continuing a broken stream on another deployment (LiteLLM-style "mid-stream fallback"), an outcome
event on success, and any change to proxy mode.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `router-deployment`: "Candidate walk" covers a failure after the upstream answered and hands an exhausted list to
  the router. Added "Decision limit", "Re-decide after the candidates", "Mid-stream notice" and "Decision record
  across decisions".

## Impact

Motivated by the review of PR #2232, the thread on how the router learns which candidate served
(https://github.com/epam/ai-dial-core/pull/2232#discussion_r4483555769), and the follow-up discussion. It covers the
chosen design: list plus re-decide, header-borne history, `maxDecisions` default 3, no restriction on repeated
candidates, and the mid-stream notice. It ships in PR #2232.

Code:
- `config`: `DeploymentInterface.maxDecisions`.
- `ConfigPostProcessor`: `maxDecisions` must be positive.
- `RouterDecision`: several verdicts, `received`/`partial` attempts, `decisions` on the row.
- `RouterDecisionClient`: the attempts header, and `notice` (fire-and-forget).
- `BaseDeploymentPostController`:
  - `decideIfRouter` and a new re-decide step;
  - `nextCandidateOrError` and `retryWithNextCandidate`, which take what to answer when no decision is left;
  - `handleProxyConnectionError`, `nextUpstream` and `handleResponseError`.
- The four request controllers' `handleRequestError` call sites.
- `DeploymentPostController.handleRateLimitHit`.

Docs: `docs/dynamic-settings/routers.md`, regenerated `docs/open_api_core.yaml`.

Cost: none on a request whose first candidate serves. On the failure path, one decide request (with its key mint and
invalidation) per extra decision, and one fire-and-forget request per mid-stream failure.
