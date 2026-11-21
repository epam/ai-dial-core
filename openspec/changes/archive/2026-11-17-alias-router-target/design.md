# Design

## Context

What the code on `development` does today:

- `AliasTargets.java:parse` accepts `models/`, `applications/` and `toolsets/` only; `AliasTargets.java:resolve`
  looks the name up in those three config maps; `AliasTargets.java:fits` accepts a `Model` or an `Application` for
  the `model` and `application` types.
- `Alias.java:getEffectivePresentation` derives the type from the first path segment and knows the same three.
- `AliasService.java` builds the alias pass with `AliasPasses.java:build`: an `AliasApplicationPass` whose interfaces
  point at Core's own address (`localBaseUrl`), one per interface the target declares
  (`DeploymentEndpointUtil.java:isInterfaceDeclared`). The pass copies none of the alias's `defaultHeaders`.
- `ApplyDefaultDeploymentSettingsFn.java:applyDefaultHeaders` sets a deployment's default headers on the inbound
  request, only under names the request does not carry. For an alias call it runs with the pass, which has none.
- `ApiKeyData.java` sets `lentDeployment` to the pass's target name; `DeploymentService.java:isLent` then admits the
  target pass.
- `RouterDecisionClient.java:routerHeaders` copies the client's headers to the decide request, so a header set on
  the alias pass's outgoing request reaches the router as a client header.
- `ApiKeyData.java:initFromContext` appends the router's name to the execution path of a decided pass's key.
  `RouterModelFn.java` rewrites the answer's `model` to `RouterDecision.getRouterName()` at the answer call sites
  (chat, Responses, Messages and the interceptor controllers); `ReplaceResponseIdFn.java` stores the same name in a
  stored response's mapping, and `ResponseItemController.java` renames the read-back with it (requirement "Router
  name in the answer").
- `RouterDecisionFlow.java:askRouter` refuses a candidate that is in `config.getRouters()` with `502`.
- `StoredAliases.java:validateFields` checks an alias written through `/v1/aliases/{public|platform}/<name>` with
  `AliasTargets.validateFields`, so what that accepts, both accept. A platform alias joins the config
  (`MergedConfigStore` runs the same check); a public one is read on each call (`AliasService.java:parseStored`).

## Goals / Non-Goals

Goals: an alias may point to a router; the answer names the alias; the alias's default headers reach the target.

Non-goals: one-pass alias serving; granting the router's chosen deployments; stored aliases to a router.

## Decisions

1. **`routers/<name>` is a fourth config target, resolved like the other three.** `parse` returns
   `ResourceTypes.ROUTER`, `resolve` reads `config.getRouters()`, `fits` admits a `Router` for `model` and
   `application`. Rejected: publishing the router itself in the model listings. It would change
   "Routers absent from other listings" for every router and still give one name per router.
   A public alias with a `routers/` target is refused by `StoredAliases.validatePublicFields`, which the public
   `PUT`, the read for calls and listings and the `GET` (`status: invalid`) use, so it stays out of scope. A platform
   alias may point to a router, as it joins the config like an alias of the config file. Drift from the proposal
   draft, which put the refusal in `StoredAliases.validateFields`: that method gates platform aliases too, so it
   would have refused router aliases managed through the admin API. A stored alias takes no `defaultHeaders`
   (`StoredAliases` keeps a fixed field list), so tier aliases that differ by a header are config-file aliases.

2. **No default type for a router target.** `getEffectivePresentation` is not changed: the type is `presentAs`
   or the type the path names, and `routers` names none of the three, so the alias must state `presentAs`.
   The refusal is in `AliasTargets.validateFields`, which runs before any `fits` call and whether or not the router
   exists; `fits` never sees a null type. Rejected: mapping `routers` to `MODEL`, a special rule the feature does
   not need.

3. **The alias pass is built as for any model or application target.** `AliasPasses.build` already works from the
   interfaces the target declares, and a router declares its interfaces as a model does (requirement "Router
   interfaces validated as a model's"). The listing view goes through `AliasPasses.java:modelView`, which gives a
   chat model without pricing or limits for a target that is not a `Model`.

4. **The answer names the alias, and the target pass does the rename.** The target pass already rewrites `model`
   for a decided request. `RouterDecision` gets an answer name, set by `RouterDecisionFlow` when it decides: when the
   pass was lent by an alias (`DeploymentService.isLent` for the router), the last name of the incoming execution
   path that is neither the router nor one of its interceptors, which is the alias pass's own name whoever called
   the alias; else the router's name. The
   `RouterModelFn` call sites and the mappings `ReplaceResponseIdFn` and `ResponsesController` store use the answer
   name, so a stored response
   read back names the alias too. `getRouterName` keeps naming the router for the execution path, the analytics
   rows and the re-entry check. Rejected: rewriting in the alias pass, which today relays the body untouched and
   would have to parse every chunk a second time; taking the first name of the path, which is the calling
   application when an application calls the alias.

5. **Alias default headers work as a deployment's.** `AliasPasses.build` copies the alias's `defaultHeaders` to
   the pass, and `ApplyDefaultDeploymentSettingsFn.java:applyDefaultHeaders` applies them as it does for any
   deployment: only under names the request does not carry. Rejected for now: replacing the client's value, a
   new rule for one deployment kind; it is added when a header must not be changeable by the caller.

6. **The headers apply to the top-level `defaultHeaders` only.** An alias has no `interfaces` of its own, so
   `Deployment.java:resolveDefaultHeaders` gives the top-level map for every interface type.

7. **Header names follow the existing rules.** Core checks no name in a deployment's `defaultHeaders`, and none is
   added for an alias. The headers land on the alias pass's inbound request, so they reach the target pass through
   the client-header copy and its exclusions (`ProxyUtil.copyHeaders`), and the decide request still drops what only
   Core may set (`X-DIAL-ROUTER-ATTEMPTS`, `X-DIAL-ROUTER-NOTICE`).

8. **An alias over a router is not a routing candidate.** `askRouter` refuses a candidate that is an alias whose
   target is `routers/<name>` with `502`, as it refuses a router. Without it a router could reach another router's
   decision through an alias, which "Candidate is a router" forbids.

9. **The lend of a router stops at the decision.** The lend goes on to the interceptors in front of the target and
   the target's callbacks (`ApiKeyData.initFromContext`, through `initialDeployment`). Behind a global interceptor
   the decided pass has the router as `initialDeployment`, so the key of the chosen deployment would carry the lend
   of the router too. `initFromContext` does not pass the lend on from a decided pass: the chosen deployment is
   checked against the caller's own rights. Before this change no lent pass was a router pass, so nothing else
   changes. Found in review.

Deliberate differences from today's behaviour: an alias's `defaultHeaders`, ignored until now, are sent; a decided
answer reached through an alias names the alias.

## Risks / Trade-offs

- An existing config with `defaultHeaders` on an alias changes behaviour on upgrade. Named in the release note.
- A client may send its own value of a header the alias defaults, as with any deployment. With a tier header a
  client of alias `weak` can then ask for the tier of alias `hi-end`; what it may reach is still limited by its
  access to the chosen deployments.
- A header set by the alias travels on to the deployment that serves the call, as any client header does. A tier
  header is harmless there; an admin must not put a secret in an alias's `defaultHeaders`.
- A router with `userRoles: []` makes an alias without `userRoles` unreachable, because the alias takes the
  target's roles (requirement "Who may use an alias"). Documented in `aliases.md`; unchanged by this change.

## Cost per request

No new Core pass, body copy, body parse, Redis or blob round trip. An alias over a router costs what an alias over
a model costs (one loopback pass) plus what the router costs (one decide request). The alias pass sets a few
headers. Not measured in this change; the loopback pass is measured in the follow-up that removes it.

## Migration Plan

None for data. Rolling upgrade: a pod on the old version fails to load a config with a `routers/` alias target, so
add such aliases only after every pod runs the new version.
