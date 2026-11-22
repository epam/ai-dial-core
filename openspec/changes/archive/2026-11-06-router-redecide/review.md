# Review: router-redecide

Implemented on `feat/router-deployment-type` at ac1c8f45 (PR #2232). This change gives the router a way to learn which
candidate served, as agreed in the review thread: list plus re-decide, history in a header, `maxDecisions` default 3
with no restriction on repeated candidates, and the mid-stream notice.

The plan was reviewed before apply by an independent agent with no context of the discussion. It reported 22
findings, three of them blockers:
- The re-decide key would have been minted for the failed candidate, with path `[r1, m1]`.
- The first candidate's response headers would have been duplicated on a fallback during a stream.
- The plan contradicted the unmodified "Decision failures" requirement.

All 22 were folded in. The main ones:
- A narrow broken-body rule: a `200`, a body that never fully arrived, and not a background job.
- `resolving` set again by a new verdict.
- Client-sent router headers dropped.
- A cap of 10 on `maxDecisions`.
- `partial` kept off the analytics row, which is not written for a broken stream.

## What changed

- **Config (D5).** `DeploymentInterface.maxDecisions` is validated from 1 to 10 in `validateDecisionEntry`, with default
  3 in `RouterDecisionClient.maxDecisions`. Tests: `ConfigPostProcessorTest` (0 and 11 rejected, 10 accepted).
- **Record (D1).** `RouterDecision` gains `redecided`, `fail`, `canRedecide`, `exhaust`, the router, entry and
  key-template fields, `Attempt.received` and `partial`, and `Row.decisions`. Tests: `RouterDecisionTest` (new, 5
  cases). The existing row shape is unchanged for a plain fallback (`GfLogStoreTest`).
- **Client (D4, D6).**
  - `decide` takes the attempts.
  - `notice` sends no key, an empty body and no wait.
  - One header builder serves both, drops client router headers, and writes the attempts with non-ASCII escaped.
  - Tests: `RouterDecisionClientTest` (attempts and dropped headers, notice, escaping).
- **Walk (D2, D3).**
  - `askRouter`, `redecide` and `mintKeyLike`. The re-decide key is a copy of the first decide key, so its path is
    `[r1]`.
  - `nextCandidateOrError(error, answer)` and `retryWithNextCandidate(status, reason, answerOriginal)`, with all call
    sites passing their handler.
  - A broken `200` body that never arrived, outside background jobs, now falls back. It resets the upstream and clears
    the copied client headers. The new `ProxyContext.upstreamBodyReceived` is set at the four buffered body reads.
  - In `handleResponseError`: a fallback when the client saw nothing, a notice when it saw bytes, nothing when the
    client left.
- **Tests in `RouterDecisionApiTest`.**
  - Re-decide: history and second key, limit reached, re-decide failure, refusal in the second verdict, body sent
    again, forged headers dropped.
  - Broken answers: broken body on chat completions, Messages and Responses; no second background job; broken stream
    before the first chunk (one `Content-Type`); notice after the first chunk; client gone, no notice.
  - Changed tests: `everyCandidateForbidden` (6 decide requests for 2 calls) and
    `routerReEnteredThroughAnApplication` (3 outer decisions, the nested call refused without one of its own).
    `bodyFailureAfterTheAnswerIsNotRetried` became `brokenBodyBeforeTheClientGotByte`.
  - Fixture: router `r-two` (`maxDecisions: 2`) and model `m3` (Responses).
- **Docs.** `docs/dynamic-settings/routers.md`:
  - `maxDecisions` in the entry fields;
  - a "Fallback and re-decide" section with the three cases, both headers, the notice and three use cases;
  - the failures note limited to the first decide request;
  - the `reason` list, `received` and `decisions` in the accounting text.

  `docs/open_api_core.yaml`: `maxDecisions` on `DeploymentInterface`.

## Verification

- `./gradlew checkstyleMain checkstyleTest` is clean.
- A targeted run of `Router*`, `Deployment*`, `Responses*`, `Messages*`, `Interceptor*`, `controller.*`,
  `RouterDecisionClientTest`, `RouterDecisionTest`, `GfLogStoreTest` and `ConfigPostProcessorTest` passes: 608 tests.
- `checkOpenApiDiff` passes, and `validate router-redecide --strict` is valid.
- The full suite runs in CI after the push.

## Drift from the plan

- The broken-body rule reads a new `ProxyContext.upstreamBodyReceived` flag instead of `responseBody == null`. The
  Responses path sets `responseBody` only after saving its mapping, so a mapping failure would have looked like a
  broken body.
- When a stream breaks before the client got a byte and no decision is left, the exhausted path answers `502` "The
  upstream answer broke" instead of resetting the connection. The head was not written, so an error can still be
  answered (review nit 22).
- Test mechanics: an empty MockWebServer body with `DISCONNECT_DURING_RESPONSE_BODY` never closes the socket, so the
  broken-stream test uses `DISCONNECT_AT_END` with a promised `Content-Length`. Raw streaming tests pin the JDK client
  to HTTP/1.1, because Core answers an h2c upgrade with `505`.

## Notes

- The notice is best-effort and is not retried.
- A connect failure or a broken body still skips the candidate without trying its other upstreams, as a connect
  failure already did. Header-read failures and retriable statuses go through the candidate's upstreams first.
