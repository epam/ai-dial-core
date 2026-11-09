## Why

`deployment-alias-config` lets an admin define aliases in the config file only. The design document also asks for
aliases an admin creates at run time, stored as resources under `aliases/public/<name>`, so that a new alias or a new
target does not need a config change.

## What Changes

- New management API, admin only for every method (`GET` included): `PUT /v1/aliases/public/<name>` (create or
  update, `If-Match`/`If-None-Match`), `GET /v1/aliases/public/<name>` (the definition with its target),
  `DELETE /v1/aliases/public/<name>`, `GET /v1/metadata/aliases/public/` (listing). Another bucket answers `404`; a
  nested path or a name that is not an entity name answers `400`.
- A stored alias keeps only `presentAs`, `target`, `userRoles` and the display fields (`displayName`,
  `displayVersion`, `iconUrl`, `description`, `intro`, `descriptionKeywords`). Any other field, or a body `name` other
  than the path name, answers `400`. `PUT` checks what the config load checks (target shape, reserved `id`/`version`,
  no alias target, type against a config target) and also the type against a resource target that exists; a target
  that does not exist is accepted.
- A stored alias is called by its url `aliases/public/<name>`, as public applications and toolsets are
  (`/openai/deployments/aliases/public/x/chat/completions`, `"model": "aliases/public/x"`,
  `/v1/toolset/aliases/public/x/mcp`). The url is its deployment name: execution path, analytics and listing ids use it.
  The short name never reaches it, so stored aliases share no name space with config deployments. It is read from
  storage on each call (through the resource cache), not merged into the config.
- Who may call a stored alias is decided by its `userRoles`, as for a config alias (none means every caller).
- Listings list and read the stored aliases on the task executor: `/openai/models`, `/openai/deployments`,
  `/anthropic/v1/models`, `/openai/applications`, `/openai/toolsets`, `/v1/deployments`, `/v1/deployment-names`, and the
  single-item endpoints by url. Aliases to resource targets are listed too.
- `/v1/admin/health/config` reports stored aliases whose config target is missing or no longer fits, as
  `aliases/public/<name>`.
- Sharing, publishing, copying and moving an alias answer `400`.
- `/v1/admin/validate` and `/v1/admin/apply` refuse a model named like a config-file alias (their scratch config had
  no aliases).

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `deployment-aliases`: new requirements for stored aliases; "Aliases in listings" modified; "Listings answered from
  memory" removed.

## Impact

- Design document: `deployment_alias.md` (repository root, updated 2026-11-04). This change covers "Where aliases
  live" (public storage), "Who may do what" (public column, with the access difference in design.md), "Aliases in
  public storage" of the API section, the public parts of "Calling and listing" and "Publishing", and the admin-only and
  no-private-alias points of "Changes for operational processes". It does not follow requirement 13 and "Short-name
  resolution and uniqueness" for stored aliases: they are called by url (design.md).
- Depends on `deployment-alias-config`, archived in b028e3bf7.
- Out of scope: aliases in `/v1/admin/validate`, `/v1/admin/apply` and the file migration (no `Alias` manifest kind);
  alias folders under `aliases/public/`; private, shared or published aliases; config aliases through the admin config
  resource API; health of stored aliases to resource targets (one more read per alias).
- Code: `server` (routes, `ConfigResourceController`, `ConfigResourceMetadataController`, `AdminRoleAuthorizationService`,
  `EntityBucketBinding`, `ConfigManifestSupport`, `DeploymentService`, `AliasService`, `StoredAliases`, the model
  listing controllers and `AliasListings`, `ResourceOperationController`, `AdminHealthConfigController`), OpenAPI
  annotations (CI generates `docs/open_api_core.yaml`).
