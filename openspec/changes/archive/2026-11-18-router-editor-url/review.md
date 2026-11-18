# Review: router-editor-url

## Scope and findings

The plan was checked against the code before apply: two fixes, a verification step that `git diff --stat` could not
do and a missing source citation. After apply, a reviewer without the session's context read the implementation
against the spec delta, the design, `docs/CODE_STYLE.md` and the `router-deployment` baseline. It found no bug and
reported five findings:

- Fixed: proposal.md and design.md D2 said the "Router listing" requirement names every field an entry carries. It
  does not: `RouterData.toData` also writes `intro`, `reference`, `interfaces`, `created_at` and `updated_at`, which
  the requirement leaves out. The sentence is gone; the new scenario pins the absence of `editorUrl` on its own.
- Fixed: `GET /v1/deployments` lists routers through its own loop (`DeploymentController.java:getRouters`) and was
  not tested. It is now in the scenario and in `RouterApiTest.editorUrlNotListed`.
- Fixed: design D3 rested "no validation" on a DIAL Admin check (`new URL(...)`) that does not filter URL schemes. D3
  now rests on what holds: only administrators write routers.
- Fixed: two design citations named the file without the method (`FileConfigStore.java:loadConfig`,
  `FileConfigController.java:handleSingle`).
- Not changed: no test of its own for the manifest kind `Router` in validate and apply, the file-to-platform
  migration, the rebuild or the export. Each serializes or parses the whole `Router` with `BLOB_MAPPER`, with no field
  copied by hand, and the reviewer traced the field through each of them.

## What changed

- `Router.editorUrl` with the application's aliases and `@JsonInclude(NON_NULL)`; the class javadoc names it (D1).
  `RouterData` unchanged (D2).
- Tests: `ConfigRouterTest.editorUrlReadBack`, `editorUrlSnakeCaseAlias`, `fileRouterEditorUrl`, and
  `untypedRouterStoredWithoutTheFields` now also asserts no `editorUrl` key; `RouterApiTest.editorUrlNotListed`.
  Fixture `dial-config/routers.json`: `r2` has `editorUrl`.
- Docs: `docs/dynamic-settings/routers.md` (the field, where it is returned, the downgrade note, the listing entry
  never carries it). `docs/open_api_core.yaml` regenerated: the `Router` schema gains `editorUrl`, nothing else.

## Verification

Before the field existed, the new tests failed with `Unrecognized field "editorUrl" (class
com.epam.aidial.core.config.Router)`, which is also what a Core without this change does with such a router.
After it: `./gradlew checkstyleMain checkstyleTest` is clean; `ConfigRouterTest` (23), `RouterApiTest` (24),
`ConfigFileMigrateApiTest` (23), `AdminValidateApiTest` (45) and `config.FileConfigStoreTest` (13) pass, and
`ConfigRouterTest` and `RouterApiTest` pass again after the review fixes. `checkOpenApiDiff` passes.
`validate router-editor-url --strict` is valid. The full suite runs in CI after the push.

Per request: no effect, by inspection; no code in `server` reads the field.
