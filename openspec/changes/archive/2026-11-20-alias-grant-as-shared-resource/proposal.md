# Proposal

## Why

An alias lets its callers call its target without rights on the target. Core records that right in the per-request
key the alias pass gives the target pass, in a field of its own, `lentDeployment`: one deployment name. Next to it the
key already has a general record of what its holder may use, `perRequestSharedResources`: resource urls with the
rights on each. The same key so has two records of granted access, read by different code, and the lending is not a
resource right that the listing, the access checks and the resource-dependencies machinery know.

A config deployment has a resource url of its own: the platform bucket of its type, `models/platform/gpt-4` for the
model `gpt-4`, as the config store already names it. With that url the alias's right fits the general record: the
target pass holds `READ` on its target's url, as any other grant. The alias's `target` then reads as a resource
dependency with one entry, and the grant is baked the way a resource dependency's is.

The rate limits of a call through an alias come from all the caller's roles today, so a role outside the alias's
`userRoles` can widen or narrow what the alias's users get. They come from the roles the alias admits instead.

## What Changes

- The alias pass grants its target pass `READ` on the target's url in `perRequestSharedResources`: a resource target's
  url, or `<type>/platform/<name>` for a config target (`models`, `applications`, `toolsets`, `routers`). The alias's
  `target` is turned into a resource-dependency list of one entry, and its grant baked as a resource dependency's is.
  The key no longer has `lentDeployment`; for a resource target it no longer has the `attachedDeployments` entry.
  Next to the grant the alias pass records the alias's name, `aliasName`, which grants nothing: the target pass finds
  the alias by it for the limits, and a router's answer reads it.
- `READ` on a deployment's url in `perRequestSharedResources` lets the key's holder call the deployment: Core then
  checks neither the deployment's `userRoles` nor `features.accessibleByPerRequestKey`. **Behaviour change** for keys
  that hold such a grant from elsewhere; today none can (see the next point).
- The per-request permission API refuses a url in the platform bucket with `403`: only Core grants rights on a config
  deployment. A global reader holds `READ` on every resource, so an application it calls could otherwise pass the right
  to call any model on to another application.
- The grant goes on, as the lending does today, to the interceptors in front of the target and to calls back for the
  target itself, and stops at a router's decision. What goes on is `READ` on the url of the deployment the pass acts
  for, with the key's `aliasName`.
- The rate limits of the target pass of an alias call come from the caller's roles that are in the alias's
  `userRoles`. An alias without `userRoles` takes the target's limits as a direct call of the target does: the caller's
  roles in the target's `userRoles`, or all of them when the target has none. **Behaviour change**: today the target
  pass uses all the caller's roles.
- Unchanged: who may call an alias, the two passes and the execution path, limits charged under the target's name, a
  router's choice checked against the caller's own rights, consent.

Out of scope, each a later change if wanted:

- `execute` as a right of its own; `READ` stands for it.
- A resource-dependency list on the alias in the API (`GET` of an alias), and declaring a delegated dependency in an
  application.
- Config deployments in `resourceDependencies` of an application.
- The Auto Router's decide-time grant.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-aliases`: ADDED "Limits of a call through an alias", "Grant of the target in the per-request key" and
  "Alias name in the per-request key".
- `resource-access-control`: MODIFIED "Grant per-request permissions" (a platform url is refused).

## Impact

- Code: `server` module. The alias pass's key (`ApiKeyData`), the grant check of a deployment (`DeploymentService`,
  the four controllers that check `accessibleByPerRequestKey`, `RouterDecisionFlow`), the bake of a resource
  dependency (`ResolveResourceDependenciesFn`), the per-request permission API (`PerRequestPermissionService`) and the
  limits' roles (`RateLimiter`). No config or settings change.
- Records: the per-request key loses `lentDeployment` and gains a `perRequestSharedResources` entry and `aliasName`. A key written by
  0.50 still decodes: Core ignores the unknown field.
- Motivation: issue #2285 (one record of granted access), and "Delegated link v1 — the alias cut" (10 Nov 2026), whose
  idea of the alias target as a one-entry resource dependency this change takes. It does not take that document's
  separate lend set and `execute` right: `READ` in `perRequestSharedResources` stands for both.
- Dependencies: none open.
