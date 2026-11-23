# Design

## Context

An origin plugin answers a request inside Core and gets a host for that request. Through the host it can call a
create operation of a deployment: Core serves the call as a child pass, an in-memory request that Core's own controller
selection routes, authenticated by the per-request key record the plugin's pass already holds, so there is no HTTP
request into Core and no key lookup. Everything else a plugin needs from Core goes over HTTP to Core's address, with
the per-request key in `API-KEY`. The model-provider plugin needs one such thing on every request that resolves
`UserInfo.<path>` session tags: `GET /v1/user/info`, whose roles, project and claims it puts into the tags. Over HTTP
that read is a server span, a Redis lookup of the key and a pass through the user info controller. Core tells a child
pass apart by the body tree it carries, and a pass without one is taken for a pass with a server span of its own.

Today:

```mermaid
sequenceDiagram
    Core pass->>Plugin: handle(request, host)
    Plugin->>User info pass: HTTP GET /v1/user/info, per-request key
    User info pass->>Redis: read the key
    User info pass-->>Plugin: roles, project, claims
    Plugin->>Cloud: model request, session tags
    Cloud-->>Plugin: answer
    Plugin-->>Core pass: answer
```

After:

```mermaid
sequenceDiagram
    Core pass->>Plugin: handle(request, host)
    Plugin->>User info pass: host call GET /v1/user/info, in process
    User info pass-->>Plugin: roles, project, claims
    Plugin->>Cloud: model request, session tags
    Cloud-->>Plugin: answer
    Plugin-->>Core pass: answer
```

## Decisions

### D1. A GET goes through the same `call`, and `Call` gains a method

The plugin builds `new Call(HttpMethod.GET, "/v1/user/info", headers, null)` and gets a `Response` as for any call:
the status, the headers, then the body as a stream. The answer, the cancel, the end with the request and the `401`
after it are the ones a `POST` call has, with no second path to keep in step. The method comes first, as in `Request`.
The three-argument constructor stays and means `POST`, so plugin jars built against today's API run, and their
sources compile, unchanged.

- **Rejected: the user info in `Caller` (claims and display name added), so no call is needed** - the plugin's tags
  are paths into the `/v1/user/info` answer, whose shape `Caller` does not have: `Caller.project` is the JWT's project
  claim for a JWT caller, while the answer has no `project` for one. The plugin would rebuild the user info
  controller and drift from it, and every plugin request would carry claims few plugins read.
- **Rejected: `HostDelegate.userInfo()` returning the answer as a tree** - a separate contract per route a plugin
  needs later; the issue asks for methods other than `POST`.
- **Rejected: a second record or interface method for a `GET`** - two ways to make a call that differ only in the
  method.

### D2. The host serves a list of method and route pairs; `GET /v1/user/info` is the one new pair

The host checks a call against a list of method and route pairs: `POST` on the five create routes, as today, and
`GET` on the user info route. Any other call fails before a pass starts. A read route a plugin needs later (file
reads, file metadata, limits) is one more pair and a test: the in-process answer already streams with backpressure,
reset and cancel, which a file download's pipe needs. A JSON write or an upload needs more, because the in-process
request has no body bytes and no multipart.

The alias hop asks the host whether a path is a create route, to decide whether an alias pass goes in process. That
question keeps its meaning: create routes only, whatever the list holds.

- **Rejected: every route Core serves** - each controller would run on a request it was never tested on, and a
  member of the in-memory request that throws would turn into a `500` for the plugin.
- **Rejected: `GET` on every route** - the same risk for the read controllers, with no consumer today.

### D3. A pass the host serves is marked as a child pass by a flag, not by its body tree

A `GET` call has no tree, so with today's test its pass would count as one with a server span of its own:
`Proxy.dispatch` writes the pass's conversation id and parent span on the current span, which is the plugin's server
span, and the log layout enriches that span with the user info pass's log fields. The host sets the mark on every
pass it starts. The two readers that mean "a pass with a tree but no bytes" (the decision router's stand-in body and
the empty-body read of a router pass) check for the tree as well, as they do today.

- **Rejected: an empty tree for a `GET`** - those two readers would take it for a body.

### D4. A `GET` call with a body is refused

`Call.body` is the tree a `POST` hands over. A `GET` that carries one would have it dropped without a word, and a
plugin bug (a `GET` built where a `POST` was meant) would show as a wrong answer instead of a failed call. The call
fails, as a call to a route outside the list does.

- **Rejected: ignoring the body** - hides the bug.

### D5. The user info call keeps the key, the depth limit and the end of every call

The call carries the key a `POST` call carries: the plugin's pass key, or the decide request's key on a decide
request, so the answer describes the client's caller, as the HTTP read with that key does. The depth check runs for
every call, so a `GET` from a plugin 32 deployments deep gets `400`, as that plugin's `POST` calls do. After the
request has ended, and while answering a notice, the call gets `401`.

- **Rejected: no depth check for a `GET`** - an extra branch for a plugin whose other calls fail at that depth
  anyway; a `GET` of user info starts no further pass, so it cannot loop.

## Differences from today

| What | Today | After | Requirement |
|---|---|---|---|
| `GET /v1/user/info` through the host | the call fails: not a create operation | served in process with the call's key | deployment-origin "Caller's user info through the host" |
| User info while deciding, through the host | the call fails | served with the decide request's key; a notice gets `401` | router-deployment "Calls of an embedded router while it decides" |
| Server span of a plugin's user info read, when it uses the host | a server span of its own | none; the read writes nothing on the plugin's span (D3) | none; `docs/tracing.md` |
| `Call` in the plugin API | path, headers, body; always `POST` | method first; the three-argument constructor means `POST` | none (plugin API) |

The user info read over HTTP keeps working unchanged.

## Effect per request

Per plugin request that reads the user info through the host instead of over HTTP:

| | Over HTTP (today) | Through the host |
|---|---|---|
| HTTP requests into Core | 1 | 0 |
| Redis | 1 GET of the per-request key | none |
| OTel server spans | 1 | 0 |
| Core passes | 1 (user info: no limits, no row) | 1, in process |
| Encode and parse of the answer | Core encodes, the plugin parses | the same |
| Analytics rows, blob operations | none | none |

Requests that do not read the user info: none, the host's check is one more list entry. Expected, not measured: the
saving needs the plugin side, which comes after this change; task 4.1 measures it then.

## Risks / Trade-offs

- [The user info controller runs on the in-memory request for the first time] → it reads only the pass's context
  (roles, key, claims, display name) and answers through the context's `respond`; tests cover both caller kinds and
  the decide key.
- [A plugin keeps the host past its request and reads the user info later] → `401`, as for any call after the end,
  and as the HTTP read gets once the key is deleted.
- [The plugin API record changes] → the kept constructor and accessors keep jars built against the old API working;
  a plugin that deconstructs `Call` with a record pattern stops compiling, which is unlikely for a record a plugin
  builds and hands over.
- [Traces lose the user info read's server span] → `docs/tracing.md` says that calls from an origin plugin have no
  server span of their own; a user info call is one of them.

## Migration Plan

None. No config or settings change. Plugins keep reading the user info over HTTP until they move to the host call;
a plugin that uses a `GET` call needs a Core with this change, as it needs the matching plugin API version. Rollback
is a revert.

## Code map

On `development` (4a2aecb0d).

Plugin API (`aidial-core-plugin-api`):

- `Call.java`: the record and its javadoc, which lists the create operations.
- `HostDelegate.java:call`: the javadoc says `GET /v1/user/info` and file reads go to Core over HTTP.
- `Request.java`: the method comes first there.

The host and the in-process request (D1, D2, D4, D5):

- `VertxHostDelegate.java:call`: the argument check (path and body), the closed host's `401`, `servesCall` against
  `CREATE_ROUTES`, the background check (`isResponses`), the depth check (`MAX_CALL_DEPTH`), the
  `EmbeddedServerRequest` and `EmbeddedServerResponse`, the child `ProxyContext` from `callKey` or
  `ProxyContext.java:getProxyApiKeyData`, `setCallTree`, `setAliasTargetPass`, then `Proxy.java:dispatch`.
- `VertxHostDelegate.java:servesCall` is also read by `BaseDeploymentPostController.java:isInProcessAliasHop`; it keeps
  meaning the create routes (D2).
- `VertxHostDelegate.java:forRouter`: the decide request's key, and a host closed from the start for a notice.
- `EmbeddedServerRequest.java`: `method()` answers `POST`, and the constructor sets `Content-Type: application/json`
  and drops `DROPPED_HEADERS`.
- `EmbeddedServerResponse.java`: the answer side, unchanged.

The user info pass:

- `Proxy.java:dispatch`: `GenAiTraceAttributes.initialize`, `ContextManager.setProxyContext`, then
  `ControllerSelector.select` by method and path.
- `ControllerSelector.java`: `get(RouteTemplate.USER_INFO, ...)`; `RouteTemplate.USER_INFO`.
- `UserInfoController.java:handle`: roles, then `project` for a key caller or `userId` and `userClaims` for a JWT
  caller, then `userDisplayName`.
- `ProxyContext.java:<init>`: for a per-request key, the claims and the original key come from the key record.

The child-pass mark (D3):

- `ProxyContext.java:isChildPass`, `setCallTree`, `getCallTree`, `isAliasTargetPass`.
- Readers: `GenAiTraceAttributes.java:set`, `AutoEnrichedOtelJsonLayout.java:buildEnrichedOtelLogRecord`,
  `VertxHostDelegate.java:writesOnAliasSpan`, `DeploymentPostController.java:readRequestBody`,
  `RouterDecisionFlow.java:inProcessCall`.

Tests to extend:

- `EmbeddedCallApiTest` (`testCallOutsideTheCreateOperations`, `testCallAfterThePluginsPassEnded`,
  `testCallerWithApiKey`, `testCallerWithJwt` show the patterns), `EmbeddedDecisionApiTest`.
- `config/TestOriginPluginProvider.java`: `X-Test-Call`, `X-Test-Decide-Call`, `X-Test-Notice-Call`, and `HOSTS` for a
  call after the pass.

Docs: `docs/dynamic-settings/plugins.md` ("Calling another deployment"; "Deciding for a router", "The key"),
`docs/tracing.md` ("Calls from an origin plugin").
