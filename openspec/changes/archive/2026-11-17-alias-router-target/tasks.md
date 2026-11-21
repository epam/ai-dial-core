# Tasks

## 1. Router as an alias target

- [x] 1.1 Compare `design.md` with the code after `feat/plugin-system` is merged; record drift in `design.md`
- [x] 1.2 Tests in `ConfigPostProcessorAliasTest` (beside the other load checks): "Router target loaded", "Router
      target without a type" (with and without `r1` in the config), "Router shown as a toolset"; in
      `RouterDecisionApiTest` (its config has the routers): "Router shown as a model"; in `AliasPublicApiTest`: "Router
      target of a stored alias"; unit cases for `AliasTargets.parse`, `resolve`, `fits`, `isRouterPath` and
      `validateFields`
- [x] 1.3 `AliasTargets` (`parse`, `resolve`, `fits`, the `validateFields` message) accept `routers/<name>`;
      `AliasTargets.validateFields` refuses it without `presentAs`; a public alias refuses it on `PUT` and on read
- [x] 1.4 Tests in `RouterDecisionApiTest`: "Decision through an alias", "Chosen deployment the caller may not
      call", "Messages through an alias", "Alias over a router as a candidate"
- [x] 1.5 Make them pass: the alias pass and the lend for a router target; a missing router target answers `503`
      and is reported by `/v1/admin/health/config` as other missing targets are; `RouterDecisionFlow.askRouter`
      refuses an alias over a router as a candidate
- [x] 1.6 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "*AliasApiTest" --tests "*RouterDecisionApiTest"`

## 2. Alias name in the answer

- [x] 2.1 Tests in `RouterDecisionApiTest`: "Answer through an alias names the alias" (buffered and streamed),
      "Answer through an alias called by an application names the alias", "Stored Responses answer through an alias
      read back names the alias", "Messages answer through an alias names the alias"
- [x] 2.2 `RouterDecision` carries the answer name (decision 4); the `RouterModelFn` call sites and
      `ReplaceResponseIdFn`'s mapping use it, `getRouterName` is unchanged
- [x] 2.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*RouterDecisionApiTest"`

## 3. Default headers of an alias

- [x] 3.1 Tests in `AliasApiTest`: "Header reaches a model target"; in `RouterDecisionApiTest`: "Header reaches the
      decide request", "Client value kept", "Direct call unaffected"
- [x] 3.2 `AliasPasses.build` copies the alias's `defaultHeaders` to the pass
- [x] 3.3 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "*AliasApiTest" --tests "*RouterDecisionApiTest"`

## 4. Docs

- [x] 4.1 `docs/dynamic-settings/aliases.md`: the `routers/<name>` target, `defaultHeaders` (a client value wins), the note on a router with `userRoles: []`, the rolling-upgrade note
- [x] 4.2 `docs/dynamic-settings/routers.md`: a router behind aliases, the name in the answer
- [x] 4.3 `npx -y @fission-ai/openspec@1.14.3 validate alias-router-target --strict`
