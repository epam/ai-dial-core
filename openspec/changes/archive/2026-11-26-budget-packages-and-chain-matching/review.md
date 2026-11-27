# Review

## What shipped

- Packages: config type `packages` (`PUT/GET/DELETE /v1/packages/platform/<name>`, admin only, blob-only like
  policies) and the `Package` manifest kind, ordered before `Policy`. A body with a nested `@` member, `*`, an empty
  list or an unknown field gets `400`; a member that names no deployment is accepted and logged at WARN; deleting a
  package a policy names gets `409`.
- A policy's `object` names a package with `@`, alone or in a list, and is read back as written by the policy API and
  in `budgets[].object` of the user stats. A write naming a missing package gets `422`; on a rebuild such a policy goes
  through `config.onInvalidEntity`. A package edit re-scopes its policies without touching counters.
- Coverage along the call chain: the model and the names before it up to the first application, interceptor or
  toolset; only budgets at the earliest covered position are candidates. The priority-tie check expands packages.
- OpenAPI annotations for the package endpoints (CI regenerates `docs/open_api_core.yaml`).
- Docs: new `docs/dynamic-settings/packages.md`; `docs/dynamic-settings/policies.md` gains "Packages in a policy's
  object" and "Which budgets cover a call".

## Verification

- `./gradlew checkstyleMain checkstyleTest`: pass.
- `./gradlew :config:test` and `:server:test` for `PackageApiTest`, `BudgetChainCoverageApiTest`, `PolicyApiTest`,
  `MergedConfigStorePolicyTest`, `PolicyRecordCodecTest`, `EffectiveBudgetsTest`, `BudgetEnforcerTest`,
  `UserBudgetsApiTest`, `BudgetSpendCapsApiTest`, `MessagesSpendLimitApiTest`, `ResponsesSpendLimitApiTest`,
  `BudgetAnalyticsLogApiTest`, `RateLimiterTest`, `PersonalBudgetGuardTest`, `AdminValidateApiTest`,
  `RouterDecisionApiTest` and the alias tests: pass (config tests include `PolicyObjectSetTest`, `CostObjectIndexTest`,
  `PolicyAssignmentValidatorTest`).
- `./gradlew :openapi-generator:mergeOpenApiSpec`: the generator accepts the new annotations.
- Cost measurement (`EffectiveBudgetsTest.testResolveCostWithLongChainAndManyBudgets`, 100 budgets): about 44 µs per
  resolve for the model alone, about 50 µs for a 10-name chain; subject-rule matching dominates both.
- `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`: pass.

## Drift from design.md

- D3 and D6 check packages and referencing policies in storage under the admin bucket locks, not in the merged config,
  as `PolicyService` checks its other cross-record rules; a validate batch stages packages in
  `PolicyAssignmentPrecheck`.
- A package change on the partial-update path requests a full rebuild when policies were skipped or a policy names a
  missing package.
- The POJO is `PolicyPackage` (`Package` clashes with `java.lang.Package`); packages are blob-only.
- The chain stops at names starting with `applications/` or `toolsets/` instead of reusing
  `AliasTargets.isResourcePath`, which would also stop at a stored alias.

## Not done

- Alias tags on counter rows (Core design D13), left to the object-shaped `per` of #2367.
- Pods of the previous release do not publish replica events for `packages`; during a rolling upgrade a package edit
  on an old pod reaches new pods at their next periodic rebuild.
- The full `:server:test` suite runs in CI.
