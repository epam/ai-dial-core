# Tasks

Spec scenarios (`user-info`), all in `UserInfoApiTest`: "API-key caller": `testApiKeyInfo`. "JWT caller" and "Token
without a project": `testUserInfoInfo`. "JWT caller without a display name": `testUserInfoWithoutDisplayName`. "Token
with a project": `testJwtUserInfoWithProject`. "Per-request key of a JWT caller with a project":
`testPerRequestKeyOfJwtCallerWithProject`.

Spec scenarios (`deployment-origin` "Caller in the plugin request"), all in `EmbeddedCallApiTest`: "Caller with an API
key": `testCallerWithApiKey`. "Caller with a JWT": `testCallerWithJwt`. "Caller with a JWT that carries a project":
`testCallerWithJwtProject`.

## 1. JWT project in user info

- [x] 1.1 Tests first, in `UserInfoApiTest`: `testJwtUserInfoWithProject` (`Authorization: user@projA`, asserts the
  whole answer) and `testPerRequestKeyOfJwtCallerWithProject` (an `ApiKeyData` with
  `createClaims("user", "projA")` assigned through `apiKeyStore.assignPerRequestApiKey`, sent as `api-key`; asserts
  `project` and `userId`). The three existing tests stay as they are. Verify both new tests fail before 1.2. In
  `EmbeddedCallApiTest`: `testCallerWithJwt` also asserts no project, and `testCallerWithJwtProject`
  (`Authorization: user@projA`) asserts user id `user`, roles `["user"]` and project `projA`; both pass already (the
  plugin's caller has the JWT project today).
- [x] 1.2 `UserInfoController.java:handle`: in the JWT branch put `project` from `context.getUserProject()` when it is
  not null (design, Code map). Verify with 1.4.
- [x] 1.3 Docs, so they say that a JWT user has a project too: the `UserInfoResponse.project` description in
  `docs/open_api_core.yaml` (present for an API key, and for a JWT when the identity provider's `projectPath` resolves
  one; `SpecMerger` keeps descriptions from the committed file, as #1738 did for `userId`); the
  `identityProviders.*.projectPath` row in `README.md` (the JWT user's project: the analytics `project.id`, the budget
  cost object, the plugin's caller, and `project` in `GET /v1/user/info`, as the `userDisplayName` row says for its
  value); the user-info sentence in `docs/dynamic-settings/routers.md` (`project` also for a JWT whose provider
  resolves one); the caller in `docs/dynamic-settings/plugins.md` "Origin plugins" (the project is the API
  key's, or the one the JWT's provider resolves). Verify each against the two spec deltas.
- [x] 1.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.UserInfoApiTest" --tests "com.epam.aidial.core.server.RouterApiTest" --tests
  "com.epam.aidial.core.server.EmbeddedCallApiTest"` pass (`RouterApiTest` reads user info through a router's key),
  and `npx -y @fission-ai/openspec@1.14.3 validate
  user-info-jwt-project --strict` passes.

## Workflow follow-up

- No measurement task: no cost effect (design, Effect per request).
- Write `review.md`, archive, `validate --specs --strict`, one commit staging explicit paths.
