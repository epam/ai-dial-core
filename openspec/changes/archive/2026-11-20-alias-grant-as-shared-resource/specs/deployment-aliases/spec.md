# Spec Delta

## ADDED Requirements

### Requirement: Alias name in the per-request key
The per-request key of the target pass SHALL record the name of the alias as `aliasName`. A key that the grant of the
target goes on to, for an interceptor in front of the target or a call back for it, SHALL carry the same `aliasName`; a
key that does not get the grant SHALL have none. The name SHALL grant nothing itself: the limits of the target pass
SHALL come from the roles of the alias that name stands for, as it is when the target pass runs.

#### Scenario: Alias name in the key
- **WHEN** a caller calls `assistant`, which targets `gpt-4-internal`
- **THEN** the per-request key the model receives has `aliasName` `assistant`

#### Scenario: Limits of a stored alias
- **WHEN** a caller has the roles `default-user` and `limited-user`, `limited-user` has a `day` token limit for
  `gpt-4-internal`, and the stored aliases `aliases/public/both-v1` and `aliases/public/plain-v1` target it, `both-v1`
  admitting both roles and `plain-v1` `default-user` only
- **THEN** after the limit is used up a call of `both-v1` answers `429` and a call of `plain-v1` answers `200`

#### Scenario: No alias name past a router's decision
- **WHEN** an alias targets a router, and the router chooses `gpt-4`
- **THEN** the per-request key `gpt-4` receives has no grant and no `aliasName`

### Requirement: Limits of a call through an alias
The limits of the target pass of an alias call SHALL be those of the caller's roles that are in the alias's
`userRoles`. For an alias without `userRoles` they SHALL be those a direct call of the target has: the caller's roles
in the target's `userRoles`, or all the caller's roles when the target has none.

#### Scenario: Limit of a role the alias does not admit
- **WHEN** a caller has the roles `default-user` and `limited-user`, `limited-user` has a `day` token limit for
  `gpt-4-internal`, and `plain-alias-v1`, which targets `gpt-4-internal`, admits `default-user` only
- **THEN** the limit of `limited-user` does not apply: a call of `plain-alias-v1` after the limit is used up answers
  `200`

### Requirement: Grant of the target in the per-request key
The per-request key of the target pass SHALL hold the right to call the target as `READ` on the target's url in
`perRequestSharedResources`: the url of a resource target, or `<models|applications|toolsets|routers>/platform/<name>`
for a config target. `READ` on a deployment's url in `perRequestSharedResources` SHALL let the key's holder call the
deployment whatever its `userRoles` and `features.accessibleByPerRequestKey`.

#### Scenario: Grant in the key
- **WHEN** a caller calls `assistant`, which targets the config model `gpt-4-internal`
- **THEN** the per-request key the model receives has `perRequestSharedResources` `{"models/platform/gpt-4-internal": {"permissions": ["READ"]}}`
  and no `lentDeployment`
