# Spec Delta

## MODIFIED Requirements

### Requirement: Path variable decoding
Core SHALL percent-decode (`+` stays `+`) the identifier variables it extracts: deployment, model, application,
toolset, response, consent, admin-consent, invitation and external-service ids, config entity `{path}`, admin file
`{name}` and the v2 `{filePath}`. File, resource and v2 resource paths and the app-route `{routePath}` SHALL be passed on
still encoded. An identifier that is not a valid URI path (an unencoded `[`, say) SHALL get `500` with an empty body.

#### Scenario: Encoded deployment id
- **WHEN** an authenticated client sends `GET /openai/deployments/deployment_x-y%2B%2F`
- **THEN** the deployment lookup receives id `deployment_x-y+/`

#### Scenario: Encoded file path kept encoded
- **WHEN** an authenticated client sends `GET /v1/files/bucket/fol%2Fder%201/file1%23.txt`
- **THEN** the file download handler receives `files/bucket/fol%2Fder%201/file1%23.txt` (see resource-access-control)

### Requirement: Multi-segment ids
Core SHALL let a deployment, model, application, toolset, consent or admin-consent `{id}` span `/`, so a custom
application addressed by its url (`applications/{bucket}/{name}`) is a valid id; the id is the shortest match before the
pattern's fixed suffix.

#### Scenario: Custom application in a chat completions path
- **WHEN** an authenticated client sends `POST /openai/deployments/applications/bucket/my-application/chat/completions`
- **THEN** the deployment request handler receives deployment id `applications/bucket/my-application`

#### Scenario: Custom application in an admin-consent path
- **WHEN** an admin sends `GET /v1/admin-consent/applications/public/dependency-consent-app`
- **THEN** the admin consent handler receives id `applications/public/dependency-consent-app`

### Requirement: Bucket, user and schema routes
Core SHALL route `GET /v1/bucket`, `GET /v1/user/info`, `GET /v1/user/limits`, `GET /v1/user/usage`,
`GET /v1/user/offline-credentials`, `POST /v1/user/offline-credentials/(signin|signout)`, `GET` and
`POST /v1/consent/{id}`, `GET`, `POST` and `DELETE /v1/admin-consent/{id}`, and
`GET /v1/application_type_schemas/(schemas|schema|meta_schema)` and
`GET /v1/catalog_schemas/(schemas|schema|meta_schema)` to their handlers.

#### Scenario: Bucket
- **WHEN** an authenticated client sends `GET /v1/bucket`
- **THEN** the bucket handler answers

#### Scenario: Admin consent
- **WHEN** an admin sends `GET /v1/admin-consent/applications/public/dependency-consent-app`
- **THEN** the admin consent handler answers

#### Scenario: Admin-consent suffix on a user consent path
- **WHEN** an admin sends `POST /v1/consent/applications/public/dependency-consent-app/admin-consent` with no body
- **THEN** the user consent handler answers `400` (it demands a body), and no admin consent is stored
