# Review: deployment-alias-config

Reworked on 2026-11-04 for the updated `deployment_alias.md`: target `{path}` with reserved `id`/`version`, optional
`presentAs`, display fallbacks, admin-managed grant (no lending check), resource targets in config aliases, health
report, `/v1/admin/config/file/aliases`. Rebased on `development` 6dae044af.

## Verification
- `AliasApiTest` (19), `AliasReloadApiTest` (1), `ConfigPostProcessorAliasTest` (11), `AliasServiceTest` (8),
  `AliasPassesTest` (9), `AliasTargetsTest` (5), `DeploymentServiceLentTest` (1) pass locally (Windows, JDK 21). Every
  spec scenario names its test in tasks.md.
- Regression set `controller.*`, `config.*`, `alias.*`, `DeploymentServiceTest`, `AdminHealthConfigTest`,
  `FileConfigApiTest`, `AliasApiTest`, `AliasReloadApiTest`: 586 tests, all pass. Checkstyle clean.
- Not caused by this change, and failing the same way on a clean `development` worktree: `ConsentServiceTest` (4,
  pretty-printed JSON line endings on Windows) and `ComplexResource*Test` (Windows path escaping in setup).

## Independent review (findings and outcome)
- No privilege escalation (the grant matches the exact target name, handed-on keys of the target get 403), no
  blocking I/O on the event loop, no endpoint or credentials in listings, no leftovers of the earlier link/bucket
  design.
- Medium: a schema-based application target was not completed from its schema, so a toolset alias of a schema-based
  MCP application was refused at load or answered 503. Fixed: `AliasService.resolveSchema`; the load-time type check
  skips schema-based applications; unit test added.
- Medium: the MCP endpoints limit-checked the alias pass, so a limit under the alias name applied there. Fixed:
  `McpProxyController.limit`; scenario "No limit under the alias's name".
- Medium: the spec said every request is forwarded. Fixed: the requirement names the endpoints served; the rest is
  out of scope in proposal.md and listed in the docs.
- Low: an API write that makes a config target unfit was not reported. Fixed: `alias_target_incompatible` in the
  health report, also left out of `/v1/deployment-names`. `/v1/deployment-names` does not read resource targets;
  the spec says so.
- Low, fixed: an unreachable `aliases` branch in `AliasTargets.parse`, stale javadoc; a unit test for
  `DeploymentService.isLent`.
- Low, accepted: `/v1/deployments/<alias>/limits` reports under the alias name (limit endpoints are out of scope);
  `ResourceTypes.of("aliases")` now resolves, but nothing in this change accepts an `aliases/...` url from a client;
  the rejections of share, publication and copy are pinned by tests in `deployment-alias-public`.
- Low, accepted and documented: errors of the target pass are relayed and may name the target; the caller's
  `Authorization` is not forwarded through an alias.

## Drift recorded in design.md
- Loopback HTTP second pass instead of an in-process one.
- Limits of a granted call use all the caller's roles (`RateLimiter.getLimitByUser`).
- The alias pass relays the target's status and uses a fresh balancer per request.
- Aliases to resources are left out of the listings answered from memory.

## Review of PR #2235 (2026-11-06)

- `target.id` and `target.version` removed: not reserved until there is a use for them.
- An alias without `userRoles` takes the target's (`AliasService.hasConfigAccess`, `hasAccess`): the `userRoles` of
  a config target, or read access to a resource target, checked when the target is read; open to all was unsafe.
- `iconUrl`, `intro` and `descriptionKeywords` fall back to the target's, as the display name and description do;
  `displayVersion`, `author`, the dates and the catalog fields describe the alias's own entry and do not.
- The alias pass no longer parses the usage of the relayed answer as its own
  (`BaseDeploymentPostController`), which counted the target's usage twice in the token stats.
- Toolset sign-in through an alias: `ResourceCredentialsController` resolves the target with
  `AliasService.toSecuredTarget` and stores the credentials under it; the toolset view carries the target's auth
  settings without secrets, and `ToolSetController` sets the caller's statuses on it.
- `/v1/admin/health/config` reads resource targets on the task executor (`AliasService.checkTarget`); the cheap
  check stays for config load and listings.
- Kept: one analytics row per pass. The alias's row is what shows which alias a call came through.
- Stored responses: `ResponseItemController` resolves the mapping's deployment with `findCallableDeployment`, so
  `GET`, `DELETE`, `cancel` and background polling of a response stored through an alias go through the alias pass
  to the response its target pass stored (503 before).
- Feature endpoints: `DeploymentFeatureController` uses `findCallableDeployment`, and the pass's `rate`, `tokenize`,
  `truncate_prompt` and `configuration` endpoints point to Core's own for the target (403 for tokenize/truncate, a
  dropped rating before).
- Consent: `ConsentService.buildConsent` adds the alias's target, resolved through the alias, with the target's
  requirement for both entries, so the accepted consent passes both passes' checks (the alias could not be called).
- Checks: `AliasApiTest` (stored response, tokenize and rate, consent), `ResponseItemControllerTest`,
  `ResponsesApiTest`, `FeaturesApiTest`, `ConsentApiTest` pass. `ConsentServiceTest` has 4 failures on Windows that
  the base commit has too (CRLF in the expected JSON).
