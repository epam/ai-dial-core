# Tasks

## 1. Config field

- [ ] 1.1 Compare `design.md` with the code on `development`; on a drift, correct the decision or the Code map entry in
      place
- [ ] 1.2 D3: `Model.sessionAffinity` (boolean, default `false`, alias `session_affinity`)
- [ ] 1.3 Test models in `server/src/test/resources/aidial.config.json`: four upstreams `up-a` to `up-d` with distinct
      keys; one upstream in tier 0 and two in tier 1; two upstreams with `autoCachingSupported: true` that serve
      Messages and Responses; each with `sessionAffinity: true`
- [ ] 1.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*UpstreamRouteProviderTest"`

## 2. Session pick

- [ ] 2.1 Tests first. `TieredBalancerTest`: "Shares follow weights" (`testSessionSharesFollowWeights`), "Added
      upstream takes only its share" (`testAddedUpstreamTakesOnlyItsShare`), plus a unit case for a cooled-down
      upstream (`testSessionMovesOnlyFromCooledDownUpstream`). `UpstreamRouteProviderTest`: "Same pick on a new
      balancer" (`testSessionPickSameOnNewProvider`), "No session id" (`testNoSessionIdUsesRandomPick`), "Field not
      set" (`testSessionIgnoredWhenAffinityOff`), the last two with a fixed `Random`. `UpstreamRouteTest`: the cache
      entry before the session pick (`testCachedUpstreamWinsOverSessionPick`) and the retry through the balancer
      (`testRetryAfterSessionPickUsesBalancer`)
- [ ] 2.2 D1, D2, D6: the score from SHA-256 and the synchronized session pick in `TieredBalancer` over the same
      candidates as `next`, with its fallback when no upstream is available
- [ ] 2.3 D5: `UpstreamRoute.next` (first attempt only, after the cache entry) and `UpstreamRouteProvider.get` (the
      session id only for a `Model` with `sessionAffinity` and no upstream id); the digests run before the lock
- [ ] 2.4 Measurement: a timing loop of 1,000,000 session picks over 20 upstreams, against the same number of random
      picks; the result goes to `review.md`
- [ ] 2.5 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*TieredBalancerTest"
      --tests "*UpstreamRouteTest" --tests "*UpstreamRouteProviderTest"`

## 3. Controllers

- [ ] 3.1 Tests first. New `UpstreamSessionAffinityApiTest` (full stack, `MockWebServer`, upstreams told apart by
      `X-UPSTREAM-KEY`): "Requests of one session with different last messages"
      (`testSessionRequestsReachSameUpstream`), "The value decides, not the header name"
      (`testSessionIdFromAnyListedHeader`), "Lower tier only when the higher one is unavailable"
      (`testSessionPickUsesLowerTierWhenHigherFails`), "Upstream cooling down"
      (`testSessionMovesToNextChoiceDuringCoolDown`), "X-UPSTREAM-ID wins" (`testUpstreamIdHeaderWinsOverSession`),
      "Retry after the session's upstream fails" (`testRetryAfterSessionUpstreamFails`). `MessagesApiTest`: "Cache
      entry wins" (`testCacheEntryWinsOverSessionPick`), "Cache entry written for the session's upstream"
      (`testCacheEntryWrittenForSessionUpstream`). `ResponsesApiTest`: "Stamped reasoning item wins"
      (`testReasoningItemWinsOverSessionPick`)
- [ ] 3.2 D4, D5: `BaseChatCompletionController.processChatCompletionsRequestBody`,
      `ResponsesController.handleRequestBody` and `MessagesBaseController.prepareUpstreamRoute` resolve the session id
      with `GenAiTraceAttributes.resolveConversationId` and pass it
- [ ] 3.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*UpstreamSessionAffinityApiTest"
      --tests "*MessagesApiTest" --tests "*ResponsesApiTest"`

## 4. Docs

- [ ] 4.1 `docs/dynamic-settings/models.md`: `sessionAffinity` - what it does, where the session id comes from, the
      precedence of the pins, and that every Core instance must run this version before the field is set
- [ ] 4.2 `docs/tracing.md`: `conversationIdHeaders` also feeds `sessionAffinity`, so an empty list turns it off
- [ ] 4.3 After the push, check that CI regenerated `docs/open_api_core.yaml` with `sessionAffinity` in the `Model`
      schema (deferred: needs the CI run)
- [ ] 4.4 `./gradlew checkstyleMain checkstyleTest`, then
      `npx -y @fission-ai/openspec@1.14.3 validate upstream-session-affinity --strict`
