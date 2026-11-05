# Proposal

## Why

Model selection in front of DIAL models is a narrow, common pattern that today has to be built as an application:
the router receives the request, picks a model and calls it back through Core, and Core cannot tell it from any
other application. The perf improvement initiative needs a first-class deployment type for it, because the next
change (`router-decision-mode`) lets Core ask such a deployment for a verdict and serve the chosen model itself,
removing the second Core body pass and the router's user-info call from every routed request. This change lays the
entity down with today's proxy behaviour only, so the decision mode has a type to attach to and operators can
already declare routers as what they are.

## What Changes

- A new dynamic-config map `routers`, each entry a deployment with the common deployment fields (`baseUrl`,
  `interfaces`, `userRoles`, display and feature fields, `defaultHeaders`, `defaults`, `overridePaths`) and
  nothing model- or application-specific: no `upstreams`, `limits`, `pricing`, `interceptors`, runner or
  app-type schema, and no legacy `endpoint` or `responsesEndpoint`: a router declares `interfaces`. Router names share the one deployment-id namespace with models, applications, toolsets and
  interceptors.
- A router's `interfaces` are validated by the same rules as a model's (base url or translator decided by mode,
  pass-through entry needs a url, translator matches the interface, override paths).
- A router is invoked wherever a model id is accepted: `POST /openai/deployments/{id}/chat/completions` (and
  `/embeddings`, `/completions` on the same route), `/openai/v1/chat/completions`, `/openai/v1/responses` and `/anthropic/v1/messages`, and is served in the existing
  `passthrough` and `translator` modes exactly like an application: a per-request key is minted, the router calls
  back through Core, cost rolls up through the call stack.
- A router is never limit-checked or charged itself, on any API; the deployments it calls and the caller are. This
  extends the existing rule that deployments-POST limit-checks only a model to the three `/v1`-style APIs for routers.
- Core adds one identity header, `X-DIAL-USER-INFO`, to every request it forwards to a router (the JSON
  `roles`, `project`, `userId` that `GET /v1/user/info` answers), set after the client's headers are copied so a client
  cannot forge it, and drops a client-sent header of that name from the chat completions, completions, embeddings, Responses,
  Messages and deployment feature requests it forwards (the interceptor and MCP proxy paths copy headers on their own
  and are unchanged; neither reaches a router).
- New read-only endpoints `GET /v1/routers` (routers the caller's roles allow) and `GET /v1/routers/{id}`. Routers do
  not appear in `GET /openai/models`, `/openai/deployments`, `/openai/applications`, `/v1/deployments`,
  `/v1/deployment-names` or `/anthropic/v1/models`, so existing clients see no change.
- The resource type `routers` is registered (blob group `routers`, 30-day default expiry, published on the resource
  event topic) so that the admin-API change can rely on its events one release later, as the event-type rollout rule
  requires. No blob is read or written for routers in this change.

Out of scope, each a change of its own:

- `mode: decision`, the verdict protocol, cross-candidate fallback, conversation binding and outcome hooks
  (`router-decision-mode` and a later change depend on this one).
- The Configuration API for routers (`/v1/routers/platform/{name}`, `/v1/metadata/routers/...`, `Router` manifests
  for `/v1/admin/validate` and `/v1/admin/apply`, file-config inspection and migration). Routers come from the
  config files only in this change. The touch list for that change is in design.md.
- `customProperties` with an app-type schema on a router, user routers in own buckets, sharing, publishing,
  loop protection beyond what applications have today, the simulated analytics hop, routers in `/v1/user/limits`.

## Capabilities

### New Capabilities
- `router-deployment`: what a router deployment is in the dynamic config, how its name and interfaces are validated,
  how it is listed and how a request to it is served and accounted. The decision protocol joins this capability in
  the next change.

### Modified Capabilities
- `request-routing`: the two `GET /v1/routers` routes join the route table.
- `app-to-model-accounting`: "Which requests are limit-checked" gains the router exemption on the three APIs that
  limit-check every deployment kind today.
- `deployment-origin`: "Client headers forwarded and dropped" drops `X-DIAL-USER-INFO` from the client's headers;
  "Identity headers for applications and translated interfaces" gains the router's `X-DIAL-USER-INFO`.

## Impact

Motivated by the router resource design, documentation MR 211 (`design/router_resource.md`, branch
`feat/router_as_DIAL_resource`). Of its phase 1 this change covers: the `routers` config map and resource type
(requirements 1, 8, 10 for the static config), invocation like a model in the proxy modes (2), no limits of its own
(the "Limits, pricing and analytics" section), the own listing and absence from existing listings (7). It leaves
out: admin CRUD over the Configuration API (3, 10 for the platform bucket), the optional decision protocol, the
delegation mechanism (9, a separate design), router-to-router protection beyond what applications have (6),
`customProperties` with an app-type schema (8, the schema part), and all of phase 2. It changes one thing MR 211
does not ask for: the identity header, so a router in proxy mode can stop calling `GET /v1/user/info` on every
request.

Code: module `config` (`Router`, `Config`), module `storage` (`ResourceTypes`), server config layer
(`ConfigPostProcessor`, `MergedConfigStore`), the upstream route provider, the base deployment controller (limits
and headers), a new listing controller with its route templates and OpenAPI registration. Docs: a new
`docs/dynamic-settings/routers.md`, the README dynamic-settings table, CLAUDE.md's config map list, the regenerated
`docs/open_api_core.yaml`. No new dependency, no migration, no behaviour change for a config without `routers`.
