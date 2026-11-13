# Design

## Context

`ChildPass.call` serves a plugin's call as a child pass: a `ProxyContext` built on the key record of the plugin's
pass (`parent.getProxyApiKeyData()`, or the decide key for a router's plugin). `ApiKeyData.initFromContext` builds
that record's `executionPath`: the path of the incoming key, plus the router's name for a decided request, plus the
deployment's name. So the path grows by at least one name with every pass of a chain, and it is already in the
record `ChildPass.call` holds. No lookup is needed.

`RouterDecisionFlow.decideWith` already uses the path the same way, to refuse a router that is re-entered.

## Decisions

### D1. Bound the depth, not the names

`ChildPass.call` answers `400` when `executionPath.size() >= MAX_CALL_DEPTH` (32), before it builds the child
request. The answer is made like the `401` of a closed attempt: a `ChildServerResponse` with the status and the
message, and no dispatch.

Rejected: refusing a call whose target is already in the path. A translator plugin calls its own deployment on the
`out` interface, and an interceptor's call returns to the deployment it fronts. Both repeat a name and are correct.

Rejected: a guard after routing, in the controllers. The target of a `/openai/v1/responses` call is named in the
body, so it is known only after the parse; the depth is known at the call.

### D2. `400`, not `502`

A `502` from a plugin is an origin failure to the pass above it: Core answers its own `502` `No route`, and for a
model it may retry. The first run of the integration test showed exactly that: the client got `No route`. A `4xx` is
an answer: every pass of the chain relays it unchanged, with no retry, and the client reads the message.

### D3. A constant of 32

No real chain is close to it: a client request through a router, an interceptor, a translator and a model is under
ten names. Rejected: a setting. Nothing needs to tune it yet, and a setting needs a code default, docs and a test.

## Difference from today's behaviour

A chain of in-process calls 32 names deep now ends with `400`. Before, it ran until something else failed.

## Effect per request

None on Core passes, body copies, body parses, Redis or blob round trips. The check reads the size of a list the
call already holds. Not measured: there is nothing to measure.

## Migration Plan

none
