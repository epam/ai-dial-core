# Proposal

## Why

Review of PR #2232 reversed three choices of the router changes and simplified a fourth:

- Routers were hidden from the generic `/v1` deployment listings, so clients that browse deployments cannot find
  them.
- Core sent the router the caller's identity in its own header, `X-DIAL-USER-INFO`, instead of the standard path,
  where a deployment calls `GET /v1/user/info` with the key Core gives it. The perf gain does not justify a
  non-standard and riskier contract: a router that trusts the header must be unreachable from anywhere but Core.
- In decision mode the router got no key at all, so it could not call Core while deciding, for example to ask a
  classifier model, and the docs wrongly said it never calls Core back.
- The verdict had two shapes, `candidates` and a `deployment` shorthand, where one array covers both.

## What Changes

- **Generic listings.** `GET /v1/deployments`, `GET /v1/deployment-names` and `GET /v1/deployments/{id}` list routers
  the caller's roles admit, as entries with `object: "router"`. The typed listings (`/openai/models`,
  `/openai/deployments`, `/openai/applications`, `/anthropic/v1/models`) stay router-free, and `GET /v1/routers`
  stays.
- **BREAKING for router services: no identity header.** Core no longer sends `X-DIAL-USER-INFO` to a router, on any
  path: proxied requests, feature endpoints, Responses item calls and decide requests. A router that needs the
  caller's identity calls `GET /v1/user/info` with the `API-KEY` Core sends it. Core still drops a client-sent
  `X-DIAL-USER-INFO`, so an older router that trusts the header cannot be fooled.
- **A key for the decide request.** Core mints a per-request key for every decide request and sends it as
  `API-KEY`. It carries the caller's identity, roles and claims and the per-request permissions granted to the router,
  and has the router last in its execution path. While deciding, the router can call `GET /v1/user/info` and any
  deployment open to per-request keys. Those calls are passes of their own with their own analytics rows, and their
  usage shows in the served answer's `statistics.usage_per_model`. Core invalidates the key when the decide call ends.
- **One verdict shape.** The decide answer is `{"candidates": [...]}` only. A `{"deployment": ...}` answer is an
  invalid verdict (`502`), like any other body without `candidates`.

Out of scope: telling the router which candidate served (a follow-up once its mechanism is chosen), the Go
router's own changes (it already answers `candidates`, and it must read identity from `GET /v1/user/info`), and any
change to how a decided request is served or recorded.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `router-deployment`: "Routers absent from other listings" narrows to the typed listings; added "Routers in the
  generic deployment listings"; removed "Identity header for a router"; added "Router identity through user info";
  modified "Decide request" and "Verdict"; added "Decide request key"; "Decision failures" and "Decision record" say
  they hold for the request itself, not for the router's own calls.
- `deployment-origin`: "Identity headers for applications and translated interfaces" drops the router's identity
  header.

## Impact

Motivated by the review of PR #2232 (the author's inline comments on `docs/dynamic-settings/routers.md`,
https://github.com/epam/ai-dial-core/pull/2232#discussion_r4483298886 and the five that follow). It covers
the comments on listings, the identity header, the decide request's key and its callbacks, and the verdict shape.
It leaves out the comment that asks how the router learns which candidate served, which is a discussion with no
mechanism chosen yet. It reverses the identity-header part of `router-entity` and the no-key and shorthand parts of
`router-decision-mode`. It ships in PR #2232.

Code: `DeploymentController` and `DeploymentData` (listings), `BaseDeploymentPostController`,
`DeploymentFeatureController`, `ResponseItemController`, `ResponsesApiClient` and `RouterDecisionClient`
(identity header removed), `ProxyUtil.buildUserInfoHeader` (deleted), `BaseDeploymentPostController.decideIfRouter`
and `RouterDecisionClient.decide` (decide key), `RouterDecisionClient.parseCandidates` (verdict). Docs:
`docs/dynamic-settings/routers.md`, regenerated `docs/open_api_core.yaml`.

Cost: every routed request that needs identity adds the router's `GET /v1/user/info` round trip. Every decided
request adds a key mint, a key invalidation and one more span update. The review accepts this for now; optimizing
it is later work.

Dependency: the Go router must take identity from `GET /v1/user/info` (with the key it receives) before or together
with this change. Otherwise, after this change it sees no caller identity.
