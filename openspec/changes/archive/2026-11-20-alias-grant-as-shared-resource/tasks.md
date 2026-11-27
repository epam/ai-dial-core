# Tasks

## 1. Tests first

- [x] 1.1 `AliasApiTest.targetPassCarriesTheCallChain`: assert the scenario "Grant in the key" (the model's key has
  `READ` on `models/platform/gpt-4-internal` in `perRequestSharedResources`); `RouterDecisionApiTest` asserts the
  model a granted router chose holds no grant and no `aliasName`; `targetPassCarriesTheCallChain` asserts the
  scenario "Alias name in the key"; `AliasPublicApiTest.limitsOfTheTargetFollowTheRolesOfTheStoredAlias` the scenario
  "Limits of a stored alias".
- [x] 1.2 `AliasApiTest.limitOfARoleTheAliasDoesNotAdmitDoesNotApply` for "Limit of a role the alias does not admit"
  (fixture key `mixed-key` with `default-user` and `limited-user`); `limitIsCheckedUnderTheTargetName` keeps "Limit
  under the target's name".
- [x] 1.3 `PerRequestPermissionsApiTest` for "Grant on a config deployment".
- [x] 1.4 `ApiKeyDataTest`: the grant of a resource and of a config target, the grant kept when a plain share of the
  same url is shared on (D4), the grant and `aliasName` carried to an interceptor (by the encoded and the decoded
  initial deployment) and to a callback, not to an unrelated deployment nor to another deployment's pass (D5), a key with
  unknown fields decodes. `AccessServiceTest`: the target pass reads a resource target through the grant and not its
  sibling. `DeploymentServiceGrantTest` (was `DeploymentServiceLentTest`): `isGranted` by the exact url, only with a per-request key.
- [x] 1.5 Run the classes of 1.1 to 1.4.
- [ ] 1.6 Run `./gradlew checkstyleMain checkstyleTest` (deferred: the reviewer runs it before the push).

## 2. Grant in `perRequestSharedResources`

- [x] 2.1 `DeploymentGrants.urlOf` (D1); `AliasPass.getTargetDependencies` built in `AliasService.toPass` (D3).
- [x] 2.2 `ApiKeyData`: drop `lentDeployment`, ignore unknown fields, `grant`, bake after the sharing (D4), carry
  (D5); `ResolveResourceDependenciesFn.bakeGrant` uses `ApiKeyData.grant`.
- [x] 2.3 `DeploymentService.isGranted` (D2) in place of `isLent` at every call site.
- [x] 2.4 `PerRequestPermissionService.validatePermissions` refuses a platform url (D6).
- [x] 2.5 `aliasName` in the key, found by the target pass for the limits, read by the router (D7);
  `DeploymentServiceTest` covers the lookup (config alias, stored alias, no grant, alias gone).
- [x] 2.6 Run the classes of 1.5 and `AliasPublicApiTest`, `AliasInProcessApiTest`, `AliasServiceTest`,
  `ResolveResourceDependenciesFnTest`.

## 3. Docs

- [x] 3.1 `docs/dynamic-settings/aliases.md`: the grant in the key and the limits' roles.
