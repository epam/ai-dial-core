# org-units Specification

## Purpose
Which callers act through the roles that org-unit role assignments give them in the shared space, and how a caller
matches an assignment's subject. The rest of org units (the unit tree, roles and permissions, content access inside a
unit, approvals) is not specified here yet; `docs/org-units.md` describes it.

## Requirements

### Requirement: Callers other than applications act through unit roles
Core SHALL let every caller that is not an application (a request without a per-request key), a signed-in user and
an API key alike, act through the roles its matching role assignments give it at a unit, as those roles allow: read
and write unit content, approve and reject publication requests into the unit and read their review copies, manage
the unit's role assignments, get the unit in `GET /v1/user/role-assignments`, and see the units above it.

#### Scenario: API key writes through its role
- **WHEN** an API key with role `default` calls Core and the unit `Acme` gives `editor` to `{"type": "claimsPredicate", "source": "roles", "function": "EQUAL", "targets": ["default"]}`
- **THEN** the key reads `files/public/Acme/a.txt` with `200` and uploads `files/public/Acme/b.txt` with `200`; before the assignment both answer `403`

#### Scenario: API key approves into its unit
- **WHEN** the unit `Acme` gives `admin` to a `roles` subject matching an API key, and a user requests a publication into `public/Acme/`
- **THEN** the key lists the request, reads it and its review copy with `200`, and approves it with `200`; a second request is rejected by the key with `200`

#### Scenario: API key manages role assignments
- **WHEN** the unit `Acme` gives `admin` to a `roles` subject matching an API key
- **THEN** the key lists the unit's assignments with `200`, adds a `viewer` assignment with `200` and removes it with `200`

#### Scenario: API key lists its units and the units above
- **WHEN** the unit `Acme/Risk` gives `viewer` to a `roles` subject matching an API key
- **THEN** `GET /v1/user/role-assignments` with the key lists `Acme` with no roles and `Acme/Risk` with `viewer`, and `GET /v1/metadata/files/public/` lists `Acme/`

### Requirement: API keys match role assignments through their roles
An API key SHALL match a `claimsPredicate` subject on `roles` through its configured roles and no subject on any
other source. A subject of type `apiKey` SHALL be refused with `400`.

#### Scenario: API key subject refused
- **WHEN** an admin adds an assignment with subject `{"type": "apiKey", "id": "proxyKey1"}`
- **THEN** the answer is `400` and nothing is stored

### Requirement: Applications only read through unit roles
An application's per-request key SHALL read through the roles its user's claims match, and SHALL NOT write, approve,
review, manage role assignments, list units or see the units above through them.

#### Scenario: Application stays read-only
- **WHEN** the unit `Acme` gives `editor` to a subject matching an application's user
- **THEN** the application's per-request key reads `files/public/Acme/a.txt` with `200`, gets `403` uploading `files/public/Acme/b.txt`, and `GET /v1/user/role-assignments` with it lists no units

#### Scenario: Application neither approves, manages nor sees units above
- **WHEN** the unit `Acme` gives `admin` and its child `Acme/Risk` gives `viewer` to subjects matching an application's user, and a user requests a publication into `public/Acme/`
- **THEN** the application's per-request key gets `403` listing, reading, approving and rejecting the request, reading its review copy and listing the unit's role assignments, and a key whose user holds a role only in `Acme/Risk` gets `403` listing `public/Acme/`
