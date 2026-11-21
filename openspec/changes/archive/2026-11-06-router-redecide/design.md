# Design

## Context

See proposal.md for motivation. Current code on the PR branch (ac1c8f45):

- **`RouterDecision`** holds one verdict's `candidates`, an `index`, `attempts`, `resolving` and `closed`.
  `skip(status, reason)` records an attempt and moves `index`. `hasNext()` is false once the list is used up or the
  request was handed to an interceptor chain (`closed`). `toRow()` builds the analytics `decision` object from
  `current()`.
- **`BaseDeploymentPostController.decideIfRouter`** starts the span, mints the decide key, calls
  `RouterDecisionClient.decide`, invalidates the key and sets `context.decision`.
- **The candidate walk has two synchronous entry points.**
  - `nextCandidateOrError(error)` handles refusals while the candidate is being resolved (access, limits, not found,
    unavailable). It answers `null` after re-entering, or the error to respond with. It is called from the
    `handleRequestError` of `DeploymentPostController`, `ChatCompletionsController`, `ResponsesController` and
    `MessagesBaseController`, and from `DeploymentPostController.handleRateLimitHit`.
  - `retryWithNextCandidate(status, reason)` handles call failures. It answers `true` after re-entering. It is called
    from `handleProxyConnectionError`, a connect failure or a broken buffered body (the
    `handleNonStreaming...Response` paths of chat completions and Responses), and from `nextUpstream`, upstreams
    exhausted. It refuses once `context.proxyResponse` is set, so a broken body today ends the request even though
    the client got nothing.
- **`handleResponseError(error, stream)`** runs when a piped answer (streamed, or a non-buffered body) fails. It
  resets the client connection, collects the usage of a model's partial answer, writes the row and finalizes. The
  client's head is written on the first piped chunk (`HttpServerResponse.headWritten`).

## Goals / Non-Goals

**Goals:**

- An exhausted list goes back to the router with the history, up to `maxDecisions`.
- A broken answer that reached no client byte is a fallback, on any API.
- A broken answer that did reach the client is reported to the router.
- One decision record across all verdicts.

**Non-Goals:**

- Continuing a broken stream on another deployment.
- An outcome event on success.
- Restricting what the router names.

## Decisions

### D1. `RouterDecision` grows across verdicts

The candidate list becomes mutable and append-only.

- `redecided(List<String> next, long decideMs)` appends the new verdict's candidates, adds to `decideMs`, increments
  `decisions`, moves `index` to the first new candidate and sets `resolving = true`, as `skip` does. Without that, a
  refused first candidate of the new verdict would be answered instead of skipped.
- `fail(status, reason, received)` records an attempt without moving; `skip(...)` is `fail` plus a move.
- `canRedecide(maxDecisions)` is `!closed && !exhausted && decisions < maxDecisions`. `exhaust()` sets `exhausted`.
- The decision keeps the `Router`, its `DeploymentInterface` and the first decide key's `ApiKeyData` as a template
  (D2). These are transient and not in the row.
- `Attempt` gains `received` and `partial`, each annotated `@JsonInclude(NON_DEFAULT)` on the boolean only, so
  `status` 0 is never dropped.
- `Row.decisions` is an `Integer` set only when above 1.

`toRow()` keeps `current()`, which stays on the last tried candidate when the loop gives up.

An attempt is recorded once, at the moment Core moves on: a skip or the start of a re-decide. The "exhausted"
callback re-enters the walk, and on that second pass it records nothing.

Rejected: a list of verdicts in the record. Analytics only needs what was tried, in order, and what served, and a
flat `candidates` keeps today's consumers working.

### D2. The re-decide step and the "answer if exhausted" callback

`BaseDeploymentPostController.redecide(Runnable whenExhausted)`:

1. If the client's response is closed, finalize the request (span and key) and stop.
2. Mint a key from the decision's template: a copy of the first decide key's `ApiKeyData` with a new per-request key
   and the same TTL rule. Calling `initFromContext` again would be wrong: the context's deployment is now the failed
   candidate and the decision is set, so the path would become `[..., r1, m1]`, the source `m1`, and `m1`'s shares
   would go to the router.
3. Call `RouterDecisionClient.decide(..., attempts)` with the decision's router and entry. The body is what the first
   decide request sent: the original request bytes when `context.body` is on, from `context.getOriginalRequestBody()`.
   Every controller sets it before deciding.
4. Invalidate the key.
5. Check the client's response is still open.
6. Reject router candidates as today, then call `decision.redecided(...)` and `reenterWithCandidate()`.

When any step fails (a timeout, a router error, an invalid verdict, an empty list), the step logs the failure, calls
`decision.exhaust()` and runs `whenExhausted`.

The entry points become:

- `nextCandidateOrError(error, Consumer<Throwable> answer)`. When there is no next candidate but a decision is left, it
  records the attempt, starts `redecide(() -> answer.accept(error))` and answers `null`. Every call site passes its own
  handler, `e -> handleRequestError(deploymentId, e)` in the four controllers and `e -> respond((HttpException) e)` in
  `handleRateLimitHit`.
- `retryWithNextCandidate(status, reason, Runnable answerOriginal)`, the same shape for call failures.
  `handleProxyConnectionError(error)` passes `() -> handleProxyConnectionError(error)` and reason `body` when
  `proxyResponse` is set, `connect` otherwise. `nextUpstream` passes `() -> respond(e)`.

Both entry points refuse to move on, and answer the error instead, once the client's response is closed.

Rejected: making the walk return a `Future`. Every controller's error mapping would have to change, while a callback
keeps their code as it is.

Rejected: answering the router's re-decide error. The client asked a model for an answer, and the last candidate's
failure tells it more than "the router failed while recovering".

### D3. A broken answer before the client got a byte is a fallback, narrowly

`retryWithNextCandidate` drops its `proxyResponse != null` refusal, but only when all of these hold:

- the client's head is not written;
- the client's response is not closed;
- `proxyResponse` is `200`;
- the body read itself failed (`context.getResponseBody() == null`);
- the request is not a background Responses job (`!context.isBackgroundJob()`).

The background exclusion exists because the first candidate's accepted job, or its saved response mapping, would keep
running beside the second one. The `200` rule keeps "no fallback after a non-retriable answer". The body-read rule
keeps a failure after the body arrived (mapping storage, enhancement) from becoming a fallback, after limits and spans
were already charged.

When it falls back with `proxyResponse` set, it:

- resets `context.proxyRequest`, dropping the half-read upstream connection;
- clears `proxyResponse`;
- clears the client response's headers and sets `chunked` back to false. On the streamed paths
  `handleChunkedResponse` already copied the first candidate's headers with `add`, and the second answer would
  otherwise carry two of each.

The attempt carries `received: true`.

`handleResponseError(error, stream)` first checks for a decision with the client response open and its head not
written. In that case it tries `retryWithNextCandidate(502, "stream", () -> <today's handling>)` and returns if that
re-entered.

Deliberate, unchanged: a connect failure or a broken body skips the candidate without trying its other upstreams, as a
connect failure does today. Header-read failures and retriable statuses still go through `nextUpstream` first.

Rejected: checking "no upstream byte was read". What matters is what the client saw. A buffered non-streamed body is
invisible to the client until it is complete.

### D4. The mid-stream notice

`handleResponseError` sends the notice when there is a decision that is not `closed`, the client response is open and
its head is written. `RouterDecisionClient.notice(...)` builds its headers synchronously, because it can outlive the
request:

- the client headers and `defaultHeaders`, as the decide request has them;
- `X-DIAL-ROUTER` and `X-DIAL-API`;
- `X-DIAL-ROUTER-NOTICE: true`;
- `X-DIAL-ROUTER-ATTEMPTS`, the decision's attempts plus `{proxyResponse status, "stream", received, partial}`.

It has an empty body and no `API-KEY`. It runs within the interface's `decisionTimeoutMs`, discards the answer's body
so the pooled connection is released, and logs a failure. Then today's handling continues: reset the client, collect
usage, finalize.

Behind a chosen deployment's interceptors the decision is `closed`, and `BaseInterceptorController` sends no notice.

Rejected: sending the notice after the response ends. The next turn can arrive right after the client sees the broken
stream, so the notice goes first.

### D5. `maxDecisions`

`DeploymentInterface.maxDecisions` (`Integer`, no alias, like `decisionTimeoutMs`) is validated in
`ConfigPostProcessor.validateDecisionEntry`: from 1 to 10, otherwise `maxDecisions must be between 1 and 10`. It is
ignored on an entry not in decision mode, as `decisionTimeoutMs` is. The default of 3 lives in
`RouterDecisionClient.maxDecisions(entry)`.

Rejected: no upper bound. Each decision can cost a decide timeout plus every candidate's upstream attempts, so the cap
keeps a misbehaving router's worst case finite.

### D6. Router headers

`X-DIAL-ROUTER-ATTEMPTS` is the JSON array of `RouterDecision.Attempt`, written with non-ASCII escaped (header values
are ISO-8859-1, as `ProxyUtil.USER_INFO_WRITER` did). Its field names match the analytics row: `deployment`, `status`,
`reason`, `received`, `partial`. Both router headers are added to the decide and notice requests' `excludeHeaders`, so a
client-sent copy never passes through. Core then sets them itself.

Rejected: the history in the decide body. The body may already be the client's request bytes (`context.body`).

## Effect per request

- **First candidate serves.** No change: no extra request and no extra Redis command.
- **Each extra decision.** One decide request, plus its key's SET and DEL.
- **Each mid-stream failure.** One fire-and-forget request, with no key.
- **A broken answer that now falls back** costs a full candidate attempt instead of an error to the client.
- **Worst case.** About `maxDecisions` (at most 10) × (`decisionTimeoutMs` + each candidate's upstream attempts),
  with no overall deadline beyond those.
- **Measurement.** None claimed beyond these counts.

## Risks / Trade-offs

- [A router that keeps naming a failing deployment] → bounded by `maxDecisions`, and each try is visible in
  `attempts`.
- [The failed candidate did billable work before its answer broke] → `received: true` marks it, and its usage is lost
  only where today's broken-answer path loses it too.
- [Telling "upstream broke" from "client left"] → `HttpServerResponse.closed()` separates them. A client that leaves
  gets no fallback and no notice.
- [Notice lost] → it is best-effort. The router's next decision for the conversation is made from scratch in any case.

## Migration Plan

None. With `maxDecisions` left at its default, a router that answers one list sees one decide request on the success
path, as today. A router that does not know the two new headers ignores them, and it gets at most two more decide
requests per failing request.

## Open Questions

None.
