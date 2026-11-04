# Review: spending-limits-settings-api

## What shipped

- `GlobalSettings.spendingLimits` (`@Valid`, default `new SpendingLimits()`), so the settings singleton reads and
  writes it and `ValidationUtil.validate` reaches its constraints.
- `MergedConfigStore`: `applySettingsWrite`, `applySettingsDelete` and `applySettingsOverlay` copy the field (a null in
  the blob becomes the default); the Javadoc of those methods and of `isSettingsFromApi` names it.
- `ConfigResourceController.handleSettingsGet`, `FileConfigController.handleSettings` and
  `ConfigFileMigrateController.collectSettings` carry the field.
- `ConfigManifestSupport.newScratch` and `mutateScratch` carry `spendingLimits` and `rateLimitSchedule` (the latter was
  missed in #1987), with plain setters like the other two settings fields.
- Tests, in the style of #1987: `spendingLimits` assertions in the existing `ConfigSettingsTest`, `FileConfigApiTest`,
  `ConfigFileMigrateApiTest` (its config file now sets a non-default `spendingLimits`), `AdminApplyApiTest` and the two
  settings tests of `MergedConfigStoreReplicaUpdateTest`; new: two invalid-value tests in `ConfigSettingsTest`,
  and `fullRebuildTakesSettingsFromTheBlob`. A check by removing every `setSpendingLimits` copy and
  projection from the main code failed 9 of these tests.
- `docs/open_api_core.yaml`: regenerated with `:openapi-generator:replaceSpec`; the only diff is `spendingLimits` under
  `GlobalSettings`. `:openapi-generator:checkOpenApiDiff` passes.
- README: no change; no other setting there says it can be managed through the API.
- `CLAUDE.md` and `docs/dynamic-settings/*.md` need no change: neither lists the settings fields (`interceptors.md` shows
  `globalInterceptors` only as a file-config example, and `roles.md` documents `rateLimitSchedule` as a role-limit anchor).

## Drift from design.md

- None. An unknown `mode` fails in `ConfigEntityCodec.treeToEntity` and a null `mode` in `ValidationUtil`, as the design
  says; the logged apply and validate error reads `Failed to parse GlobalSettings at "spendingLimits.mode": ... mode must
  be one of off, shadow, enforce`.

## Not done

- `openspec validate --all --strict` fails on two specs this change does not touch, `user-limits` and
  `resource-subscription` (placeholder Purpose section). It is not a task of this change, by decision: this change
  validates on its own (`validate spending-limits-settings-api --strict`), and the two Purposes need their own change.
- The `ConfigManifestSupport` scratch change has no test, by decision: no response depends on the scratch fields yet.
- The full test suite was not run here; it runs in CI. The touched classes and checkstyle passed locally.
- The upgrade step in design.md, Migration Plan (a settings blob written before this change hides a file
  `spendingLimits`) is documented, not enforced.

## Known limits

- A settings blob without `spendingLimits` reads as `off` and `ORG`, as for the other settings fields.
- The "Spending limits mode is ... has no budget" warning is logged on a full rebuild only; a settings write moves the
  `dial_budget_personal_missing` gauge but writes no log line.

## Review

Not yet reviewed. Issue #2219.
