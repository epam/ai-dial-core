# Review

## What shipped

- The alias pass grants its target pass `READ` on the target's url in `perRequestSharedResources`: the resource url of
  a resource target, `<models|applications|toolsets|routers>/platform/<name>` for a config target. The target becomes
  a one-entry resource-dependency list on the pass (`AliasPass.getTargetDependencies`), baked with `ApiKeyData.grant`,
  the merge `ResolveResourceDependenciesFn` now also uses. The key has no `lentDeployment` and ignores unknown fields.
- `READ` on a deployment's exact url lets the key's holder call it (`DeploymentService.isGranted`, which replaces
  `isLent` in the deployment lookup, the four `accessibleByPerRequestKey` checks, `RouterDecisionFlow` and
  `RateLimiter`). The grant goes on to interceptors and callbacks for the same deployment, and stops at a router's
  decision.
- The per-request permission API refuses a url in the platform bucket with `403`.
- The alias pass records `aliasName` next to the grant, carried with it. The target pass finds the alias by that name
  (`DeploymentService.findCallableDeployment`: the config, or storage for `aliases/public/`), and its limits come from
  the caller's roles in the alias's `userRoles`; a router behind the alias answers under `aliasName`. The grant goes on by the deployment the pass acts for: the initial deployment for an
  interceptor's hop, its own deployment otherwise.
- Docs: `docs/dynamic-settings/aliases.md` describes the grant in the key and the limits' roles.

## Verification

- Targeted classes, JDK 25: `ApiKeyDataTest`, `AccessServiceTest`, `DeploymentServiceGrantTest`, `AliasApiTest`,
  `AliasServiceTest`, `AliasPassesTest`, `AliasPublicApiTest`, `AliasInProcessApiTest`, `RouterDecisionApiTest`,
  `PerRequestPermissionsApiTest`, `ResolveResourceDependenciesFnTest`, `RateLimiterTest`, `ResponsesControllerTest`,
  `ResponseItemControllerTest`, and the classes matching `*ResourceDependenc*`, `*Interceptor*`, `*EmbeddedCall*`,
  `*RateLimit*`, `*PerRequest*`, `*ApiKeyStore*`, `*McpProxy*`, `*RouterDecision*`, `*ConsentApiTest`: all pass.
- `AliasApiTest.limitOfARoleTheAliasDoesNotAdmitDoesNotApply` and
  `PerRequestPermissionsApiTest.testGrant_ConfigDeploymentRefused` fail with the `RateLimiter` and
  `PerRequestPermissionService` changes undone (`429`, and `200` instead of `403`).
- `ConsentServiceTest` has five failures on this machine, the same on `development`: the expected JSON has `\n` line
  ends and the pretty printer writes `\r\n` on Windows.
- `npx -y @fission-ai/openspec@1.14.3 validate alias-grant-as-shared-resource --strict`: valid.

## Drift from design.md

None.

## Not done

- Checkstyle (task 1.6), left to the reviewer.
- Out of scope as the proposal lists: `execute` as a right of its own, the dependency list in the alias API, config
  deployments in an application's `resourceDependencies`, the Auto Router's decide-time grant.
