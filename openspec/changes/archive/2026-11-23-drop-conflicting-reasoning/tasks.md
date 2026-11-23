# Tasks

Spec scenarios (`responses-api`), all in `ResponsesApiTest`: "Echoed reasoning goes back to its upstream":
`testEncryptedContentAffinityPinsSecondTurnToSameUpstream`. "Stamped upstream no longer configured":
`testUnavailableUpstreamEncryptedContentAffinityRejected`. "Reasoning from two upstreams":
`testReasoningFromAnotherUpstreamDropped`.

## 1. Drop reasoning items from another upstream

- [x] 1.1 Tests: `ResponsesApiTest.testReasoningFromAnotherUpstreamDropped` replaces
  `testConflictingEncryptedContentAffinityRejected`; `EncryptedContentAffinityUtilTest.reasoningItemFromAnotherUpstreamIsDropped`.
- [x] 1.2 `EncryptedContentAffinityUtil.java:resolveAndUnwrap` removes the items and logs; the conflict `400` and
  `conflictingAffinityException` are removed.
- [x] 1.3 `./gradlew :server:test --tests "com.epam.aidial.core.server.util.EncryptedContentAffinityUtilTest" --tests
  "com.epam.aidial.core.server.ResponsesApiTest"` passes; checkstyle is left to the reviewer.
