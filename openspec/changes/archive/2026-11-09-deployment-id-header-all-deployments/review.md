# Review: deployment-id-header-all-deployments

A record of what was done and checked. It is not an independent or three-lens review. The change folder was written
after the code, for PR #2247.

## Shipped

- `BaseDeploymentPostController.sendProxyRequest` sets `X-DIAL-DEPLOYMENT-ID` to the deployment name in every
  interface mode, with `putHeader`, so a client value is replaced.
- `MessagesBaseController` no longer overrides it with the body's `model`; Anthropic Messages gets the deployment name
  from the base controller.
- `DeploymentFeatureController.handleProxyRequest` sets it on `tokenize`, `truncate_prompt`, `rate` and
  `configuration`.
- `ResponsesApiClient.send` takes the deployment name and sets it on Responses item operations;
  `ResponseItemController` passes the mapping's deployment. Background-job polling goes through the same route.
- `docs/open_api_core.yaml` has a "Deployment id" section.

## Checked

Run with JDK 21 and GitHub Packages credentials.

| Check | Result |
|---|---|
| `checkstyleMain`, `checkstyleTest` | pass |
| `DeploymentPostControllerTest` | 40, 2 skipped (`@Disabled` before this change), 0 failed |
| `ResponseItemControllerTest` | 17/17 |
| `FeaturesApiTest` (1 new test) | 7/7 |
| `MessagesApiTest` | 20/20 |
| `ResponsesApiTest` | 8/8 |
| Anthropic controller tests, alias tests | pass |
| Full `./gradlew test` | 3975 tests, 3 failed: `ExternalServiceCredentialsApiTest`, `SkillResourceApiTest`, `AdminSystemExportServiceTest` ("Failed to start Redis service"); all three classes pass when rerun alone |
| `openspec validate deployment-id-header-all-deployments --strict` | valid |

The full suite ran before the Messages override was dropped. After that commit, the Anthropic, alias and
`DeploymentPostControllerTest` classes were rerun. One `MessagesApiTest` run failed
`testAutoCachingPinsSecondTurnToSameUpstream` with `NoHttpResponseException` from the test client; two reruns passed
20/20.

Scenario tests: Feature endpoint (`FeaturesApiTest#testTokenizeEndpointNamesTheDeployment`), Pass-through interface
with overrideName (`DeploymentPostControllerTest#testHandleProxyRequest_NamesTheDeploymentInEveryMode`), Schema
application id (unchanged).

## Not covered

- The "Pass-through interface with overrideName" scenario is covered by a unit test with a mocked request, not by an
  integration test through the full stack. The unit test checks the `putHeader` call; that `putHeader` replaces a
  copied client value is Vert.x behaviour, not tested here.
- The item-request header is checked in `ResponseItemControllerTest` as the argument passed to
  `ResponsesApiClient.send`; no test reads it off the request an origin receives.
- No red check was run (the new assertions were not seen failing against the old code).
