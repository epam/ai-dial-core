# Tasks

## 1. Author of an application or toolset: write, read, listings

- [x] 1.1 Write the tests first. `CustomApplicationApiTest`: created without author (the existing `author` assertions at
  creation stay), created with another author with the audit author in `GET /v1/metadata/applications/<bucket>/`, created
  with `""`, update without `author` keeps it, update with `author` replaces it. `ToolSetApiTest`: created with another
  author (metadata item `author` is the caller's), update without author keeps it, `owner` in `GET /openai/toolsets` and
  `GET /v1/deployments`. `owner` `""` in `GET /openai/applications/{id}`. For the entity saved
  before the change, seed a body without `author` and the audit author `legacy-user` with `ResourceService.putResource`
  and assert `GET` and the `owner` of the listing (application and toolset), and a toolset with no author at all. Verify:
  the new tests fail on `development`.
- [x] 1.2 `ApplicationService.getApplication` and `extractFrom` stop replacing `author`: the body value, the audit author
  only when the body value is `null`. Verify: the application tests from 1.1 on reads pass.
- [x] 1.3 `ToolSetService.getToolSet(ResourceDescriptor, EtagHeader)`, `getToolSetWithDecryptedAuthSettings` and
  `extractFrom` the same way. `ToolSetRepairService.repair` stops replacing `author` and passes `meta.getAuthor()` to
  `computeResource`. Verify: the toolset tests from 1.1 on reads pass, and `ToolSetRepairServiceTest` asserts the audit
  author passed to `computeResource` while the body `author` stays what is stored.
- [x] 1.4 `ApplicationService.writeApplication` and `ToolSetService.writeToolSet`: in the `computeResource` callback, a
  create with no `author` stores the `author` argument; an update with no `author` keeps the stored one; a value is
  stored as sent, `""` included. Set it before the existing merge and serialization steps. Verify: the write tests from
  1.1 pass.
- [x] 1.5 Find the tests that send an `author` in a user or `public` application or toolset write and expect the caller's
  name back (`rg '"author"' server/src/test`), and change them to the new behaviour. Verify: `ApplicationDeploymentApiTest`,
  `CustomApplicationApiTest`, `ToolSetApiTest`, `PerRequestPermissionsApiTest` and `DeploymentPostApiTest` pass.
- [x] 1.6 Docs: `docs/dynamic-settings/applications.md` and `toolsets.md` say that `author` is the developer's name,
  that a write without it stores the caller's name, that an update without it keeps the stored one, that `""` means
  anonymous, and that the audit author is `author` in `GET /v1/metadata/`. `docs/open_api_core.yaml` is generated and
  needs no change: its `author` entries are examples of the metadata response, which stays the audit author.
  Verify: the text matches the spec scenarios; `git diff --stat docs` lists only the two `.md` files.
- [x] 1.7 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.CustomApplicationApiTest" --tests "com.epam.aidial.core.server.ToolSetApiTest" --tests "com.epam.aidial.core.server.ApplicationDeploymentApiTest" --tests "com.epam.aidial.core.server.service.ToolSetRepairServiceTest" --tests "com.epam.aidial.core.server.ToolSetRepairApiTest"`.

## 2. Admin paths: Configuration API, apply and file migration

- [x] 2.1 Write the tests first. `ConfigEntityWriteApiTest`: `PUT /v1/toolsets/platform/t` without `author`, then `GET`
  has the admin's display name. `AdminApplyApiTest`: an `Application` manifest without `spec.author` and a `ToolSet`
  manifest with `spec.author` `team-x`, both in `public`; a `Model` manifest, then
  `GET /v1/metadata/models/platform/` item `author`; `models/platform/m2` stored with the audit author `first-admin`,
  applied, still `first-admin`. `ConfigFileMigrateApiTest`: a migrated file model lists with the admin's display name as
  `author`. Verify: the new tests fail on `development`.
- [x] 2.2 `ConfigApplyService.applyEntries` takes the caller's name, `AdminApplyController.process` and
  `ConfigFileMigrateController.runMigration` pass `context.getUserDisplayName()`, and every write in the class passes it
  (`resourceService.putResource`, `putApplication`, `putToolSet`, `PolicyService.putPolicy` and `putAssignment`).
  Verify: the tests from 2.1 pass.
- [x] 2.3 Find the tests that expect no audit author on an applied or migrated entity and change them (`rg -n author
  server/src/test/java/com/epam/aidial/core/server/AdminApplyApiTest.java server/src/test/java/com/epam/aidial/core/server/ConfigFileMigrateApiTest.java`).
  Verify: `AdminApplyApiTest`, `ConfigFileMigrateApiTest`, `AdminValidateApiTest` and `MergedConfigStoreApiTest` pass.
- [x] 2.4 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.ConfigEntityWriteApiTest" --tests "com.epam.aidial.core.server.AdminApplyApiTest" --tests "com.epam.aidial.core.server.ConfigFileMigrateApiTest" --tests "com.epam.aidial.core.server.MergedConfigStoreApiTest"`.

## 3. Publication approval

- [x] 3.1 Write the tests first, in `PublicationApiTest` (the application and toolset publication tests there): a user
  application saved with `"author": "original-dev"`, approved with `displayAuthor` `dream-team`, then `GET` of the public
  application has `author` `dream-team` and `GET /openai/applications` has `owner` `dream-team`; a toolset approved without
  `displayAuthor` has `author` `original-dev`. Verify: the new tests fail on `development`.
- [x] 3.2 `ApplicationService.copyApplication` and `ToolSetService.copyToolSet`: when the `author` argument is not
  `null`, set it as the copy's body `author`, before it defaults to the source's audit author; otherwise the copy keeps
  the source's body `author`, and the read fallback covers a source without one. Verify: the tests from 3.1 pass,
  and a move or copy through `ResourceOperationService` still carries the source's `author`.
- [x] 3.3 Run `./gradlew checkstyleMain checkstyleTest` and
  `./gradlew :server:test --tests "com.epam.aidial.core.server.PublicationApiTest" --tests "com.epam.aidial.core.server.ResourceOperationApiTest"`.

## 4. Integration checks

- [x] 4.1 Run the `config-changes-for-admin-api` check on this change. Verify: it reports that no field of `Config` or of
  a class reachable from it changed.
- [x] 4.2 Run `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`. Verify: it reports no error.
- [x] 4.3 Run `./gradlew checkstyleMain checkstyleTest` and the test classes of groups 1 to 3 together.
