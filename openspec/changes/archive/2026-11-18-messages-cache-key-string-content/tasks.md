# Tasks

Spec scenarios to test (`upstream-cache-affinity`, "A string and its text block are the same prefix"), both in
`MessagesApiTest`: "String message after its text block": `testStringMessagePinnedAfterItsTextBlock`. "String system
after its text block": `testStringSystemPinnedAfterItsTextBlock`. The requirement's sentence that a text block with
another field is a different prefix is covered by the unit test
`MessagesApiRequestTest.testBuildCacheKeys_textBlockWithExtraFieldDiffersFromString` (task 1.1).

## 1. A string hashes as its text block

- [x] 1.1 Tests first. `server/src/test/resources/aidial.config.json`: model `claude-cache-tiers` right after
  `claude-cache` (so the `ListingTest` order checks keep their models), with `interfaces.anthropicMessages.base_url`
  `http://localhost:4848`, `features.autoCachingSupported: true` and upstreams `up-a` (`key: keyA`, `tier: 0`) and
  `up-b` (`key: keyB`, `tier: 1`) (design D4). `MessagesApiTest`, the two scenario tests above: the first request
  carries `X-UPSTREAM-ID: up-b`, the mock answers `200` with `X-DIAL-CACHE-BREAKPOINT-PATH` (the scenario's path) and
  `X-DIAL-CACHE-EXPIRE-AT`, the test waits for the async pin write as `testAutoCachingPinsSecondTurnToSameUpstream`
  does, then sends the second request with the same API key and no `X-UPSTREAM-ID`, and asserts the
  `X-UPSTREAM-KEY` the mock received (`keyB` in both). `MessagesApiRequestTest`:
  `testBuildCacheKeys_stringContentMatchesTextBlock` (`content: "Hello"` against
  `[{"type": "text", "text": "Hello", "cache_control": {"type": "ephemeral"}}]`, followed by an assistant message:
  equal hashes at `messages[0].content[0]` and at `messages[1].content[0]`),
  `testBuildCacheKeys_stringSystemMatchesTextBlock` (`system: "Be brief"` against its one-block form: equal at
  `system[0]` and at `messages[0].content[0]`), `testBuildCacheKeys_textBlockWithExtraFieldDiffersFromString` (a
  block with `citations: []`: different at `messages[0].content[0]`). Verify the two string-message and string-system
  tests of each class fail before 1.2 (the integration tests see `keyA`).
- [x] 1.2 `MessagesApiRequest.java:updateExcludingCacheControl`: a textual node is turned into its text block and
  goes down the object path (design D1, D2). Verify with 1.4.
- [x] 1.3 `docs/dynamic-settings/models.md`, the `fieldsHashingOrder` paragraph on the hash: one sentence that on
  Anthropic Messages a string `content` or `system` hashes as its one-text-block form, so a client that sends the same
  text in either shape keeps its pinned upstream wherever Core looks the pin up: at every position with
  `autoCachingSupported`, only at positions with `cache_control` otherwise. Verify it against
  `testStringMessagePinnedAfterItsTextBlock` and `UpstreamCacheService.java:buildCacheBreakpointContext`.
- [x] 1.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.function.request.MessagesApiRequestTest" --tests
  "com.epam.aidial.core.server.MessagesApiTest" --tests "com.epam.aidial.core.server.ListingTest" --tests
  "com.epam.aidial.core.server.service.UpstreamCacheServiceTest"` pass (`ListingTest` reads the same config file).

## 2. The pin-lookup warning

- [x] 2.1 Test first. `UpstreamRouteProviderTest`, with a logback `ListAppender` on the `UpstreamRouteProvider` logger
  as `ProxyContextTest.testRespondWithNoContentDoesNotLogWarning` does: add to the existing
  `testGet_CachedUpstreamMissed` (entry with an id no upstream has) the assertion of one `WARN` event, and add one new
  test, `testGet_PinMissLogsNoWarning` (entry `CachedUpstreamEntry(null, null, "prefix", null)`: no `WARN` event).
  Verify the new test fails before 2.2.
- [x] 2.2 `UpstreamRouteProvider.java:get(String, List, int, UpstreamCacheContext)`: when no upstream matched, warn as
  today only if the entry has an id or an endpoint; otherwise `log.debug` with the entry's prefix path (design D3).
  Verify with 2.3.
- [x] 2.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.upstream.UpstreamRouteProviderTest" --tests
  "com.epam.aidial.core.server.upstream.UpstreamRouteTest"` pass, and `npx -y @fission-ai/openspec@1.14.3 validate
  messages-cache-key-string-content --strict` passes.

## Workflow follow-up

- No measurement task: the only cost is one small block and its sorted copy per string element, by inspection
  (design, Effect per request).
- Write `review.md`, archive, `validate --specs --strict`, one commit staging explicit paths.
