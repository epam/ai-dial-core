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

### Requirement: Reasoning from a removed upstream
When the upstream that the request would be sent to by its stamped `reasoning` items is no longer in the model's
`upstreams`, Core SHALL answer `409` with error code `encrypted_content_upstream_unavailable` and send nothing to the
origin.

#### Scenario: Stamped upstream no longer configured
- **WHEN** the `input` holds a `reasoning` item stamped with an upstream id that `m` does not have
- **THEN** Core answers `409` with error code `encrypted_content_upstream_unavailable`

### Requirement: Reasoning from another upstream dropped
When the `input` of `POST /openai/v1/responses` to a model with `upstreams` holds `reasoning` items stamped by
different upstreams, Core SHALL send the request to the upstream of the first stamped item in `input`, SHALL remove
from `input` every later `reasoning` item whose `id` or `encrypted_content` is stamped by another upstream, and SHALL
log a warning for each removed item. Other items keep their order.

#### Scenario: Reasoning from two upstreams
- **WHEN** model `m` has upstreams `up-a` and `up-b`, and `input` is a `reasoning` item stamped by `up-a`, a
  `reasoning` item stamped by `up-b`, and a user message
- **THEN** Core answers with the origin's `200`, the request reaches `up-a`, and the origin `input` is the `up-a` item,
  restored, followed by the user message
