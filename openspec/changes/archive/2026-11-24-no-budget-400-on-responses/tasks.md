# Tasks

Starts after `no-budget-should-retry-false` is applied: its `isClientRetryDisabled` and tests are the base.

## 1. The message and the direct-call 400

- [x] 1.1 Compare `design.md` with the code. On a drift, correct the decision or the Code map entry in place.
- [x] 1.2 Tests in `BudgetSpendCapsApiTest`, written first. Run them and confirm each fails on today's code:
  - "Budget for another model only": `testBudgetForAnotherModelOnly` asserts the new message. It passes after 1.3.
  - "No budget, through an alias and a router, Messages": `testNoBudgetThroughAliasAndRouterOnMessages` asserts the new message. It passes after 1.3.
  - `testNoBudgetOnChatCompletionsHasNoShouldRetry` asserts the new message, and still no `x-should-retry`. It passes after 1.3.
  - "Budget for another model only, Responses": `testBudgetForAnotherModelOnlyOnResponses`, through `postResponses` and through `send` on `/openai/responses`. It asserts:
    - 400 with `Content-Type: text/plain`;
    - the body `No DIAL spend limit is assigned for gpt-spend in project projA. Ask your DIAL admin to assign one.`;
    - no upstream call;
    - `x-should-retry: false`, and no `retry-after`, `x-codex-*` or `X-DIAL-REFUSAL`.
  - "Streaming Responses request": `testNoBudgetOnStreamingResponses`, the same call with `"stream": true`. It asserts the same 400 and that the body is not an event stream.
  - "Router called directly, Responses": `testNoBudgetRouterCalledDirectlyChoosesModel`, `r1` answering `["m1"]`. It asserts three decide requests, then the 400.
  - "Responses call with a per-request key": `testApplicationCallWithoutBudgetGets429`, after the pattern of `testApplicationCallToTheModelUsesInitiatorBudget`. `app-chain` calls `gpt-spend`, then `alias-m1`, with its key. Each inner call gets 429 with the DIAL body and the new message, `x-should-retry: false`, and no `X-DIAL-REFUSAL`. Both halves pass after 1.4.
  - "Header from a model's upstream or an application": `testUpstreamRefusalHeaderRemoved`. An upstream answers 200 with `X-DIAL-REFUSAL: no_budget` for `gpt-spend` directly, for `alias-m1` and for the application `app-responses`, each streaming and not. Every answer is 200 without the header.
  - `testNoBudgetThroughAliasAndRouterOnResponses` asserts the 400 now. It fails until 2.2.
- [x] 1.3 Implement D5 in `BudgetEnforcer.rejectNoBudget`: the new message, and the rejection kept recognisable after `throwIfError`.
- [x] 1.4 Implement D2 and the model and application half of D4:
  - `ResponsesController.handleRequestError` answers the client's own request with the 400.
  - `putLimitHeaders` removes the upstream's `X-DIAL-REFUSAL`.
  - The tests of 1.2 pass, except `testNoBudgetThroughAliasAndRouterOnResponses`.
- [x] 1.5 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*BudgetSpendCapsApiTest" --tests "*BudgetEnforcerTest" --tests "*RateLimiterTest" --tests "*ResponsesControllerTest"`. The only red test is the one 1.2 marks "until 2.2".

## 2. Through an alias, a router and an interceptor

- [x] 2.1 Test config and tests, written first. Run them and confirm each fails before 2.2.
  - `dial-config/budget-spend-caps.json` gains:
    - an interceptor `icpt` served by the test server;
    - a model `m-icpt` whose interceptors are `[icpt]`;
    - an alias `alias-icpt` of `m-icpt`;
    - an application `app-responses` that serves the Responses interface.

    The role that calls `m1` may call them.
  - "No budget through an alias, Responses": `testNoBudgetThroughAliasOnResponses`, a call of `alias-m1`, streaming and not. It asserts the 400 with the message for `m1`, the headers of a direct 400, and no upstream call.
  - "No budget through an alias, background Responses request": `testNoBudgetThroughAliasOnBackgroundResponsesKeeps429`. The target pass is reached over HTTP to the test Core's own address, which is `backgroundJob.localBaseUrl` by default, and reads the key from Redis without the flag. The client gets the 429 with the DIAL body.
  - "Router called directly, Responses": `testNoBudgetRouterCalledDirectlyChoosesAlias`, `r1` answering `["alias-m1"]`.
  - "Router behind an alias chooses the model, Responses": `testNoBudgetRouterBehindAliasChoosesModel`, `strong` with `r1` answering `["m1"]`. It asserts three decide requests, then the 400.
  - "No budget on the Responses routes" (deployment-aliases): `testNoBudgetThroughAliasDoesNotAffectOtherCallers`. A caller with no budget for `m1` gets the 400, then a caller with one gets 200.
  - "Interceptor in front of the model behind an alias, Responses": `testNoBudgetBehindInterceptorKeeps429`. The test server as `icpt` calls Core for `m-icpt` with the key it got, and records the answer. That answer is 429 without `X-DIAL-REFUSAL`, and the client gets 429.
  - "Header from an interceptor behind an alias": `testInterceptorRefusalHeaderRemoved`. The test server as `icpt` answers 429 with `X-DIAL-REFUSAL: no_budget` and a JSON body. The client gets that 429 without the header.
  - `ApiKeyDataTest`:
    - `flagIsNeverStored`: a flagged key's JSON has no such field, and reads back unflagged;
    - `initFromContextDoesNotCopyTheFlag`: a key made from a flagged key, for an interceptor or a callback, is not flagged.
- [x] 2.2 Implement D3 and the interceptor half of D4:
  - `ResponsesController.handleRequestBody` flags the key a Responses alias pass makes. The field is `@JsonIgnore`, so it is never stored, and `initFromContext` does not pass the flag on.
  - `ResponsesController.handleRequestError` adds `X-DIAL-REFUSAL: no_budget` when the pass's key is flagged.
  - `ResponsesController.handleProxyResponse` answers a marked 429 in the alias pass as D3 says, ending through `completeProxyResponse`, and drops the header otherwise.
  - The Responses interceptor relay removes the header.
  - Every test of 1.2 and 2.1 passes.
- [x] 2.3 Docs, each reading the same as the requirements:
  - the "No budget for the model" bullet of `docs/codex-spend-limits.md`, with the interceptor exception;
  - the same bullet of `docs/anthropic-spend-limits.md`, for the message only;
  - `docs/dynamic-settings/aliases.md`: its "The alias relays the target's answer, error statuses included" gains the exception;
  - `docs/dynamic-settings/policies.md`: its "In `enforce` mode a call with `no_budget` or `exhausted` is refused with 429" gains the Responses 400;
  - `X-DIAL-REFUSAL`: Core's own header, which Core removes from what interceptors, applications and model upstreams send. Document it with the other `X-DIAL-*` headers, or in `docs/dynamic-settings/aliases.md`;
  - the 400 and 429 paragraphs of `docs/error_codes.md`;
  - the 400 and 429 `@ApiResponse` descriptions on `ResponsesController.handle`.
- [ ] 2.4 Codex CLI 0.172.0, calling a model that no budget of the project covers, directly and through an alias, prints the message after one request (deferred: needs a dev environment with this build and budgets in enforce mode).
- [x] 2.5 `./gradlew checkstyleMain checkstyleTest`, then `./gradlew :server:test --tests "*BudgetSpendCapsApiTest" --tests "*BudgetEnforcerTest" --tests "*RateLimiterTest" --tests "*ResponsesControllerTest" --tests "*ResponsesSpendLimitApiTest" --tests "*MessagesSpendLimitApiTest" --tests "*AliasApiTest" --tests "*AliasInProcessApiTest" --tests "*ApiKeyDataTest"`, then `npx -y @fission-ai/openspec@1.14.3 validate no-budget-400-on-responses --strict`

## Workflow follow-up

- Write `review.md`, then archive with `npx -y @fission-ai/openspec@1.14.3 archive no-budget-400-on-responses -y` after `no-budget-should-retry-false` is archived. Then run `npx -y @fission-ai/openspec@1.14.3 validate --specs --strict`.
- One local commit, staging explicit paths. No push and no PR until the owner has reviewed it.
- The change is not small (it alters what passes answer one another), so CLAUDE.md asks for a draft PR with `proposal.md` and the spec deltas before applying. The owner asked to apply before that draft PR; the proposal and the spec deltas went through two independent reviews instead. Open the PR with the whole change once the owner has reviewed it.
