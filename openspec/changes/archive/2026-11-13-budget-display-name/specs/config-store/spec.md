## ADDED Requirements

### Requirement: Policy display name
Core SHALL accept an optional string `displayName` on a policy in per-policy PUT and admin manifests. Policy reads SHALL retain a supplied string. Omission or null SHALL leave the policy unnamed;
an empty or whitespace-only string SHALL remain valid. The name SHALL NOT change the policy path or its grants.

#### Scenario: Policy name survives a write and reload
- **WHEN** an admin PUTs a valid policy with `displayName: "Engineering Pro"` and reloads the configuration
- **THEN** GET of the same policy returns that display name and the original policy id

#### Scenario: Policy name applied in a manifest
- **WHEN** an admin validates and applies a valid Policy manifest with `displayName: "Engineering Pro"`
- **THEN** validation and apply succeed and GET of the policy returns that display name

#### Scenario: Unnamed policies remain valid
- **WHEN** an admin writes a valid policy with the name absent, null, empty or whitespace-only
- **THEN** the write succeeds and the policy keeps the same id and grants
