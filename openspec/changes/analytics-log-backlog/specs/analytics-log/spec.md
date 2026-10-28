# Spec Delta

## Purpose

What Core writes to the analytics log for a pass: which parts of the request and response a row carries, and when
Core leaves the bodies out. Which passes write a row, and the usage and trace fields in it, belong to
`app-to-model-accounting` and the capabilities that own those passes.

## ADDED Requirements

### Requirement: Bodies in the analytics row
For a key that is not `secured`, Core SHALL write the request body as `request.body` and the response body as
`response.body`, each cut at 4 MiB and then ending in `>>`, and, for a streamed response that Core assembled, the
assembled response as `assembled_response`. For a `secured` key, Core SHALL write none of these three fields.

#### Scenario: Key not secured
- **WHEN** a pass with a key that is not `secured` writes its row
- **THEN** the row has `request.body` and `response.body` with the bodies of that pass

#### Scenario: Secured key
- **WHEN** a pass with a `secured` key writes its row
- **THEN** the row has no `request.body`, `response.body` or `assembled_response`, and has every other field

### Requirement: No limit on bodies waiting to be logged by default
Core SHALL keep the bodies of every row whatever the backlog when `analytics.maxPendingBodyBytes` (env
`aidial.analytics.maxPendingBodyBytes`) is absent, which means `-1`, or is 0 or below.

#### Scenario: Setting absent
- **WHEN** Core runs without `analytics.maxPendingBodyBytes`, a row with a 2,000-byte request body is waiting to be
  written, and another pass writes its row
- **THEN** that row has its `request.body` and `response.body`

#### Scenario: Value 0
- **WHEN** Core runs with `analytics.maxPendingBodyBytes: 0`, a row with a 2,000-byte request body is waiting to be
  written, and another pass writes its row
- **THEN** that row has its `request.body` and `response.body`

### Requirement: Rows without bodies while the log writer is behind
When `analytics.maxPendingBodyBytes` is positive, and while the bodies held by rows waiting to be written reach it,
Core SHALL write each new row without `request.body`, `response.body` and `assembled_response`, with every other
field, and SHALL NOT drop the row. Once the held bodies fall below the limit, new rows SHALL carry their bodies again.

#### Scenario: Writer behind
- **WHEN** `analytics.maxPendingBodyBytes` is 1,000, a row with a 2,000-byte request body is waiting to be written,
  and another pass writes its row
- **THEN** that row has no `request.body`, `response.body` or `assembled_response`, and has its `deployment`,
  `token_usage`, `trace` and `response.status`

#### Scenario: Nothing waiting
- **WHEN** `analytics.maxPendingBodyBytes` is 1,000, no row is waiting, and a pass with a 2,000-byte request body
  writes its row
- **THEN** the row has `request.body` with that body

#### Scenario: Writer caught up
- **WHEN** `analytics.maxPendingBodyBytes` is 1,000, rows were written without bodies, and the rows waiting since then
  have been written
- **THEN** the next row has its `request.body` and `response.body` again
