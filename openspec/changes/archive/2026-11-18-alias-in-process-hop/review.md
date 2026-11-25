# Review

## Scope and findings

Two reviews by a reviewer without the session's context: one of the proposal before any code, one of the
implementation (`91132ecd1`) against the spec deltas, the design, `docs/CODE_STYLE.md` and the code paths it touches.

Proposal review, eleven findings, all confirmed against the code and folded into the artifacts before the code:

- The target pass in process would have been a plugin child pass, whose row has no bodies, so the model's row would
  have lost them. Settled with the author: an alias pass's row has no bodies, every other row keeps them, in process or
  not; the analytics-log rule for plugin child rows is removed (a REMOVED requirement), so a plugin's call logs its
  bodies in two rows.
- Messages `count_tokens` through an alias would have broken (not an in-process route), and a body check that disagreed
  with the transport check could loop. One check now decides both.
- The model's `gen_ai.*` attributes would have been on no span: the target pass writes them on the alias's span.
- Attachment grants of the target now reach the alias's caller, as on a direct call: a deliberate difference with its
  own requirement.
- Header differences recorded (`X-DIAL-OVERRIDE-NAME` dropped; duplicate CORS and trace id headers gone); cost claims
  corrected (a decision-router target serializes a stand-in); `/openai/responses` added; the router-deployment
  requirement on in-process decide requests extended to alias hops; tasks completed.

Implementation review, ten findings:

- Fixed (major): an alias reached by an origin plugin's in-process call runs under the plugin's span, and its target
  pass wrote its span attributes on it. `VertxHostDelegate.writesOnAliasSpan` marks the target pass only when the alias
  pass has a server span of its own, or is itself a marked target pass (an alias candidate of a router behind an
  alias). Test: `VertxHostDelegateTest.testAliasTargetWritesOnTheAliasSpanUnlessItIsThePluginsSpan`.
- Fixed: when the client left before an in-process alias candidate's target answered, the pass ended in
  `EmbeddedProxyRequest`'s client-gone branch with no finalize, so no router row was written (router-deployment
  "Decision record"); over HTTP it was. An alias hop's failure now goes to the alias pass's connect-failure path, which
  tries no other candidate for a closed client and whose finalize writes the router row, ends the span and deletes the
  key. Test: `RouterDecisionApiTest.clientGoneBeforeAliasCandidateAnswersKeepsTheRouterRow`. Design D8.
- Fixed: the transport check resolved the request URI on every pass. It now reads the request's path (which classifies
  as the target's path: an override path keeps the action, `count_tokens` stays `count_tokens`) after the
  `instanceof AliasPass` test, and the send takes the base url from `DeploymentEndpointUtil.resolveServingEndpoint`.
- Fixed: `EmbeddedProxyRequest.aliasHop` is final, set through the constructor chain.
- Fixed in the artifacts and docs: a target pass that refuses the call before its origin (limit, access, interface)
  writes no row, as on a direct call, so such a call through an alias logs no bodies, where the alias row carried them
  before. Not changed in behaviour: keeping bodies on alias rows with an error status would bring back part of what
  the author chose to drop; stated in the analytics-log delta, the proposal and `aliases.md`.
- Fixed in the docs and design: no span attribute carries cost; the alias's `dial.latency.*`,
  `dial.upstream.attempts`, Responses `gen_ai.response.id` and later log-line keys stay on its span, as the alias
  writes after its target answered.
- Fixed: stale javadocs and comments (`AnalyticsLogContext.from`, `VertxHostDelegate`, `EmbeddedProxyRequest`,
  `ProxyContext.callTree`, `Proxy.dispatch`, `RouterDecisionFlow.inProcessCall`, `ProxyUtil.parseRequestBody`,
  `setProcessedRequestBody`, `EmbeddedServerRequest`), the `ProxyClientTest` comment, and the fourth difference from a
  direct call in `aliases.md` (a client gone before the answer cancels the target pass at once).
- Fixed: a test of the layout's alias-target branch
  (`AutoEnrichedOtelJsonLayoutTest.aliasTargetPassEnrichesTheCurrentSpan`, `pluginCallPassLeavesTheCurrentSpanAlone`).
- Not changed (nits): the duplicate `X-DIAL-TRACE-ID` / `X-DIAL-SPAN-ID` headers are not tested, as the API tests run
  without an OTel SDK (the scenario's `Access-Control-Allow-Origin` part is); four tests pass on `development` too and
  stay as regression guards for behaviour the change must keep.

Found while implementing: the behaviour tests are in `AliasApiTest` at the default `backgroundJob.localBaseUrl`, so
that they also run on `development`; `AliasInProcessApiTest` points Core's own address at a closed port to prove no
loopback. On a scratch checkout of `development` (f61fff688) the new tests gave: 9 failed as the spec says (no
loopback x3, `X-DIAL-OVERRIDE-NAME: gpt-4-internal` at the model, `access-control-allow-origin=[*, *]`, the attachment
read `403`, bodies in the alias row x2, no body in a plugin child row), the rest passed.

Request cost, per in-process alias call (design, Effect per request): one HTTP request into Core and one Redis GET
fewer, one parse fewer, one serialize fewer unless the target is a decision router, and one row's bodies fewer. Not
measured: task 4.1 needs the perf environment.

## Verification

- `npx -y @fission-ai/openspec@1.14.3 validate alias-in-process-hop --strict`: valid.
- `./gradlew checkstyleMain checkstyleTest`: passed.
- Before the implementation-review fixes, `./gradlew :server:test` passed for 15 classes, 419 tests: `AliasApiTest`
  (33), `AliasInProcessApiTest` (4), `AliasPublicApiTest` (20), `AliasPlatformApiTest` (12), `AliasReloadApiTest` (1),
  `RouterDecisionApiTest` (92), `EmbeddedCallApiTest` (29), `EmbeddedDecisionApiTest` (26), `EmbeddedOriginApiTest`
  (51), `MessagesApiTest` (20), `ResponsesApiTest` (8), `ProxyClientTest` (3), `GfLogStoreTest` (53),
  `GenAiTraceAttributesTest` (44), `AutoEnrichedOtelJsonLayoutTest` (23).
- After the fixes, checkstyle passed and `./gradlew :server:test` passed for 17 classes, 452 tests: the 15 above
  (`RouterDecisionApiTest` 93, `AutoEnrichedOtelJsonLayoutTest` 25), `VertxHostDelegateTest` (13) and
  `EmbeddedProxyRequestTest` (17). The two runs before it failed on test code only: a mock stubbed inside another
  stubbing, and a variable name checkstyle refuses.
- After the archive, `validate --specs --strict` flagged the merged "Bodies in the analytics row" over 500 characters;
  the sentence on in-process passes, which the scenario "Call from an origin plugin" states, was dropped in the main
  spec and here. All specs pass except `resource-subscription`, whose placeholder Purpose predates this change.
- Task 4.1 (perf run) is deferred: it needs the perf environment.
