## ADDED Requirements

### Requirement: In-process child pass
Core SHALL check, charge and record a call from an origin plugin as a pass authenticated by the per-request key of
the plugin's pass: the target's role, consent and per-request-key access checks; its limits against the initiator's
bucket; a token-stats span under the plugin's span, rolled up as any child's; and a per-request key for the
target's origin and an analytics row for the target, each kept and written as for any child pass.

#### Scenario: Target limits charged
- **WHEN** plugin `p1` serving application `app` calls model `m`, and `m` answers `200` with `usage.total_tokens: 15`
- **THEN** the caller's token usage of `m` grows by `15`, as `GET /v1/deployments/m/limits` reports in
  `minuteTokenStats.used`

#### Scenario: Usage rolled up to the plugin's pass
- **WHEN** the same call is not streamed and `p1` relays its answer as the answer of `app`
- **THEN** the client's answer carries `statistics.usage_per_model` with one entry for `m`

#### Scenario: Child row
- **WHEN** the call completes
- **THEN** two analytics rows are written: one for `app`, and one for `m` with `parent_deployment: app`,
  `execution_path` `["app", "m"]` and the trace id of `app`'s row
