# Review: alias-open-without-user-roles

## What shipped

- An alias without `userRoles` over a config deployment (config file, platform, public stored) is open to every
  caller for calls, listings, single-item endpoints, toolset sign-in and consent. `[]`, explicit roles and resource
  targets (read access; a `platform` resource's `userRoles`) are unchanged. Code: `AliasService` only.
- `docs/dynamic-settings/aliases.md`: the `userRoles` rule, Listings and the Access of stored aliases. The release
  note, with the earlier behaviour and the step before upgrade, is in the PR description.

## Verification

- `./gradlew checkstyleMain checkstyleTest`: passed.
- `./gradlew :server:test --tests`: `AliasApiTest`, `AliasPublicApiTest`, `AliasPlatformApiTest`,
  `AliasInProcessApiTest`, `AliasReloadApiTest`, `RouterDecisionApiTest`, `DeploymentServiceTest`, `AliasServiceTest`,
  `AliasPassesTest`, `StoredAliasesTest`: all passed; the four new tests failed before the code change.
- `openspec validate alias-open-without-user-roles --strict`: valid. Cost claim checked: no pass, copy or round trip added.

## Drift from design.md

- `toView` stops copying the target's `userRoles` for a `platform` resource too; it already gave no view to a caller
  without them, so nothing changes there. The Code map says so.

## Not done

- The full test suite runs in CI. No issue exists yet; one is to be opened before the PR leaves draft.
