# Tasks

## 1. Refuse per-request keys on consent acceptance

- [x] 1.1 In `ConsentApiTest`, add the scenario "Application accepts consent with its key": a per-request key of a call
  by `u1` posts a valid consent for `secured-app`; assert `403` with body `User consent is not allowed for per-request
  keys`, then `GET /v1/consent/secured-app` by `u1` still answers `accepted: false`. The test fails before 1.2.
- [x] 1.2 In `ConsentController.acceptConsent`, answer `403` with that body when
  `context.getApiKeyData().getPerRequestKey() != null`, before reading the body. Verify: 1.1 passes and
  `testUserConsentWorkflow` (scenario "User accepts consent") still passes.
- [x] 1.3 Run `./gradlew checkstyleMain checkstyleTest :server:test --tests "*ConsentApiTest"` and
  `npx -y @fission-ai/openspec@1.14.3 validate user-consent-refuses-per-request-keys --strict`.
