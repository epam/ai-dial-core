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
- `Call` gains a `method`, first, as in `Request`. The three-argument constructor stays and means `POST`, so plugins
  built against today's plugin API compile and run unchanged.
- The rules of every call apply: `401` once the plugin's request has ended, `401` while answering a router's notice,
  and the depth limit of 32.
- Refused as a call outside the create operations is today (the call fails): a `GET` of any other path, any method
  other than `POST` and `GET`, and a `GET` with a body.

Out of scope:

- Other methods and routes through the host: file reads, file metadata, the resource and sharing APIs, uploads. A read
  route is one more entry in the host's list and a test; a JSON write or an upload needs request body bytes that the
  in-process request does not carry yet.
- The plugin side: the model-provider plugin's user info through the host (its PR #2 and a follow-up there).
- `project` for a JWT caller in the user info answer: change `user-info-jwt-project`, PR #2348. No dependency either
  way.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-origin`: ADDED "Caller's user info through the host".
- `router-deployment`: MODIFIED "Calls of an embedded router while it decides": the plugin may read the caller's user
  info through its host with the decide request's key; a notice still answers every call `401`.

## Impact

- Motivation: issue #2346, "Core Plugin API: support HTTP methods other than POST in HostDelegate". Covered: `GET` of
  `/v1/user/info`, the one route a plugin needs today. Left out: other methods and routes (see Out of scope). The
  consumer is epam/ai-dial-model-provider-plugin PR #2, which is blocked by the issue.
- Code: `aidial-core-plugin-api` (`Call`, and the javadoc of `Call` and `HostDelegate`); `server` (`VertxHostDelegate`,
  `EmbeddedServerRequest`, the child-pass mark in `ProxyContext`). The published plugin API gains a record
  component; the kept constructor and accessors keep plugin jars built against the old API working.
- Records, per plugin request that reads the user info through the host instead of over HTTP: one HTTP request into
  Core fewer, one Redis GET fewer (the per-request key lookup) and one OTel server span fewer. Neither way writes an
  analytics row.
- Docs: `docs/dynamic-settings/plugins.md` (what a call reaches; the decide key paragraph, which says user info goes
  over HTTP) and `docs/tracing.md` (calls from an origin plugin).
- Dependencies: none.
