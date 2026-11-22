## Why

Access, limits and routing in Core are keyed by the name a caller uses, so an admin cannot give users a model under
another name, or give them a model they have no role for, without copying the model entry. An alias is a deployment
of its own (name, catalog entry, `userRoles`) that forwards calls to a target deployment and grants its callers
the right to call that target. Aliases are managed by admins only.

## What Changes

- New top-level `aliases` section in the dynamic config, keyed by the alias's name. An entry has `target`
  (`{"path": "models/gpt-4-internal"}`; the path names a config deployment or an application or toolset resource
  such as `applications/public/assistant`), optional `presentAs` (`model`, `application` or `toolset`, by default
  the target's type), display fields and `userRoles`.
- Alias names share the deployment-name namespace with models, applications, toolsets and interceptors.
- Config load refuses an alias with no `target.path`, an alias target (`aliases/...`), or a type that does not fit
  a config target. A config target that does not exist is a warning; calling that alias answers `503`.
  `/v1/admin/health/config` reports aliases whose target, in the config or in storage, is missing or no longer fits.
- A caller with a role in the alias's `userRoles` can call it, by its name, with every endpoint of its type, also
  when it has no rights on the target. An alias without `userRoles` takes the target's: a config target's
  `userRoles`, or read access to a resource target. Core calls the target in a second pass over its own HTTP address,
  as an application calls a model: the target pass has its own span, analytics row and limit check, and
  `execution_path` is `[alias, target]`.
- `accessibleByPerRequestKey: false` on the target does not refuse the target pass of an alias call.
- Listings show the alias by its name under its type, with its own display fields (the display name, description,
  icon, intro and keywords fall back to the target's; the display version does not) and the target's pricing, features, limits and interfaces. They never show
  the target's path or endpoint.
- A role limit keyed by an alias's name logs a warning at load: it has no effect.
- The alias pass does not report the usage in the relayed answer as its own, so it is counted once.
- `/v1/ops/toolset/signin` and `signout` accept a toolset alias and store the credentials under its toolset target;
  the alias's toolset entry carries the target's auth settings without secrets.
- `/v1/admin/config/file/aliases` lists and reads the aliases of the config file.

## Capabilities

### New Capabilities
- `deployment-aliases`: alias deployments: definition, validation, calling the target, listing.

### Modified Capabilities
- `app-to-model-accounting`: the requirement "Deployment closed to per-request keys" gains the alias exception.

## Impact

- Design document: `deployment_alias.md` (repository root, updated 2026-11-04). This change covers "The alias target
  is a reference" (path only), config aliases from "Where aliases live" and "Short-name
  resolution and uniqueness", "Display properties", "Alias names and versions", "How access is granted", "Showing an
  alias as another type", "Calling the target", "Limits", "Aliases in the config" and "Calling and listing" of the
  API section, and the monitoring and limit-warning points of "Changes for operational processes".
- Follow-up change: `deployment-alias-public` (admin-managed aliases stored in `aliases/public/<name>`) depends on
  this change.
- Out of scope: aliases stored as resources (the follow-up); private, shared or published aliases, which the design
  document leaves out; the admin config resource API for aliases and its validation on `/v1/admin/apply`;
  `/v1/toolset/{id}/tools` through an alias (feature endpoints, Responses API item operations and consent were added
  after review); rewriting `model` in answers; an alias to a resource in the listings answered from memory
  (`/openai/models`, `/openai/deployments`, `/anthropic/v1/models`, the model part of `/v1/deployments`).
- Code: `config` (new `Alias`, `AliasTarget`, `AliasPresentation`, `Config.aliases`), `storage`
  (`ResourceTypes.ALIAS`), `server` (config post-processing and merge, `DeploymentService`, `AliasService`, the
  calling and listing controllers, `ApiKeyData`, the health and file-config controllers).
