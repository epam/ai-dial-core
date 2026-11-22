# Proposal

## Why

An alias takes its type from the first segment of its target path when it has no `presentAs`: `models/` gives
`model`, `applications/` gives `application`, `toolsets/` gives `toolset`. Since the change `alias-router-target`
an alias may point to `routers/<name>`, but `routers/` gives no type, so such an alias must state `presentAs` and a
config without it does not load. That is a rule of its own for one target kind. Giving routers the type `router`
makes them follow the same rule as the other three: the type of the target by default.

## What Changes

- `presentAs` takes a fourth value, `router`, and it is the type of an alias whose target path is `routers/<name>`
  and that has no `presentAs`. A config with such an alias, refused today, loads.
- An alias of type `router` must target `routers/<name>`: any other target path is refused, whether or not the
  target exists. So a resource target, and a stored alias of the public bucket, never has this type.
- An alias of type `router` is called as one of type `model` is, and listed where routers are: `GET /v1/routers`,
  `GET /v1/routers/{id}`, `GET /v1/deployments` (with its interface filters), `GET /v1/deployments/{id}` and
  `GET /v1/deployment-names` (`type=router`), as an `object: "router"` entry. It is not in `/openai/models`,
  `/openai/deployments`, `/openai/applications`, `/openai/toolsets` or `/anthropic/v1/models`.
- `presentAs: model` and `presentAs: application` over a router stay as they are; they are how a router is
  published under a model name.
- Docs: `docs/dynamic-settings/aliases.md` and `routers.md`.

Out of scope:

- Any change to how a router decides, to the answer's `model` (an alias over a router already names the alias), to
  the lend, or to `defaultHeaders`.
- Stored aliases of the public bucket with a router target, refused since `alias-router-target`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-aliases`: the type `router` (the default for a `routers/` path, fitting a router only), its endpoints
  and listings; the requirement "Router target needs a type" is removed.
- `router-deployment`: `GET /v1/routers` lists the aliases of type `router` with the routers.

## Impact

No GitHub issue yet. Motivation: the review of `alias-router-target` (PR #2286), where a router target needing
`presentAs` was a rule only routers have. This change covers that point; it leaves the model-name use of a router
(`presentAs: model`) as it is.

Depends on `alias-router-target` (PR #2286): it builds on the router target and replaces that change's requirement
"Router target needs a type". It ships as its own PR after #2286, or stacked on it.

Touches `AliasPresentation`, `Alias`, `AliasTargets`, `AliasPasses` (the listing view), `RouterController`,
`DeploymentController` (router entries and names), and their tests. No new endpoint, setting or dependency.
