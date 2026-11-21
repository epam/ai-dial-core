# Design

## Context

Global settings are one blob, `settings/platform/global`, typed as `GlobalSettings`
(`config/.../GlobalSettings.java`: `globalInterceptors`, `retriableErrorCodes`, `rateLimitSchedule`). It overlays the
file config field by field: `MergedConfigStore.applySettingsOverlay` on a full rebuild,
`MergedConfigStore.applySettingsWrite` / `applySettingsDelete` on a write or delete and on a replica event
(`applyReplicaEvent`, GLOBAL_SETTINGS arm). `spendingLimits` (`Config.spendingLimits`, #2182) is outside that type, so
the overlay ignores it and the file is its only source. #1987 did the same job for `rateLimitSchedule`; this change
repeats it, as the `config-changes-for-admin-api` checklist for a new global setting (`new-global-setting.md`) lays out.

The surfaces that already handle `GlobalSettings` and need no new code path:

- `ConfigResourceController.handleSettingsPut` runs `ValidationUtil.validate(settings)` before the blob is written and
  maps a parse error or `ConstraintViolationException` to `400` (`handleWriteError`).
- `ConfigApplyService.applySettings` runs `ValidationUtil.validate(settings)` before `putResource`;
  `ConfigValidationService.validateOnly` runs it in its `AdminSettingsManifest` arm and maps
  `ConstraintViolationException` to `FAILED`. A spec that does not parse fails earlier, in
  `ConfigManifestSupport.parseManifest` (`ConfigEntityCodec.treeToEntity`), which both services turn into a `FAILED`
  entry.

## Goals / Non-Goals

**Goals:**
- `spendingLimits` is read and written through every surface that carries the other settings fields, with the file
  load's validation.

**Non-Goals:**
- No change to `BudgetEnforcer`, `RateLimiter`, or the policy API.
- No new reader of the scratch's settings fields; carrying them is for completeness only.
- No per-field merge between the blob and the file (see the first decision).

## Decisions

- **Add `spendingLimits` to `GlobalSettings` with `@Valid` and the same default as `Config`
  (`new SpendingLimits()`).** `ValidationUtil.validate` cascades only through `@Valid`; without it `mode == null` and a
  bad `defaultCostObject` would reach the blob. The constraints themselves (`@NotNull mode`, `@NotBlank` and `@Pattern`
  on `defaultCostObject`) stay on `SpendingLimits`, so the file load and the API use one definition.
- **Copy the field in each place `rateLimitSchedule` is copied, with the same null rule (null becomes
  `new SpendingLimits()`):** `MergedConfigStore.applySettingsWrite`, `applySettingsDelete` (restores
  `base.getSpendingLimits()`), `applySettingsOverlay`, and the Javadoc of those and of `isSettingsFromApi`.
  `shallowClone` and `rebuildMergedConfig` already copy `spendingLimits` from the file config and need no change.
- **The blob replaces the file value wholesale; a missing field reads as the default.** This is how the other three
  fields behave and what the "Settings overlay" requirement says. Rejected: keep the file's value when the blob omits
  the field. It needs a nullable field to tell "omitted" from "default", gives `spendingLimits` a rule that the other
  fields lack, and makes `PUT {}` mean different things for different fields. The cost is the upgrade risk in the
  Migration Plan.
- **Project the field in the three read/copy places:** `ConfigResourceController.handleSettingsGet`
  (`body.set("spendingLimits", ProxyUtil.MAPPER.valueToTree(config.getSpendingLimits()))`),
  `FileConfigController.handleSettings` (from `fileConfig`) and `ConfigFileMigrateController.collectSettings`
  (`settings.setSpendingLimits(fileConfig.getSpendingLimits())`). `SpendingLimitMode` serializes as `off` / `shadow` /
  `enforce` through its `@JsonValue`, so the GET answers what the file accepts.
- **Carry `spendingLimits` and `rateLimitSchedule` in the batch scratch:** `ConfigManifestSupport.newScratch` copies them
  from the live config and `mutateScratch`'s `AdminSettingsManifest` arm sets them from the spec, like
  `globalInterceptors` and `retriableErrorCodes`. The scratch stands for "the merged config plus the batch's earlier
  accepted entries", so it has to hold every settings field. Today no batch validation reads either field (a search of
  `server/src/main` for `getSpendingLimits` and `getRateLimitSchedule` finds only `MergedConfigStore`, the controllers
  above and `limiter/`), so the fix changes no result; it removes a stale default that the next reader of the scratch
  would trust. `rateLimitSchedule` was missed, not left out on purpose: the scratch copy of the settings fields dates
  from the migration endpoint (#1830), #1987 added the field to `GlobalSettings` without touching it, and the
  `new-global-setting.md` checklist does not list it. This change fixes both fields, with plain setters like the other
  two (a null in the spec is not special-cased, because nothing reads these fields from the scratch). Rejected: add only
  `spendingLimits` and leave `rateLimitSchedule`, which would keep the two settings objects out of step for no reason.
- **Do not call `warnIfPersonalBudgetMissing` from `applySettingsWrite`.** The log line is documented as written on each
  config rebuild, and a partial write of a policy or an assignment, which can also make the personal budget missing,
  does not write it either. The gauge `dial_budget_personal_missing` reads `store.config` when it is scraped
  (`MergedConfigStore` constructor), so it follows a settings write at once. Rejected: log on a settings write. It would
  make the log differ between a policy write and a settings write for no reader's benefit.
- **No enum constant is added**, so the sweep in CODE_STYLE "Completing a cross-cutting change" has nothing to do.
- **Tests follow #1987: assertions added to the existing settings tests, no new fixtures or test layers.**
  `ConfigSettingsTest`, `FileConfigApiTest`, `ConfigFileMigrateApiTest` (its config file gets a non-default
  `spendingLimits`, so the copy is visible) and `AdminApplyApiTest` get `spendingLimits` assertions, `ConfigSettingsTest`
  gets two invalid-value tests, and the two settings tests of `MergedConfigStoreReplicaUpdateTest` get it too, with one
  new test for the full rebuild, which no HTTP test reaches. The scratch change in `ConfigManifestSupport` has no test of its
  own: no response depends on it yet, and a test would need a mocked `MergedConfigStore`. A new
  global setting is covered the same way: a few assertions in these tests. Rejected: shared fixtures that compare whole
  `GlobalSettings` and parameterized invalid-value tests. They were built first and dropped: they changed seven existing
  test classes and added three files for a one-field change, where #1987 changed three classes by about 27 lines.
- **OpenAPI:** the `GlobalSettings` schema gains `spendingLimits` by regeneration (`:openapi-generator:replaceSpec`); the
  `SpendingLimits` and `SpendingLimitMode` schemas exist already. The annotations on the settings operations do not list
  fields, so no text changes.

Differences from the reference (#1987): none in shape. The one difference in content is the `@JsonCreator` on
`SpendingLimitMode`, which `rateLimitSchedule`'s `WeekDay` does not have: an unknown `mode` fails in
`ConfigEntityCodec.treeToEntity` (a parse error, `400` / `FAILED`), not in `ValidationUtil`; `mode: null` fails in
`ValidationUtil` (`@NotNull`).

## Risks / Trade-offs

- A request that sends `spendingLimits` with a field unknown to it is accepted and the field dropped, as for the other
  settings objects (`@JsonIgnoreProperties(ignoreUnknown = true)`).
- Per request: no effect. The proxy path reads `config.getSpendingLimits()` as before; no Core pass, body copy, parse, or
  Redis / blob round trip is added. A settings `PUT` validates one more small object. No measurement is needed.
- A replica that has not yet received the resource event keeps the previous `spendingLimits` until it does (as for the
  other fields); every pod converges on the next full rebuild.

## Migration Plan

No data migration, but one operator step. A settings blob written before this change has no `spendingLimits`. After the
upgrade the next rebuild or replica event reads it as `off` / `ORG` and hides the file's `spendingLimits`: a deployment
with `mode: enforce` in the file and a settings blob would silently stop enforcing. Before upgrading, such an operator
adds the file's value to the blob (`PUT /v1/settings/platform/global` with the current body plus `spendingLimits`), or
runs the file migration after deleting the blob. A deployment with no settings blob is unaffected. Rollback: deploy the
previous version; a blob that carries `spendingLimits` is read with that field ignored.
