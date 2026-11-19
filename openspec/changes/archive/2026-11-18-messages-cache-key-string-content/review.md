# Review: messages-cache-key-string-content

## What shipped

- On `POST /anthropic/v1/messages` to a model with `cacheSupported` or `autoCachingSupported`, a string
  `messages[i].content` or `system` is hashed as its one text block `{"type": "text", "text": <s>}`. A client that
  sends the same text in the other shape later keeps its pinned upstream: at any position with `autoCachingSupported`,
  at a `cache_control` position with only `cacheSupported`. A text block with another field (for example `citations`)
  is still a different prefix. Paths and the adapter contract are unchanged.
- A lookup that finds no pin logs `no cached upstream found` at debug instead of the warning
  `cached upstream doesn't exist any longer in config: id=null, endpoint=null`. The warning stays for a pin that names
  an upstream not in the route, whether the pin holds an id or an endpoint.
- Spec: new capability `upstream-cache-affinity` with the requirement "A string and its text block are the same
  prefix"; its Purpose says the rest of the pin mechanism is not specified yet.
- Docs: one sentence in the `fieldsHashingOrder` paragraph of `docs/dynamic-settings/models.md`.
- Test config: model `claude-cache-tiers` in `server/src/test/resources/aidial.config.json` (`up-a` on tier 0,
  `up-b` on tier 1).

## Verification

- `./gradlew checkstyleMain checkstyleTest`: passed.
- `./gradlew :server:test` on `MessagesApiRequestTest`, `MessagesApiTest`, `UpstreamRouteProviderTest`,
  `UpstreamRouteTest`, `UpstreamCacheServiceTest` and `ListingTest`: passed. `MessagesApiTest` covers both spec
  scenarios (`testStringMessagePinnedAfterItsTextBlock`, `testStringSystemPinnedAfterItsTextBlock`), and its
  two-turn tests wait until the first turn's pin is in Redis (`awaitPin`) rather than for a fixed time;
  `MessagesApiRequestTest` the equal hashes and the extra-field block; `UpstreamRouteProviderTest` no warning on a miss
  and one warning for an endpoint-only and for an id-only pin of a removed upstream.
- The classes that read the same test config for the new model: `CanonicalIdListingTest`, `ConfigModelListTest`,
  `AliasApiTest`, `AliasPublicApiTest`: passed.
- Without the fix, the string tests failed: both integration tests reached `up-a`, the hash tests saw different hashes
  and the no-warning test saw the warning.
- `npx -y @fission-ai/openspec@1.14.3 validate upstream-cache-affinity --strict`: valid. `validate --specs --strict`
  fails only on `resource-subscription`, whose placeholder Purpose predates this change.
- Effect per request checked by inspection of the diff against design.md; no load run.
- The full suite runs in CI on the PR.

## Drift from design.md

- D2: the string first fed itself and then a constant `"text"`, the values a text block feeds. It now becomes a real
  block (`asBlock`, at both callers of the hashing helper) and goes through the block code, so a later change to that
  code cannot split the two shapes. design.md D2 says so; the hashes are the same either way.

## Not done

- Findings left open: the new hash tests in `MessagesApiRequestTest` also assert the cache-key paths, which
  `testBuildCacheKeys_stringSystemAndContent` already covers.
- Follow-ups outside this change:
  - A string `tool_result.content` is not turned into its text blocks.
  - A pin that names an upstream other than the one a client `X-UPSTREAM-ID` asks for still logs the warning (D3).
  - The adapter drops unsupported tools before it reports the breakpoint path, so a `tools[i]` path can point at a
    different prefix than the one Core hashed.
  - With only `cacheSupported`, a top-level `cache_control` makes the adapter report the last position, which Core
    never looks up, so that pin is written and never found.
  - A chat-completions upstream configured with only a `key` gets a pin with no id and no endpoint, which can never
    match; it now logs at debug.
  - `docs/dynamic-settings/models.md` says explicit breakpoints are shared across initiators; with
    `autoCachingSupported` every hash is scoped to the initiator.
  - The production report has more misses than this change explains; the rest (first requests of a conversation, a
    prefix that changes between turns, pins not written) needs a breakdown from the production logs.
