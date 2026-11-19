# Design

## Context

Core pins an Anthropic Messages conversation to the upstream that served its prompt prefix, so the provider's prompt
cache stays warm on that upstream. For each position in the request (each tool, each `system` element, each message
content block) Core computes a rolling hash of everything up to that position, leaving out `cache_control`, and looks
the hashes up in Redis from the last one back. With `autoCachingSupported` every position is looked up and the hashes
are scoped to the user or the key's project; with only `cacheSupported`, only the positions that carry `cache_control`.
When the upstream answers, Core stores a pin under the hash of the path the adapter reports, and the adapter counts a
string `content` or `system` as index 0, as Core does. A string hashes as the bare string, but a text block hashes as
its `text` and `type` values, so Claude Code, which sends the newest user message as a marked text block and the same
message as a plain string in the next request, misses its pin and gets the balancer's pick. Every such miss also logs
the warning `cached upstream doesn't exist any longer in config` with an empty id and endpoint.

## Decisions

### D1. A string is hashed as its text block, not a text block as its string

Claude Code sends `system` and the newest user message as blocks, so the pins it relies on are block hashes. Changing
the string side keeps every prefix without a string at its current hash, and both directions match: a pin written on
the block is found by the string later, and the reverse. LiteLLM does the same for the same Claude Code behaviour
(https://github.com/BerriAI/litellm/pull/42080).

**Rejected: hashing a plain text block as its string** - every block pin stored today would move to a new hash, and a
block with any other field would still need the block hash.

### D2. The string becomes a real block before it is hashed, at both callers of the hashing helper

A string is turned into `{"type": "text", "text": <s>}` and then hashed by the same code as any block, so the two
shapes stay equal whatever that code feeds. The conversion is one small function, called where the `system` elements
and where the message blocks are read, so the hashing helper only skips `cache_control`. The `system` caller also
reads `tools`, so a string `tools[i]` is converted too; the Anthropic API rejects a string tool, so that hash never
belongs to a served prompt. Numbers, booleans and arrays where a block is expected are hashed as they are.

**Rejected: feeding the string and then a constant `"text"`** - the same hash and no object per string, but it copies
by hand what the block code feeds, so a change there would break it; only the equality unit test would notice.

**Rejected: converting inside the hashing helper** - the same hash, but the conversion hides in a function about
`cache_control` and reassigns its parameter.

**Rejected: converting at the message-content caller only** - a string `system` would keep the bare-string hash.

**Rejected: a parameter that limits the conversion to `content` and `system`** - a branch for input the provider
refuses.

### D3. The pin warning fires only for a pin that names an upstream

A lookup that finds no pin returns an entry with no id and no endpoint, so the warning on it fired on every request
without a pin. It now logs at debug with the entry's prefix path. A pin whose upstream is gone from the config still
warns. So does a pin that names an upstream other than the one a client `X-UPSTREAM-ID` asks for, because the
upstream list is narrowed to that one before the pin is matched; that case is left as it is.

**Rejected: returning no entry on a miss** - the miss entry carries the path Core sends to the adapter as
`X-DIAL-CACHE-BREAKPOINT-PATH`, so the origin request would change.

### D4. The integration test pins to a tier-1 upstream, so a missed pin always goes elsewhere

A new test model, `claude-cache-tiers`, has `autoCachingSupported: true`, `up-a` on tier 0 and `up-b` on tier 1.
Without a pin the balancer always picks `up-a`. The first turn forces `up-b` with `X-UPSTREAM-ID`, which keeps the
cache context, so the pin names `up-b`; the second turn, sent without the header, reaches `up-b` only if the pin is
found.

**Rejected: the existing `claude-cache` model with two equal upstreams** - the test would pass half the time without
the fix.

**Rejected: seeding the balancer's random generator** - the integration stack does not expose it.

## Differences from today

| What | Today | After | Requirement |
| --- | --- | --- | --- |
| Hash of a prefix that holds a string `messages[i].content` or `system` | the bare string | the string's one text block `{"type": "text", "text": <s>}` | upstream-cache-affinity, "A string and its text block are the same prefix" |
| Upstream of a later request that sends the same prefix in the other shape | the balancer's pick | the pinned upstream: at any position with `autoCachingSupported`, at a `cache_control` position with only `cacheSupported` | same |
| Log line of a lookup that finds no pin | `WARN cached upstream doesn't exist any longer in config: id=null, endpoint=null` | `DEBUG no cached upstream found: prefixPath=<path>` | none (a log line is not a spec boundary) |

## Effect per request

| What | Today | After | How measured |
| --- | --- | --- | --- |
| Core passes, body copies, parses of a forwarded body | 1 pass, none, none | the same | inspection of the diff |
| Hashing per string `content` or `system` element | one digest update | one two-field block, its sorted copy and two digest updates, as any text block costs; the sort of the message already copies every block | inspection of the diff; no load run, one small object per string against the copies of a prompt of kilobytes |
| Redis on a request that now finds its pin | every `EXISTS` batch of 64 candidates, no `HMGET` | the batches up to the one holding the pin, then one `HMGET`, as any pin hit | inspection of the diff |

## Risks / Trade-offs

- [The provider renders the two shapes differently] → Anthropic documents a string `content` as shorthand for one
  text block, and the report says Bedrock's cache hits on either shape (reported, not checked from code). If a provider
  did differ, the pinned upstream would miss its cache once, which costs no more than today's random pick.

## Migration Plan

No config change. After the deploy, a prefix that holds a string `content` or `system` at or before the pinned
position has a new hash, so its stored pin is not found once: the request goes through the balancer and its answer
writes a pin under the new hash. Each live conversation pins again once. Old entries expire by their TTL, the
adapter's `X-DIAL-CACHE-EXPIRE-AT` or 10 minutes. Prefixes with no string keep their hash. During a rolling deploy,
old and new pods hash such a prefix differently, so a conversation served by both can lose its pin more than once
until the rollout ends. Rollback has the same one-time cost in reverse.

## Code map

- `MessagesApiRequest.java:asBlock` - the change (D1, D2): a string becomes its text block.
- `MessagesApiRequest.java:appendItemCacheKeys` (`tools`, `system`) and `MessagesApiRequest.java:appendMessageCacheKeys`
  (the message's other fields, then `messages[i].content[j]`) - call `asBlock`, then the hashing helper.
- `MessagesApiRequest.java:updateExcludingCacheControl` - feeds an object's values in sorted key order without
  `cache_control`, any other node as it is.
- `MessagesApiRequest.java:hasCacheControl` - false for a string, so with only `cacheSupported` a string position is
  not a lookup candidate.
- `CacheKeyBuilder.java:update`, `CacheKeyBuilder.java:buildKey`, `CacheKeyBuilder.java:elements` - one rolling SHA-1
  over each node's sorted JSON text; a snapshot per position without reset; a scalar as one element at index 0.
- `JsonUtil.java:sort` - the sorted copy of an object.
- `UpstreamCacheService.java:buildCacheBreakpointContext` - the lookup candidates and the initiator scope
  (`ProxyContext.java:getInitiatorId`).
- `UpstreamCacheService.java:getCacheEntry` - the walk from the last candidate back, `EXISTS` batches of 64, `HMGET`
  on the first hit, and the miss entry with the last candidate path.
- `BaseDeploymentPostController.java:sendProxyRequest` - sends the route's breakpoint path to the adapter as
  `X-DIAL-CACHE-BREAKPOINT-PATH`.
- `UpstreamRouteProvider.java:get(String, List, int, UpstreamCacheContext)` - the match by id, then by endpoint, and the
  log line (D3).
- `UpstreamRouteProvider.java:get(Deployment, CacheBreakpointContext, Function, String)` and
  `MessagesBaseController.java:prepareUpstreamRoute` - a client `X-UPSTREAM-ID` narrows the list and keeps the cache
  context (D3, D4).
- `UpstreamRoute.java:next`, `UpstreamRoute.java:updateUpstreamCache`, `TieredBalancer.java:next` - the first attempt
  goes to the pinned upstream, else the lowest tier; the pin write from `X-DIAL-CACHE-BREAKPOINT-PATH` and
  `X-DIAL-CACHE-EXPIRE-AT`.
- Adapter, not in this repo: `aidial_adapter_anthropic/passthrough/_caching.py:_get_cache_controls` and
  `get_cache_headers` - a scalar counts as index 0; the reported path is the last marked block, or the last candidate
  when the request has a top-level `cache_control`.
- Tests to reuse: `MessagesApiTest.testAutoCachingPinsSecondTurnToSameUpstream` (the two-turn pin pattern),
  `MessagesApiTest.awaitPin` (waits for the async pin write in Redis),
  `ProxyContextTest.testRespondWithNoContentDoesNotLogWarning` (a logback `ListAppender` on a logger).
