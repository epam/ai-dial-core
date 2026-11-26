## MODIFIED Requirements

### Requirement: Settings overlay
When the blob `settings/platform/global` exists and parses, Core SHALL take `globalInterceptors`,
`retriableErrorCodes`, `rateLimitSchedule`, `spendingLimits` and `analytics` from it, a missing field reading as empty
(`rateLimitSchedule` defaulting to timezone `UTC`, `spendingLimits` to mode `off` and default cost object `ORG`,
`analytics` to `securedSubject: []`), instead of from the file. Deleting the blob SHALL restore the file values.

#### Scenario: Empty settings blob
- **WHEN** an admin sends `PUT /v1/settings/platform/global` with `{}`
- **THEN** `GET /v1/settings/platform/global` answers `globalInterceptors: []`, `retriableErrorCodes: []`,
  `rateLimitSchedule.timezone: "UTC"`, `spendingLimits` with `mode: "off"` and `defaultCostObject: "ORG"`, and
  `analytics.securedSubject: []`

#### Scenario: Delete reverts to the file
- **WHEN** an admin sends `DELETE /v1/settings/platform/global` after a `PUT`
- **THEN** Core answers `204`, `GET /v1/settings/platform/global` answers `404`, and the effective settings are the
  file's

#### Scenario: Spending limits from the blob
- **WHEN** an admin sends `PUT /v1/settings/platform/global` with
  `{"spendingLimits": {"mode": "shadow", "defaultCostObject": "PERSONAL"}}`
- **THEN** `GET /v1/settings/platform/global` answers `spendingLimits` with `mode: "shadow"` and
  `defaultCostObject: "PERSONAL"`

#### Scenario: Blob replaces the file's spending limits
- **WHEN** the file sets `spendingLimits.mode` to `shadow` and the settings blob sets `enforce`
- **THEN** the effective mode is `enforce`, on a config rebuild and on a replica event for the blob

#### Scenario: Delete restores the file's spending limits
- **WHEN** the file sets `spendingLimits.mode` to `shadow`, the settings blob sets `enforce`, and the blob is deleted
- **THEN** the effective mode is `shadow`

#### Scenario: Secured subject from the blob
- **WHEN** an admin sends `PUT /v1/settings/platform/global` with
  `{"analytics": {"securedSubject": [{"source": "user_name", "function": "EQUAL", "targets": ["test-user"]}]}}`
- **THEN** `GET /v1/settings/platform/global` answers `analytics.securedSubject` with that one rule

### Requirement: Settings singleton URL
`/v1/settings/platform/global` SHALL be the only settings entity: another name SHALL answer `404` on `GET`, `PUT` and
`DELETE`, and `POST` SHALL answer `405`.
`GET` SHALL answer `404` while no settings blob exists, else `200` with the five fields, `name: "global"` and
`status: "valid"`. A `PUT` SHALL drop unknown fields and answer `400` on an invalid `rateLimitSchedule`,
`spendingLimits` or `analytics`, writing nothing. `analytics.securedSubject` is invalid when it is `null`, or when a
rule has no `source` or `function`, has empty targets for a function other than `TRUE` and `FALSE`, or has a `REGEX`
target that doesn't compile; an empty list is valid.

#### Scenario: Unknown field dropped
- **WHEN** an admin sends `PUT /v1/settings/platform/global` with `{"globalInterceptors": ["q"], "extraField": "ignored"}`
- **THEN** Core answers `200` and the GET has one global interceptor and no `extraField`

#### Scenario: Unknown spending limit mode
- **WHEN** an admin sends `PUT /v1/settings/platform/global` with `{"spendingLimits": {"mode": "strict"}}`
- **THEN** Core answers `400`

#### Scenario: Invalid default cost object
- **WHEN** an admin sends `PUT /v1/settings/platform/global` with `{"spendingLimits": {"defaultCostObject": "a/b"}}`
- **THEN** Core answers `400`

#### Scenario: Secured subject rule with an invalid pattern
- **WHEN** an admin sends `PUT /v1/settings/platform/global` with
  `{"analytics": {"securedSubject": [{"source": "user_name", "function": "REGEX", "targets": ["("]}]}}`
- **THEN** Core answers `400`

#### Scenario: Secured subject rule without a source
- **WHEN** an admin sends `PUT /v1/settings/platform/global` with
  `{"analytics": {"securedSubject": [{"function": "EQUAL", "targets": ["test-user"]}]}}`
- **THEN** Core answers `400`

#### Scenario: Null secured subject
- **WHEN** an admin sends `PUT /v1/settings/platform/global` with `{"analytics": {"securedSubject": null}}`
- **THEN** Core answers `400`
