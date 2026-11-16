# Review: deployment-alias-public

Reworked on 2026-11-05 after the user's decision that a stored alias is called by its url `aliases/public/<name>`,
not by a short name, and is read from storage on demand like a public application. The earlier version merged stored
aliases into the in-memory config; that code was removed.

## Verification
- Regression set `alias.*`, `config.*`, `controller.*`, `DeploymentService*`, `EntityBucketBindingTest`,
  `AliasApiTest`, `AliasReloadApiTest`, `AliasPublicApiTest`, `AdminHealthConfigTest`, `FileConfigApiTest`,
  `AdminApplyApiTest`, `AdminValidateApiTest`, `ConfigEntityWriteApiTest`: 755 tests, all pass locally (Windows,
  JDK 21). Every spec scenario names its test in tasks.md. Checkstyle clean.
- `PublicationApiTest`, `ShareApiTest` and `ResourceOperationApiTest` fail on Windows in about the same numbers on the
  base commit (test-folder `FileSystemException`, state left between tests); they need the Linux CI run.
- Not measured: latency of the model listings, which now list `aliases/public/` on the task executor (task deferred).

## Independent reviews (findings and outcome)
- First version: stored aliases could not be restricted, because public rules never reach the root of the public
  bucket. Decided with the user: access by `userRoles`. The merge-specific findings went away with the merge.
- Second version, no security defect and no blocking I/O found:
  - Medium: a storage failure made the whole model listing answer 500. Fixed: stored aliases are left out and a
    warning is logged; test added.
  - Low/medium: names with `[`, `]` or `%` were listed under an encoded id that could not be called. Fixed: stored
    alias names are letters, digits, `.`, `_` and `-`; test added.
  - Low, fixed: a null config snapshot in the PUT check; GET showed a blob that calls ignore as valid; stale OpenAPI
    descriptions; `/v1/deployments` and `/v1/deployment-names` listed `aliases/public/` once per type (now once).
  - Low: an alias could target an application in a user bucket, whose owner could change it later. Decided with the
    user: a stored alias targets a config deployment or a public resource only (`400` otherwise); config-file aliases
    are unchanged.

## Deliberate differences from the design document (design.md)
- Stored aliases are called by url, not by short name; they share no namespace with config names.
- Access by `userRoles`, not by public rules.
- The metadata listing is admin-only; copy and move are refused outright; no alias folders; field errors answer 400.

## Rebased on the review of PR #2235 (2026-11-06)

- A stored alias without `userRoles` takes the target's, as a config alias does (`AliasService.hasConfigAccess`);
  before, it served every caller. `findStoredAlias`, `listUsableAliases` and `findAliasView` use it.
- `/v1/admin/health/config` checks config, resource and stored aliases on the task executor; a resource target is
  read (`AliasService.checkTarget`), so a stored alias to a missing public resource is reported too.
- Toolset sign-in through a stored toolset alias works as for a config alias; `ToolSetController` finds the alias of
  a listing entry with `DeploymentService.findListedAlias` to set its auth statuses.
- `target.id` and `target.version` are no longer mentioned; `target` has `path` only, so a body with other target
  fields is refused as for any unknown field.

