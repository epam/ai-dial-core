## MODIFIED Requirements

### Requirement: Router listing
`GET /v1/routers` SHALL answer `200` with `{"data": [...]}` holding one entry per router whose `userRoles` admit the
caller and one per alias of type `router` the caller may use, and `GET /v1/routers/{id}` SHALL answer `200` with that
entry, `404` for an unknown name and `403` for a router the caller's roles do not admit. An entry carries `id`,
`object: "router"`, `display_name`, `display_version`, `icon_url`, `description`, `features`, `defaults`,
`description_keywords` and no url.

#### Scenario: Listing filtered by role
- **WHEN** the config declares `routers.r1` with `userRoles: ["analysts"]` and `routers.r2` without `userRoles`, and a
  caller whose roles do not include `analysts` sends `GET /v1/routers`
- **THEN** the answer's `data` holds `r2` and not `r1`, and the `r2` entry has `object: "router"` and no `base_url`
  or `endpoint` field

#### Scenario: Single router by role
- **WHEN** the same caller sends `GET /v1/routers/r1`, then `GET /v1/routers/r2`, then `GET /v1/routers/missing`
- **THEN** Core answers `403`, then `200` with `id: r2`, then `404`

#### Scenario: Alias of type router listed
- **WHEN** the alias `tier` without `presentAs` targets `routers/r1` and has `userRoles: ["analysts"]`, and a caller
  with `analysts` sends `GET /v1/routers`, then `GET /v1/routers/tier`
- **THEN** the list holds an entry with `id: "tier"` and `object: "router"`, and the second answer is `200` with
  `id: "tier"`; a caller without `analysts` gets `403` from `GET /v1/routers/tier` and no `tier` entry in the list

#### Scenario: Only config aliases of type router
- **WHEN** a caller sends `GET /v1/routers/aliases%2Fpublic%2Fx`, where `aliases/public/x` is a stored alias, and
  `GET /v1/routers/tier` while the router `tier` targets is missing
- **THEN** both answers are `404`
