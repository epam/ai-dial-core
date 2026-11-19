## MODIFIED Requirements

### Requirement: Who may use an alias
The callers who may use an alias SHALL be those with one of its `userRoles`; `userRoles: []` SHALL admit no caller.
An alias without `userRoles` SHALL be open to every caller, as any deployment without `userRoles` is, unless its
target is a resource: then it SHALL take the caller's read access to the resource, or, in the `platform` bucket, the
resource's `userRoles`. Listings and single-item endpoints SHALL apply the same rule.

#### Scenario: Alias without userRoles
- **WHEN** an alias without `userRoles` targets `gpt-4-internal`, which has `userRoles: ["power-user"]`, and another
  alias without `userRoles` targets an application in another user's bucket
- **THEN** a caller with only `default-user` calls the first alias with `200`, sees it in `/openai/models` and
  `/v1/deployment-names`, and gets `200` from `/openai/models/<alias>`; the same caller gets `403` from
  `gpt-4-internal` directly, `403` from the second alias, and does not see the second alias in `/openai/applications`

### Requirement: Who may call a stored alias
Core SHALL let a caller use a stored alias when one of the caller's roles is in the alias's `userRoles`, or, when the
alias has none, as "Who may use an alias" says for an alias without `userRoles`, exactly as for an alias of the config
file, and SHALL answer `403` to other callers. The same SHALL decide whether listings show the alias and whether its
single-item endpoints answer.

#### Scenario: Caller with a role of the alias
- **WHEN** `pub-v1` is stored with `userRoles: ["default-user"]` and target `models/gpt-4-internal`
- **THEN** a caller with `default-user` calls `aliases/public/pub-v1` with `200`, and a caller with only `power-user` gets `403`

#### Scenario: Listings follow userRoles
- **WHEN** `pub-v1` is stored with `userRoles: ["default-user"]`
- **THEN** `/openai/models` lists `aliases/public/pub-v1` for a caller with `default-user` and not for one with only `power-user`; for the latter `/openai/models/aliases/public/pub-v1`, `/openai/deployments/aliases/public/pub-v1` and `/v1/deployments/aliases/public/pub-v1` answer `403` and `/v1/deployment-names` does not name it

#### Scenario: No userRoles
- **WHEN** `open-v1` is stored without `userRoles` and `closed-v1` with `userRoles: []`, both with target
  `models/gpt-4-internal`, which has `userRoles: ["power-user"]`
- **THEN** a caller with only `default-user` calls `aliases/public/open-v1` with `200` and sees it in
  `/openai/models`; callers with `default-user` and with `power-user` get `403` from `aliases/public/closed-v1` and
  do not see it
