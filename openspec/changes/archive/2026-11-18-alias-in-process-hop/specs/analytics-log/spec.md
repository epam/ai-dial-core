# Spec Delta

## ADDED Requirements

### Requirement: Rows without bodies for an alias pass
Core SHALL write the row of an alias pass without `request.body`, `response.body` and `assembled_response`, and with
every other field, whatever the key, the interface and whether the target pass runs in process. The row of the
alias's target pass SHALL carry its bodies as "Bodies in the analytics row" says; a target pass that refuses the call
before its origin writes no row, as on a direct call.

#### Scenario: Messages through an alias
- **WHEN** a caller with a key that is not `secured` sends `/anthropic/v1/messages` with `model: multi-alias`, and
  both passes write their rows
- **THEN** the alias's row has no `request.body`, `response.body` or `assembled_response` and has its `deployment`
  and `trace`, and the target's row has `request.body` with `model` set to the target name and `response.body`

#### Scenario: Streamed chat completion through an alias
- **WHEN** a caller with a key that is not `secured` sends a streamed chat completion to `assistant`
- **THEN** the alias's row has no `assembled_response`, and the row of `gpt-4-internal` has its `assembled_response`

## MODIFIED Requirements

### Requirement: Bodies in the analytics row
For a key that is not `secured`, Core SHALL write the request body as `request.body` and the response body as
`response.body`, a body over 4 MiB cut there and ending in `>>`, and, for a streamed response that Core assembled, the
assembled response as `assembled_response`, except while the log writer is behind or in the row of an alias pass (see
the two requirements on rows without bodies). For a `secured` key, Core SHALL write none of these three fields.

#### Scenario: Key not secured
- **WHEN** a pass with a key that is not `secured` writes its row
- **THEN** the row has `request.body` and `response.body` with the bodies of that pass

#### Scenario: Secured key
- **WHEN** a pass with a `secured` key writes its row
- **THEN** the row has no `request.body`, `response.body` or `assembled_response`, and has every other field

#### Scenario: Call from an origin plugin
- **WHEN** plugin `p1` serving application `app` calls model `m` with a key that is not `secured`, and both passes
  write their rows
- **THEN** `app`'s row and `m`'s row each have their `request.body` and `response.body`

## REMOVED Requirements

### Requirement: Rows without bodies for an in-process child pass
**Reason**: Leaving the bodies out is now a property of an alias pass, not of the way Core serves a pass. A pass an
origin plugin calls in process logs its bodies as a pass called over HTTP does, as an application's call to a model
does today.
**Migration**: None for Core config. An analytics consumer that read a plugin call's bodies from the plugin's row
finds them in the row of the called deployment too; a plugin that changes the body before the call now has the
changed body logged in the target's row.
