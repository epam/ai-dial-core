# Review

## What shipped

- Dynamic setting `analytics.securedSubject` (`AnalyticsConfig`, default `[]`): subject rules on `roles`, `email` or a
  claim path, OR'd, checked by `ValidSubjectValidator` (empty is valid and matches nobody; otherwise as a policy
  assignment's subject). On `Config` and `GlobalSettings`, wired through the settings singleton like `spendingLimits`:
  overlay, write, delete, file view, migrate and manifest apply.
- `ProxyContext.isSecured()` replaces `isSecuredApiKey()`: a `secured` key, or a caller matching the subject in the
  config in force when the row is built. `RuleMatcher.toRules` is shared with budgets. A file config with
  `"analytics": null` matches nobody.
- The analytics row's flag (`AnalyticsLogContext.secured`) drops `request.body`, `response.body` and
  `assembled_response` in `GfLogStore.admit` and `append`. A background job stores the submit-time decision under the
  unchanged JSON property `securedApiKey`.
- Docs: an `analytics` row in the README dynamic-settings table; `docs/dynamic-settings/keys.md` `secured` points to it.

## Verification

- `checkstyleMain checkstyleTest` pass.
- `:config:test` `ValidSubjectValidatorTest` passes.
- `:server:test` `SecuredSubjectAnalyticsLogApiTest`, `ConfigSettingsTest`, `RouterDecisionApiTest`, `GfLogStoreTest`
  and `ProxyContextTest` pass.
- `validate secured-subject --strict` passes.

## Drift from design.md

None.

## Not done

- The settings blob is not validated when Core reads it back (`MergedConfigStore.applySettingsOverlay`), only when it
  is written through the API. A blob edited directly in storage with an invalid `REGEX` target would throw on each
  row. `spendingLimits` has the same gap; a separate change.
- `RuleMatcher` compiles each `REGEX` target on every match, here once per analytics row. Budgets already pay this.
- `backgroundJobOfSecuredSubjectHasNoBodies` does not change the config between submit and completion, so it does not
  show that the submit-time decision is kept.
