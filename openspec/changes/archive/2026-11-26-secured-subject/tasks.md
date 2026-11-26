# Tasks

## 1. Callers matching `analytics.securedSubject` keep bodies out of analytics rows

- [x] 1.1 Config: `AnalyticsConfig` (`securedSubject`, `@NotNull`, `@ValidSubject`) on `Config` and `GlobalSettings`;
  `ValidSubjectValidator` (empty valid, else `PolicyAssignmentValidator.validateSubject`, message escaped) with
  `ValidSubjectValidatorTest`: null, empty, valid rules, rule without source, escaped admin text.
- [x] 1.2 Settings wiring as for `spendingLimits`: `MergedConfigStore` (write, delete, overlay read, base copies),
  `ConfigResourceController` and `FileConfigController` settings GET, `ConfigFileMigrateController`,
  `ConfigManifestSupport`. `ConfigSettingsTest`: Secured subject from the blob → `testPutSecuredSubjectGetReturnsIt`;
  Empty settings blob → `testPutEmptyBodyDefaultsToEmptyFields` (asserts `analytics.securedSubject: []`); invalid
  pattern → `testPutSecuredSubjectWithInvalidPatternReturns400`; rule without a source →
  `testPutSecuredSubjectRuleWithoutSourceReturns400`; null → `testPutNullSecuredSubjectReturns400`.
- [x] 1.3 Matching: move `EffectiveBudgets.toRule` to `RuleMatcher.toRules`; `ProxyContext.isSecured()` (secured key, or
  a non-empty subject that `RuleMatcher.match` matches) replaces `isSecuredApiKey()`. Rename
  `AnalyticsLogContext.securedApiKey` → `secured` and use it in `GfLogStore.admit`/`append`. `BackgroundJobRecord.from`
  stores `context.isSecured()` under the unchanged JSON property `securedApiKey`.
- [x] 1.4 `SecuredSubjectAnalyticsLogApiTest`, config `dial-config/secured-subject.json` (rules: role `legal`,
  `user_name` EQUAL `test-user`, `groups` CONTAIN `secure`, `c-level-management` `"true"`; keys `legalKey`/`devKey`). Scenarios: Role rule → `testRoleRule`;
  No rule matches → `testNoRuleMatches` (and `testClaimRuleOtherValue`); Claim rule ignores case →
  `testClaimRuleIgnoresCase`; Contain rule on an array claim → `testContainRuleOnArrayClaim`; Contain rule with no
  matching entry → `testContainRuleNoEntryContainsTheTarget`; One rule of several → `testOneRuleOfSeveral`; Text claim "true" → `testStringClaimTrue`;
  Boolean claim → `testBooleanClaimNotMatched`; API key with a matching role → `testApiKeyWithMatchingRole` (and
  `testApiKeyWithoutMatchingRole`); Application called by a secured user → `testApplicationCalledBySecuredUser`;
  Settings blob without a subject → `testSettingsBlobReplacesTheSubject`. `ResourceBaseTest` tokens `roles:` and
  `claims:`. Key not secured / Secured key / Call from an origin plugin keep their existing tests.
- [x] 1.5 Background job of a secured user → `RouterDecisionApiTest.backgroundJobOfSecuredSubjectHasNoBodies` (a role
  rule in `dial-config/router-decision.json`). `GfLogStoreTest` `testSecuredRowHasNoBodies` and
  `testSecuredRowQueuedWithoutBodies` cover the writer for the renamed flag.
- [x] 1.6 Docs: an `analytics` row in the README dynamic-settings table (rules, matching, boolean claims never
  match, API keys, call chain, default, settings API, example); `docs/dynamic-settings/keys.md` `secured` points to it.
- [x] 1.7 `./gradlew checkstyleMain checkstyleTest`; `:config:test --tests "*ValidSubjectValidatorTest"`; `:server:test`
  with `SecuredSubjectAnalyticsLogApiTest`, `ConfigSettingsTest`, `RouterDecisionApiTest`, `GfLogStoreTest`,
  `ProxyContextTest`, `FileConfigApiTest`, `ConfigFileMigrateApiTest`, `AdminApplyApiTest`,
  `MergedConfigStoreReplicaUpdateTest`, `RuleMatcherTest`, `limiter.budget.*`, `BudgetAnalyticsLogApiTest`; all pass.

## Workflow follow-up

- Open a draft PR with proposal.md and the spec deltas before review.
- After review: review.md, `npx -y @fission-ai/openspec@1.14.3 archive secured-subject -y`, then `validate --specs --strict`.
