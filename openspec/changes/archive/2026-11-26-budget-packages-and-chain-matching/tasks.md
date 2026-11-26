# Tasks

## 1. Package record and policy references

- [x] 1.1 Add `PACKAGE` to `ResourceTypes`, the `Package` POJO (`deployments`, `description`, unknown fields
  rejected, members neither blank, `*` nor `@...`) and `Config.packages`; verify with new cases in
  `PolicyRecordCodecTest` (or a new `PackageTest`) that bind and reject each body of "Package record"
- [x] 1.2 Extend `PolicyObjectSet` with `@name` terms kept as written (single reference serialised as a string, lists
  sorted) and `expand(packages)`; verify with `PolicyRecordCodecTest` / `PolicyAssignmentValidatorTest` cases for
  "Policy names a package" (mixed list order, `*` inside a list rejected, reserved `packages` still `'packages' is not
  supported yet`)
- [x] 1.3 Serve packages through `ConfigResourceController` (GET/PUT/DELETE on the platform bucket, admin only, flat
  name) and the `Package` manifest kind ordered before `Policy` in `ConfigManifestSupport` and `AdminManifest`;
  verify with new `PackageApiTest` scenarios "Package written and read back", "Non-admin write", "Nested package or
  wildcard", "Unknown field", "Name with a folder", and the config-store scenarios "Package manifest with a folder"
  and "Policy before its package in the input"
- [x] 1.4 Referential integrity (D3): `422` on a policy write naming a missing package in `PolicyService.checkPolicy`
  (stored plus staged packages), `409` on deleting a referenced package, missing package as an invalid policy in
  `ConfigPostProcessor.validateSinglePolicy`, WARN on a member that is not a deployment; verify with `PackageApiTest`
  "Write naming a missing package", "Delete of a referenced package", "Delete of an unreferenced package", "Dangling
  member accepted", and `MergedConfigStorePolicyTest` "Rebuild under skip"
- [x] 1.5 Build `CostObjectIndex` after the package map with the expanded set on each `BudgetInstance`, and treat a
  package write as touching the index in `MergedConfigStore.refreshCostObjectIndex`; verify with
  `MergedConfigStorePolicyTest` cases that a package edit changes the covered set of its policies without a policy
  write
- [x] 1.6 Expand packages in `PolicyService.Instances.requireNoTie` (D6); verify with a `PolicyApiTest` case where
  budgets naming `@engineering` and `["strong"]` at one priority and overlapping subjects get `409`
- [x] 1.7 Write `docs/dynamic-settings/packages.md` (record, API, manifest, delete rule, dangling members, no
  permission effect) and update `docs/dynamic-settings/policies.md` (`object` with `@name`); verify the JSON examples
  in both are accepted by `PUT` in `PackageApiTest`
- [x] 1.8 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :config:test :server:test --tests
  "*PackageApiTest" --tests "*PolicyApiTest" --tests "*MergedConfigStorePolicyTest" --tests "*PolicyRecordCodecTest"
  --tests "*PolicyAssignmentValidatorTest"`, all green

## 2. Coverage chain and attribution

- [x] 2.1 Extract `ApiKeyData.pathOf(context)` from `initFromContext` with no change to the minted key; verify the
  existing app-to-model-accounting and deployment-aliases execution-path tests still pass
- [x] 2.2 Build the coverage chain in `BudgetEnforcer` (D4: stop at a config application, interceptor or toolset, or
  an `applications/` / `toolsets/` resource url) and pass it to `EffectiveBudgets.resolve`; verify with
  `BudgetEnforcerTest` cases for each "Coverage chain" scenario
- [x] 2.3 Implement coverage and attribution in `EffectiveBudgets.resolve` (D5: earliest covered position, then
  priority and id; `*` at position 0); verify with `EffectiveBudgetsTest` cases for "Alias budget and model budget",
  "Same position" and "Direct call"
- [x] 2.4 Full-stack tests in a new `BudgetChainCoverageApiTest` (`spendingLimits.mode: enforce`, MockWebServer
  router in decision mode): "Package of tier aliases", "Model called directly", "Tier outside the package",
  "Application calling a tier alias", "Interceptor breaks the chain", "Budget for every deployment", "Earliest budget
  exhausted", "Router in proxy mode", and the budget-packages scenarios "Member added" and "Member removed" (counter
  untouched by the edit)
- [x] 2.5 Pin the translated path (design Risks): a Messages request to `strong` whose model serves
  `anthropicMessages` through a translator is paid by the `@engineering` budget in `BudgetChainCoverageApiTest`; if
  not, correct D4 in design.md in place and report it for review.md
- [x] 2.6 `object` as written in the user stats: verify the user-limits scenario "Budget for a package" in
  `UserBudgetsApiTest`
- [x] 2.7 Measure resolve with a 10-name chain and 100 budgets against today's resolve in an `EffectiveBudgetsTest`
  timing case, and record the numbers for review.md (design "Effect per request")
- [x] 2.8 Document coverage along the chain and attribution, with the topology caveat and the interceptor note, in
  `docs/dynamic-settings/policies.md`; verify the documented example matches "Package of tier aliases"
- [x] 2.9 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*EffectiveBudgetsTest"
  --tests "*BudgetEnforcerTest" --tests "*BudgetChainCoverageApiTest" --tests "*UserBudgetsApiTest" --tests
  "*BudgetSpendCapsApiTest" --tests "*MessagesSpendLimitApiTest" --tests "*ResponsesSpendLimitApiTest"`, and
  `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`, all green

## Workflow follow-up

- Write review.md, archive with `npx -y @fission-ai/openspec@1.14.3 archive budget-packages-and-chain-matching -y`,
  then `validate --specs --strict`.
