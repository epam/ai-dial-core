# Design

## Context

See proposal.md for the motivation. Current state on this branch:

- **Decide and notice requests** go out with the plain Vert.x client.
  - `RouterDecisionClient.java:decide` and `RouterDecisionClient.java:notice` call `proxy.getClient().request(...)` with
    `DeploymentEndpointUtil.java:resolveDecideUri`.
  - That URI comes from `resolveInterfaceBaseUrl`, which resolves an embedded entry to `plugin://<name>`
    (`DeploymentEndpointUtil.java:resolveBaseUrl`). That is why `ConfigPostProcessor.java:validateDecision` rejects an
    embedded interface on a decision router today.
  - `decide` registers `verdict.future().onFailure(ignore -> request.reset())` as soon as the request exists. So it
    resets on every verdict failure (the deadline, also during the connect; a `4xx`; an invalid verdict), not only at
    the deadline.
  - It returns `Future<List<String>>`. `RouterDecisionFlow.java:askRouter` invalidates the decide key when that future
    completes and never sees the request.
- **The origin seam** sends one request either way: `OriginClient.java:http` and `OriginClient.java:embedded`;
  `ResponsesApiClient.java:send` picks one with `DeploymentEndpointUtil.java:resolvePlugin`.
  - `OriginClient.embedded` refuses a non-platform `Application` as `context.getDeployment()`.
  - `EmbeddedProxyRequest.java:send` builds the `OriginRequest`:
    - the path is the URI minus `plugin://<name>`;
    - the body tree comes from `EmbeddedProxyRequest.java:bodyTree`, which takes the context's request tree or parses
      the body;
    - the caller comes from the context.
  - It sets a close handler on the client's response. When the client is gone and the plugin then fails, it ends the
    pass and leaves its promise uncompleted, which is right for an origin, whose pass ends with the client.
  - It creates one `ChildPass` per attempt. `ChildPass.java:call` serves the plugin's calls with
    `parent.getProxyApiKeyData()` and no key lookup, to the create operations only. `ChildPass.close` cancels the
    calls still open, and later calls get `401`.
  - The attempt gives its plugin instance back once the plugin's future has settled and its `ChildPass` has closed.
    `reset()` completes the plugin's `cancelled()`.
- **The pass's own proxy key** is unset at the first decide request. `RouterDecisionFlow.java:retryWithNextCandidate`
  sets it to null before a redecide. During a mid-stream notice it is the serving candidate's key.
- **Parses of the client's bytes on a decided request.**
  - The body-addressed controllers parse before the decision: `MessagesBaseController.java:handle`,
    `ChatCompletionsController.java:dispatch` and `ResponsesController.java:dispatch`. They pass only the bytes to
    `decideIfRouter`.
  - `RouterDecisionFlow.java:mintDecideKey`, which runs on a virtual thread (`decideWith`), parses the bytes again,
    when `decision.body` is true, to collect the attachments.
  - Each fallback re-entry, `reenterWithCandidate` in the three controllers, parses them again, because the failed
    candidate's processing changed the parsed tree in place.
  - `DeploymentPostController.java:handleDeployment` decides before it parses, and parses per candidate in
    `handleRequestBody`.
- **An origin plugin's in-process call** to a body-addressed API is a pass with empty bytes (`ChildServerRequest`).
  - Its `ProxyUtil.parseRequestBody` returns the call's tree while the pass has no request body.
  - To a decision router with `decision.body: true`, `mintDecideKey` fails on the empty buffer and the request answers
    `500`.
  - At a fallback, `retryWithNextCandidate` sets the request body to the empty original bytes. The re-entry then
    parses nothing and fails.
  - On deployments-POST, `readRequestBody` stores the empty bytes as the request body, so the call tree is lost before
    the first candidate (`400`).

## Goals / Non-Goals

**Goals:**
- One decide path: only the transport differs, and everything after the verdict is the existing decision flow.
- With `decision.body: true`, the in-process decide costs no parse, no copy of the body text and no socket write; the
  attachment grants stop re-parsing in HTTP mode too.
- An origin plugin's in-process call to a decision router works on every create operation, with and without
  `decision.body`, fallback included.

**Non-Goals:**
- Embedded interceptors in front of a router, and FILTER plugins (see "Direction").
- Re-entry in a pass with bytes keeps parsing them. It happens only on fallback.
- No change to the plugin API types, to proxy-mode routers, or to HTTP decide requests on the wire, except that an
  HTTP router's connection is no longer reset after it answered (D3).

## Decisions

### D1. Decide and notice go through the origin seam

`RouterDecisionClient` sends both requests through `OriginClient`.
- It uses the router entry of D2 when `DeploymentEndpointUtil.resolvePlugin(router, type)` names a plugin, and `http`
  otherwise: the pattern of `ResponsesApiClient.send`.
- It is retyped from `HttpClientRequest`/`HttpClientResponse` to `ProxyRequest`/`ProxyResponse`. Headers, verdict
  parsing and the error mapping stay.
- The plugin receives `POST /decide`. So the router jar answers the same protocol in process as over HTTP, and a
  config change moves a router between the two.

Rejected:
- A typed decider plugin kind. It would be a third kind beside ORIGIN and FILTER, and a second protocol to keep in
  step, for microseconds (no JSON verdict).
- Routing strategies built into Core. Routing would ship with Core's releases.
- HTTP only.

### D2. A router entry in the origin seam

`OriginClient` gets a second embedded entry for decide and notice requests. It differs from the origin entry in five
ways:

1. **No platform-application check.** A router is never an `Application`, and at a redecide or a notice
   `context.getDeployment()` is the failed candidate, which a user's custom application may be.
2. **The key for the attempt's calls is explicit.**
   - With the decide key, `ChildPass` serves each call as a request with that key.
   - With none, as for a notice, every call is answered `401`.
3. **The body tree is explicit:** D4's copy for a decide with body, none otherwise. The context's request tree is
   left alone.
4. **It does not watch the client, and always settles its future.** The decision flow handles a client gone, as for
   an HTTP router (`RouterDecisionFlow.redecide`'s `closed()` check). The origin entry's handling would leave a decide
   hanging until the deadline, with the decide key alive. It would also cancel a notice, which Core sends right
   before it resets the client connection.
5. **It closes its own attempt** (D3).

Rejected: reusing the origin entry with the pass's proxy key. That key is null at the first decide and at a redecide,
where `ChildPass` would throw building the child's `ProxyContext`. At a notice it is the serving candidate's key, so
calls would act as that deployment.

### D3. When a router attempt ends

The router entry's request closes its `ChildPass` itself. For a decide, it closes when the plugin's future fails or its
answer body has ended or failed. Every way a decide ends passes through one of those points: a verdict read, a `4xx`
relayed after its body is read, an invalid verdict, a failed answer.

Closing cancels calls still open and answers `401` to later ones. It also lets the instance be given back once the
plugin's future has settled. The attempt stays registered with the pass (`ProxyContext.addChildPass`), so
`finalizeRequest` closes it too, as a backstop.

The deadline resets the attempt. `RouterDecisionClient` keeps registering the reset when the request is created, but
the reset fires only once the deadline has fired: the timer sets a flag, and the failure handler checks it. A request
created after the deadline is reset at once, so a deadline during the connect still resets.
- At the deadline, the plugin's `cancelled()` completes and the attempt closes. A plugin that sent a status and then
  stalls its body is cut off there, with its calls.
- On any other failure the plugin never sees `cancelled()`, as the `OriginHost.cancelled()` contract says. This
  covers a `4xx`, an invalid verdict, and a failed future before any answer.
- On HTTP, every failure other than the deadline happens after the answer was read. So the no-reset rule most likely
  changes nothing on the wire. `RouterDecisionClientTest` checks it at the mock level only.

A notice's `ChildPass` is created closed, since it carries no key: every call gets `401`. Its instance is given back
when the plugin's answer settles. In process a notice has no deadline (over HTTP it has the idle timeout), so a plugin
that never settles a notice holds its instance, as an origin plugin's attempt would.

Closing is needed because an in-process call carries the key record without a lookup. Invalidating the decide key,
which stops an HTTP router's late calls, would not stop a plugin's.

Rejected: a new "end attempt" operation on `ProxyRequest`, called by `RouterDecisionClient` next to the key
invalidation. `RouterDecisionClient` would need to keep the request until the verdict future completes, and HTTP would
need a no-op, while every end coincides with an event the embedded request already sees.

### D4. The body of an embedded decide with `decision.body`

- **The bytes:** the original buffer, the same object, not copied.
- **The tree.** Each decide request gets a copy of its own, since the plugin may change it.
  - On the body-addressed APIs, the first decide copies the controller's live tree, which `decideIfRouter` now
    receives as a parameter. No candidate has changed it yet.
  - A redecide parses the bytes (for an in-process call, D6's serialization). Candidates changed the live tree, a
    redecide is rare, and an HTTP redecide sends the same bytes.
  - On deployments-POST, the tree is the attachment parse's result, already a fresh tree.
- **The copy** is Jackson's `deepCopy`, which returns value nodes as they are. Only container nodes are copied, which
  is expected to cost tens of microseconds at 383 KB; task 5.2 measures it.
- **Where it runs:** the copy is taken inside the virtual-thread task that already runs `mintDecideKey`, not on the
  event loop.

Rejected:
- Handing the live tree read-only. The candidate path changes it in place, and so does a child pass the plugin hands
  it to.
- One snapshot per decided request, used for every re-entry, in HTTP mode too. It costs a copy on every decided
  request to save a parse only on fallback. It has no natural point to drop at, since a normal decision is never
  closed or exhausted. Held through each stream, it would keep the body's strings alive: hundreds of MB at 2,500
  streams per pod.

### D5. Attachment grants

On the body-addressed APIs, `mintDecideKey` collects the attachments from the controller's live tree, unchanged at
that point, instead of parsing the bytes. On deployments-POST it keeps parsing the bytes, and its tree serves D4.

Rejected: collecting from a copy, which is not needed while nothing changes the tree.

### D6. An origin plugin's in-process call

An in-process call reaching a decision router has a tree and no bytes. Core serializes the call's tree once, on the
virtual thread, before the first candidate, and makes that serialization the pass's original bytes. The decision flow
then runs unchanged:
- the HTTP decide body and the redecide body are those bytes (`RouterDecisionFlow.redecide`);
- `retryWithNextCandidate` resets the request body to them, and `reenterWithCandidate` parses them;
- the Responses checks run again on the same content.

Where the serialization happens depends on the path:
- **Body-addressed APIs:** in `decideWith`, from the tree `decideIfRouter` now receives. Its trigger is a child pass
  (`ProxyContext.isChildPass()`) whose original bytes are empty, deciding with any `decision.body`, since fallback
  needs the bytes.
- **Deployments-POST:** in `DeploymentPostController.readRequestBody`, which maps the child request's empty body to the
  serialization of the call tree when the deployment is a router. This keeps the tree from being lost (`400` today).
  The candidate then parses those bytes.

This is one serialization per such request, plus one parse per fallback, and on deployments-POST one parse for the
first candidate. In-process calls to a decision router are rare, and bytes held through the call take less memory
than a held tree.

Rejected: a snapshot tree carried on the `RouterDecision` and copied for each decide and re-entry. It needs a field,
three re-entry branches and a special redecide body, where the bytes make the existing flow work unchanged.

### D7. Failures map as an HTTP router's

- `toHttpException` answers `502` for a failed plugin future, and for a router entry that cannot be created (no entry,
  no provider, a plugin that cannot be created).
- A plugin `4xx` goes through `parseVerdict` and is relayed.
- A non-`200` or an invalid verdict answers `502`.
- The deadline answers `504` and is the only reset.
- Notice failures are logged and ignored.

Rejected: a distinct status or message for a plugin failure. The client cannot tell a router service from a plugin,
and should not need to.

### D8. The decide key stays in Redis

The embedded decide mints and invalidates the decide key as the HTTP one does: two Redis round trips per decide.
- A plugin may call Core over HTTP with the `API-KEY` header it received, for `GET /v1/user/info` or attachment
  reads, which in process it cannot.
- Such a call may land on another Core pod, which finds the key only in Redis.

Rejected: a key held in memory only, for the embedded decide. It saves the two round trips, but breaks those HTTP
calls.

### D9. Validation

- `ConfigPostProcessor.validateDecision` drops the embedded rule and keeps the translator rule.
- `ConfigPostProcessor.validatePluginReference` still requires a plugin on an embedded entry and refuses a `base_url`
  next to it.
- A router-level `baseUrl` can still serve the router's other, HTTP-decided, interfaces.

### What a plugin can do while deciding

- In process, its host serves the create operations only. `GET /v1/user/info` and attachment reads stay HTTP calls
  with the decide request's `API-KEY` header.
- A plugin rarely needs them, since the caller is in the request.
- `docs/dynamic-settings/plugins.md` and the javadoc of `OriginHost` and `OriginRequest` say so, together with:
  - the end of the decide attempt and the key-less notice;
  - a decide's `bodyTree`, which is the plugin's own copy;
  - a decide's `body`, the client's buffer, which Core reuses for later candidates;
  - the absence of `traceparent` on an in-process decide, as for an embedded origin.

### Effect per decided request

| | HTTP, no body | HTTP, `body: true` | embedded, no body | embedded, `body: true` | in-process call, `body: true` |
|---|---|---|---|---|---|
| Core passes | 1 | 1 | 1 | 1 | 1 (a child pass) |
| Parses of the body, today | 1, +1 per fallback | 2, +1 per fallback | not possible | not possible | fails with `500` |
| Parses after this change | 1, +1 per fallback | 1, +1 per fallback (deployments-POST: 2) | 1, +1 per fallback | 1, +1 per fallback and redecide (deployments-POST: 2) | 0, +1 per fallback (deployments-POST: 1, +1) |
| Serializations for the decide | 0 | 0 | 0 | 0 | 1 (with or without `decision.body`) |
| Tree copies | 0 | 0 | 0 | 1 per decide request | 1 per decide request, embedded router only |
| Body bytes over a socket for the decide | 0 | 383 KB | 0 | 0 | the serialization (HTTP router only) |
| Redis round trips | unchanged | unchanged | unchanged (2 per decide, D8) | unchanged | unchanged |

Measured as tasks 5.2 describes: one install; the direct path, then decision over HTTP and in process, each with and
without `decision.body`. Per variant: Core CPU per user request, router CPU, Redis commands per request and JVM
allocation per request.

### Direction: embedded interceptors in front of a router

This is not built here, but it shapes D4 and D6.
- With HTTP interceptors, the decision runs in the pass the last interceptor's call back into Core opens, which has
  bytes.
- An ORIGIN-plugin interceptor would reach the router in an in-process child pass: D6's case, on every create
  operation.
- A FILTER plugin would change the tree in the same pass, so the client's bytes no longer match it.

The rule that covers all three: the decision input is the tree; bytes are produced only where a decide with body
needs them, reusing the client's buffer when the tree is its unchanged parse and serializing once otherwise. The
spec's "the client's request bytes unchanged" then reads "the request as it reaches the router". With HTTP
interceptors that is already what happens.

## Risks / Trade-offs

- [A plugin blocks the event loop while deciding] → The decide runs on the request's context, as an embedded origin
  does, under the `OriginPlugin` contract. Plan-based selection takes microseconds.
- [A plugin that never settles holds its instance] → As for an origin plugin's attempt. The client gets its `504` at
  the deadline whatever the plugin does.
- [Calls still open at the end of a decide are cancelled, where an HTTP router's call with the decide key runs to its
  end] → Stated in the spec and in routers.md. A plugin that needs an answer waits for it before answering the decide
  request.
- [The serialized bytes and the call's tree disagree] → The serialization is made before any candidate changes the
  tree. From then on it is the only source for fallbacks, as a client's bytes are.
- [The deadline races the answer] → The reset fires only while no answer has arrived. An answer that arrives after
  the 504 is discarded with the request, as over HTTP.

## Migration Plan

None. A config that loaded before loads the same. A decision router with an embedded interface is opt-in, and was
rejected before. Rolling back is a config change: give the interface a `base_url` and drop `mode: embedded`.

## Open Questions

- What should `OriginRequest.body` be once FILTER plugins can change the tree in the client's own pass? Serializing
  once, as D6 does for an in-process call, is the default. An empty `body` beside `bodyTree` would only suit decide
  requests: the provider plugin reads `body`. This is decided with the interceptor change and does not change this
  one.
