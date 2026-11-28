# Tasks

## 1. Received bytes on a direct pass

- [x] 1.1 `ReceivedBody` with the strict parse, the model range and the UTF-8 check (design D2), immutable with
  `withModel`, `withoutBytes`, `serialize` and a `toString` without the body; `ReceivedBodyTest` (the splice, a body
  without a model string, a change, a body past the parser's 8000-byte buffer with a 4-byte char across its edge) and
  the parse tests in `JsonBodyRequestTest` (repeated keys, invalid UTF-8 past the first 8 KB, content around the
  object, malformed JSON parsed once).
- [x] 1.2 `JsonBodyRequest` under the three request classes: `setModel` splices, `applyDefaults`,
  `clearInterceptorSettings` and the encrypted-content unwrap drop the bytes on a change (design D1, D3);
  `JsonBodyRequestTest`, `ResponsesApiRequestTest`, `EncryptedContentAffinityUtilTest`.
- [x] 1.3 The enhancement functions report whether they changed the body (`ApplyDefaultDeploymentSettingsFn`,
  `ResolveEncryptedContentAffinityFn`); `ApplyDefaultDeploymentSettingsFnTest`.
- [x] 1.4 The parse sites build the request from `ReceivedBody` (`ProxyUtil.parseRequestBody`,
  `BaseInterceptorController.parseBody`), and `setProcessedRequestBody` sets the body from `serialize()` on every pass
  (design D1); scenarios "Body unchanged" (`ResponsesApiTest.testReceivedBytesForwardedAsTheyAre`,
  `ChatCompletionApiTest.testChatCompletions_Success`), "Override name replaces only the model"
  (`MessagesApiTest.testOverrideNameSplicedIntoReceivedBytesAfterMultiByteText`) and "Repeated key"
  (`ChatCompletionApiTest.testChatCompletions_RepeatedKeySerialized`).
- [x] 1.5 `RequestObject.getTree()` replaced by `getBody()`; its readers take `getBody().tree()`.
- [x] 1.6 Run `./gradlew checkstyleMain checkstyleTest` and the touched test classes with `--tests`.

## 2. In-process hops

- [x] 2.1 The context's call body and plugin body hold a `ReceivedBody` (`ProxyContext`, `ProxyUtil.parseRequestBody`,
  `BaseInterceptorController`, `DeploymentPostController.readRequestBody`); `ProxyContextTest`,
  `GenAiTraceAttributesTest`, `EmbeddedProxyRequestTest`.
- [x] 2.2 The alias pass hands its body to the alias-hop host, which gives it to the target pass for that very tree,
  once (design D4: `EmbeddedProxyRequest.send`, `VertxHostDelegate.forAliasHop`, `callBody`);
  `VertxHostDelegateTest.testAliasHopHandsOnTheAliasPassBodyForItsTreeOnly`,
  `JsonBodyRequestTest.testGetBody_handsTheBytesAndTheModelOnToTheNextPass` and `testGetBody_handsOnChangesAsTheTreeSerialized`.
- [x] 2.3 Scenarios "Alias target served in process"
  (`AliasInProcessApiTest.aliasTargetGetsTheClientBytesWithTheModelReplaced`), "Change on the way"
  (`aliasTargetGetsTheBodySerializedAfterChangesOnTheWay`) and "Router candidate behind an alias"
  (`routerCandidateBehindAnAliasGetsTheClientBytesWithTheModelReplaced`).
- [x] 2.4 Scenarios "Embedded origin" (`EmbeddedOriginApiTest.testPluginGetsTheClientBytes`,
  `testPluginBehindAnAliasGetsTheClientBytesWithTheModelReplaced`) and "Call from an origin plugin"
  (`EmbeddedCallApiTest.testCallBodyIsTheTreeSerialized`).
- [x] 2.5 Run `./gradlew checkstyleMain checkstyleTest` and the touched test classes with `--tests`.
- [x] 2.6 The prefix hash leaves the body as received (`JsonUtil.sort` copies arrays). Scenario "Serialized body keeps
  the client's member order" (`MessagesApiTest.testSerializedBodyKeepsTheClientsMemberOrder`,
  `MessagesApiRequestTest.testBuildCacheKeys_leavesTheBodyAsReceived`).

## 3. Existing tests

- [x] 3.1 Tests that compared a forwarded body with Core's compact layout compare the JSON or the client's bytes
  (`CustomApplicationApiTest`).
- [x] 3.2 Run the API test classes of the touched paths (aliases, routers, embedded origins and calls, Messages,
  Responses, chat completions, interceptors, deployments-POST) under the shared Redis port lock.

## 4. Docs

- [x] 4.1 `docs/dynamic-settings/models.md` (`overrideName`) and `docs/dynamic-settings/aliases.md` (the in-process
  second pass): the body an origin receives.

## 5. Measurement

- [x] 5.1 Core CPU per request, A/B against `development`, on the in-process alias, router and plugin-served model
  chain with large bodies. Measured 2026-11-21: 2,000 users, Anthropic Messages with a 650 KB mean body, `development`
  and an image with this change and three other perf changes on the same two nodes, JFR ms of Core CPU per user
  request. Tier alias → router → alias → model: serialization 7.39 → 0.25, parse 3.36 → 4.32 (the strict parse),
  net −6.2 of 67.0 ms. Model called directly: serialization 6.68 → 0.15, parse 2.95 → 3.89, net −5.6 of 45.0 ms.
