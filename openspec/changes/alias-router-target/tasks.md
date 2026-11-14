# Tasks

## 1. Router as an alias target

- [ ] 1.1 Compare `design.md` with the code after `feat/plugin-system` is merged; record drift, and the header-name
      check of decision 7, in `design.md`
- [ ] 1.2 Tests in `AliasApiTest`: "Router target loaded", "Router shown as a model by default", "Router shown as a
      toolset"; unit cases for `AliasTargets.parse`, `resolve`, `fits` and `Alias.getEffectivePresentation`
- [ ] 1.3 `AliasTargets` (`parse`, `resolve`, `fits`, the `validateFields` message) and
      `Alias.getEffectivePresentation` accept `routers/<name>`
- [ ] 1.4 Tests in `RouterDecisionApiTest`: "Decision through an alias", "Chosen deployment the caller may not
      call", "Messages through an alias"
- [ ] 1.5 Make them pass: the alias pass and the lend for a router target; a missing router target answers `503`
      and is reported by `/v1/admin/health/config` as other missing targets are
- [ ] 1.6 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "*AliasApiTest" --tests "*RouterDecisionApiTest"`

## 2. Alias name in the answer

- [ ] 2.1 Tests in `RouterDecisionApiTest`: "Answer through an alias names the alias" (buffered and streamed),
      "Messages answer through an alias names the alias"
- [ ] 2.2 The decision flow takes the answer name from the alias when the pass was lent by one
- [ ] 2.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*RouterDecisionApiTest"`

## 3. Default headers of an alias

- [ ] 3.1 Tests in `AliasApiTest`: "Header reaches a model target"; in `RouterDecisionApiTest`: "Header reaches the
      decide request", "Client value replaced", "Direct call unaffected"
- [ ] 3.2 The alias pass sets the alias's `defaultHeaders` on the inbound request, replacing client values
- [ ] 3.3 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "*AliasApiTest" --tests "*RouterDecisionApiTest"`

## 4. Docs

- [ ] 4.1 `docs/dynamic-settings/aliases.md`: the `routers/<name>` target, `defaultHeaders` and that they replace
      client values, the note on a router with `userRoles: []`, the rolling-upgrade note
- [ ] 4.2 `docs/dynamic-settings/routers.md`: a router behind aliases, the name in the answer
- [ ] 4.3 `npx -y @fission-ai/openspec@1.14.3 validate alias-router-target --strict`
