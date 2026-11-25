## ADDED Requirements

### Requirement: Reasoning item affinity
For a model with at least one entry in `upstreams`, Core SHALL stamp the `id` and the `encrypted_content` of each
`reasoning` item in a Responses answer with the `id` of the upstream that produced it, in JSON and streamed answers.
When a later `POST /openai/v1/responses` to such a model has stamped `reasoning` items in `input`, Core SHALL restore
them to the values the upstream returned and send the request to the stamped upstream.

#### Scenario: Echoed reasoning goes back to its upstream
- **WHEN** model `m` has upstreams `up-a` and `up-b`, a first request gets a `reasoning` item with `id` `rs_1` and
  `encrypted_content` `cipher-xyz`, and the client sends that item back in the `input` of a second request
- **THEN** the second request reaches the same upstream as the first, and its origin body carries the item with `id`
  `rs_1` and `encrypted_content` `cipher-xyz`

### Requirement: Reasoning from an unavailable or another upstream dropped
When the `input` of `POST /openai/v1/responses` to a model with `upstreams` holds `reasoning` items, Core SHALL send
the request to the upstream of the first item stamped by an upstream that is in the model's `upstreams`, and SHALL
remove from `input` every `reasoning` item that this upstream could not decrypt. Other items keep their order.

#### Scenario: Reasoning from two upstreams
- **WHEN** model `m` has upstreams `up-a` and `up-b`, and `input` is a `reasoning` item stamped by `up-a`, a
  `reasoning` item stamped by `up-b`, and a user message
- **THEN** Core answers with the origin's `200`, the request reaches `up-a`, and the origin `input` is the `up-a` item,
  restored, followed by the user message

#### Scenario: Stamped upstream no longer configured
- **WHEN** model `m` has upstreams `up-a` and `up-b`, and `input` is a `reasoning` item stamped by `up-old`, a
  `reasoning` item stamped by `up-a`, and a user message
- **THEN** Core answers with the origin's `200`, the request reaches `up-a`, and the origin `input` is the `up-a` item,
  restored, followed by the user message

#### Scenario: Only a removed upstream is stamped
- **WHEN** the `input` holds a `reasoning` item stamped by `up-old`, which `m` does not have, and a user message
- **THEN** Core answers with the origin's `200` without pinning an upstream, and the origin `input` is the user message

### Requirement: Reasoning items that cannot be decrypted
Core SHALL treat a `reasoning` item as one the chosen upstream cannot decrypt when it is stamped by an upstream that is
not in the model's `upstreams`, by another upstream than the chosen one, or by two different upstreams in its `id` and
`encrypted_content`. Core SHALL log one warning per request that removed items, with their count. The removed reasoning
is not sent again on later turns.

#### Scenario: Reasoning with inconsistent stamps
- **WHEN** `input` holds a `reasoning` item whose `id` is stamped by `up-a` and whose `encrypted_content` is stamped by
  `up-b`, followed by a `reasoning` item stamped by `up-b`
- **THEN** the first item is removed, the request reaches `up-b`, and the origin `input` holds the second item
