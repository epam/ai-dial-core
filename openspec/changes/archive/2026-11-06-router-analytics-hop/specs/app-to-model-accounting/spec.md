## MODIFIED Requirements

### Requirement: One analytics row per Core pass
Core SHALL write one analytics row per pass that relays an origin's answer, a `Model` pass whose client disconnected
during the relay included, with `deployment`, `execution_path` from the key it minted, `parent_deployment` the
nearest preceding `execution_path` entry that is not one of the deployment's interceptors (absent for a root), and
`trace.trace_id`, `trace.core_span_id` and, for a child, `core_parent_span_id`; a decided request adds a router row.

#### Scenario: Parent deployment of a model called by an app
- **WHEN** root `app` calls `gpt-4`
- **THEN** two rows are written: `app` with `execution_path: ["app"]` and no `parent_deployment`, and `gpt-4` with
  `execution_path: ["app", "gpt-4"]` and `parent_deployment: "app"`

#### Scenario: Conversation id carried to the child row
- **WHEN** the client sent `X-CONVERSATION-ID: c1` to `app` and `app` calls `gpt-4` without that header
- **THEN** the `gpt-4` row carries conversation id `c1`

#### Scenario: One row for a decided request
- **WHEN** a user calls router `r1` in decision mode and its decision chooses `gpt-4`, which answers
- **THEN** one row is written for the pass, `gpt-4` with `execution_path: ["r1", "gpt-4"]` and
  `parent_deployment: "r1"`, and the router row for `r1` is written beside it
