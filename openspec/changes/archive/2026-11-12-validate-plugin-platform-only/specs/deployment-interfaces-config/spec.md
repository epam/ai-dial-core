# Spec Delta

## ADDED Requirements

### Requirement: Validate reports a plugin outside the platform
`/v1/admin/validate` SHALL report an `Application` manifest outside the `platform` bucket that names a plugin, the
entry's own or its inline translator's, `FAILED` with the error a write of it gets, so validate and apply agree.

#### Scenario: Custom application refused by validate
- **WHEN** an admin sends `POST /v1/admin/validate` with a manifest of kind `Application` named
  `applications/public/my-app` declaring `interfaces.openaiChatCompletions: {"plugin": "p1"}`
- **THEN** Core answers `422`, and the manifest's result has `status: FAILED` and
  `error: A plugin is reserved for platform deployments`
