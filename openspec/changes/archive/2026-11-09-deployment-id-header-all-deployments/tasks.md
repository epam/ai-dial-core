# Tasks

## 1. Deployment interfaces

- [x] 1.1 In `BaseDeploymentPostController.sendProxyRequest`, drop the `TRANSLATOR` guard so `X-DIAL-DEPLOYMENT-ID` is
  set to `deployment.getName()` with `putHeader` in every mode; remove the unused `InterfaceMode` import. Verify with
  1.2.
- [x] 1.2 In `DeploymentPostControllerTest`, rename the mode test to `testHandleProxyRequest_NamesTheDeploymentInEveryMode`
  and expect the header for `PASSTHROUGH`, `TRANSLATOR` and no mode, on a model with `overrideName` (scenario
  "Pass-through interface with overrideName"). Verify with `./gradlew :server:test --tests
  "com.epam.aidial.core.server.controller.DeploymentPostControllerTest"`.
- [x] 1.3 In `MessagesBaseController`, drop the `deploymentId` field and the `enrichProxyRequestHeaders` override.
  Verify that `./gradlew :server:test --tests "com.epam.aidial.core.server.MessagesApiTest"` passes unchanged,
  including its spoofed-header case.
- [x] 1.4 Run `./gradlew checkstyleMain checkstyleTest` and the test classes of 1.2 and 1.3.

## 2. Feature endpoints and Responses item operations

- [x] 2.1 In `DeploymentFeatureController.handleProxyRequest`, `putHeader` `X-DIAL-DEPLOYMENT-ID` with the deployment
  name after `copyHeaders`. Verify with 2.2.
- [x] 2.2 In `FeaturesApiTest` add `testTokenizeEndpointNamesTheDeployment`: a `tokenize` call with
  `X-DIAL-DEPLOYMENT-ID: spoofed` reaches the origin with `chat-gpt-35-turbo` (scenario "Feature endpoint"). Verify
  with `./gradlew :server:test --tests "com.epam.aidial.core.server.FeaturesApiTest"`.
- [x] 2.3 Add a `deploymentId` parameter to `ResponsesApiClient.send` and set the header there; pass
  `context.getDeployment().getName()` from `ResponseItemController.sendRequest`. In `ResponseItemControllerTest`,
  check that the deployment name is passed and update the other `send` stubs and verifications. Verify with
  `./gradlew :server:test --tests "com.epam.aidial.core.server.controller.ResponseItemControllerTest" --tests
  "com.epam.aidial.core.server.ResponsesApiTest"`.
- [x] 2.4 Add a "Deployment id" section to `docs/open_api_core.yaml` next to "Deployment features". Verify by reading
  it against the spec requirement.
- [x] 2.5 Run `./gradlew checkstyleMain checkstyleTest` and the test classes of 2.2 and 2.3.

## 3. Integration checks

- [x] 3.1 Run `npx -y @fission-ai/openspec@1.14.3 validate deployment-id-header-all-deployments --strict`; it passes.
- [x] 3.2 Run the full `./gradlew test`.

## Workflow follow-up

- Write `review.md` in the change folder, archive with `npx -y @fission-ai/openspec@1.14.3 archive
  deployment-id-header-all-deployments -y`, then `validate --specs --strict`; commit with explicit paths.
