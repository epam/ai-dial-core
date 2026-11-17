## ADDED Requirements

### Requirement: Plugin API level checked at start
Each plugin provider SHALL state the plugin API level it was compiled against. Core SHALL fail to start when a
provider it loads states a level above Core's own or below the oldest level Core serves, or was compiled against a
plugin API that had no level, with a message naming the provider class, its level and the range Core serves.

#### Scenario: Level in range
- **WHEN** the plugins directory holds a jar whose provider states Core's own API level
- **THEN** after start a `PUT /v1/plugins/platform/p1` with that provider's `type` is accepted

#### Scenario: Provider built against a newer API
- **WHEN** the plugins directory holds a jar whose provider states a level one above Core's
- **THEN** Core does not start, with a message naming the provider class, that level and the range Core serves

#### Scenario: Provider built against an API no longer served
- **WHEN** the plugins directory holds a jar whose provider states a level one below the oldest level Core serves
- **THEN** Core does not start, with a message naming the provider class, that level and the range Core serves

#### Scenario: Provider built before levels existed
- **WHEN** the plugins directory holds a jar whose provider class does not implement the level method
- **THEN** Core does not start, with a message naming the provider class and saying it must be rebuilt
