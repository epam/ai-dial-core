# Tasks

## 1. An alias without userRoles is open to anyone

- [x] 1.1 Tests first, seen failing. They replace the four tests of the old rule:
      `AliasApiTest.aliasWithoutUserRolesTakesTheTargetRoles` becomes `aliasWithoutUserRolesIsOpenToAnyone` (scenario
      "Alias without userRoles"; in `dial-config/aliases.json` the alias `inherited-alias` is renamed `open-alias`, and
      `open-resource-alias`, without `userRoles`, targets `res-app` in the bucket of `proxyKey1`);
      `AliasPublicApiTest.storedAliasWithoutUserRolesTakesTheTargetRoles` becomes
      `storedAliasWithoutUserRolesIsOpenToAnyone` (scenario "No userRoles");
      `AliasPlatformApiTest.platformAliasWithoutUserRolesTakesTheTargetRoles` becomes
      `platformAliasWithoutUserRolesIsOpenToAnyone`; `AliasServiceTest.aliasWithoutUserRolesTakesTheConfigTargetRoles`
      becomes `aliasWithoutUserRolesIsOpenOverConfigTarget`
- [x] 1.2 `AliasService`: `hasConfigAccess` and `hasAccess` admit every caller for a config target, `toView` no longer
      copies the target's `userRoles` and decides a resource target by the `hasAccess` a call uses; the Javadoc there
      and in `DeploymentService`, `StoredAliases`, `ResourceCredentialsController` and `RoleBasedEntity` (`[]` admits
      no role) says the same. 1.1 passes
- [x] 1.3 `docs/dynamic-settings/aliases.md`: the `userRoles` bullet (open to anyone, `[]` nobody, the resource rule),
      Listings, and the Access of stored aliases; the earlier behaviour and the step before upgrade are in 1.4
- [x] 1.4 Release note: the BREAKING line and the step before upgrade, in the PR description
- [x] 1.5 `./gradlew checkstyleMain checkstyleTest`, then `./gradlew :server:test` with `--tests` for `AliasApiTest`,
      `AliasPublicApiTest`, `AliasPlatformApiTest`, `AliasServiceTest`, `AliasInProcessApiTest`,
      `AliasReloadApiTest`, `RouterDecisionApiTest`, `DeploymentServiceTest`, `AliasPassesTest` and
      `StoredAliasesTest`; `npx -y @fission-ai/openspec@1.14.3 validate alias-open-without-user-roles --strict`

## Workflow follow-up

- `review.md`, then `npx -y @fission-ai/openspec@1.14.3 archive alias-open-without-user-roles -y` and
  `validate --all --strict`; one commit, staging explicit paths.
