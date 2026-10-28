# Tasks

Checks for every group: `./gradlew checkstyleMain checkstyleTest` and the group's test classes with `--tests`, using a
JDK 21 `JAVA_HOME`.

## 1. Cap selection and Codex formats

- [x] 1.1 Add the secondary-window selection to `SpendCaps`: the fullest capped window other than `reported()`, with
  ties going to the longer period. Cover none, one, two and three windows, an exceeded reported window, and ties in
  `SpendCapsTest`. Verify `--tests "com.epam.aidial.core.server.limiter.SpendCapsTest"` passes.
- [x] 1.2 Write `CodexLimitResponsesTest` first, then add `controller/CodexLimitResponses`:
  - stripping is a case-insensitive prefix match;
  - primary only, and primary plus secondary;
  - percent rounded down (7.499/10 gives 74);
  - window minutes 1440/10080/43200;
  - `reset-at` in Unix seconds;
  - the reached-cap exception carries status 429, `retry-after`, `x-codex-primary-used-percent: 100`, a body with
    `type`, `code`, `message`, `resets_at`, `limit_window_minutes`, and `content-type: application/json`;
  - the unreadable-spend exception is a 429 without `retry-after` or `x-codex-*`.

  Verify `--tests "com.epam.aidial.core.server.controller.CodexLimitResponsesTest"` passes.

## 2. Responses controller

- [x] 2.1 Write `ResponsesSpendLimitApiTest` (an integration test on `ResourceBaseTest`, like
  `MessagesSpendLimitApiTest`, with a new `src/test/resources/dial-config/codex-spend-limits.json`). Write one test
  per spec scenario:
  - two capped windows, streaming;
  - one capped window, non-streaming;
  - unversioned path;
  - percent rounded down;
  - state before the request;
  - uncapped caller;
  - upstream error;
  - upstream sends its own limits;
  - upstream headers replaced;
  - day cap reached (the MockWebServer receives no request);
  - reached spend cap wins over a token limit;
  - rolling token limit;
  - cost-limited caller, spend unreadable (break only the cost record, for example with an unparsable value);
  - limit check fails otherwise;
  - application's call to the model;
  - application's call over the cap;
  - translated deployment at 82%;
  - translated deployment over the cap.

  The tests fail before 2.2.
- [x] 2.2 In `ResponsesController`, replace `verifyLimit` with the flow of `MessagesController.verifyLimit`: use
  `limitWithSpendCaps` or `readSpendCaps`, reject a reached cap first, map `SpendReadException` with `hasCostLimit`
  to `spendUnavailable()`, and keep the cap set for the answer. Strip and put the headers in both
  `handleProxyResponse` (after `handleChunkedResponse`) and `handleNonStreamingResponse` (after `copyResponse`), on
  a 200 only. Verify `ResponsesSpendLimitApiTest` passes, and that `ResponsesApiTest` and `MessagesSpendLimitApiTest`
  still pass.
- [x] 2.3 Add `@ApiHeader` entries for the six `x-codex-*` headers to the 200 response, and a 429 description of the
  three 429 shapes, on both operations in `ResponsesController`. Verify `./gradlew :openapi-generator:replaceSpec`
  updates `docs/open_api_core.yaml` with them, and that the generator's validation passes.

## 3. Docs and cost

- [x] 3.1 Write `docs/codex-spend-limits.md`, modelled on `docs/anthropic-spend-limits.md`. Cover:
  - Codex provider setup (`wire_api = "responses"` against `/openai/v1`);
  - which cap is primary and which is secondary;
  - the header table;
  - the three 429 shapes;
  - what Codex shows (the warning after an answer, the indicator above the input, "You've hit your usage limit. Try
    again at …");
  - the empty `/status` (openai/codex#45052);
  - interceptors and applications: Core answers their inner call with the headers, and Codex sees them only if
    they are relayed.

  Link the new page from `docs/anthropic-spend-limits.md` and the docs index or README where the Anthropic page is
  linked. Verify every link resolves.
- [x] 3.2 Add the `usage_limit_reached` 429 to `docs/error_codes.md`. Verify it sits next to the Anthropic
  `billing_error` entry.
- [x] 3.3 Measure: in `ResponsesSpendLimitApiTest` or a `RateLimiter` unit test, count the cost-record reads for one
  capped Responses request, and show that they match the count before this change (the same reads for a model; a translated
  deployment goes from none to one cost-record read). Record the numbers in `review.md`.
- [x] 3.4 Manual check on Codex CLI against a local Core with a capped user: a warning at 75%, the indicator above
  the input, and the usage-limit message at 100%. Done on 2026-10-27, see review.md: the warning and the indicator
  showed; the usage-limit answer reached Codex as a 429, but its message was not seen on screen.

## Workflow follow-up

- Write `review.md`, archive with `npx -y @fission-ai/openspec@1.14.3 archive codex-spend-limit-headers -y`, then run
  `validate --specs --strict`.
