# Design

## Context

What the code does today, on `feat/alias-router-target` (PR #2286) merged with `development`:

- `AliasPresentation.java` has `MODEL`, `APPLICATION` and `TOOLSET`; `AliasPresentation.java:of` refuses any other
  value (`Unknown presentAs`).
- `Alias.java:getEffectivePresentation` returns `presentAs`, or the type the first path segment names (`models`,
  `applications`, `toolsets`), else null.
- `AliasTargets.java:validateFields` checks, in order: `aliases/` refused, a resource path of an application or a
  toolset, a config path `parse` accepts, and last, for a config path only, a `routers/` path without `presentAs`
  (the null type). So `AliasTargets.java:fits` never sees a null type. `fits` admits a model, an application or a
  router for `MODEL` and `APPLICATION`, a toolset or an application with `mcp` for `TOOLSET`.
  `AliasTargets.java:validate` returns early for a resource path: the config load never reads a resource target.
- `AliasPasses.java:build` makes the toolset pass for `TOOLSET` and the application pass for every other type, and
  copies the alias's `defaultHeaders` to that application pass. `AliasPasses.java:view` builds the listing view by
  type (`modelView`, `applicationView`, `toolSetView`) and fills it with `copyOperational` and `copyDisplay`.
- `DeploymentService.java:listAliasViews` and `listAliasNames` select aliases by type, config aliases and the stored
  ones the supplier gives. `DeploymentService.java:findAliasView` looks up a config alias, then, for an id of the form
  `aliases/...`, a stored one in storage.
- `RouterController.java:getRouters` and `getRouter` answer from `config.getRouters()` only, on the event loop; the
  route admits an id with an encoded slash. `DeploymentController.java:getRouters` (the router part of
  `/v1/deployments`, filtered by `plan.routerFilters`) runs on the event loop too.
  `DeploymentController.java:listDeploymentNames` runs on the task executor with a memoized stored supplier.
  `DeploymentController.java:toDeploymentData` already turns a `Router` into `RouterData.java:toData`, and an `Alias`
  into its view first, so `/v1/deployments/{id}` needs no change.
- `StoredAliases.java:validatePublicFields` refuses a `routers/` target on a public stored alias.

## Goals / Non-Goals

Goals: a `routers/` path names the type `router`, as the other config paths name theirs; an alias of that type is
called as a model alias and listed and answered as a router.

Non-Goals: changes to deciding, the answer's `model`, the lend, or the model-name use of a router.

## Decisions

1. **`router` is a fourth alias type, the default for `routers/`.** `AliasPresentation.ROUTER("router")`, and
   `Alias.getEffectivePresentation` maps `routers` to it. Rejected: keeping `presentAs` required (a rule only router
   targets had); defaulting `routers/` to `model` (a router-only mapping to a type that is not the target's, which
   would put every router alias in the model listings).

2. **The type `router` fits a router only, checked on the fields for every path.** `fits(ROUTER, target)` admits a
   `Router`. `AliasTargets.validateFields` refuses the type `router` with a path that is not `routers/<name>`, as a
   check of its own after the path-form checks, so it covers resource paths as well as config ones (the branch that
   requires `presentAs` goes: a `routers/` path always has a type now). It refuses a missing config target and a
   resource target, so an alias of type `router` always has a config router target and its view reads no storage
   (decision 5); a stored alias of the public bucket cannot have the type, since `validatePublicFields` refuses its
   `routers/` target. A platform alias may. Rejected: checking in `fits` only, which leaves a resource target to be
   read on a listing that runs on the event loop.

3. **Served as a model alias is.** `AliasPasses.build` already gives every type but `toolset` the application pass,
   with the target's interfaces and the alias's `defaultHeaders`; calls, feature requests, stored responses, consent
   and default headers work for the type `router` as for `model`, with no code change. The spec now names the type
   in those requirements.

4. **Listed as a router.** `AliasPasses.view` gets a `ROUTER` branch building a `Router` view, filled by
   `copyOperational`, `copyDisplay` and the display fallbacks as the other views are; `RouterData.toData` renders it
   (`object: "router"`, no url). A router has no pricing or limits, so the view has none.

5. **Router listings add the aliases of type `router`, from the config only.**
   - `RouterController.getRouters` and `DeploymentController.getRouters` add
     `DeploymentService.listAliasViews(context, ROUTER, List::of)`: no stored alias can have the type (decision 2),
     and the view of a config router target reads nothing, so both stay on the event loop.
     `DeploymentController.getRouters` applies `plan.routerFilters` to these views as to routers.
   - `DeploymentController.listDeploymentNames` adds `listAliasNames(context, ROUTER, List::of)` for `type=router`,
     with no stored aliases for the same reason.
   - `RouterController.getRouter`, for a name that is not a router, looks up `config.getAliases().get(id)` only, never
     a stored url: an alias of another type or none answers `404`, `AliasService.hasConfigAccess` false answers `403`,
     and `AliasService.toView` null (target missing) answers `404`. Rejected: `DeploymentService.findAliasView`,
     which reads storage for an `aliases/...` id, on the event loop.
   Rejected: moving the router endpoints to the task executor, not needed when no storage is read.

Deliberate differences from today's behaviour: an alias without `presentAs` over a router loads (it was refused),
with the type `router`; `presentAs: router` is accepted; `/v1/routers`, `/v1/deployments` and
`/v1/deployment-names` list aliases of type `router`.

## Risks / Trade-offs

- An admin who leaves out `presentAs` on a tier alias gets a router-type alias, listed in `/v1/routers` and not in
  `/openai/models`, where today the config load fails and says why. The docs say to state `presentAs: model` for the
  model listings.
- `RouterData` comes from a view, so an alias of type `router` carries the target router's `features`, `defaults`
  and interfaces, as other alias entries carry their target's.

## Cost per request

No change to a call: same alias pass, no new Core pass, body copy or parse, Redis or blob round trip. A router
listing walks the config aliases once more (an in-memory map). Not measured.

## Migration Plan

None for data. Rolling upgrade: a Core without this change refuses `presentAs: router` and an alias without
`presentAs` over a router, and a config file with either does not load there; add such aliases, in the config file
or as platform aliases (which an older pod's config rebuild would record as invalid), after every pod runs this
version (a Core before `alias-router-target` refuses every `routers/` target anyway). Rollback: remove such
aliases, or give them `presentAs: model` or `application`, before rolling back.
