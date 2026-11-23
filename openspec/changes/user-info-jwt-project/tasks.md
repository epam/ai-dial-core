# Tasks

Spec scenarios (`user-info`), all in `UserInfoApiTest`: "API-key caller": `testApiKeyInfo`. "JWT caller" and "Token
without a project": `testUserInfoInfo`. "JWT caller without a display name": `testUserInfoWithoutDisplayName`. "Token
with a project": `testJwtUserInfoWithProject`. "Per-request key of a JWT caller with a project":
`testPerRequestKeyOfJwtCallerWithProject`.

## 1. JWT project in user info

- [ ] 1.1 Tests first, in `UserInfoApiTest`: `testJwtUserInfoWithProject` (`Authorization: user@projA`, asserts the
  whole answer) and `testPerRequestKeyOfJwtCallerWithProject` (an `ApiKeyData` with
  `createClaims("user", "projA")` assigned through `apiKeyStore.assignPerRequestApiKey`, sent as `api-key`; asserts
  `project` and `userId`). The three existing tests stay as they are. Verify both new tests fail before 1.2.
- [ ] 1.2 `UserInfoController.java:handle`: in the JWT branch put `project` from `context.getUserProject()` when it is
  not null (design, Code map). Verify with 1.4.
- [ ] 1.3 Docs: the `UserInfoResponse.project` description in `docs/open_api_core.yaml` (present for an API key, and
  for a JWT when the identity provider's `projectPath` resolves one; `SpecMerger` keeps descriptions from the committed
  file, as #1738 did for `userId`); the `identityProviders.*.projectPath` row in `README.md` (also returned as
  `project` by `GET /v1/user/info`, as the `userDisplayName` row says); the user-info sentence in
  `docs/dynamic-settings/routers.md` (`project` also for a JWT whose provider resolves one). Verify against 1.1.
- [ ] 1.4 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.UserInfoApiTest" --tests "com.epam.aidial.core.server.RouterApiTest"` pass
  (`RouterApiTest` reads user info through a router's key), and `npx -y @fission-ai/openspec@1.14.3 validate
  user-info-jwt-project --strict` passes.

## Workflow follow-up

- No measurement task: no cost effect (design, Effect per request).
- Write `review.md`, archive, `validate --specs --strict`, one commit staging explicit paths.
