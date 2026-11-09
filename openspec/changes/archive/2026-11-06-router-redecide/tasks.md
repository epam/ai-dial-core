# Tasks

Spec scenario to test:

- `ConfigPostProcessorTest`: "Limit out of range rejected".
- `RouterDecisionApiTest`:
  - "Router decides again with the history", "Limit reached", "Re-decide fails", "Refusal in the second verdict
    skipped", "Body sent again" and "Forged history dropped";
  - "Stream broken after the first chunk" and "Client gone, no notice";
  - "Row of a re-decided request";
  - the "Candidate walk" scenarios, "Broken body before the client got a byte" and "Broken stream before its first
    chunk" included;
  - "Decision failures" "Router re-entered through an application".
- `RouterDecisionTest` (new) and `RouterDecisionClientTest`: unit coverage of the record and the client.

## 1. Config and record

- [x] 1.1 `DeploymentInterface.maxDecisions` (no alias), the 1..10 check in `validateDecisionEntry`, and
  `RouterDecisionClient.maxDecisions(entry)` with default 3 (design D5). Test first in `ConfigPostProcessorTest` (0
  and 11 rejected, 10 accepted).
- [x] 1.2 `RouterDecision`: `redecided` (setting `resolving`), `fail`, `canRedecide`, `exhaust`, the transient router,
  entry and key template, `Attempt.received`/`partial` with `NON_DEFAULT` on the booleans only, and `Row.decisions` as
  an `Integer` set only above 1 (D1). Update the existing uses of the three-argument `Attempt`. Tests first in a new
  `RouterDecisionTest`:
  - append and index move;
  - `resolving` set again;
  - attempts recorded without moving;
  - limit and `exhaust`;
  - a plain fallback's row JSON without the new fields.
- [x] 1.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.config.ConfigPostProcessorTest" --tests "com.epam.aidial.core.server.data.RouterDecisionTest"` pass.

## 2. Re-decide

- [x] 2.1 `RouterDecisionClient.decide` takes the attempts. It always drops client `X-DIAL-ROUTER-ATTEMPTS` and
  `X-DIAL-ROUTER-NOTICE`, and sets the attempts header (non-ASCII escaped) when there are any (D6). Tests in
  `RouterDecisionClientTest`: the header present on a second decision, and a client-sent pair dropped.
- [x] 2.2 `redecide(whenExhausted)` with the key from the template and the decision's router and entry;
  `nextCandidateOrError(error, answer)` and `retryWithNextCandidate(status, reason, answerOriginal)`, recording an
  attempt only when moving on, refusing once the client is gone, with every call site passing its handler (D2).
  - Tests first in `RouterDecisionApiTest`:
    - "Router decides again with the history" (second key differs and has `executionPath: ["r1"]`, through `readKey`);
    - "Limit reached" (fixture router with `maxDecisions: 2`);
    - "Re-decide fails";
    - "Refusal in the second verdict skipped";
    - "Body sent again" (the `/decide-body` fixture router);
    - "Forged history dropped";
    - "Row of a re-decided request".
  - Rewrite `everyCandidateForbidden`: three decide requests, then `403`.
  - Rewrite `routerReEnteredThroughAnApplication`: the nested call gets `502` without a decide request of its own, and
    the outer request decides `maxDecisions` times before `502`.
- [x] 2.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest" --tests "com.epam.aidial.core.server.service.RouterDecisionClientTest"` pass.

## 3. Broken answers

- [x] 3.1 `retryWithNextCandidate` falls back after the upstream answered only under D3's conditions, resetting the
  upstream and clearing the client headers and chunked flag; `handleResponseError` tries a fallback first.
  - Tests first:
    - "Broken body before the client got a byte" (MockWebServer `SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY` on a
      non-streamed chat completion);
    - the same on Messages and on Responses;
    - "Broken stream before its first chunk" (headers sent, body withheld, then a disconnect; assert one
      `Content-Type` on the client);
    - a background Responses job whose body breaks is not retried.
  - Rewrite `bodyFailureAfterTheAnswerIsNotRetried` into the fallback test it now is.
- [x] 3.2 `RouterDecisionClient.notice` and the notice in `handleResponseError` (D4).
  - Tests first:
    - "Stream broken after the first chunk": a raw streaming client reads the first chunk, and the test polls
      `received` for the notice, because Core does not wait for it;
    - "Client gone, no notice";
    - in `RouterDecisionClientTest`, the notice's headers, empty body and no `API-KEY`.
- [x] 3.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest" --tests "com.epam.aidial.core.server.service.RouterDecisionClientTest"
  --tests "com.epam.aidial.core.server.Router*"` pass.

## 4. Docs and OpenAPI

- [x] 4.1 `./gradlew :openapi-generator:replaceSpec`; `maxDecisions` on `DeploymentInterface` is the only addition, and
  `./gradlew :openapi-generator:checkOpenApiDiff` passes.
- [x] 4.2 `docs/dynamic-settings/routers.md`:
  - `maxDecisions` in the decision entry fields;
  - a short "Fallback and re-decide" section: the three failure cases, the two headers, the notice request, and the
    three use cases (sticky conversation, cheap local fallback, adaptive fallback), kept brief;
  - the fallback text and the attempt reason list (`body`, `stream`), the failures table and the decision record.

  Verify each statement against a test.
- [x] 4.3 `./gradlew checkstyleMain checkstyleTest` passes and `npx -y @fission-ai/openspec@1.14.3 validate router-redecide --strict` passes.

## 5. Integration check

- [x] 5.1 `./gradlew :server:test --tests "com.epam.aidial.core.server.Router*" --tests
  "com.epam.aidial.core.server.Deployment*" --tests "com.epam.aidial.core.server.Responses*" --tests
  "com.epam.aidial.core.server.anthropic.*" --tests "com.epam.aidial.core.server.Messages*" --tests
  "com.epam.aidial.core.server.controller.*" --tests "com.epam.aidial.core.server.service.RouterDecisionClientTest"
  --tests "com.epam.aidial.core.server.data.RouterDecisionTest"` pass; the full suite runs in CI after the push.

## Workflow follow-up

- Write `review.md`, archive, `validate --specs --strict`, one commit staging explicit paths, push to PR #2232.
