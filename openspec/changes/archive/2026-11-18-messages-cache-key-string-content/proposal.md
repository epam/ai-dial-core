# Proposal

## Why

Core pins a conversation to the upstream that holds its provider prompt cache: the upstream reports the cached prefix
in `X-DIAL-CACHE-BREAKPOINT-PATH`, Core stores a Redis entry under the hash of that prefix, and the next request whose
prefix has the same hash goes to the same upstream. For `POST /anthropic/v1/messages` the hash depends on the shape of
a string element. Claude Code sends a user message as a one-element text-block array
`[{"type": "text", "text": "...", "cache_control": {...}}]` while it carries the cache marker, and as the plain string
`"..."` in the next request. Anthropic treats both as the same prompt, but Core hashes them differently, so the second
request misses the pin and lands on a random upstream whose provider cache is cold.

Reported from production (Core 0.49.4): a Claude model with `autoCachingSupported: true` and about 50 upstreams on
different AWS accounts. Each miss is a full prompt write on another account instead of a cache read.

## What Changes

- On `POST /anthropic/v1/messages`, a string `messages[i].content` and a string `system` hash as their one-text-block
  form `[{"type": "text", "text": <the string>}]` (without `cache_control`, which Core already leaves out of the
  hash). A request that sends the same text in the other shape finds the pin wherever Core looks it up (every
  position with `autoCachingSupported`, only `cache_control` positions otherwise) and goes to the pinned upstream.
  The paths Core hashes and the adapter reports (`content[0]`, `system[0]`) do not change.
- A text block with any other field (for example `citations`) still hashes differently from the string.
- Logging: Core no longer logs the warning `cached upstream doesn't exist any longer in config` when no pin was found;
  that case logs at debug. The warning stays for a pin whose upstream is not in the route's upstream list: one
  removed from the config, or one that a client `X-UPSTREAM-ID` leaves out.

No config change. After the upgrade, a pin stored for a prefix that contains a string element is not found once; the
conversation pins again on its next request (see design.md, Migration Plan).

Out of scope:

- `POST /openai/deployments/{id}/chat/completions` and `POST /openai/v1/responses`. They hash differently, have no
  native `cache_control`, and no known client flips the shape between turns.
- Lookups at every position when `autoCachingSupported` is false. Without the flag only positions with a
  `cache_control` marker are candidates, so a plain-string message in a later turn is not looked up at all. Production
  has the flag on, so this does not cause the reported misses. It is a separate decision.
- A cap on how far back the lookup walks, adapter changes, and any change to when or how pins are written.

## Capabilities

### New Capabilities

- `upstream-cache-affinity`: how Core sends a request whose prompt prefix an upstream has cached to that upstream.
  This change adds only the requirement it needs: a string element and its one-text-block form are the same prefix on
  the Anthropic Messages interface. No existing capability covers upstream cache pinning; `deployment-origin` names
  the `X-DIAL-CACHE-BREAKPOINT-PATH` header Core sends and the balancer's upstream choice, not how a pin is matched.

### Modified Capabilities

None.

## Impact

Motivated by a production report of upstream cache misses for Claude Code on `/anthropic/v1/messages`. This change
covers the string-versus-block hash mismatch and the misleading warning. It leaves out the lookup without
`autoCachingSupported` (see Out of scope), which the report did not need.

Precedent, not ported code: LiteLLM PR #42080 "keep prompt caching affinity when the breakpoint moves" (merged
2026-09-21) hashes a string `content` as one `text` block for the same Claude Code behaviour.

Code: `server` (`MessagesApiRequest` digest input, `UpstreamRouteProvider` log level). Tests: `MessagesApiRequestTest`,
`MessagesApiTest`, `UpstreamRouteProviderTest`, a new test model in `server/src/test/resources/aidial.config.json`.
Docs: one sentence in `docs/dynamic-settings/models.md`. No API, OpenAPI or dependency change.
