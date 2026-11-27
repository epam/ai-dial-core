## What shipped

`applications.allowUserAuthoredDependencies` defaults to `true` (code default in `ApplicationService`, the built-in and
sample `aidial.settings.json`, the README row). A user's write of an own application with a valid
`resource_dependencies` section is answered `200` unless an operator sets the flag to `false`. Shape rules, resolution
and admin consent are unchanged.

## Verification

- `CustomApplicationApiTest.testResourceDependenciesSectionWriteTimeValidation` (scenario "User declares with the setting
  left at its default") failed before the change (`403`, "User-authored applications may not change resource
  dependencies") and passes after it.
- The flag-off behaviour keeps its tests with the flag set explicitly: `CustomApplicationApiTest$AllowUserResourceDependenciesOff`
  (`403` for a user, `400` for a malformed section, `200` for an admin in their own bucket),
  `CustomApplicationApiTest$AdminRulesUnconfigured`, `ResourceDependencyOrgUnitApiTest$FlagOff`.
- `./gradlew checkstyleMain checkstyleTest` and `:server:test` for every class that touches resource dependencies
  (AdminApply, AdminValidate, AliasPublic, ConsentService, CustomApplication, PlatformAppToolset, ResourceDependency*,
  ResourceDependencyValidator, ApplicationService): 290 tests, all pass; the 9 tests of the nested classes, all pass.

## Drift from design.md

`CustomApplicationApiTest$AdminRulesUnconfigured` also relied on the old default; it now sets the flag to `false`
(Code map updated).

## Not done

None.
