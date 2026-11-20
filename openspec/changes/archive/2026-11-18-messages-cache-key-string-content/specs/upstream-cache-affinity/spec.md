# Spec Delta

## Purpose

How Core sends a request whose prompt prefix an upstream has already cached to that same upstream: the pin Core
stores in Redis when an upstream answers with `X-DIAL-CACHE-BREAKPOINT-PATH`, and how a later request is matched to it.

## ADDED Requirements

### Requirement: A string and its text block are the same prefix
On `POST /anthropic/v1/messages` to a model with `cacheSupported` or `autoCachingSupported`, Core SHALL look up a
pin as if a string `messages[i].content` or `system` were `[{"type": "text", "text": <the string>}]`. With
`autoCachingSupported`, the same prefix sent later in the other shape SHALL go to the pinned upstream; with only
`cacheSupported`, only when it matches at a `cache_control` position. A text block with a field other than `type`,
`text` and `cache_control` is a different prefix.

#### Scenario: String message after its text block
- **WHEN** model `m` has `autoCachingSupported: true` and upstreams `up-a` (tier 0) and `up-b` (tier 1), a client
  sends `messages: [{"role": "user", "content": [{"type": "text", "text": "Hello", "cache_control": {"type":
  "ephemeral"}}]}]` to `m` with `X-UPSTREAM-ID: up-b`, and `up-b` answers `200` with
  `X-DIAL-CACHE-BREAKPOINT-PATH: prefix.body.messages[0].content[0]`
- **AND** the same API key then sends, without `X-UPSTREAM-ID`, messages that start with
  `{"role": "user", "content": "Hello"}`, followed by an assistant answer and a new user text block with
  `cache_control`
- **THEN** the first attempt of the second request goes to the pinned `up-b` in place of the balancer's pick `up-a`
  (see deployment-origin, "Upstream choice and the attempt bound"): its origin request carries the `X-UPSTREAM-KEY`
  of `up-b`

#### Scenario: String system after its text block
- **WHEN** the same model gets `system: [{"type": "text", "text": "Be brief", "cache_control": {"type":
  "ephemeral"}}]` with `X-UPSTREAM-ID: up-b`, and `up-b` answers `200` with
  `X-DIAL-CACHE-BREAKPOINT-PATH: prefix.body.system[0]`
- **AND** the same API key then sends `system: "Be brief"` with a longer conversation and no `X-UPSTREAM-ID`
- **THEN** the second request goes to `up-b`
