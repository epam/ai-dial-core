# Spec Delta

## ADDED Requirements

### Requirement: Dependency grants in the key
For a called application that declares `resource_dependencies`, Core SHALL add to `perRequestSharedResources` each
granted dependency with its declared rights (see resource-dependencies), adding them to an entry already there for the
same target.

#### Scenario: Granted dependency in the key
- **WHEN** a user calls an approved application declaring `prompts/{current-user}/dep-smoke/` with `write`
- **THEN** the key the application receives can write under `prompts/<user bucket>/dep-smoke/` and nowhere else in the
  user's bucket
