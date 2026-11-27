# Tasks

## 1. Default the flag to on

- [x] 1.1 Tests first: in `CustomApplicationApiTest`, the default-flag test expects `200` for a user's valid section
  (scenario "User declares with the setting left at its default"); move the `403` case to a nested class that sets the
  flag to `false`; `ResourceDependencyOrgUnitApiTest.FlagOff` sets the flag to `false` explicitly. The default case fails
  before 1.2.
- [x] 1.2 `ApplicationService`: the code default of `allowUserAuthoredDependencies` becomes `true`; the built-in and
  sample `aidial.settings.json` say `true`; the README row says `true` and how to keep today's behaviour.
- [x] 1.3 Run `./gradlew checkstyleMain checkstyleTest` and `:server:test` with `--tests` for `CustomApplicationApiTest`,
  `ResourceDependencyApiTest`, `ResourceDependencyConsentApiTest`, `ResourceDependencyOrgUnitApiTest`,
  `ResourceDependencyResolutionApiTest`; `npx -y @fission-ai/openspec@1.14.3 validate user-authored-dependencies-on-by-default --strict`.
