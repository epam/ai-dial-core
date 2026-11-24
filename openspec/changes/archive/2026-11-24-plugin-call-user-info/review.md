# Review

## What shipped

An origin plugin reads the caller's user info through its host: `HostDelegate.call` serves `GET /v1/user/info`
without a body in process, with the key the plugin's calls carry (the decide request's key on a decide request), and
the answer is the one that request gets over HTTP with that key. `Call` gains `method` first; the three-argument
constructor means `POST`. The host checks the call's own fields, then answers `401` once closed, then the method and
route, the background rule and the depth; a call without a method, a `POST` without a body, another call with one, a
`GET` of another path and any other method fail. A pass is a child pass when its request is the in-memory one, so the
user info pass writes nothing on the plugin's span. Docs: `docs/dynamic-settings/plugins.md` (calling another
deployment, translating, deciding for a router, the forward sample), `docs/dynamic-settings/routers.md` (a decision
plugin's calls), `docs/tracing.md` (calls from an origin plugin), and the `Call` and `HostDelegate` javadoc.

## Verification

- `./gradlew checkstyleMain checkstyleTest`: passes.
- `./gradlew :server:test` with `EmbeddedCallApiTest`, `EmbeddedDecisionApiTest`, `VertxHostDelegateTest`,
  `EmbeddedServerRequestTest`, `ProxyContextTest`, `GenAiTraceAttributesTest`, `AutoEnrichedOtelJsonLayoutTest`,
  `AliasInProcessApiTest`, `AliasApiTest`: pass. The scenario tests failed before the host change.
- `./gradlew :aidial-core-plugin-api:compileJava :plugin-samples:compileJava`: the three-argument callers compile
  unchanged.
- `npx -y @fission-ai/openspec@1.14.3 validate --specs --strict` after the archive: passes.

## Drift from design.md

None.

## Not done

- Task 4.1, the A/B of the user info read over HTTP against the read through the host: deferred until the
  model-provider plugin reads it through the host and the perf environment is available.
- Before the host serves another `GET` route (design D2), drop a `GET`'s body headers (`Content-Type`,
  `Content-Length`) that a plugin passes on from its own request, and check the in-memory request's throwing members
  against that route's controller.
