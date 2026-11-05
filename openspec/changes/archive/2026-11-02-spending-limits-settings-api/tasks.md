# Tasks

## 1. Settings model and overlay

- [x] 1.1 `GlobalSettings`: add `@Valid private SpendingLimits spendingLimits = new SpendingLimits();`; verified by the invalid-value `PUT` tests of 2.5 (a bad `defaultCostObject` answers `400`, which fails without the `@Valid`)
- [x] 1.2 `MergedConfigStore`: copy `spendingLimits` in `applySettingsWrite` (null becomes `new SpendingLimits()`), `applySettingsDelete` (from `base`) and `applySettingsOverlay`; update the Javadoc of those and of `isSettingsFromApi`
- [x] 1.3 `ConfigManifestSupport.newScratch` and `mutateScratch` (`AdminSettingsManifest` arm): carry `spendingLimits` and `rateLimitSchedule` like the other two settings fields (no test of its own, by decision: no response depends on the scratch fields yet)
- [x] 1.4 `MergedConfigStoreReplicaUpdateTest`: `spendingLimits` in the existing `globalSettingsUpdateAppliesSettingsWrite` and `globalSettingsDeleteAppliesSettingsDelete` (scenarios "Blob replaces the file's spending limits" and "Delete restores the file's spending limits"), and a new `fullRebuildTakesSettingsFromTheBlob` for `applySettingsOverlay`
- [x] 1.5 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests` `MergedConfigStoreTest`, `MergedConfigStoreReplicaUpdateTest`, `SpendingLimitsConfigTest`, `PersonalBudgetGuardTest`

## 2. Management API surfaces

- [x] 2.1 `ConfigResourceController.handleSettingsGet`: `body.set("spendingLimits", ProxyUtil.MAPPER.valueToTree(config.getSpendingLimits()))`
- [x] 2.2 `FileConfigController.handleSettings`: the same from `fileConfig`; add `spendingLimits` to the class Javadoc list
- [x] 2.3 `ConfigFileMigrateController.collectSettings`: `settings.setSpendingLimits(fileConfig.getSpendingLimits())`
- [x] 2.4 `dial-config/config-file-migrate.json`: add `"spendingLimits": {"mode": "shadow", "defaultCostObject": "PERSONAL"}`, so that the migration test sees a non-default value
- [x] 2.5 `ConfigSettingsTest`: `spendingLimits` in `testFileConfigEndpointReturnsDefaultsWhenFileSilent`, `testPutUpsertsAndGetReturnsBlobValues` and `testPutEmptyBodyDefaultsToEmptyFields`, and the new `testPutUnknownSpendingLimitModeReturns400` and `testPutInvalidDefaultCostObjectReturns400`
- [x] 2.6 `FileConfigApiTest.testSettingsGetReturnsFileDefaultValues`: assert the `off` / `ORG` defaults
- [x] 2.7 `ConfigFileMigrateApiTest.testMigrateSettings`: assert `shadow` and `PERSONAL` after the migration
- [x] 2.8 `AdminApplyApiTest.testApplySettingsSpecialCase`: apply `spendingLimits` and assert it on the GET
- [x] 2.9 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests` `ConfigSettingsTest`, `FileConfigApiTest`, `ConfigFileMigrateApiTest`, `AdminApplyApiTest`

## 3. OpenAPI, docs and checks

- [x] 3.1 Regenerate `docs/open_api_core.yaml` with `./gradlew :openapi-generator:replaceSpec` (not by hand); verify the only diff is `spendingLimits` under the `GlobalSettings` schema, then `./gradlew :openapi-generator:checkOpenApiDiff` passes
- [x] 3.2 README: no change. The `spendingLimits` row of the dynamic settings table stays as #2182 wrote it, because no other row says that a setting can be managed through the API
- [x] 3.3 Confirm `CLAUDE.md` and `docs/dynamic-settings/*.md` need no change (they do not list settings fields: `roles.md` documents `rateLimitSchedule` only as a role-limit anchor); record the result in `review.md`
- [x] 3.4 `npx -y @fission-ai/openspec@1.14.3 validate spending-limits-settings-api --strict`

## Workflow follow-up

- Write `review.md` in the change folder, archive with `npx -y @fission-ai/openspec@1.14.3 archive spending-limits-settings-api -y`, then `validate --specs --strict`, and commit with explicit paths.
