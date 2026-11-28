# Design

## Context

For a model request, Core builds an upstream route from the model's `upstreams`. The balancer groups the upstreams by
tier, takes the highest tier that has an available upstream, and picks one at random in proportion to `weight`. An
upstream that answered a retriable status cools down and is skipped until its time is over. Three pins replace the
random pick: a client `X-UPSTREAM-ID` and, on Responses, the upstream stamped in echoed reasoning items both narrow the
list to one upstream, and an upstream cache entry, found in Redis by the hash of the prompt prefix, names the upstream
of the first attempt. Attempts after the first go through the balancer, within `maxRetryAttempts`. Core already
resolves a session id from the `tracing.conversationIdHeaders` request headers for spans and for the analytics
`session.id`, but upstream choice does not use it. So a request of a session that no pin catches, such as a harness
side request, lands on a random upstream, where the provider's prompt cache for that session is cold.

## Decisions

### D1. Sessions are bound by weighted rendezvous hashing

For each candidate upstream Core computes `u = hash(session id, upstream id)` in (0, 1) and the score
`-ln(u) / weight`. The upstream with the smallest score wins. This is the same as the largest `weight / -ln(u)`, the
usual form of weighted rendezvous ("highest random weight") hashing. The score is an exponential random value with
rate `weight`, and the smallest of independent exponential values comes from upstream `i` with probability
`weight_i / sum of weights`. So each upstream gets its weight's share of sessions. Each score depends only on its own
upstream. So when an upstream leaves the candidates (cool-down, config), only its sessions move, spread over the others
in proportion to their weights, and they return when it comes back. A new upstream takes only its own share, from
every other upstream in proportion. A weight change moves sessions only to or from that upstream. There is no state to
share between Core instances, and the cost is one hash per upstream (about 20 for the largest models today).

**Rejected: a plain modulo hash** (`hash(session) mod n`, or a table of weighted buckets) - any change in the set of
candidates, including one upstream cooling down, remaps almost every session, and a weight change rebuilds the table.
**Rejected: a consistent-hash ring with virtual nodes** - weights are only approximate (through the number of virtual
nodes per upstream). The ring must be rebuilt on every config change, and the sessions of an upstream that cools down
go to its ring neighbours, not to all upstreams in proportion to weight. Its faster lookup gains nothing with about 20
upstreams.
**Rejected: a sticky table in Redis** (session id to upstream) - a Redis read on every request and a write on the
first one, a TTL to choose, entries to repair on config changes, and a new stored record. Weights hold only at the
moment of assignment. The upstream cache entry already is the stored pin for prefix matches.

### D2. The hash is SHA-256 from the JDK, cut to 53 bits

`u` comes from the first 8 bytes of SHA-256 over the UTF-8 session id, one zero byte and the UTF-8 upstream id. The
bytes are read as an unsigned 64-bit number `h`, and `u = (floor(h / 2^11) + 0.5) / 2^53`, so `u` is never 0 or 1.
The upstream id is the `id`, or the `endpoint` when there is no `id`, the same identity that `X-UPSTREAM-ID` matches.
On equal scores the upstream listed first wins. Every JVM computes the same value, so all Core instances and versions
agree until this decision changes.

**Rejected: `String.hashCode` or another 32-bit hash** - similar session ids give correlated values, and 32 bits
leave visible steps in `u`.
**Rejected: xxHash or Murmur3** - faster, but they need a new dependency or a copied implementation. About 20 SHA-256
digests of under 1 KB cost a few microseconds (task 2.4 measures it).

### D3. The toggle is a per-model boolean `sessionAffinity`, default `false`

The benefit depends on the model: it needs several upstreams and a provider cache that is local to one upstream. The
dynamic config is hot-reloaded, so an operator can turn the feature on for one model and off again without a restart.
With the default `false`, nothing changes on upgrade.

**Rejected: a global default in the static settings** - it needs a restart, it reaches models that gain nothing, and
it makes the per-model field three-state (unset, `true`, `false`). It can be added later as the value of an unset
field, with no change to the model field.
**Rejected: a field in the dynamic global settings** - hot-reloaded, but the same reach problem. It can also be added
later as the value of an unset field.
**Rejected: an enum such as `upstreamSelection: "random" | "session"`** - there are two values today. A boolean is
simpler to set and to test.
**Rejected: a request header that turns it on** - the harnesses cannot be changed, and the operator, not the client,
knows which models have many upstreams.

### D4. The session id is the one Core resolves for tracing and analytics

Core reads `tracing.conversationIdHeaders` (the first usable header, trimmed, at most 256 characters, credential
headers refused) through the resolver that spans, the analytics `session.id` and background jobs already use. One list
covers the harness headers for all three uses. The cost of this choice: an operator who empties the list to stop the
tracing correlation also stops session affinity. The docs say so.

**Rejected: a separate header list for routing** - two lists to keep in step for the same harness headers.
**Rejected: a hash key that includes the caller (user or API key)** - the shipped harnesses send random session ids,
so two callers rarely share one. When they do, both only land on the same upstream, which any two callers can do
today.

### D5. The session pick is for the first attempt only, below the three existing pins

`X-UPSTREAM-ID` is an explicit choice. A stamped reasoning item can be decrypted only by its upstream. A cache entry is
evidence that the prefix is cached on that upstream. The hash is only a good guess, so it comes after all three.
Requests narrowed to one upstream by `X-UPSTREAM-ID` or by a reasoning item do not compute the hash. The cache entry is
written as today, whichever way the upstream was chosen. Attempts after the first go through the balancer as today, so
the attempt bound, the retriable statuses and the error answers do not change.

**Rejected: the session pick above the cache entry** - the cache entry knows where the prefix is cached; the hash does
not.
**Rejected: later attempts by the session's next choice** - this would keep a retry on the upstream that the session's
later requests use while the failed one cools down. Retries stay as they are today, so after a failure the retry can
warm a different upstream than the one the session's next requests reach (see Risks).

### D6. An upstream that cools down is skipped by the same hash

The hash runs over the upstreams that the balancer would use for the first attempt: the available ones with a weight
above 0 in the highest tier that has one. When the session's upstream cools down, the hash names the session's next
choice, the same one for every request until the upstream is available again. When no upstream of any tier is
available, Core uses the balancer's existing fallback.

**Rejected: the weighted random pick while the session's upstream cools down** - a cool-down lasts 30 s by default
after a `429` and up to 5 minutes after repeated `5xx`. Each request of the session would land on a random upstream
and miss the provider cache.
**Rejected: the session's upstream even while it cools down** (as the cache entry does) - every request would go to an
upstream known to fail.

## Differences from today

| What | Today | After | Requirement |
| --- | --- | --- | --- |
| Model config | no such field | `sessionAffinity`, boolean, default `false` | "Session affinity picks the first upstream" |
| First attempt of a request with a session id to a model with `sessionAffinity: true` that no other pin catches | weighted random pick in the highest available tier | the session's pick by weighted rendezvous hash in the same tier | "Session affinity picks the first upstream", "The session id comes from the conversation id headers", "Session shares follow upstream weights and tiers" |
| The same while the session's upstream cools down | weighted random pick | the session's next choice, the same for every request until the upstream is back | "Sessions move only from an upstream that leaves" |
| `X-UPSTREAM-ID`, reasoning items, cache entry lookup and write, later attempts | as described in their capabilities | unchanged | "Pins that win over session affinity", "Retries and requests without a session id are balanced as today" |
| Model without the field, request without a session id | weighted random pick | unchanged | "Retries and requests without a session id are balanced as today" |

## Effect per request

| What | Today | After, field off or no session id | After, field on with a session id | How measured |
| --- | --- | --- | --- | --- |
| Core passes, body copies, parses of a forwarded body | 1 pass, none, none | the same | the same | inspection of the diff |
| Redis and blob round trips | the cache entry lookup | the same | the same; no new read or write | inspection of the diff |
| Session id resolution | done for the analytics log and the span | the same | once more: up to 5 header lookups | inspection of the diff |
| Hashing on the first attempt | none | none | one SHA-256 per upstream with a weight above 0, only when no other pin applies | timing loop, task 2.4 |
| Balancer lock | held for the random pick | the same | held for a comparison of the scores; the digests run before the lock is taken | inspection of the diff |

## Risks / Trade-offs

- [Sessions differ in size] → weights split the sessions, not the requests or the tokens. A few heavy sessions, or a
  client that sends one constant value as its session id, put their whole load on one upstream. A `429` then moves
  them to their next choice (D6). The analytics `session.id` shows such values, and the operator can turn the field off
  for the model.
- [Cool-down state is per Core instance, as today] → during a cool-down one instance can send a session to its first
  choice and another instance to its next choice.
- [Config changes] → adding, removing or reweighting an upstream moves the sessions that map to it or away from it
  (D1). Each of them misses the provider cache once.
- [A retry can warm another upstream] → after the session's upstream fails, the retry takes the balancer's pick, but
  the session's next requests take its next choice (D5). The retry's cache work is lost when the two differ.
- [A client can steer] → a client can choose a session id that maps to a given upstream. `X-UPSTREAM-ID` already lets
  it name one, so this adds nothing.

## Migration Plan

No data migration, and nothing changes until an operator sets the field. Set `sessionAffinity: true` on a model only
after every Core instance runs this version: `Model` does not ignore unknown properties, so an older Core fails to
load a model that sets the field. For the same reason, remove the field before a rollback. Turning the field on or off
moves the model's sessions once, so each live session misses the provider cache at most once.

## Code map

- `Model.java` - the new field `sessionAffinity` with `@JsonAlias({"sessionAffinity", "session_affinity"})`, like its
  neighbours (D3).
- `UpstreamRouteProvider.java:get(Deployment, CacheBreakpointContext, Function, String)` - gains the session id. It
  keeps it only for a `Model` with `sessionAffinity` and when no upstream id narrows the list (D5); the `AliasPass`
  branch stays as it is.
- `UpstreamRouteProvider.java:get(String, List, int, UpstreamCacheContext)` - hands the session id to the route.
- `UpstreamRoute.java:next` - first attempt: the cache entry's upstream, else the session pick, else
  `TieredBalancer.next`; later attempts unchanged (D5). The scores are computed here, before the balancer's lock.
- `TieredBalancer.java:next`, `RandomizedWeightedBalancer.java:next`, `UpstreamState.java:isUpstreamAvailable` - the
  tier walk, the weight filter and the availability check that the session pick repeats in a new synchronized method
  of `TieredBalancer`, and the fallback rules in `TieredBalancer.next` for when nothing is available (D6).
- `GenAiTraceAttributes.java:resolveConversationId`, `TracingSettings.java` - the session id (D4); already called by
  `AnalyticsLogContext` and `BackgroundJobRecord`.
- `BaseChatCompletionController.java:processChatCompletionsRequestBody`, `ResponsesController.java:handleRequestBody`
  (header first, then `request.getEncryptedUpstreamId()`), `MessagesBaseController.java:prepareUpstreamRoute` - the
  three callers that resolve and pass the session id. `McpProxyController`, `ToolSetToolsController` and
  `BaseRouteController` pass none.
- `HashUtil.java:sha256Hex` - the JDK digest pattern: a `MessageDigest` per call (D2).
- Tests to reuse: `TieredBalancerTest`, `UpstreamRouteTest`, `UpstreamRouteProviderTest` (the provider takes a
  `Supplier<Random>`, so a unit test can fix the random pick), `MessagesApiTest.testAutoCachingPinsSecondTurnToSameUpstream`
  and `MessagesApiTest.awaitPin`, `ResponsesApiTest.testEncryptedContentAffinityPinsSecondTurnToSameUpstream`, and the
  test models in `server/src/test/resources/aidial.config.json`.
