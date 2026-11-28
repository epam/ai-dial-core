# Spec Delta

## ADDED Requirements

### Requirement: Body sent to the origin
Core SHALL send the origin the client's body bytes as received while no pass changed the body, and those bytes with
only the top-level `model` string replaced while passes changed only `model`. On any other change, on bytes Core and an
origin may read differently (not UTF-8, a repeated key, content around the object), or on a `model` the bytes lack, it
SHALL send the body serialized. An in-process alias target pass takes the alias pass's body; an origin plugin's call is
serialized.

#### Scenario: Body unchanged
- **WHEN** a client sends `POST /openai/v1/responses` to `model-iface-only`, a model without override name or
  defaults, with a body holding spaces, a line break and non-ASCII text
- **THEN** the origin receives exactly the client's bytes

#### Scenario: Override name replaces only the model
- **WHEN** a client sends `POST /anthropic/v1/messages` with `"model" : "claude-override"` after multi-byte text and
  escapes, and that model has an override name
- **THEN** the origin receives the client's bytes with only that value replaced by the override name

#### Scenario: Repeated key
- **WHEN** a client sends `POST /openai/v1/chat/completions` with `"temperature"` twice, `1` then `2`, to a model
  without override name or defaults
- **THEN** the origin receives the body serialized, with `"temperature":2` once

#### Scenario: Alias target served in process
- **WHEN** a client sends `POST /openai/v1/chat/completions` with `"model" : "assistant"`, an alias whose target
  `gpt-4-internal` runs in process, with spaces, an escape and multi-byte text in the body
- **THEN** the origin of `gpt-4-internal` receives the client's bytes with only `assistant` replaced by
  `gpt-4-internal`

#### Scenario: Change on the way
- **WHEN** the same client body also carries `custom_fields.interceptor_configuration`, which Core removes
- **THEN** the origin receives the body serialized, without `custom_fields`

#### Scenario: Router candidate behind an alias
- **WHEN** a client sends `POST /anthropic/v1/messages` with `"model": "strong"`, an alias of a router in decision
  mode that chooses `alias-m1`, an alias of model `m1`, every hop served in process
- **THEN** the origin of `m1` receives the client's bytes with only `strong` replaced by `m1`

#### Scenario: Embedded origin
- **WHEN** application `app` is served by plugin `p1`, and a client sends it a chat completion with spaces and
  multi-byte text; and a client sends `POST /openai/v1/chat/completions` to an alias of a model served by `p1`
- **THEN** `p1` receives the client's bytes as its request's `body` for the first, and the client's bytes with only
  the alias replaced by the model's name for the second

#### Scenario: Call from an origin plugin
- **WHEN** plugin `p1`, serving `app`, calls a model behind an HTTP origin with the body tree of a client body that
  holds spaces
- **THEN** the model's origin receives that tree serialized, without the spaces

### Requirement: Serialized body keeps the client's member order
Core SHALL send a body it serializes with the members in the client's order; computing the prefix cache keys SHALL NOT
reorder them.

#### Scenario: Serialized body keeps the client's member order
- **WHEN** a client sends `POST /anthropic/v1/messages` to `claude-cache`, a model with automatic caching, with a
  content block whose members are in the order `type`, `text`, `cache_control`, and with
  `custom_fields.interceptor_configuration`, which Core removes
- **THEN** the origin receives the body serialized, with that block's members in the client's order
