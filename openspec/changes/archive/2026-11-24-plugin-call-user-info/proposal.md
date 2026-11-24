# Proposal

## Why

An origin plugin that needs the caller's user info, as the model-provider plugin does for its AWS session tags
(`UserInfo.<path>`), calls `GET /v1/user/info` over HTTP to Core's own address with the per-request key of its
request. On every such request that costs an HTTP request into Core, a Redis lookup of the key and a server span, and
the plugin has to be configured with Core's address to reach the Core it runs in. The host cannot serve it: a call
through `HostDelegate` is always a `POST` to a create operation (issue #2346).

## What Changes

- `HostDelegate.call` serves `GET /v1/user/info` in process. The plugin passes a `Call` with the method `GET` and no
  body. Core serves it with the key the plugin's other calls carry (the key of the request the plugin answers, or the
  decide request's key on a router's decide request), with no HTTP request into Core and no key lookup, and hands the
  answer back as it does for any call: the status and headers, then the body.
- The answer is the one `GET /v1/user/info` gives over HTTP with that key: the caller's roles, and `project` for an
  API-key caller or `userId` and `userClaims` for a JWT caller, and `userDisplayName` when there is one.
- After the PR review, the host serves every route of Core's API the same way, not only the user info: a resource's
  write, read or delete, a file read, the sharing API. A call outside the create operations takes its body as JSON
  bytes (design D2, D4).
- `Call` gains a `method`, first, as in `Request`. The three-argument constructor stays and means `POST`, so plugins
  built against today's plugin API compile and run unchanged.
- The rules of every call apply: `401` once the plugin's request has ended, `401` while answering a router's notice,
  and the depth limit of 32.
- A call without a method or a uri fails.

Out of scope:

- Uploads and WebSockets through the host: a call carries a JSON body only.
- The plugin side: the model-provider plugin's user info through the host (its PR #2 and a follow-up there).
- `project` for a JWT caller in the user info answer: change `user-info-jwt-project`, PR #2348. No dependency either
  way.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-origin`: ADDED "Core's API through the host"; MODIFIED "Calling a deployment from an origin plugin": a
  Responses call in background is the one call refused.
- `router-deployment`: MODIFIED "Calls of an embedded router while it decides": the plugin's calls through its host
  are the ones deployment-origin lists, with the decide request's key; a notice still answers every call `401`.

## Impact

- Motivation: issue #2346, "Core Plugin API: support HTTP methods other than POST in HostDelegate". Covered: every method
  and route, `GET /v1/user/info` being the one a plugin needs today. Left out: uploads and WebSockets. The
  consumer is epam/ai-dial-model-provider-plugin PR #2, which is blocked by the issue.
- Code: `aidial-core-plugin-api` (`Call`, and the javadoc of `Call` and `HostDelegate`); `server` (`VertxHostDelegate`,
  `EmbeddedServerRequest`, `ProxyContext.isChildPass`, `DeploymentPostController.readRequestBody`). The published plugin API gains a record
  component; the kept constructor and accessors keep plugin jars built against the old API working.
- Records, per plugin request that reads the user info through the host instead of over HTTP: one HTTP request into
  Core fewer, one Redis GET fewer (the per-request key lookup) and one OTel server span fewer. Neither way writes an
  analytics row.
- Docs: `docs/dynamic-settings/plugins.md` (calling another deployment; the decide key paragraph, which says user info
  goes over HTTP), `docs/dynamic-settings/routers.md` (a decision plugin's calls) and `docs/tracing.md` (calls from an
  origin plugin).
- Dependencies: none.
- Supersedes: embedded-call D1 (a call's `uri` is the path of a create operation; it is now any route of Core's
  API).
