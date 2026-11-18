# analytics-log Specification

## Purpose
What Core writes to the analytics log for a pass: which parts of the request and response a row carries, and when
Core leaves the bodies out. Which passes write a row, and the usage and trace fields in it, belong to
`app-to-model-accounting` and the capabilities that own those passes.

## Requirements

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

### Requirement: No limit on bodies waiting to be logged by default
Core SHALL keep the bodies of every row of a key that is not `secured`, whatever the backlog, when
`analytics.maxPendingBodyHeapPercent` (env
`aidial.analytics.maxPendingBodyHeapPercent`) is absent, which means `-1`, or is 0 or below.

#### Scenario: Setting absent
- **WHEN** Core runs without `analytics.maxPendingBodyHeapPercent`, a row with a 2,000-byte request body is waiting to
  be written, and another pass writes its row
- **THEN** that row has its `request.body` and `response.body`

#### Scenario: Value 0
- **WHEN** Core runs with `analytics.maxPendingBodyHeapPercent: 0`, a row with a 2,000-byte request body is waiting to
  be written, and another pass writes its row
- **THEN** that row has its `request.body` and `response.body`

### Requirement: Rows without bodies while the log writer is behind
When `analytics.maxPendingBodyHeapPercent` is positive, Core SHALL take that percent of the JVM's maximum heap as the
limit on the bodies held by rows waiting to be written. While the held bodies reach the limit, Core SHALL write each
new row without `request.body`, `response.body` and `assembled_response`, with every other field, and SHALL NOT drop
the row. Once the held bodies fall below the limit, new rows SHALL carry their bodies again.

#### Scenario: Writer behind
- **WHEN** the JVM's maximum heap is 10,000 bytes, `analytics.maxPendingBodyHeapPercent` is 10 (a limit of 1,000
  bytes), a row with a 2,000-byte request body is waiting to be written, and another pass writes its row
- **THEN** that row has no `request.body`, `response.body` or `assembled_response`, and has its `deployment`,
  `token_usage`, `trace` and `response.status`

#### Scenario: Nothing waiting
- **WHEN** the JVM's maximum heap is 10,000 bytes, `analytics.maxPendingBodyHeapPercent` is 10 (a limit of 1,000
  bytes), no row is waiting, and a pass with a 2,000-byte request body writes its row
- **THEN** the row has `request.body` with that body

#### Scenario: Writer caught up
- **WHEN** the JVM's maximum heap is 10,000 bytes, `analytics.maxPendingBodyHeapPercent` is 10 (a limit of 1,000
  bytes), rows were written without bodies, and the rows waiting since then have been written
- **THEN** the next row has its `request.body` and `response.body` again

#### Scenario: Larger heap
- **WHEN** the JVM's maximum heap is 40,000 bytes, `analytics.maxPendingBodyHeapPercent` is 10 (a limit of 4,000
  bytes), a row with a 2,000-byte request body is waiting to be written, and another pass writes its row
- **THEN** that row has its `request.body` and `response.body`

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
