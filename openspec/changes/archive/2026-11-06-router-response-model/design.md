# Design

## Context

See proposal.md, Why. Where Core already parses a decided request's answer:
- **Streamed chat, Responses and Messages answers.** `BufferingReadStream.BaseEventListener.handle` parses every SSE
  event's data, runs the controller's `BaseResponseFunction` list on the tree, and writes the tree back
  (`to(event, tree)`). This applies when the list is not empty, which is true for all three controllers and for both
  interceptor controllers (`createListener`).
- **Buffered chat completion.** `BaseDeploymentPostController.maybeInjectUsagePerModel` parses and re-serializes it
  to set `statistics.usage_per_model` (`BaseChatCompletionController.handleNonStreamingChatCompletionResponse`).
- **Buffered Responses answer.** `ResponsesController.rewriteResponseId` parses and re-serializes a `200` answer.
- **Buffered Messages answer.** `MessagesController.handleNonStreamingResponse` forwards the bytes. Only
  `MessagesTokenUsageParser.parse` reads them, into its own tree.
- **Non-SSE bodies.** `BaseDeploymentPostController.createResponseStream` attaches no listener, so the bytes pass
  through. That covers embeddings, a legacy buffered completion, and a buffered answer relayed through an interceptor
  chain.

## Goals / Non-Goals

Goals: Router name in the answer, at no cost to requests that are not decided.

Non-goals: rewriting pass-through bodies, and response headers.

## Decisions

### 1. One response function, added only for a decided request

`RouterModelFn` (a `BaseResponseFunction`) replaces a textual `model` on the root, on `response` and on `message`,
and adds none. The controllers put it first in their stream lists when `context.getDecision()` is set, so the
collectors after it see the same tree the client gets. Its static `rewrite(JsonNode, String)` serves the buffered
paths.

Rejected alternative: a byte-level replace of `"model":"..."` in pass-through bodies. It is cheap, but it is fragile
with nested `model` keys (a tool call's arguments, `usage_per_model`) and escaped text.

### 2. Buffered rewrites reuse the parse that exists

- Chat completions: `maybeInjectUsagePerModel` calls `rewrite` on the tree it already has.
- Responses: `rewriteResponseId` calls `rewrite` on the tree it already has.
- Messages: a decided `200` answer is parsed, rewritten and serialized once in `handleNonStreamingResponse`.

  Rejected alternative: sharing that tree with `MessagesTokenUsageParser`. That changes the parser's interface for one
  call site.

### 3. Pass-through bodies keep the upstream's `model`

Rewriting them means buffering and parsing the whole body. Measured with Jackson on this machine, on a
`readTree`, `put` and `writeValueAsBytes` loop after warm-up:

| Body | Cost |
|---|---|
| Embeddings, 1 × 1536 floats (17 KB) | 0.9 ms |
| Embeddings, 16 × 1536 floats (275 KB) | 5.7 ms |

That is too much for a field. A buffered answer through an interceptor chain is also an interceptor's own answer.

### 4. A stored Responses answer remembers its router

`ResponseItemController` serves `GET /openai/v1/responses/{id}`, and the resumed stream, without a decision. The
response mapping therefore gets a nullable `router`, written only when set, so a mapping that was not decided is
unchanged. `ResponseItemController.rewriteId` and `collectAndForwardStreaming` rewrite with it.

A pod without this change fails to parse a mapping that carries `router`, because the mapper rejects unknown fields.
Only responses created through a router carry it, and routers are new in this release, so this matters only while
pods of both builds of this PR run.

## Per-request cost

| What | Request not decided | Decided request |
|---|---|---|
| Core passes | none | none |
| Parses of a body Core only forwards | none | none, except a buffered Messages answer: one parse |
| Serializations | none | one for a buffered Messages answer: 6 µs at 2 KB, 139 µs at 20 KB |
| Per streamed event | none | one `ObjectNode.put` on a tree Core already parses and writes |
| Redis or blob | none | none |

The measurement is a micro-benchmark in the session scratchpad, not a perf env run. No perf env run is needed.

## Risks / Trade-offs

- [A client that read the served deployment from `model`] → It reads the router's name now. The serving deployment
  stays in analytics. This is the router design's expectation.
- [The rule has holes: embeddings and buffered answers through interceptors] → The holes are stated in the spec and
  the docs. They can close later with a streaming JSON copy if a consumer needs them.

## Migration Plan

None. The behaviour is new in this PR and unreleased.
