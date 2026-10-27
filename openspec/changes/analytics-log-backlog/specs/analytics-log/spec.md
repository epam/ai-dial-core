# Spec Delta

## Purpose

What Core writes to the analytics log for a pass: which parts of the request and response a row carries, and when
Core leaves the bodies out. Which passes write a row belongs to the capabilities that own those passes.

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

### Requirement: Limit on bodies waiting to be logged
Core SHALL take the limit on the bytes of bodies held by rows waiting to be written from
`analytics.maxPendingBodyBytes` (env `aidial.analytics.maxPendingBodyBytes`), and SHALL use one tenth of the JVM's
maximum heap when the setting is absent. Core SHALL refuse to start when the value is below 1, with an error that names
`analytics.maxPendingBodyBytes`.

#### Scenario: Setting absent
- **WHEN** Core starts without `analytics.maxPendingBodyBytes`
- **THEN** the limit is one tenth of the JVM's maximum heap

#### Scenario: Value below 1
- **WHEN** Core starts with `analytics.maxPendingBodyBytes: 0`
- **THEN** Core does not start, and the startup error names `analytics.maxPendingBodyBytes`

### Requirement: Rows without bodies while the log writer is behind
While the bodies held by rows waiting to be written reach `analytics.maxPendingBodyBytes`, Core SHALL write each new
row without `request.body`, `response.body` and `assembled_response`, with every other field, and SHALL NOT drop the
row. Once the held bodies fall below the limit, new rows SHALL carry their bodies again.

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
- **WHEN** rows were written without bodies, and the rows waiting since then have been written
- **THEN** the next row has its `request.body` and `response.body` again
