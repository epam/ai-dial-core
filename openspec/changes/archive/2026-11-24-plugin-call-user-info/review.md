# Review

## What shipped

An origin plugin calls any route of Core's API through its host, the caller's user info included:
`HostDelegate.call` serves the call in process with the key the plugin's calls carry (the decide request's key on a
decide request), and the answer is the one that request gets over HTTP with that key. `Call` gains `method` first; the
three-argument constructor means `POST`. A create operation's pass takes the body as a tree as before; any other pass
reads the body's bytes, serialized on a worker when first read, and a `Content-Length` the plugin passes on is
dropped. The host checks the call's method and uri, then answers `401` once closed, then the background rule and the
depth. A pass is a child pass when its request is the in-memory one, so a call outside the create operations writes
nothing on the plugin's span. The first design served `GET /v1/user/info` only; the PR review asked to drop the route
checks, and design D2 and D4 record the result. Docs: `docs/dynamic-settings/plugins.md` (calling another deployment,
translating, deciding for a router, the forward sample), `docs/dynamic-settings/routers.md` (a decision plugin's
calls), `docs/tracing.md` (calls from an origin plugin), and the `Call` and `HostDelegate` javadoc.

## Verification

- `./gradlew checkstyleMain checkstyleTest`: passes.
- `./gradlew :server:test` with `EmbeddedCallApiTest`, `EmbeddedDecisionApiTest`, `VertxHostDelegateTest`,
  `EmbeddedServerRequestTest`, `ProxyContextTest`, `GenAiTraceAttributesTest`, `AutoEnrichedOtelJsonLayoutTest`,
  `AliasInProcessApiTest`, `AliasApiTest`, `EmbeddedOriginApiTest`, `RouterDecisionApiTest`: pass. The user info
  scenario tests failed before the host change.
- `./gradlew :aidial-core-plugin-api:compileJava :plugin-samples:compileJava`: the three-argument callers compile
  unchanged.
- `npx -y @fission-ai/openspec@1.14.3 validate --specs --strict` after the archive: passes.

## Drift from design.md

None.

## Not done

- Task 4.1, the A/B of the user info read over HTTP against the read through the host: deferred until the
  model-provider plugin reads it through the host and the perf environment is available.
