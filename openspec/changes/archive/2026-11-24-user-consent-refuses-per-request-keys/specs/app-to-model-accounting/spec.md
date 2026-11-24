# Spec Delta

## ADDED Requirements

### Requirement: Accepting consent refuses per-request keys
Core SHALL answer `POST /v1/consent/{id}` from a per-request-key caller with `403` and body `User consent is not allowed
for per-request keys`, before reading the body, and SHALL store nothing. A caller with a user's JWT SHALL be served as
before.

#### Scenario: Application accepts consent with its key
- **WHEN** an application called by user `u1` sends `POST /v1/consent/secured-app` with a valid consent body and the
  per-request key it received
- **THEN** Core answers `403` with body `User consent is not allowed for per-request keys`, and a call by `u1` to
  `secured-app` (`consentRequired: true`) is still answered `403` `User didn't accept consent to call the deployment:
  secured-app`

#### Scenario: User accepts consent
- **WHEN** user `u1` sends the same `POST /v1/consent/secured-app` with the user's JWT
- **THEN** Core answers `200` and the call by `u1` to `secured-app` passes the consent check
