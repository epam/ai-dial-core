# Tasks

Spec scenario to test: `router-deployment` "Router in the deployment listing", "Interface filter", "Router names",
"Single router through deployment info" and "Router hidden from the model and deployment listings": `RouterApiTest`.
"No identity header, user info through the key" and "JWT caller's identity through the key": `RouterApiTest`.
"Router asks a model while deciding", "Deployment closed to per-request keys", "Key gone after the decision",
"Headers and empty body", "Body on request", "Shorthand accepted" and "Schema mismatch": `RouterDecisionApiTest`.
"Decision failures" and "Decision record" scenarios: the existing `RouterDecisionApiTest` cases stay green.
`deployment-origin` "Router identity header": `RouterApiTest`.

## 1. Generic listings

- [x] 1.1 `DeploymentData` `router` subtype; `RouterData.toData` sets `interfaces`; `DeploymentController` router
  plan, `getRouters`, `ROUTER_TYPE` in names, in the `type` parameter and in `OpenApiDescriptions.DEPLOYMENT_NAME_TYPES`,
  and the `Router` arm in `toDeploymentData` (design D1). Tests first in `RouterApiTest`: the four listing scenarios
  (the listing one asserts `interfaces` contains `chat`). Rewrite `routerHiddenFromTheModelAndDeploymentListings` to
  the typed listings only.
- [x] 1.2 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterApiTest" --tests "com.epam.aidial.core.server.DeploymentApiTest"` pass.

## 2. Identity header removed

- [x] 2.1 Delete `ProxyUtil.buildUserInfoHeader` and its four call sites, and drop the `userInfo` parameter of
  `ResponsesApiClient.send`. Keep the client-header exclusions (design D2). Tests first in `RouterApiTest`:
  - Rewrite `projectKeyCaller`, `featureEndpointCallCarriesTheIdentityHeader` and
    `responseItemCallCarriesTheIdentityHeader` to assert that no `X-DIAL-USER-INFO` arrives, a client-sent one
    included. Rewrite `jwtCaller` as "JWT caller's identity through the key": the mock router calls
    `GET /v1/user/info` with its `API-KEY` and gets `userId: "u1"` and the JWT roles.
  - Add "No identity header, user info through the key": the mock router calls `GET /v1/user/info` with the
    `API-KEY` it received, from inside its handler, and gets `project: "p1"`.
  - Delete `nonAsciiUserIdIsEscaped`; keep `identityHeaderNotAddedForApplication` and
    `clientIdentityHeaderDroppedForModel`.
  - Rewrite the typed-router and `deployment-origin` cases that assert the header, and the class javadoc, which
    still says routers get `X-DIAL-USER-INFO` and are listed only by `/v1/routers`.
- [x] 2.2 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterApiTest" --tests "com.epam.aidial.core.server.Responses*" --tests
  "com.epam.aidial.core.server.ResponseItem*"` pass.

## 3. The decide key and the verdict

- [x] 3.1 `decideIfRouter` starts the span, mints the decide key on the task executor, passes it to
  `RouterDecisionClient.decide`, and invalidates it when the call ends; `send` sets `API-KEY` and no
  `X-DIAL-USER-INFO` (design D3). Tests first in `RouterDecisionApiTest`:
  - "Headers and empty body" rewritten: `API-KEY` present and not the client's key, no `X-DIAL-USER-INFO`.
  - "Router asks a model while deciding": the decide handler calls Core's `m1` with the decide key. `m1` has no
    limit; `gpt-4` has `requestHour: 1` for the only key. The verdict is `gpt-4`. Assert:
    - the router's call row has `parent_deployment: r1` and no `decision`;
    - the served pass's key differs from the decide key;
    - the client's non-streamed answer has `m1` in `statistics.usage_per_model`.
  - "Deployment closed to per-request keys": the router calls a deployment with `accessibleByPerRequestKey: false`
    and gets `403` (add one to the test config if none exists).
  - "Key gone after the decision": the test keeps the decide key and calls `GET /v1/user/info` with it after the
    response, retrying briefly until it answers `401`, since the invalidation is not awaited.
  - Assert the decide key's Redis TTL is at most `decisionTimeoutMs` plus 60 s.
  - `RouterDecisionClientTest`: the private `decide()` helper takes the key. `decideRequestHeaders` answers
    `{"candidates":["a"]}` instead of the shorthand and asserts `API-KEY` and no `X-DIAL-USER-INFO`. Remove the
    `getKey` and `getUserRoles` mocks that become dead.
- [x] 3.2 `parseCandidates` without the `deployment` branch (D4). Tests first: `RouterDecisionApiTest` "Shorthand
  accepted" (a one-element `candidates` list with `ttl`) and "Schema mismatch" (with `{"deployment": "gpt-4"}` added);
  `RouterDecisionClientTest.shorthandReadAsOneCandidate` becomes a `502` case.
- [x] 3.3 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.RouterDecisionApiTest" --tests
  "com.epam.aidial.core.server.service.RouterDecisionClientTest" --tests
  "com.epam.aidial.core.server.token.TokenStatsTrackerTest"` pass.

## 4. Docs and OpenAPI

- [x] 4.1 `./gradlew :openapi-generator:replaceSpec`; verify that the only additions are the `router` subtype, the
  `type` value, the names description and `interfaces` on `RouterData`, and that
  `./gradlew :openapi-generator:checkOpenApiDiff` passes.
- [x] 4.2 `docs/dynamic-settings/routers.md` per design D5. Verify each statement against a test of groups 1 to 3.
- [x] 4.3 `./gradlew checkstyleMain checkstyleTest` passes and `npx -y @fission-ai/openspec@1.14.3 validate
  router-identity-and-listing --strict` passes.

## 5. Integration check

- [x] 5.1 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.Router*" --tests "com.epam.aidial.core.server.Deployment*" --tests
  "com.epam.aidial.core.server.Responses*" --tests "com.epam.aidial.core.server.service.RouterDecisionClientTest"
  --tests "com.epam.aidial.core.server.controller.*"` pass; the full suite runs in CI after the push.
- [ ] 5.2 Perf: the cost claims in design.md "Effect per request" (deferred: needs the perf environment).

## Workflow follow-up

- Write `review.md`, archive, `validate --specs --strict`, one commit staging explicit paths, push to PR #2232.
