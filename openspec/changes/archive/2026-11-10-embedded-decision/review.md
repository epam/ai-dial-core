# Review: embedded-decision

## What was built

- **Router entry in the origin seam** (design D2, D3).
  - `OriginClient.embeddedForRouter`: no platform check, the call key and body tree passed in, no client watch, its
    future always settles.
  - `EmbeddedProxyRequest.RouterRequest` carries the call key and the tree.
  - The router attempt closes its `ChildPass` when the plugin's future fails or the answer body has been read, through
    a new `onBodyRead` hook on `EmbeddedProxyResponse`. It stays registered with the pass as a backstop.
  - `ChildPass.forRouter(..., callKey)`: calls carry that key; without a key the host is created closed (`401`).
- **Decide and notice through the seam** (D1, D7). `RouterDecisionClient.decide` and `notice` use
  `proxy.getOriginClient()`: the router entry when the interface names a plugin, `http` otherwise. They are retyped to
  `ProxyRequest`/`ProxyResponse`. The reset is registered when the request is created, but fires only once the
  deadline timer has set its flag. A request created after the deadline is reset at once.
- **The body** (D4, D5). `decideIfRouter` takes the controller's parsed tree; the three body-addressed controllers
  pass theirs and deployments-POST passes none. A new `RouterDecisionFlow.prepare`, on the virtual thread, does three
  things:
  - collects the decide key's attachments from that tree, so there is no second parse on the body-addressed APIs;
  - hands an embedded router `tree.deepCopy()`, or deployments-POST's own fresh parse;
  - for a redecide, parses the bytes for the plugin's copy.
- **In-process call** (D6). A child pass reaching a decision router serializes the call's tree once and makes it the
  original bytes:
  - in `prepare` on the body-addressed APIs;
  - in `DeploymentPostController.readRequestBody` on deployments-POST, on the task executor.

  The existing decision flow (decide body, redecide body, `retryWithNextCandidate` plus re-entry parse) then runs
  unchanged.
- **Validation** (D8, called D9 in design.md). `ConfigPostProcessor.validateDecision` no longer rejects an embedded
  interface on a decision router.
- **Docs.**
  - `docs/dynamic-settings/routers.md`: the embedded decide, its calls and key, the notice, the body hand-off and the
    in-process call.
  - `docs/dynamic-settings/plugins.md`: a "Deciding for a router" section.
  - Javadoc of `OriginHost.call`, `OriginHost.cancelled` and `OriginRequest`.

## Deviations from design.md and tasks.md

- **How the router attempt closes.** It closes through an `onBodyRead` hook on `EmbeddedProxyResponse`, run when
  `body()` has read the whole body or failed. Both router requests read their answer through `body()`. The design
  names the event and not the mechanism.
- **Late-call test.** The planned `X-Test-Decide-Late-Call` header was not added. The test calls through the host the
  provider records in `HOSTS`, as round 3 of the review suggested.
- **Test names** without two capitals in a row, for checkstyle's `AbbreviationAsWordInName`:
  `testPluginAsksModelWhileDeciding`, `testCallWhileAnsweringNotice`,
  `testPluginCallsDecisionRouterThatWantsTheBody`. tasks.md is updated.
- **Task 2.2's "a call still open fails" and "the end of the pass closes an attempt nothing else closed".** The first
  is proven at the API level by `testCallOpenWhenTheDecisionEnds`. The second by the unit check that the router attempt
  is registered with the pass; `closeChildPasses` closes the registered attempts, as before this change.
- **`OriginClientTest`** is a new test class, for the router entry's missing platform check against a custom
  application.
- **Upstream requests in `EmbeddedDecisionApiTest`** are identified by `X-DIAL-DEPLOYMENT-ID`. On the body-addressed
  APIs the upstream path names no deployment.

## Tests per scenario

router-deployment:
- Decision mode
  - "Decision mode on an interface entry rejected", "Decide URL per interface": the existing tests.
  - "Embedded interface next to an HTTP one": `EmbeddedDecisionApiTest.testEmbeddedInterfaceNextToHttpOne`.
- Decision router settings
  - The first two scenarios: `ConfigPostProcessorTest.testDecisionSettingsNeedDecisionMode`,
    `testDecisionRouterWithTranslatorRejected`.
  - "Embedded interface on a decision router accepted": `ConfigPostProcessorTest.testDecisionRouterWithEmbeddedInterfaceAccepted`,
    `testEmbeddedDecisionFixtureLoads` and `EmbeddedDecisionApiTest.testEmbeddedDecisionRouterListed`.
- Router identity through user info: the existing tests, plus `testEmbeddedRouterGetsTheCaller`.
- Decision by an embedded plugin: `testPluginDecides`, `testPluginRedecides`, `testNoticeToThePlugin`,
  `testClientGoneWhilePluginDecides`, plus `testEmbeddingsDecidedByThePlugin`.
- Embedded decision failures: `testPluginFails`, `testPluginWithoutAnEntry`, `testPluginTooSlow` (with a stalled body),
  `testPluginRefusesTheRequest`. Unit level: `RouterDecisionClientTest.noCancelAfterTheRouterAnswered`,
  `cancelAtTheDeadline`, `cancelWhenTheBodyStallsPastTheDeadline`, `requestCreatedAfterTheDeadlineIsReset`.
- Body of an embedded decide request: `testBodyAndTree` (deployments-POST, chat completions, Messages, Responses),
  `testPluginChangesItsTree`, `testNoBodyWithoutTheSetting`.
- Calls of an embedded router while it decides: `testPluginAsksModelWhileDeciding`, `testCallAfterTheDecision`,
  `testCallOpenWhenTheDecisionEnds`, `testCallWhileAnsweringNotice`.
- Decide request of an in-process call: `testPluginCallsDecisionRouterThatWantsTheBody`,
  `testFallbackInsideAnInProcessCall`, `testInProcessCallOnDeploymentsPost`, plus `testInProcessCallToAnEmbeddedRouter`.

deployment-origin:
- Calling a deployment from an origin plugin, and Calls end with the plugin's pass, for a deployment's interface: the
  existing `EmbeddedCallApiTest` tests.
- The decide case: `testPluginAsksModelWhileDeciding`, `testCallAfterTheDecision`, `testCallOpenWhenTheDecisionEnds`.
- Client gone before the plugin answers: the existing tests, plus `testClientGoneWhilePluginDecides` for the decide
  case.

Attachment grants from the controller's tree (D5): `RouterDecisionApiTest.decideKeyReadsTheAttachmentsOnTheBodyAddressedApis`
covers chat completions and Responses. Messages collects no attachments.

## Mutation checks

Each broke the code on purpose, ran the named tests, and restored the code.
- The router attempt's close on body read disabled: `testCallAfterTheDecision` and `testCallOpenWhenTheDecisionEnds`
  fail. Their first version observed only after the pass ended, where the pass's own close also stops the calls. They
  passed with the mutant, so both were rewritten to observe while the chosen model is still being served.
- The in-process call's serialization not made the original bytes: `testFallbackInsideAnInProcessCall` fails.

## Runs

- Checkstyle (`checkstyleMain checkstyleTest`): clean.
- OpenSpec `validate --all --strict`: the change is valid. The only failures are `resource-subscription` and
  `user-limits`, whose placeholder Purpose comes from `development`.
- Each group's classes passed in that group's run:
  - group 2: `ChildPassTest` 13, `EmbeddedProxyRequestTest` 19, `OriginClientTest` 2, `EmbeddedCallApiTest` 21,
    `EmbeddedOriginApiTest` 36;
  - group 3: `ConfigPostProcessorTest` 90, `ConfigRouterTest` 20, `RouterDecisionClientTest` 17,
    `RouterDecisionApiTest` 68 (HTTP tests unchanged);
  - group 4: `RouterDecisionApiTest` 69, `RouterApiTest` 23, `MessagesApiTest` 20, `ResponsesApiTest` 8,
    `EmbeddedDecisionApiTest` 23, `EmbeddedCallApiTest` 21.
- The run of all of them together (task 5.1) was stopped by the host for low memory. Before that it had passed
  `EmbeddedDecisionApiTest` 23/23 and was partway through `MessagesApiTest`, with no failure. CI ran the full
  suite on `05f556ba8` and it passed (PR Workflow run 42790150447: build, tests, code and style checks), which
  closes task 5.1.
- One combined run earlier hit `RejectedExecutionException: event executor terminated` in a `RouterDecisionApiTest`
  test. Its source was `testClientGoneWhilePluginDecides`: Core went on to serve the chosen model after the test ended
  and shut Core down. The test now waits for that pass to finish. The class has run clean since, with no shutdown
  error in the log.

## Measurement

Task 5.2 is deferred. It needs the perf environment and the router plugin answering `POST /decide`, which is in the
router's repository. The expected effects are in design.md's table and are not measured.

## Open

- The router plugin's `/decide` answer: a follow-up in the router's repository, using
  `strategy.select(rc, identity(request.caller()))`.
- `OriginRequest.body` for a pass whose client bytes no longer match its tree, once FILTER plugins exist (design.md
  Open Questions).

## PR review, round 1 (after the archive)

A review of the whole PR against `development` found two defects, both fixed:
- **A fallback did not end the failed candidate's calls.** When the origin plugin serving a candidate failed,
  `RouterDecisionFlow.retryWithNextCandidate` invalidated the candidate's key and cleared the pass's proxy key, but
  left the plugin's attempt open (plugin-close D2 keeps it open until the pass ends). A late call through its host
  then threw an NPE while the key was null, or ran with the next candidate's key once it was set. The fallback now
  closes the pass's child passes next to the key invalidation, as the decide attempt already does at its end.
  `EmbeddedDecisionApiTest.testFallbackEndsTheFailedCandidatesCalls`; deployment-origin "Calls end with the plugin's
  pass".
- **A plugin future that succeeds with `null` hung the request.** `EmbeddedProxyRequest.send` now fails the attempt,
  as for a plugin that throws. `EmbeddedOriginApiTest.testPluginAnswersNull`; deployment-origin "Embedded origin
  failure".

## PR review, round 2

Fixed:
- **A skipped candidate in an in-process call re-entered with the call's live tree.** On a refusal before anything
  was sent (`nextCandidateOrError`), the pass's request body was still unset, so the re-entry took the call's tree, not
  the serialization D6 makes, and reset the body's read time. `nextCandidateOrError` now resets the request body to
  the original bytes, as `retryWithNextCandidate` does; for a pass with client bytes this changes nothing. Only `model`
  changes before such a refusal today, so this closes a latent gap.
  `EmbeddedDecisionApiTest.testSkipInsideAnInProcessCall`; router-deployment "Skip inside an in-process call".
- **The `OriginHost.cancelled()` Javadoc** said a decide plugin that answered is never told. D3 and
  `testPluginTooSlow` tell a plugin that sent a status and then stalled its body past the deadline. The Javadoc,
  `plugins.md` and the router-deployment requirement now say "has not finished its answer".

Rejected:
- A plugin answer whose `body()` is `null`. It breaks the `OriginResponse` contract, as a `null` `headers()` or a
  throwing `statusCode()` would. Core guards its own seam, the future's value (round 1), not each method of the
  plugin's answer. The decide still fails with `502`, and the pass's end closes the attempt.
- A notice's plugin instance is given back when its answer settles, before its body is read. D3 records this; a
  notice answer is `{}`, and a close needs a settings change in that window, whose effect is a notice failure, which
  is logged and ignored.
- `ChildPass` keeps each call's response until the attempt closes, and `closeChildPasses` keeps the closed attempts
  until the pass ends. Today's plugins make at most one call per attempt; bound the list when a plugin makes many.
