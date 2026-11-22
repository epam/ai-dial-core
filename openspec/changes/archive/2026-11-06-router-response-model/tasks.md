# Tasks

Spec scenario to test, all in `RouterDecisionApiTest`:
- "Buffered chat answer names the router": `bufferedChatAnswerNamesTheRouter`.
- "Streamed chat answer names the router": `streamedChatAnswerNamesTheRouter`.
- "Responses answer names the router": `responsesAnswerNamesTheRouter`.
- "Streamed Messages answer names the router": `streamedMessagesAnswerNamesTheRouter`.
- "Stored Responses answer read back names the router": `storedResponseReadBackNamesTheRouter`.
- "Buffered Messages answer names the router": `bufferedMessagesAnswerNamesTheRouter`.
- "Streamed answer through the candidate's interceptor names the router":
  `streamedAnswerThroughTheCandidatesInterceptorNamesTheRouter`.
- "Embeddings keep the upstream model": `embeddingsKeepTheUpstreamModel`.
- Also `streamedResponsesAnswerNamesTheRouter`, for `response.model` inside events.
- "Undecided answer unchanged": `undecidedAnswerUnchanged`.

## 1. Rewrite

- [x] 1.1 `RouterModelFn` with a static `rewrite(JsonNode, String)` that replaces a textual `model` on the root,
  `response` and `message` (design D1).
- [x] 1.2 Put it first in the stream function lists of `BaseChatCompletionController`, `ResponsesController`,
  `MessagesController`, `ChatCompletionInterceptorController` and `ResponsesInterceptorController` when the context
  has a decision (D1).
- [x] 1.6 `ResponseMapping.router`, written where the mapping is saved and read by `ResponseItemController` for a
  stored answer (D4). Test: `storedResponseReadBackNamesTheRouter`.
- [x] 1.3 Buffered paths: `maybeInjectUsagePerModel` and `rewriteResponseId` call `rewrite`, and `MessagesController`
  rewrites a decided `200` answer (D2).
- [x] 1.4 Tests, written first, for each scenario above.
- [x] 1.5 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest"`.

## 2. Docs and checks

- [x] 2.1 `docs/dynamic-settings/routers.md`: which answers carry the router name, in a few lines.
- [x] 2.2 Measurement: the cost table in design.md, from the session micro-benchmark. No perf env run.
- [x] 2.3 Full module suite `./gradlew :server:test`, checkstyle, and
  `npx -y @fission-ai/openspec@1.14.3 validate router-response-model --strict`.

## Workflow follow-up

- `review.md`, archive, `validate --specs --strict`, one commit into PR #2232.
