# Spec Delta

## ADDED Requirements

### Requirement: Invalid entity read omits auth-settings secrets
A per-entity GET of a recorded invalid toolset or application SHALL NOT return `client_secret`, `code_verifier` or
`client_secret_hint` from the toolset's `auth_settings` or from the `auth_settings` of each of the application's
`external_services`, and SHALL NOT add a `client_secret_hint` of its own, because the stored value may not decrypt.
This comes on top of the fields "Invalid entity on read" already omits.

#### Scenario: Toolset secret that does not decrypt
- **WHEN** the blob `toolsets/platform/bad-secret-toolset` holds `auth_settings` with `authentication_type: OAUTH`,
  `client_id`, and a `client_secret` and `code_verifier` that the running key cannot decrypt, the config reloads, and
  an admin sends `GET /v1/toolsets/platform/bad-secret-toolset`
- **THEN** Core answers `200` with `status: "invalid"`, `auth_settings.client_id` still present, and no
  `client_secret`, `code_verifier` or `client_secret_hint`; the body contains neither stored ciphertext

#### Scenario: Application external service secret that does not decrypt
- **WHEN** the blob `applications/platform/bad-secret-app` holds `external_services.github.auth_settings` with a
  `client_secret` that the running key cannot decrypt, the config reloads, and an admin sends
  `GET /v1/applications/platform/bad-secret-app`
- **THEN** Core answers `200` with `status: "invalid"`, `external_services.github.display_name` still present, and no
  `client_secret`, `code_verifier` or `client_secret_hint` under `external_services.github.auth_settings`; the body
  does not contain the stored ciphertext
