# Design

## Context

See proposal.md for motivation. Current code on the PR branch (79418c3c):

- **Listings.** `DeploymentController` builds `/v1/deployments` from models, applications and toolsets (`execute`,
  `build`), `/v1/deployment-names` from `DEPLOYMENT_NAME_TYPES` = `model`, `application`, `toolset`, and
  `GET /v1/deployments/{id}` through `toDeploymentData`, whose `default` arm answers `404` for a router.
  `DeploymentData` is `kind`-discriminated on `object` (`@ApiSubTypes` model, application, toolset). `RouterData`
  already extends `DeploymentData` with `object: "router"` (`RouterData.toData`). `DeploymentService.findDeployment`
  finds routers and answers `PermissionDeniedException` when `hasAccess` fails. `RouterData.toData` sets no
  `interfaces`, while every other generic entry has them (`DeploymentController.to(...)`).
- **Identity header.** `ProxyUtil.buildUserInfoHeader` is called from four places:
  `BaseDeploymentPostController.sendProxyRequest`, `RouterDecisionClient.send`, `DeploymentFeatureController`, and
  `ResponseItemController`, which passes it to `ResponsesApiClient.send`. The first three drop a client-sent header of
  that name (`excludeHeaders`). `ResponsesApiClient` copies no client header at all.
- **Usage in the answer and the row.** A model pass's row `price` is its own cost only (app-to-model-accounting
  "Usage in the analytics row"), while the roll-up of a span's children reaches the client's
  `statistics.usage_per_model` (`BaseDeploymentPostController.maybeInjectUsagePerModel`). The decide key's tests check
  the roll-up there.
- **Decide flow.** The decide request runs in `decideIfRouter`, before the pass's key and span exist: the controllers
  call `decideIfRouter` and then `handleRateLimitSuccess` or the candidate's `handleDeployment`, which sets up the key
  (`ApiKeyData.initFromContext`) and starts the span (`TokenStatsTracker.startSpan`). `initFromContext` builds the
  execution path from the context: with no decision yet and the router as the context deployment, the path ends with
  the router and `sourceDeployment` is the router. A span started again keeps its usage roll-up and takes the new
  deployment name (`TokenStatsTracker.TraceContext.addSpan`, from `router-decision-gaps`). `ApiKeyStore.assignPerRequestApiKey`
  blocks on Redis; `invalidatePerRequestApiKey` runs on the task executor and returns a future.
- **Verdict.** `RouterDecisionClient.parseCandidates` reads `candidates`, or `deployment` as one candidate.

## Goals / Non-Goals

**Goals:**

- Routers in the three generic listings with the `GET /v1/routers` entry shape.
- No `X-DIAL-USER-INFO` from Core on any path. Routers use `GET /v1/user/info` with their key.
- A per-request key on every decide request, scoped to the decision, whose children's usage rolls up.
- `candidates` as the only verdict shape.

**Non-Goals:**

- Telling the router which candidate served.
- Cutting the new per-request cost (user-info round trip, decide key), which the review accepts for now.
- Any change to the Go router.

## Decisions

### D1. Listings reuse `RouterData`

`DeploymentData` gains `@ApiSubType(discriminatorValue = "router", type = RouterData.class)`. In `DeploymentController`:

- `ExecutionPlan` gets `useRouters` and router filters. `all` sets it with no filter, `chat` adds "declares
  `openaiChatCompletions`", `embedding` adds "declares `openaiEmbeddings`", and `mcp` and `custom_ui` leave it off.
- `getRouters(filters)` lists `config.getRouters()` with `hasAccess`, mapped by `RouterData.toData`.
- `RouterData.toData` sets `interfaces` as `DeploymentController.to(Application)` does: `chat` when the router declares
  `openaiChatCompletions`, `embedding` when it declares `openaiEmbeddings`, plus the declared interface types. It is
  shared, so `GET /v1/routers` entries gain the field too.
- Deliberate difference: `embedding` lists a router that declares `openaiEmbeddings`. Applications are never listed
  for `embedding` (`build` adds only a model filter), but a router that serves embeddings is a natural answer to that
  filter.
- `DEPLOYMENT_NAME_TYPES` gains `router` (`ROUTER_TYPE`), the `type` parameter's `allowableValues` gain it, and
  `listDeploymentNames` adds `addAccessibleNames(names, config.getRouters().values(), ROUTER_TYPE)`.
- `toDeploymentData` gets `case Router router -> RouterData.toData(router)`, and its comment loses "routers".
  `findDeployment` already answers `403` for a router the caller's roles do not admit.

OpenAPI: `DeploymentData`'s `router` subtype also shows on `/openai/deployments` responses, which never return one.
That is a limit of the shared schema. `OpenApiDescriptions.DEPLOYMENT_NAME_TYPES` lists `router`.

Rejected: a router-specific entry type for the generic listings. `RouterData` is already the router's public shape,
and two shapes for one entity would drift.

### D2. The identity header goes away everywhere; the client-header drop stays

`ProxyUtil.buildUserInfoHeader` is deleted along with its four call sites, and `ResponsesApiClient.send` loses its
`userInfo` parameter. `Proxy.HEADER_USER_INFO` stays, because the three `excludeHeaders` entries still drop a
client-sent header: an older router that trusts it must not be fooled by a client.

Rejected: keeping the header as an opt-in per router. The review asked for the standard path. An opt-in keeps the
riskier contract alive and doubles the router's identity code.

### D3. The decide key: span first, key, decide, invalidate

In `decideIfRouter`, on the decision branch, after the re-entry check:

1. `proxy.getTokenStatsTracker().startSpan(context)`. The span is recorded under the router's name, so child usage
   has a parent to roll up into.
2. On the task executor, a new `ApiKeyData`, `ApiKeyData.initFromContext(key, context)` (path `[..., router]`,
   source `router`, the pass's span id), and `assignPerRequestApiKey(key, ttl)` with a TTL of the entry's
   `decisionTimeoutMs` plus 60 s. A Core that dies before the invalidation then leaves no long-lived key.
3. `RouterDecisionClient.decide(context, router, entry, type, bytes, key.getPerRequestKey())`. `send` sets `API-KEY`.
4. On completion, success or failure, `invalidatePerRequestApiKey(key)`, logged on failure and not awaited.

The key is never `context.proxyApiKeyData`. `finalizeRequest` and the candidate walk keep handling only the pass's
own key. The candidate's pass then mints its own key as today (path `[..., router, candidate]`) and starts the span
again. `addSpan` finds the span, keeps what the router's calls rolled up, and takes the candidate's name.

What the key carries is what `initFromContext` copies at that moment: the caller's original key, claims and trace,
and the per-request permissions granted to the router (and to the initial deployment after an interceptor chain).
It carries no attachment shares, because the enhancement chain has not run yet. So with `context.body: true` the
router sees attachment URLs it cannot read. A deployment with `accessibleByPerRequestKey: false` refuses it
(`handleDeployment`).

A call the router makes with the key is a pass of its own: it gets its own analytics row (`parent_deployment` the
router, no `decision`), and its usage rolls up into the span. "Decision failures" and "Decision record" now say they
hold for the request itself.

A deployment the router calls with the decide key has the router last in its path, so the existing re-entry check
(`Router <name> re-entered`, `502`) still bounds a loop back into the router.

Rejected: invalidating the key at the end of the request. The design document's rule is "valid only for the decision
call", and a key that outlives the decision widens what a router could do with it.

Rejected: no span before the decision. The router's calls would then report to a span that does not exist yet, and
their cost would be missing from the request's roll-up.

### D4. One verdict shape

`parseCandidates` drops the `deployment` branch. An answer without a non-empty `candidates` array is the existing
`502` "invalid verdict". `RouterDecisionClientTest.shorthandReadAsOneCandidate` becomes a rejection test.

Rejected: keeping `deployment` as a deprecated alias. The review asked for one shape, and the only router that
speaks the protocol, the Go router, already answers `candidates`.

### D5. Docs

In `docs/dynamic-settings/routers.md`:

- The "Identity header" section becomes "Identity": call `GET /v1/user/info` with the `API-KEY`, and Core drops a
  client-sent `X-DIAL-USER-INFO`. The in-cluster trust paragraph goes.
- "Listing" names the generic listings.
- "Decision mode" loses "never calls Core back", and the decide request bullets name the key and its lifetime.
- "The verdict" shows one shape.

`docs/open_api_core.yaml` is regenerated: the `router` subtype and the new `type` value.

## Effect per request

- **Routed request, proxy mode.** Core builds one header less. A router that needs identity adds one
  `GET /v1/user/info` round trip through Core (the A/B measured the user-info pass at about 3 ms of Core CPU and 34
  Redis commands).
- **Decided request.** One more span update, which is a Redisson lock acquire and release plus a GET and a SET
  (`TokenStatsTracker.update`). One key mint and invalidation: a Redis SET with TTL and a DEL. Add the router's own
  user-info call if it makes one.
- **Listings.** One more map scan per listing request.
- **Measurement.** None claimed beyond these counts. A perf-env run is deferred (it needs the perf environment).

## Risks / Trade-offs

- [The Go router still expects the identity header] → after this change it sees no identity until it calls
  `GET /v1/user/info`. Proposal Dependency, Migration Plan.
- [Clients of `/v1/deployments` that assume three kinds] → they now see `object: "router"`. This is the review's
  explicit choice. The `OpenAPI` subtype documents it.
- [A router's classification calls fail after it answers] → the key is gone (`401`). The docs say the router must
  finish calling Core before it answers.

## Migration Plan

Deploy the Go router change that reads identity from `GET /v1/user/info` first, or together with this Core. Rollback:
Core before this change sends the header again and sends no decide key. A router that copes with both (header if
present, else user info) works across the rollback.

## Open Questions

None.
