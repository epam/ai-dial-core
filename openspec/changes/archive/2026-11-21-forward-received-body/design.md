# Design

## Context

Each controller parses the client's body into a tree and wraps it in a request object (`ChatCompletionRequest`,
`ResponsesApiRequest`, `MessagesApiRequest`). The enhancement chain changes the tree through that object: the model
name, defaults, the interceptor configuration, encrypted content. After the chain, every pass serializes the whole tree:
chat completions too, since applying the deployment defaults always reports a change. A pass
Core serves in process (an alias's target, an origin plugin's call) gets the parent's tree and no bytes, so it always
serializes. The model is usually the only thing that changes, and the client's bytes already hold everything else.

## Decisions

### D1. The body is taken from the request on every pass, and any change but `model` drops the bytes

`ReceivedBody` holds the tree, the bytes it was parsed from, the byte range of the top-level `model` string and the
model to splice in. It is immutable; `withModel` and `withoutBytes` return a new one. `JsonBodyRequest` keeps the
current one and is the only place the tree changes: `setModel` splices, `applyDefaults`, `clearInterceptorSettings`
and the encrypted-content unwrap drop the bytes when they changed something. `setProcessedRequestBody` sets the
request's body from `serialize()` on every pass, whatever the chain reports, so a function that changes the tree but
returns `false` cannot leave the client's bytes in place. The public `getTree()` is replaced by `getBody()`, whose
tree is for reading and handing on.

- **Rejected: the chain's result decides whether to serialize** - a function that forgets to report its change would
  send the client's bytes; with the body always taken from the request, the result does not matter for the body.
- **Rejected: check the tree against the bytes before sending (a hash or a re-parse)** - a hash can be steered by a
  client to collide, and a re-parse costs what this change saves.
- **Rejected: a frozen or copied tree** - Jackson has no read-only `ObjectNode`, and a copy costs what this change
  saves.

### D2. The bytes are kept only when Core and an origin read the same JSON from them

`ReceivedBody.parse` reads the top-level fields one by one with a reader that fails on a repeated key below the top
level, and notices a repeated top-level key itself. It keeps the bytes only when there is no repeated key, nothing but
whitespace around the object, and the whole body is valid UTF-8 (`ByteBufUtil.isText`; Jackson decodes some invalid
sequences leniently). Only a nested repeated key falls back to the lenient parse; malformed JSON fails once.

- **Rejected: forward the bytes whenever nothing changed** - with a repeated key Core reads the last value and an
  origin may read the first.
- **Rejected: the parser's `STRICT_DUPLICATE_DETECTION`** - it fails every repeated key, so each would be parsed twice.

### D3. Only an existing top-level `model` string is replaced

The splice writes `MAPPER.writeValueAsBytes(model)` over the range of the received string, quotes included, into one
buffer of the exact size. A body whose bytes have no top-level `model` string, or a non-string one, is serialized when
the model is set.

- **Rejected: insert `model` after the opening brace** - deployments-POST bodies usually have no `model`, but they are
  not the chains that carry large bodies in process; more code for a rare case.

### D4. In process, the parent's body travels whole, and only to Core's own alias target pass

The context's call body and plugin body hold a `ReceivedBody` instead of a tree. The alias pass hands its body
(`getBody()`) to the alias-hop host; the host gives it to the target pass only when the call carries that very tree
object, and only once, and gives any other call `ReceivedBody.of(tree)`. A router's candidate is served in the
router's own pass from the same request object, so it keeps the bytes; a fallback candidate is parsed from the
stand-in bytes as today. An embedded plugin gets `serialize()` as `body` and the tree as `bodyTree`.

- **Rejected: carry bytes in the plugin API's `Call`** - a public contract, and a plugin may change the tree it passes.
- **Rejected: hand on bytes for any call whose tree is the parent's** - an origin plugin may change the tree before it
  calls; only Core's own forward passes it on untouched.

## Differences from today

| What | Today | After | Requirement |
|------|-------|-------|-------------|
| Body unchanged (chat completions, Messages, Responses, in-process passes) | the tree serialized, compact | the client's bytes | Body sent to the origin |
| Only `model` changed | the tree serialized | the client's bytes with the model replaced | Body sent to the origin |
| Analytics row `request.body` | Core's compact JSON | the body sent, in the client's layout | analytics-log "Bodies in the analytics row" (wording unchanged) |

## Effect per request

| Per pass | Today | After | How measured |
|----------|-------|-------|--------------|
| Body unchanged or only `model` changed | 1 parse, 1 full serialization | 1 parse, 1 UTF-8 check, no serialization; 1 copy when `model` is replaced | A/B on the direct path, 2026-11-21 (650 KB mean body, plugin-served model, same nodes, JFR): serialization 6.68 → 0.15 ms, parse 2.95 → 3.89 ms per request, net −5.6 of 45.0 ms (−12%). The October "26% less" compared against a slow baseline run |
| Any other change | 1 parse, 1 serialization | the same, plus the UTF-8 check | - |
| In-process alias, router and model chain | the last pass serializes the whole body | the last pass replaces `model` in the client's bytes; the router's stand-in serialization stays | task 5.1, 2026-11-21: serialization 7.39 → 0.25 ms, parse 3.36 → 4.32 ms per user request through tier alias → router → alias → model, net −6.2 ms |
| Redis and blob round trips | - | unchanged | - |

## Risks / Trade-offs

- A future change to the tree that bypasses the request's methods would send the client's bytes without it. Mitigated:
  the tree is reachable only through `getBody()`, documented read-only, and every current change is a method of the
  request; the tests pin each one.
- The received buffer is kept until the pass ends; it already was, as the request's body and the router's original
  body.
- An origin that depended on Core's compact layout would see the client's; JSON readers do not depend on layout.

## Migration Plan

None.

## Code map

- `ReceivedBody.java`: `parse`, `of`, `withModel`, `withoutBytes`, `serialize`.
- `JsonBodyRequest.java`: `setModel`, `clearInterceptorSettings`, `applyDefaults`, `treeChanged`, `getBody`,
  `serialize`; `ResponsesApiRequest.java`: `resolveAndUnwrapEncryptedContentAffinity`; `RequestObject.java`: `getBody`.
- `ChatUtil.java`: `removeInterceptorConfiguration`, `applyDefaults`; `JsonUtil.java`: `applyDefault`;
  `EncryptedContentAffinityUtil.java`: `resolveAndUnwrap`; `ApplyDefaultDeploymentSettingsFn.java`: `apply`;
  `ResolveEncryptedContentAffinityFn.java`: `apply`.
- `ProxyUtil.java`: `parseRequestBody`; `ProxyContext.java`: `getCallBody`, `isChildPass`, the plugin body.
- `BaseDeploymentPostController.java`: `setProcessedRequestBody`, `isInProcessAliasHop`;
  `BaseChatCompletionController.java`: `processChatCompletionsRequestBody`; `BaseInterceptorController.java`:
  `parseBody`, `handle`, `handleRequestBody`; the parse sites of `ChatCompletionsController.java`,
  `DeploymentPostController.java`, `ResponsesController.java` and `MessagesBaseController.java` (`parseBody`,
  `handleRequestBody`).
- `EmbeddedProxyRequest.java`: `send`; `VertxHostDelegate.java`: `forAliasHop`, `call`, `callBody`;
  `ProxyClient.java`: the forward plugin.
- `RouterDecisionFlow.java`: `handOffToInterceptors` (the serialized body); `prepare` and
  `DeploymentPostController.java`: `readRequestBody` keep serializing the call's tree as the decision's stand-in.
