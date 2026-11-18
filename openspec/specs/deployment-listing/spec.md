# deployment-listing Specification

## Purpose

Describes what `GET /v1/deployments` returns and how a caller narrows it: the deployments by interface type, and
the fields of each deployment by a field selection.

## Requirements

### Requirement: Fields parameter selects response fields
`GET /v1/deployments` SHALL accept an optional query parameter `fields`. When it is present and not empty, each
returned deployment SHALL hold only the top-level fields the selection names, plus `id` and `object`. Names are the
names of the response: snake_case at the top level, and inside a nested object the names that object has.

#### Scenario: Top-level fields only
- **WHEN** a caller sends `GET /v1/deployments?fields=display_name`
- **THEN** the answer is `200` with the same deployments as without `fields`, and each one has exactly the members `id`, `object` and `display_name`

#### Scenario: Several names
- **WHEN** a caller sends `fields=display_name,display_version,features`
- **THEN** each deployment has `id`, `object`, `display_name`, `display_version` and the whole `features` object, and no other member

### Requirement: Id and object are always returned
`GET /v1/deployments` SHALL return `id` and `object` for every deployment, whether or not `fields` names them.

#### Scenario: Selection without id and object
- **WHEN** a caller sends `fields=features`
- **THEN** every returned deployment still has `id` and `object`, and its `object` is `model`, `application`, `toolset` or `router` as without `fields`

### Requirement: Nested selection
A name in `fields` MAY be followed by a parenthesised selection that applies to the value of that field. A selection
on an object keeps only the members it names. On an array, it applies to each object element. A name `*` matches
every key at its level. A selection on a value that has no members (a string, a number, an array of strings) SHALL
leave that value unchanged.

#### Scenario: Nested object
- **WHEN** a caller sends `fields=display_name,features(rate,tokenize)`
- **THEN** each deployment has `id`, `object`, `display_name` and a `features` object with only `rate` and `tokenize`

#### Scenario: Wildcard over a map
- **WHEN** a caller sends `fields=interface_configs(*(features(reasoning_efforts)))` and a listed deployment has two entries in `interface_configs`
- **THEN** that deployment's `interface_configs` has both keys, and each entry holds only `features` with only `reasoning_efforts`

#### Scenario: Selection on a scalar
- **WHEN** a caller sends `fields=display_version(major)` and `display_version` is a string
- **THEN** each deployment that has `display_version` returns it unchanged

#### Scenario: Same name twice
- **WHEN** a caller sends `fields=features(rate),features(tokenize)`
- **THEN** `features` holds both `rate` and `tokenize`

### Requirement: Selection reaches inside localized values and pricing rates
A nested selection SHALL apply inside a localized value that holds several locales (`display_name`, `description`),
where the locales are its members, and inside a pricing rate that is a decision tree (`pricing.prompt`,
`pricing.completion`, `pricing.cache_read`, `pricing.cache_write`), where `test`, `ifTrue` and `ifFalse` are its
members.

#### Scenario: Locale of a localized value
- **WHEN** a caller sends `fields=display_name(en)` and a deployment's `display_name` holds several locales
- **THEN** that deployment's `display_name` holds only `en`

#### Scenario: Member of a decision-tree pricing rate
- **WHEN** a caller sends `fields=pricing(prompt(ifTrue))` and a model's `pricing.prompt` is a decision tree
- **THEN** that model's `pricing.prompt` holds only `ifTrue`

### Requirement: Unknown names are ignored
`GET /v1/deployments` SHALL NOT reject a `fields` name because no response field has it. A name that matches no
member of a deployment adds nothing to that deployment.

#### Scenario: Misspelled name
- **WHEN** a caller sends `fields=display_name,descriptio`
- **THEN** the answer is `200` and each deployment has `id`, `object` and `display_name` only

#### Scenario: Field of another kind
- **WHEN** a caller sends `fields=application_properties` and the listing holds a model, a toolset and a schema-rich application
- **THEN** the application has `id`, `object` and its `application_properties`, and the model and the toolset have `id` and `object` only

### Requirement: Malformed selection is rejected
`GET /v1/deployments` SHALL answer `400` with a message when `fields` cannot be read as a selection: an opening
parenthesis without its closing one or the reverse, an empty name (as in `a,,b`, `a(,b)` or `()`), a parenthesis
with no name before it, or text after a closing parenthesis other than `,` or `)`.

#### Scenario: Unbalanced parenthesis
- **WHEN** a caller sends `fields=features(rate`
- **THEN** the answer is `400` and no deployment is returned

#### Scenario: Empty name
- **WHEN** a caller sends `fields=id,,display_name`
- **THEN** the answer is `400`

### Requirement: Selection size limits
`GET /v1/deployments` SHALL answer `400` when the `fields` value is longer than 1,024 bytes (UTF-8) or nests deeper than
five levels. A top-level name is level one, and each parenthesis opens the next level.

#### Scenario: Too long
- **WHEN** a caller sends a `fields` value of 1,025 bytes
- **THEN** the answer is `400`

#### Scenario: Too deep
- **WHEN** a caller sends `fields=a(b(c(d(e(f)))))`
- **THEN** the answer is `400`, and `fields=a(b(c(d(e))))` is read as a valid selection

### Requirement: Empty fields behaves as absent
`GET /v1/deployments` SHALL return the full listing when `fields` is empty or holds only whitespace.

#### Scenario: Empty value
- **WHEN** a caller sends `GET /v1/deployments?fields=`
- **THEN** the answer is the same as without `fields`

### Requirement: Fields leaves the listing otherwise unchanged
Without `fields` the response of `GET /v1/deployments` SHALL be the one it is today. With `fields` the set of
deployments, their order and the effect of `interface_type` SHALL be the same as without it, and no other endpoint
SHALL read `fields`.

#### Scenario: No parameter
- **WHEN** a caller sends `GET /v1/deployments` without `fields`
- **THEN** each deployment has every field it has today (`features`, `scale_settings`, `interfaces`, and the rest)

#### Scenario: With interface type
- **WHEN** a caller sends `interface_type=mcp&fields=display_name`
- **THEN** the deployments are the ones `interface_type=mcp` alone lists, and each has `id`, `object` and `display_name` only

#### Scenario: Other endpoints
- **WHEN** a caller sends `GET /v1/deployments/{id}?fields=display_name`
- **THEN** the answer is the full deployment, as without `fields`
