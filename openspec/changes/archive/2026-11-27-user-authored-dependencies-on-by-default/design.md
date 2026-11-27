# Design

## Context

`ApplicationService.inheritOrGuardDependencies` rejects a non-admin write that changes `resource_dependencies` while
`applications.allowUserAuthoredDependencies` is off, which is the default today. Resolution at call time is unaffected
by who wrote the list: an entry is granted only with consent (today admin consent, administrator-only) and only within
the calling user's own reach.

## Decisions

### D1. The flag defaults to on; operators can turn it off

Clients write the list for every user-created application; a default that refuses those writes would make every
installation set the flag before the clients work. A user-written list grants nothing by itself, so the default
changes who may *ask*, not what anyone *gets*.

**Rejected: keep `false` and document the setting** — every installation would need a config change before the clients'
default list works, and a forgotten setting shows up as a `403` in the editor.

**Rejected: accept from users only a list fixed in Core's settings** — Core would hold the client's list; the list is
meant to change in the clients alone.

## Differences from today

| What | Today | After | Requirement |
|---|---|---|---|
| A user's write with a valid section, setting not configured | `403` | `200` | Who may declare resource dependencies |
| The same, setting `false` | `403` | `403` | same |

## Effect per request

None: the setting is read once at start-up; the write path does the same checks.

## Risks / Trade-offs

- A user can write any valid list, not only the client's default one. It is still a request: it needs consent and never
  exceeds the calling user's reach.

## Migration Plan

none — operators who want today's behaviour set `applications.allowUserAuthoredDependencies: false`.

## Code map

- `ApplicationService` constructor — `settings.getBoolean("allowUserAuthoredDependencies", …)`, the code default.
- `ApplicationService.inheritOrGuardDependencies` — the gate, unchanged.
- `server/src/main/resources/aidial.settings.json`, `sample/aidial.settings.json`, README settings table.
- Tests: `CustomApplicationApiTest.testResourceDependenciesSectionWriteTimeValidation` (the default) and its nested
  `AllowUserResourceDependenciesOff`, `AllowUserResourceDependenciesOn`, `AdminRulesUnconfigured`;
  `ResourceDependencyOrgUnitApiTest.FlagOff`; overrides through `ResourceBaseTest.additionalSettingsOverrides`.
