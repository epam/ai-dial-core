# Design

## Context

See proposal.md, Why. Facts on `development`:

- `CacheKeyBuilder.java:update` sorts a node's object keys (`JsonUtil.java:sort`, a `TreeMap`), takes its JSON text
  and feeds it as UTF-8 into one rolling SHA-1. `CacheKeyBuilder.java:buildKey` snapshots the digest without resetting
  it, so a different input at one position changes the hash at that position and at every later one.
  `CacheKeyBuilder.java:elements` turns a scalar into one element, so a string `content` is `content[0]` and a string
  `system` is `system[0]`.
- `MessagesApiRequest.java:updateExcludingCacheControl` feeds a non-object node as it is: the string `"Hello"` feeds
  `"Hello"`. It feeds an object as its values in sorted key order without `cache_control`: the block
  `{"type": "text", "text": "Hello", "cache_control": {...}}` feeds `"Hello"` and then `"text"` (`cache_control` <
  `text` < `type`). `MessagesApiRequest.java:appendItemCacheKeys` (`tools`, `system`) and
  `MessagesApiRequest.java:appendMessageCacheKeys` (`messages[i].content[j]`, after the message's other fields such as
  `role`) both call it.
- `UpstreamCacheService.java:buildCacheBreakpointContext` makes every position a lookup candidate when the model has
  `autoCachingSupported`, otherwise only positions with `cache_control`. With `autoCachingSupported` every hash is also
  scoped to the initiator (`ProxyContext.java:getInitiatorId`: the user, or the API key's project), so the tests send
  both turns with the same API key.
- `UpstreamCacheService.java:getCacheEntry` checks the candidates from the last one back, 64 `EXISTS` per Redis batch,
  and reads the first entry found with one `HMGET`. On a miss it returns `CachedUpstreamEntry(null, null, <last
  candidate path>, null)`. That path is not dead: `BaseDeploymentPostController.java:sendProxyRequest` sends it to the
  adapter as `X-DIAL-CACHE-BREAKPOINT-PATH`.
- `UpstreamRouteProvider.java:get(String, List, int, UpstreamCacheContext)` looks the entry's upstream up by id, then
  by endpoint, and logs `cached upstream doesn't exist any longer in config` when neither matches. The miss entry has
  neither, so the warning fires on every request that finds no pin.
- `UpstreamRoute.java:next` sends the first attempt to the pinned upstream when there is one; otherwise
  `TieredBalancer.java:next` picks from the lowest tier first. `UpstreamRoute.java:updateUpstreamCache` writes the pin
  for the upstream that answered, from its `X-DIAL-CACHE-BREAKPOINT-PATH` and `X-DIAL-CACHE-EXPIRE-AT`.
- `UpstreamRouteProvider.java:get(Deployment, CacheBreakpointContext, Function, String)` with a client
  `X-UPSTREAM-ID` (read in `MessagesBaseController.java:prepareUpstreamRoute`) narrows the upstreams to that one and
  keeps the cache context, so a forced upstream still gets its pin written. The integration test relies on this.
- The anthropic adapter reports the same paths: `aidial_adapter_anthropic/passthrough/_caching.py:_get_cache_controls`
  counts a scalar as index 0, and `get_cache_headers` reports the last marked block, or the last candidate (which may
  be a string `content[0]` or `system[0]`) when the request has a top-level `cache_control`. So pins at string
  positions are written today too; D1 covers both directions (pin on the block and the string later, and the
  reverse). Paths need no change, only the digest input.

## Goals / Non-Goals

**Goals:**

- A string `content` or `system` and its one-text-block form give the same hash at their own position and at every
  later one.
- The pin-lookup warning no longer fires when no pin was found. It still fires when a pin names an upstream that is
  not in the route's upstream list: one gone from the config, or one that a client `X-UPSTREAM-ID` leaves out (see
  D3).

**Non-Goals:**

- Any change to the hashes of block-only prefixes, to the paths, or to `ChatCompletionRequest` and
  `ResponsesApiRequest`.

## Decisions

### D1. A string feeds the digest as its text block does

In `MessagesApiRequest.java:updateExcludingCacheControl`, a textual node is first turned into the block
`{"type": "text", "text": <s>}` and then goes down the object path, so the two shapes cannot drift apart whatever that
path feeds. It is done in the helper, not at its callers, so `system[i]` gets it as well as `messages[i].content[j]`.

Rejected (first version, changed after PR review): feeding the string and then a static `TextNode` `"text"`. Same
digest and no node per string, but it copies by hand what the object path feeds, so a change to that path would break
it (caught only by the equality unit test, tasks 1.1).

Rejected: the reverse, hashing a plain text block as its string. Claude Code sends `system` and the newest user message
as blocks, so every stored pin would move to a new hash; with D1, prefixes that hold no string keep their hash. D1 is
also what LiteLLM PR #42080 does.

### D2. Only a textual node changes

Numbers, booleans and arrays where a block is expected still feed as they are. A string `tools[i]` also goes through
the helper, but the Anthropic API rejects a string tool, so that hash never belongs to a served prompt.

Rejected: a parameter that limits D1 to `content` and `system`. It adds a branch for input the provider refuses.

### D3. Warn only for a pin whose upstream is gone

In `UpstreamRouteProvider.java:get(String, List, int, UpstreamCacheContext)`, when no upstream matched: `log.warn` as
today if the entry has an id or an endpoint, else `log.debug` that no pin was found, with the entry's prefix path.

Known leftover: with a client `X-UPSTREAM-ID`, `UpstreamRouteProvider.java:get(Deployment, CacheBreakpointContext,
Function, String)` narrows the list to that upstream before the pin is matched, so a pin that names another upstream
still in the config still logs `cached upstream doesn't exist any longer in config`. D3 leaves this case as it is.

Rejected: `getCacheEntry` returning `null` on a miss. The miss entry carries the path Core sends to the adapter as
`X-DIAL-CACHE-BREAKPOINT-PATH`, so the origin request would change.

### D4. A deterministic integration test

A new test model, `claude-cache-tiers`, has `autoCachingSupported: true`, `up-a` on tier 0 and `up-b` on tier 1.
Without a pin the balancer always picks `up-a`. The first turn forces `up-b` with `X-UPSTREAM-ID`, so the pin names
`up-b`, and the second turn, sent without the header, reaches `up-b` only if the pin is found.

Rejected: the existing `claude-cache` model with two equal upstreams. `MessagesApiTest.testAutoCachingPinsSecondTurnToSameUpstream`
would pass half the time without the fix. Rejected: seeding the balancer's random generator; the integration stack
does not expose it.

## Effect per request

No new Core pass, no body copy and no parse of a body Core only forwards. Per string `content` or `system`: one
two-field `ObjectNode`, its sorted copy (`JsonUtil.java:sort`) and one more `CacheKeyBuilder.java:update` call (a
6-byte digest update, `"text"`). That is what a text block already costs today; `JsonUtil.sort` of the message
already copies every block. No new Redis or blob round trip: the lookup walk is the same, and a request that now finds
a pin pays the one `HMGET` any pin hit pays today. Measured by inspection of the diff; no load run, since one small
object per string element against the copies for a prompt of kilobytes does not show in CPU per request.

## Risks / Trade-offs

- [The provider renders the two shapes differently] → Anthropic documents a string `content` as shorthand for one
  text block, and the report says Bedrock's cache hits on either shape (reported, not checked from code). If a provider
  did differ, the pinned upstream would miss its cache once, which costs no more than today's random pick.

## Migration Plan

No config change. After the deploy, a prefix that holds a string `content` or `system` at or before the pinned
position has a new hash, so its stored pin is not found once: the request goes through the balancer and its answer
writes a pin under the new hash. Each live conversation re-pins once. Old entries expire by their TTL, the adapter's
`X-DIAL-CACHE-EXPIRE-AT` or 10 minutes (`UpstreamCacheService.DEFAULT_TTL`). Prefixes with no string keep their hash.
During a rolling deploy, old and new pods hash such a prefix differently, so a conversation served by both can lose its
pin more than once until the rollout ends. Rollback has the same one-time cost in reverse.

## Implementation notes

- Drift check against the code at `a37f94da8`: none. Every Context bullet holds (`MessagesBaseController` lives in
  `controller/anthropic/`). `openspec/specs` has no `upstream-cache-affinity` yet, so the delta stays an ADDED
  requirement of a new capability; no rebase needed.
- D1 first shipped as `TEXT_TYPE = TextNode.valueOf("text")` fed after the string; after PR review the string is
  turned into its block instead (D1 now). D3: the debug line is `no cached upstream found: prefixPath={}`.
- The two integration tests share one helper, `MessagesApiTest.sendPinnedThenUnpinned`, which also asserts the first
  turn reached `keyB`. Before the fix both returned `keyA`, and `testGet_PinMissLogsNoWarning` saw the warning with
  `id=null, endpoint=null`.

## Spec review

1. Fidelity, medium, accepted. With only `cacheSupported`, `UpstreamCacheService.java:buildCacheBreakpointContext`
   makes only `cache_control` positions candidates and `MessagesApiRequest.java:hasCacheControl` is false for a
   string, so the routing SHALL claimed too much. The spec now keeps the equal-prefix sentence for both flags and states
   the routing SHALL for `autoCachingSupported`, and for `cacheSupported` alone only when the later request matches at
   a `cache_control` position. The proposal's first bullet and the docs sentence in tasks 1.3 are scoped the same way.
2. Fidelity, low, accepted. `UpstreamRouteProvider.java:get(Deployment, CacheBreakpointContext, Function, String)`
   narrows the list to the `X-UPSTREAM-ID` upstream before the pin is matched. The Goals line and the proposal's
   logging bullet now say the warning also fires when `X-UPSTREAM-ID` leaves out the pinned upstream, and D3 names
   this as a known leftover. No code change.
3. Fidelity, low, accepted. `UpstreamRoute.java:next` sends the attempt to the pinned upstream instead of
   `balancer.next`. The "String message after its text block" scenario now says the first attempt of the second
   request goes to the pinned `up-b` in place of the balancer's pick `up-a`, with a pointer to deployment-origin,
   "Upstream choice and the attempt bound". It sits in the scenario, not the requirement text, because
   `validate --strict` rejects requirement text over 500 characters.
4. Fidelity, low, accepted. `CacheKeyBuilder.java:update` runs `JsonUtil.sort`, `toString` and `getBytes` before the
   digest update. D1 now says the static node only saves creating the node; Effect per request and the tasks
   follow-up state one more `update` call per string element, the cost of a text block's `type` value today. The
   no-load-run conclusion stays.
5. Fidelity, low, accepted. `_caching.py:get_cache_headers` reports `paths[-1]` when the request has a top-level
   `cache_control`, which can be a string position. The Context bullet now says so and that D1 covers both directions.
6. Simplicity, medium, accepted. Same defect as finding 1; the same edits cover it, with the routing sentence scoped
   to `autoCachingSupported` and the `cacheSupported`-only case stated.
7. Simplicity, low, accepted. Checked: without the fix the string and the block already differ, so the second request
   goes to `up-a` with or without it. The third scenario and `testTextBlockWithExtraFieldIsNotPinnedForString` are
   dropped; the requirement keeps the extra-field sentence, covered by
   `MessagesApiRequestTest.testBuildCacheKeys_textBlockWithExtraFieldDiffersFromString`. Tasks 1.1 now asserts `keyB`
   in both integration tests.
8. Simplicity, low, accepted. `UpstreamRouteProviderTest.testGet_CachedUpstreamMissed` already uses an entry with an
   id no upstream has and asserts the tier 0 upstream. Tasks 2.1 now adds the `WARN` assertion to that test and adds
   only `testGet_PinMissLogsNoWarning`, with the no-`WARN` assertion.
