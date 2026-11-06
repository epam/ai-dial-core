# Proposal

## Why

A client that calls router `r1` gets an answer whose `model` names the deployment that served, often its upstream
override name (`gpt-4-real`). The router design leaves this as its first open question and expects a router to follow
the alias rule: the client sees the id it called, and analytics still shows the chosen deployment. The user chose the
router id, provided the rewrite is cheap.

## What Changes

- For a request served through a decision, Core sets the answer's `model` to the router's name on the answers it
  already parses:
  - chat completions, streamed and buffered, and streamed legacy completions;
  - Responses, streamed and buffered, including `response.model` in events, and a stored response read back with
    `GET /openai/v1/responses/{id}`, because its response mapping records the router;
  - Messages, streamed (`message.model` in `message_start`) and buffered.
  A streamed answer relayed through the chosen deployment's interceptors is rewritten too. Core replaces a `model`
  that is there and adds none.
- Not rewritten, because Core passes these bodies through byte for byte and a rewrite would mean buffering and
  parsing them:
  - embeddings;
  - the legacy `/completions` buffered answer;
  - a buffered answer relayed through the chosen deployment's interceptors.
- Analytics still names the deployment that served: the serving row's `deployment` and the router row's
  `decision.deployment` are unchanged. A logged response body may carry either name, depending on where Core
  rewrites it.
- **Docs.** `docs/dynamic-settings/routers.md` says which answers carry the router name.

Out of scope: response headers, and any rewrite for requests that are not decided.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `router-deployment`: "Router name in the answer" is added.

## Impact

Motivated by the router design (documentation MR 211, `design/router_resource.md`, open question 1, and "Response,
limits and analytics"). It answers that question with the router id for the answers listed above and leaves the
pass-through bodies alone. It ships in PR #2232 and builds on `router-decision-mode` and `router-analytics-hop`.

Code:
- a new response function that replaces `model`;
- the stream function lists of the chat completions, Responses and Messages controllers and of the two interceptor
  controllers;
- the buffered rewrites in `maybeInjectUsagePerModel` and `ResponsesController.rewriteResponseId`, and a new one in
  `MessagesController`;
- `ResponseMapping.router`, written by `rewriteResponseId` and `ReplaceResponseIdFn`, and read by
  `ResponseItemController` for a stored answer.

Cost: none for requests that are not decided. A decided request pays one field write per parsed event or answer.
A buffered Messages answer pays one parse and one serialization: measured at about 6 µs for 2 KB and 140 µs for 20 KB
for the serialization. Embeddings would have cost 0.9 ms for one 1536-float vector and 5.7 ms for 16, which is why they
are left out.
