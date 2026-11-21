# Tasks

## 1. Fallback from a copy of the tree

- [x] 1.1 Test first: `RouterDecisionApiTest.fallbackThroughAnAliasStartsFromTheClientsBody` for the scenario
  "Fallback inside an alias call" (a model `m-defaults` with Messages defaults in `router-decision.json`). The other
  scenarios keep their tests: `EmbeddedDecisionApiTest.testPluginCallsDecisionRouterThatWantsTheBody`,
  `testFallbackInsideAnInProcessCall`, `testSkipInsideAnInProcessCall`, `testInProcessCallOnDeploymentsPost`, and
  `RouterDecisionApiTest.aliasOverDecisionRouterThatWantsTheBody`.
- [x] 1.2 `RouterDecisionFlow.prepare`: serialize the call tree only with `decision.body`; otherwise keep a copy on
  `RouterDecision.clientTree`. `decideWith` reads the body future only with `decision.body`.
- [x] 1.3 `RouterDecisionFlow.resetCandidateOutcome`: with a copy kept, clear the request body and set a fresh copy as
  the call tree, so the re-entry's parse takes it.
- [x] 1.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*.RouterDecisionApiTest"
  --tests "*.EmbeddedDecisionApiTest" --tests "*.AliasInProcessApiTest" --tests "*.RouterDecisionTest"`.
- [ ] 1.5 Measure Core CPU per request through a tier alias against the same load before the change (deferred: needs
  the perf environment).
