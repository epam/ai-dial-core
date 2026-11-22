# Review: router-identity-and-listing

Implemented on `feat/router-deployment-type` at 79418c3c (PR #2232), applying the author's review comments on
`docs/dynamic-settings/routers.md`. The plan was reviewed before apply by an independent agent with no context of the
discussion. It reported 18 findings, one a blocker: the planned roll-up check used a model's `price`, which is its
own cost only, on a model the fixture limits to one request an hour. All 18 were folded in. Not taken: renaming the
MODIFIED requirements' scenarios, because the validator requires the existing names.

## What changed

- Listings (D1): `DeploymentData` `router` subtype. `RouterData.toData` sets `interfaces`, so `GET /v1/routers` entries
  gain the field too. `DeploymentController` lists routers for `all`, `chat` and `embedding`, adds `router` to
  `/v1/deployment-names`, and answers `GET /v1/deployments/{router}`. Tests: `RouterApiTest`
  `routerInTheDeploymentListing`, `routerInterfaceFilter`, `routerNames`, `singleRouterThroughDeploymentInfo`, and
  `routerHiddenFromTheModelAndDeploymentListings` narrowed to the typed listings.
- Identity header (D2): `ProxyUtil.buildUserInfoHeader` is deleted along with its four call sites, and
  `ResponsesApiClient.send` lost its `userInfo` parameter. The client-header exclusions stay. Tests: `RouterApiTest`
  `jwtCallerIdentityThroughTheKey` and `projectKeyCallerIdentityThroughTheKey` (the mock router calls
  `GET /v1/user/info` with its key), `featureEndpointCallCarriesNoIdentityHeader`,
  `responseItemCallCarriesNoIdentityHeader`. `nonAsciiUserIdIsEscaped` was deleted. `ResponseItemControllerTest`
  matchers follow the new signature.
- Decide key (D3): `decideIfRouter` starts the span, mints the key on the task executor with a TTL of the decision
  budget plus 60 s, passes it to `RouterDecisionClient.decide`, and invalidates it when the call completes. `send` sets
  `API-KEY`. Tests: `RouterDecisionApiTest.routerAsksModelWhileDeciding` covers:
  - the router's `m1` call is served, and its row has `parent_deployment: r1` and no `decision`;
  - the client's `statistics.usage_per_model` has `m1`;
  - the served pass's key is a different key;
  - the decide key's TTL is at most 65 s;
  - the key answers `401` after the decision.

  `deploymentClosedToPerRequestKeysRefusesTheDecideKey` (`403`, fixture model `m-closed`) and the rewritten
  `headersAndEmptyBody` cover the rest. `RouterDecisionClientTest` follows the new parameter and header set.
- Verdict (D4): `parseCandidates` reads `candidates` only. Tests: `RouterDecisionApiTest.shorthandAccepted` (one
  candidate with an unknown field), `schemaMismatch` (with `{"deployment": ...}`),
  `RouterDecisionClientTest.shorthandNoLongerRead`.
- Docs: `docs/dynamic-settings/routers.md`:
  - "Listing" names the generic listings and the `interfaces` field.
  - "Identity header" became "Identity".
  - "Decision mode" says the router may call Core while deciding, and the decide request bullets describe the key.
  - "The verdict" shows one shape.

  `docs/open_api_core.yaml`: the `router` subtype, the `type` value and the names description, which had to be edited
  by hand because the merge keeps existing descriptions.

## Verification

`./gradlew checkstyleMain checkstyleTest` is clean. A targeted run of `Router*`, `Deployment*`, `Responses*`,
`RouterDecisionClientTest`, `controller.*`, `Config*` and `token.*` passes (676 tests). `checkOpenApiDiff` passes.
`validate router-identity-and-listing --strict` is valid. The full suite runs in CI after the push. Task 5.2, the
perf measurement of the new per-request cost, is deferred: it needs the perf environment.

## Drift from the plan

- `GET /v1/deployment-names` entries serialize as `id`, not `name`. The scenario and the test say `id`.
- `RouterDecisionClient.keyTtl(entry)` carries the TTL rule. The design names the rule, not the method.

## Notes

- The Go router's `/decide` handler still reads identity only from the header. It must call `GET /v1/user/info`
  with the `API-KEY` it now receives before, or together with, this Core.
