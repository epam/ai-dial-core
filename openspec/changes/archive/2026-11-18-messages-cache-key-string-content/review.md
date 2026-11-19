# Review: messages-cache-key-string-content

## Implementation

- Tasks done: 7 of 7. None deferred.
- Files changed:
  - `server/src/main/java/com/epam/aidial/core/server/function/request/MessagesApiRequest.java`
  - `server/src/main/java/com/epam/aidial/core/server/upstream/UpstreamRouteProvider.java`
  - `server/src/test/java/com/epam/aidial/core/server/MessagesApiTest.java`
  - `server/src/test/java/com/epam/aidial/core/server/function/request/MessagesApiRequestTest.java`
  - `server/src/test/java/com/epam/aidial/core/server/upstream/UpstreamRouteProviderTest.java`
  - `server/src/test/resources/aidial.config.json`
  - `docs/dynamic-settings/models.md`
  - `openspec/changes/messages-cache-key-string-content/design.md`, `tasks.md`
- Tests added:
  - `MessagesApiTest.testStringMessagePinnedAfterItsTextBlock`
  - `MessagesApiTest.testStringSystemPinnedAfterItsTextBlock`
  - `MessagesApiRequestTest.testBuildCacheKeys_stringContentMatchesTextBlock`
  - `MessagesApiRequestTest.testBuildCacheKeys_stringSystemMatchesTextBlock`
  - `MessagesApiRequestTest.testBuildCacheKeys_textBlockWithExtraFieldDiffersFromString`
  - `UpstreamRouteProviderTest.testGet_PinMissLogsNoWarning`
  - `UpstreamRouteProviderTest.testGet_CachedUpstreamMissed`: gained an assertion that it logs one WARN.
- Design drift: none. The code matched design.md, which records this under "## Implementation notes". `MessagesBaseController` is in `controller/anthropic/`; the design's citations cover it. `openspec/specs` has no `upstream-cache-affinity` capability yet, so the delta stays an ADDED requirement of a new capability with no rebase. The debug line for a miss is `no cached upstream found: prefixPath={}`. Both integration tests share the helper `sendPinnedThenUnpinned`, which also checks that the first turn reached keyB.

## Verification

### Implementer checks

- `./gradlew checkstyleMain checkstyleTest`: passed.
- `./gradlew :server:test` on MessagesApiRequestTest, MessagesApiTest, ListingTest, UpstreamCacheServiceTest, UpstreamRouteProviderTest, UpstreamRouteTest: passed, 89 tests, 0 failed. Before the fix the same run had 5 failures, as tasks.md predicts (both integration tests got keyA instead of keyB, both unit hash-equality tests failed, `testGet_PinMissLogsNoWarning` saw the warning with id=null, endpoint=null).
- `openspec validate messages-cache-key-string-content --strict`: valid.
- `git status --short`: only the 7 modified files and the untracked change folder.

### First verification: passed

- Checkstyle passed (JDK 25.0.3.9; tasks UP-TO-DATE from an earlier passing run).
- MessagesApiRequestTest 11/0, MessagesApiTest 22/0, UpstreamRouteProviderTest 10/0, ListingTest 23/0, UpstreamCacheServiceTest 14/0, UpstreamRouteTest 9/0 (tests/failures).
- Because the shared fixture gained model `claude-cache-tiers`: CanonicalIdListingTest 4, ConfigModelListTest 5, AliasApiTest 25, AliasPublicApiTest 20, all 0 failures.
- All runs used `--rerun`; the first plain run was UP-TO-DATE and executed nothing. config and storage modules unchanged, not run.
- `openspec validate --strict`: valid.
- `git status --short --untracked-files=all`: the 7 modified files are all named in tasks 1.1-1.3 and 2.1-2.2; untracked files are only the change folder (`.openspec.yaml`, design.md, proposal.md, tasks.md, specs/upstream-cache-affinity/spec.md).
- Cost-claim check: matches design.md "Effect per request" (inspection of the diff, no load run). No new Core pass, body copy, parse, or Redis/blob round trip. The new `isTextual` branch in `MessagesApiRequest.updateExcludingCacheControl` makes two `builder.update` calls (string, then static `TEXT_TYPE`) instead of one, on the tree already held. The digest input equals the block path (sorted keys cache_control < text < type, cache_control skipped, so "Hello" then "text"). `UpstreamRouteProvider.get` only splits `log.warn` into warn (id or endpoint present) and debug (miss). No new event-loop blocking call or Future chain; `UpstreamCacheService.getCacheEntry` unchanged. Integration tests sleep 1 s for the async pin write, test-only, as in `testAutoCachingPinsSecondTurnToSameUpstream`.

### Final verification: passed

Same checks and results as the first verification; no code changed between them.

## Review findings

| Lens | Severity | File:line | Finding |
| --- | --- | --- | --- |
| conformance | low | server/src/test/java/com/epam/aidial/core/server/upstream/UpstreamRouteProviderTest.java:138 | No test covers the warning for an id-only pin. `testGet_CachedUpstreamMissed` builds `CachedUpstreamEntry("test", null, "prefix", null)`, which is endpoint-only, while tasks.md 2.1 and design.md Spec review 8 describe an id. Id-only is the usual production shape (`UpstreamCacheService.updateEntry` stores no endpoint for interface-configured upstreams). Narrowing the guard at UpstreamRouteProvider.java:150 to `cachedEndpoint != null` would drop that warning with all tests still passing. Fix: use or add an id-only entry with the one-WARN assertion, or reword tasks.md 2.1 and design.md. |
| simplicity | low | server/src/test/java/com/epam/aidial/core/server/function/request/MessagesApiRequestTest.java:224 | The new hash-equality tests also assert cache-key paths (lines 224-225, 252-253), already covered by `testBuildCacheKeys_stringSystemAndContent`; the change does not touch path computation. Fix: drop the path assertions, keep the hash assertions. |

## Fix round 1

No fix round ran. Both findings are low severity and were not acted on.

## Outcome

- High/medium findings open: none.
- Low findings left: the id-only pin warning test gap (UpstreamRouteProviderTest.java:138) and the redundant path assertions (MessagesApiRequestTest.java:224, 252).
- CI must still confirm: the full test suite across all modules (only targeted classes ran locally) and checkstyle on a clean build.

## Independent PR review

A reviewer without this run's context reviewed PR #2314 after the archive; CI was green, including the full suite. No
high or medium findings.

| Severity | File:line | Finding | Outcome |
| --- | --- | --- | --- |
| low | UpstreamRouteProviderTest.java:138 | The warning test pins an endpoint only (`CachedUpstreamEntry(endpoint, id, ...)`), not the id-only pin of an upstream configured through interfaces; design.md "Spec review" 8 calls it "an id no upstream has", which is wrong. | Fixed: `testGet_IdOnlyPinOfRemovedUpstreamWarns` asserts one WARN for `(null, "gone", "prefix", null)`. |
| low | openspec/specs/upstream-cache-affinity/spec.md:4 | The Purpose promised the whole pin mechanism; the spec holds one requirement. | Fixed: the Purpose says what is covered and that the rest is not specified yet. |
| nit | UpstreamRouteProvider.java:150 | A pin with neither id nor endpoint (a chat-completions upstream with only a `key`) can never match and now logs at debug. | Not changed: before this change the same warning fired on every miss; the fix is not to write such a pin, outside this change. |
| nit | MessagesApiTest.java | `Thread.sleep(1000)` waits for the async pin write. | Not changed: same pattern as the existing pin test. |
| nit | PR description | "six touched classes": three were touched, six run; rollback not mentioned. | Fixed in the PR description. |

## PR review: string normalization

A maintainer found the string branch of `updateExcludingCacheControl` fragile: it fed the string and a static `"text"`
node, copying by hand what the object path feeds for a text block. Accepted. The helper now turns a string into
`{"type": "text", "text": <s>}` and sends it down the object path (design D2). It is done in the helper rather than at
the `messages` call site the comment suggested, so `system[i]` keeps the fix. Hashes are unchanged, so no test changed;
checkstyle, `MessagesApiRequestTest` (11) and `MessagesApiTest` (22) pass.

Questions outside this change: the adapter drops unsupported tools before it reports the breakpoint path, so a
`tools[i]` path can point at a different prefix than Core hashed; a top-level `cache_control` with only
`cacheSupported` writes a pin that is never looked up; a string `tool_result.content` is not normalized.
