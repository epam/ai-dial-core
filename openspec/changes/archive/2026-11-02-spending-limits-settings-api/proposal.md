## Why

`spendingLimits` (#2182) is a top-level `Config` setting, but the management API cannot reach it. The settings
singleton `/v1/settings/platform/global` is typed as `GlobalSettings`, which holds only `globalInterceptors`,
`retriableErrorCodes` and `rateLimitSchedule`. An operator can turn budgets on only by editing `aidial.config.json`.
`rateLimitSchedule` had the same gap until #1987 closed it, and this change follows that one.

## What Changes

- `GlobalSettings` gains `spendingLimits` (`mode`, `defaultCostObject`), with the same type and defaults as in
  `Config`.
- When the settings blob exists it supplies `spendingLimits`, as it supplies the other three fields; a blob
  without the field reads as the defaults (`off`, `ORG`). Deleting the blob restores the file's value.
- `PUT /v1/settings/platform/global` rejects an invalid `spendingLimits` (unknown `mode`, bad `defaultCostObject`)
  with the same validation as the file load: it answers `400` and nothing is written. Apply and validate already run
  that validation on a `Settings` manifest, so they reject it too.
- `GET /v1/settings/platform/global`, `GET /v1/admin/config/file/settings/global` and the file migration carry
  `spendingLimits`.
- The batch scratch config of `/v1/admin/validate`, `/v1/admin/apply` and the migration now holds `spendingLimits`
  and also `rateLimitSchedule`, which #1987 left out. No response changes, because no validation reads either field
  yet.
- **BREAKING** (operators only): a settings blob written before this change has no `spendingLimits`, so once the
  field is part of the overlay it reads as `off` and hides the file's value. See design.md, Migration Plan.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `config-store`: the "Settings overlay" and "Settings singleton URL" requirements.

## Impact

- Issue [#2219](https://github.com/epam/ai-dial-core/issues/2219). It follows the feature issue
  [#2147](https://github.com/epam/ai-dial-core/issues/2147) (PR #2182, part of #2143), which added `spendingLimits` to
  the file config only. The ported pattern is #1986 (PR #1987), which did the same for `rateLimitSchedule`.
- Covers: reading and writing `spendingLimits` through the management API. Leaves out: any change to budget
  enforcement or to the `policies` / `policy_assignments` API, and the warning log `Spending limits mode is ... has no
  budget`, which stays tied to a full config rebuild.
- `docs/open_api_core.yaml` gets the new field in the `GlobalSettings` schema from regeneration; it is not edited by
  hand.
- Code: `GlobalSettings`, `MergedConfigStore`, `ConfigResourceController`, `FileConfigController`,
  `ConfigFileMigrateController`, `ConfigManifestSupport`.
