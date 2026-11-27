## ADDED Requirements

### Requirement: Secured callers
Core SHALL treat a pass's caller as secured when its API key is `secured`, or when it matches
`analytics.securedSubject`: rules OR'd together on `roles`, `email` or a claim path, as a policy assignment's `subject`
is matched. An empty list SHALL match nobody, and a claim whose value is not text or an array of text SHALL never match.
A pass reached with a per-request key SHALL have the caller of the request that started the chain, matched against the
config in force when the pass writes its row.

#### Scenario: Role rule
- **WHEN** `analytics.securedSubject` is `[{"source": "roles", "function": "EQUAL", "targets": ["legal"]}]` and a
  user whose token carries role `legal` calls model `m`
- **THEN** `m`'s row has no `request.body`, `response.body` or `assembled_response`, and has every other field

#### Scenario: No rule matches
- **WHEN** `analytics.securedSubject` is `[{"source": "roles", "function": "EQUAL", "targets": ["legal"]}]` and a
  user whose token carries only role `dev` calls model `m`
- **THEN** `m`'s row has its `request.body` and `response.body`

#### Scenario: Claim rule ignores case
- **WHEN** `analytics.securedSubject` is `[{"source": "user_name", "function": "EQUAL", "targets": ["test-user"]}]` and a
  user whose token carries `"user_name": "TEST-USER"` calls model `m`
- **THEN** `m`'s row has no `request.body` or `response.body`

#### Scenario: Contain rule on an array claim
- **WHEN** `analytics.securedSubject` is `[{"source": "groups", "function": "CONTAIN", "targets": ["secure"]}]` and
  a user whose token carries `"groups": ["dev", "Team-Secure-Ops"]` calls model `m`
- **THEN** `m`'s row has no `request.body` or `response.body`

#### Scenario: Contain rule with no matching entry
- **WHEN** `analytics.securedSubject` is `[{"source": "groups", "function": "CONTAIN", "targets": ["secure"]}]` and
  a user whose token carries `"groups": ["dev", "ops"]` calls model `m`
- **THEN** `m`'s row has its `request.body` and `response.body`

#### Scenario: One rule of several
- **WHEN** `analytics.securedSubject` has a rule on role `legal` and a rule on `user_name` `test-user`, and a user whose
  token carries roles `dev` and `legal` and no `user_name` calls model `m`
- **THEN** `m`'s row has no `request.body` or `response.body`

#### Scenario: Text claim "true"
- **WHEN** `analytics.securedSubject` is
  `[{"source": "c-level-management", "function": "EQUAL", "targets": ["true"]}]` and a user whose token carries
  `"c-level-management": "true"` calls model `m`
- **THEN** `m`'s row has no `request.body` or `response.body`

#### Scenario: Boolean claim
- **WHEN** `analytics.securedSubject` is
  `[{"source": "c-level-management", "function": "EQUAL", "targets": ["true"]}]` and a user whose token carries
  `"c-level-management": true`, a JSON boolean, calls model `m`
- **THEN** `m`'s row has its `request.body` and `response.body`

#### Scenario: API key with a matching role
- **WHEN** `analytics.securedSubject` has a rule on role `legal` and key `k1`, which is not `secured` and has
  `role: legal`, calls model `m`
- **THEN** `m`'s row has no `request.body` or `response.body`

#### Scenario: Application called by a secured user
- **WHEN** a user matching `analytics.securedSubject` calls application `app`, and `app` calls model `m` with its
  per-request key
- **THEN** neither `app`'s row nor `m`'s row has a `request.body` or `response.body`

#### Scenario: Background job of a secured user
- **WHEN** a user matching `analytics.securedSubject` sends a Responses API request with `background: true`, and the
  job completes
- **THEN** the job's row has no `request.body` or `response.body`

#### Scenario: Settings blob without a subject
- **WHEN** the file's `analytics.securedSubject` has a rule on role `legal`, an admin sends
  `PUT /v1/settings/platform/global` with `{}`, and a user with role `legal` calls model `m`
- **THEN** `m`'s row has its `request.body`; after `DELETE /v1/settings/platform/global`, the user's next row has no
  `request.body`

## MODIFIED Requirements

### Requirement: Bodies in the analytics row
For a caller that is not secured (see "Secured callers"), Core SHALL write the request body as `request.body` and the
response body as `response.body`, a body over 4 MiB cut there and ending in `>>`, and, for a streamed response that Core
assembled, the assembled response as `assembled_response`, except while the log writer is behind or in the row of an
alias pass (see the two requirements on rows without bodies). For a secured caller, Core SHALL write none of these three
fields.

#### Scenario: Key not secured
- **WHEN** a pass with a key that is not `secured`, whose caller matches no `analytics.securedSubject` rule, writes its
  row
- **THEN** the row has `request.body` and `response.body` with the bodies of that pass

#### Scenario: Secured key
- **WHEN** a pass with a `secured` key writes its row
- **THEN** the row has no `request.body`, `response.body` or `assembled_response`, and has every other field

#### Scenario: Call from an origin plugin
- **WHEN** plugin `p1` serving application `app` calls model `m` with a key that is not `secured`, whose caller matches
  no `analytics.securedSubject` rule, and both passes write their rows
- **THEN** `app`'s row and `m`'s row each have their `request.body` and `response.body`

### Requirement: No limit on bodies waiting to be logged by default
Core SHALL keep the bodies of every row of a caller that is not secured, whatever the backlog, when
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
