# Spec Delta

## MODIFIED Requirements

### Requirement: Config entity routes
Core SHALL route `GET`, `POST`, `PUT` and `DELETE` on `/v1/{type}/{bucket}/{path}` and `/v1/metadata/{type}/{bucket}/{path}`,
`{type}` one of `models`, `interceptors`, `translators`, `routers`, `roles`, `keys`, `routes`, `schemas`,
`catalog_schemas`, `settings`, `policies`, `policy_assignments` and `{bucket}` `[a-zA-Z0-9_-]+`, to the config entity
API, methods it does not serve included, so these four never fall through to the config routes; the `405` answers are
config-store's.

#### Scenario: Model entity
- **WHEN** an authenticated client sends `PUT /v1/models/platform/new%20model`
- **THEN** the config entity API receives type `models`, bucket `platform`, path `new model`

#### Scenario: Router entity
- **WHEN** an authenticated client sends `GET /v1/routers/platform/r1`
- **THEN** the config entity API receives type `routers`, bucket `platform`, path `r1`, while `GET /v1/routers/r1`
  (one segment) still reaches the single-router lookup

### Requirement: Admin and config routes
Core SHALL route `GET /v1/admin/health/config`, `POST /v1/admin/validate`, `POST /v1/admin/apply`,
`POST /v1/admin/config/file/migrate`, `POST /v1/ops/config/reload` and `GET`, `POST`, `PUT` and `DELETE` on
`/v1/admin/config/file/{type}[/{name}]` (`{type}` a config entity type other than `policies` and
`policy_assignments`, or `applications`, `toolsets`; `routers` included) to the admin config API (config-store), and
`POST /v1/admin/system-export` to the admin system export handler.

#### Scenario: File config entry
- **WHEN** an admin sends `GET /v1/admin/config/file/models/test-model-v1`
- **THEN** the admin file config API receives type `models` and name `test-model-v1`

#### Scenario: File router entry
- **WHEN** an admin sends `GET /v1/admin/config/file/routers/r1`
- **THEN** the admin file config API receives type `routers` and name `r1`
