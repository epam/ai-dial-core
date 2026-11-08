# Tasks

State as of the code in the working tree. `[x]` means the code and a test exist. Design decisions and spec requirements are cited by topic name.
Test classes are in `server/src/test/java/com/epam/aidial/core/server/` (API tests at that level, unit tests under `service/`) unless a module is named.

## 1. Endpoint, request and authorization

- [x] 1.1 `POST /v1/admin/export` selects Applications: `AdminExportController.exportSelection`, body parsed by `ExportRequestParser` (only `roots`; each root an Application URL in `public/` or `platform/`). Tests: `ExportRequestParserTest`, `ExportSelectionApiTest`, `AdminExportControllerSelectionTest`.
- [x] 1.2 Bad input is `400` and never echoes a submitted value: empty or blank body, empty `roots`, more than `adminExport.maxRoots`, unknown field (`include`, `onUnresolved`, `secrets` included), non-Application root, user bucket, malformed URL. Tests: `ExportRequestParserTest`, `ExportSelectionApiTest` (`testInvalidBodiesAreBadRequestsThatEchoNothing`, `testLegacyControlsAreBadRequestsThatEchoNothing`).
- [x] 1.3 Only an explicit admin may export; a per-request key, or no `access.admin.rules`, is `403` before the body is read. Tests: `ExportAdminRulesApiTest`, `ExportSelectionApiTest`.
- [x] 1.4 A selected root that does not exist is `404`; a public root is authorized through `AccessService` before it is read. Tests: `ExportSelectionApiTest`, `ExportPublicAuthorizationApiTest`.
- [x] 1.5 Platform roots and dependencies pass the explicit-admin gate alone. Test: `ExportPlatformAuthorizationApiTest`.
- [x] 1.6 The raw export of whole buckets is `POST /v1/admin/system-export`; its output is meant to be unchanged by the shared streamer. Tests that exist: `AdminSystemExportApiTest`, `AdminSystemExportServiceTest` (not modified by this branch; their run is recorded in 11.5).

## 2. Graph and discovery

- [x] 2.1 Closure with a visited set keyed by `ResourceDescriptor`; references are followed recursively, a cycle ends, each resource appears once. Tests: `ExportClosureWalkerTest`, `ExportRegistryGraphTest`.
- [x] 2.2 Edges of an Application come from the fixed fields (`dependencies`, `interceptors`, schema ids, `icon_url`) and from `dial:resource` and `dial:file` markers. Tests: `DeploymentEdgeDiscoveryTest`, `ExportSchemaMarkerDiscoveryApiTest`.
- [x] 2.3 Marker discovery goes on after an invalid reference: `ApplicationSchemaService.collectMarkerReferences` and its sibling in `CatalogSchemaService`, additive and non-throwing; existing callers keep their fail-fast behaviour. Tests: `ApplicationSchemaServiceTest`, `CatalogSchemaServiceTest`.
- [x] 2.4 Incomplete discovery is shown in the registry (`discovery.complete`, `discovery.issues`, a list of `{resource, kind, pointer}`), without references or messages, and never fails the export. Tests: `DeploymentEdgeDiscoveryTest`, `ExportRegistryBuilderTest`, `ExportSchemaMarkerDiscoveryApiTest`.
- [x] 2.5 Reference forms are classified (external URL, resource URL, short name, malformed); an empty, `.` or `..` segment, also encoded, is malformed before any read or entry name. Tests: `ShortNameResolverClassificationTest`, `DeploymentEdgeDiscoveryTest`.
- [x] 2.6 A URL-form reference reads the stored blob it names, whatever the merged configuration holds under the short name. Tests: `ExportUrlFormDependencyApiTest`.
- [x] 2.7 One snapshot per export: the merged config and the skipped entities are read under the store's publication lock, with no change to `MergedConfigStore`. Tests: `ExportPlannerTest`.
- [x] 2.8 Follow every Deployment: `DeploymentEdgeDiscovery` (renamed from `ApplicationEdgeDiscovery`) covers `interceptors`, `dependencies`, `icon_url`, `catalog_schema_id` and catalog markers for Application, Model, Interceptor and ToolSet; Application-only extras stay under an `instanceof` branch; the body is bound by descriptor type. Tests: `DeploymentEdgeDiscoveryTest`.
- [x] 2.9 `ExportTypePolicy.Content` is `EXAMINED` (the four Deployment types), `SCHEMA`, `AS_STORED`, `STREAMED` and `COMPLEX`; definition-only is a trait; the walker, the registry builder and the file entities follow; the "references not examined" warning and `hasUnexaminedBodies` are removed. Tests: `ExportTypePolicyCoverageTest`, `ExportRegistryBuilderTest`, `ExportClosureWalkerTest`.
- [x] 2.10 Walker tests for the new nodes: Application -> ToolSet -> Interceptor -> File, Application -> Model -> Interceptor, a cycle between a ToolSet and an Interceptor, and the depth, node and retained limits on them. Tests: `ExportClosureWalkerTest` (`testApplicationToolSetInterceptorFileChainIsFollowedToTheEnd`, `testApplicationModelInterceptorChainIsFollowed`, `testCycleBetweenToolSetAndInterceptorTerminatesWithEachNodeOnce`, `testToolSetAndInterceptorNodesCountAgainstTheDepthNodeAndRetainedLimits`), `DeploymentEdgeDiscoveryTest`.
- [ ] 2.11 API test: a platform ToolSet that names an Interceptor and an icon File, and a Model that names an Interceptor, are exported with those dependencies (the walker level is covered by 2.10).
- [ ] 2.12 Memoize the built schema per schema id: one `ApplicationSchemaService.buildExportSchema` per `application_type_schema_id` per export, whatever the number of Applications that name it (one runner request per schema, not per Application). Test: `DeploymentEdgeDiscoveryTest` (`testSchemaIsCheckedOncePerSchemaIdWhateverTheNumberOfApplications`).
- [x] 2.13 Scan `application_properties`: `DeploymentEdgeDiscovery` collects it against the schema built by `ApplicationSchemaService.buildExportSchema` (`collectMarkerReferences`); each marked value is an edge at `/application_properties/<path>`, classified like a marked value of `catalog_properties`; a schema failure is an unresolved schema edge, a violation is an `instance-invalid` issue; `maxEdges` counts both collections together before any is classified. Redaction is unchanged (as stored).
- [x] 2.14 Tests for the scan are updated and pass: `DeploymentEdgeDiscoveryTest`, `ApplicationSchemaServiceTest`, `ExportSchemaMarkerDiscoveryApiTest` and the API tests that held the old "not scanned" expectation.

## 3. Short-name and schema resolution

- [x] 3.1 A short name resolves to one effective entity with its origin (`file`, `platform`, shadowing, skipped, not found); three read-only signals (merged config, file config, platform blob via `ResourceService.hasResource`) and `ORIGIN_UNKNOWN` when they disagree (`ShortNameResolver`). Tests: `ShortNameResolverClassificationTest`, `config/ShortNameResolverTest`, `config/MergedConfigStoreOriginSignalsTest`.
- [x] 3.2 Candidates of a short name come from the effective holders only; a name held by two types, or a skipped entity, is reported and never guessed. Tests: `ShortNameResolverClassificationTest`, `config/ShortNameResolverTest`.
- [x] 3.3 Resolution against concurrent write, delete, file reload and replica events gives a correct origin or `ORIGIN_UNKNOWN`. Tests: `config/ShortNameResolverTest`, `config/MergedConfigStoreOriginSignalsTest`, `config/MergedConfigStoreReplicaUpdateTest`, `config/MergedConfigStorePartialUpdateTest`.
- [x] 3.4 A schema named by a fixed field is found by its body `$id` with a per-export scan of the platform schema blobs (`SchemaReferenceResolver`). Tests: `SchemaReferenceResolverTest`.
- [x] 3.5 Remote application type schemas are fetched live through the existing Runner download (`ApplicationSchemaService.buildSchema(…, quiet)`), bypassing the shared schema cache; any Runner failure is an `unresolved` schema edge with incomplete discovery and `200`. Tests: `ApplicationSchemaServiceTest`, `ExportRunnerFailureApiTest`.
- [x] 3.6 The export evaluates schemas with its own factory that refuses every non-local `$ref`; the schema download of an export logs no endpoint, body, headers or exception message. Test: `ApplicationSchemaServiceTest` (including `LogCapture`).
- [ ] 3.7 Option C, an origin index in `MergedConfigStore`. Not in v1; a documented alternative to the three-signal resolver.
- [ ] 3.8 Characterize the write-path guard against a cross-type deployment name (tests only): single `PUT`, `apply` and `validate` of a taken name, a name used twice under two types, file migration, a name held only by a skipped entity. Today `AdminApplyApiTest` and `AdminValidateApiTest` cover key secrets only.

## 4. Type policy, registry and archive

- [x] 4.1 One policy table states the supported types by location and what is done with each body (`ExportTypePolicy`): Application (root and dependency), ToolSet, Model, Interceptor, application type and catalog schema in `platform/`; Application, ToolSet, Prompt, File and Skill in `public/`; everything else, any other location and every folder is `unsupported`. Test: `ExportTypePolicyCoverageTest`.
- [x] 4.2 Dependency statuses in the registry are `included`, `unsupported`, `external` and `unresolved`; each `unresolved` edge carries the pointer, value kind and target identity, never the value; `summary.edges` has the three keys `unsupported`, `external` and `unresolved`. Tests: `ExportRegistryBuilderTest`, `ExportRegistryGoldenTest`, `ExportRegistryContractApiTest`.
- [x] 4.3 Registry content: resources by key (`type:origin:segment`), `roots`, `requiredByRoots`, `files`, `discovery`, `summary`, `warnings`; deterministic for the same state. Tests: `ExportRegistryBuilderTest`, `ExportRegistryGraphTest`, `ExportRegistryGoldenTest`, `ExportSelectionStreamApiTest` (`testTwoExportsOfTheSameStateHaveTheSameResourceEntriesInTheSameOrder`).
- [x] 4.4 Archive layout of the system export: `.dial-system-export/manifest.json` (`mode: selective`) first, `.dial-export/dependency-registry.json` second, each resource at its storage path after its metadata entry, `.dial-export/verification.json` (sha256 of every entry) last. Tests: `ExportArchiveTest`, `ExportRegistryContractApiTest`, `ExportSelectionStreamApiTest` (verification entry, sidecar times).
- [x] 4.5 File-origin entities of Model, Interceptor, ToolSet, Application and schemas are exported from the file-sourced `Config` as fragments `.dial-export/config/<type>/<segment>.json` with `sourcePointer`, redacted like any JSON, so the package is usable as a unit. Tests: `ExportFileEntitiesTest`, `ExportRegistryContractApiTest`.
- [x] 4.6 A file entity wins over a platform blob of the same name: the file entity is exported with origin `file`, the blob is not, and the registry warns. At runtime the merged configuration serves the blob, so an import can differ from the running system; this is the deliberate rule of the requirement "A file entity wins over a platform blob of the same name". Tests: `ExportRegistryContractApiTest`, `ExportRegistryBuilderTest`, `DeploymentEdgeDiscoveryTest` (`testFileEntityTakesPrecedenceOverPlatformBlobAndIsFlagged`).

## 5. Content and failure contract

- [x] 5.1 JSON is read once with its times, redacted, and kept as redacted bytes with their `sha256`; the archive writes those bytes and reads nothing again (`ExportNodeContent.Json` carries bytes, `ExportArchive.prepare` is the one preparation, `PhaseTwoSource` and the JSON second-read code are removed). Tests: `ExportArchiveTest` (`testArchiveHoldsTheRedactedBytesOfEveryResourceAndTheRedactorIsAskedOncePerResource`, `testPreparedArchiveKeepsNeitherTheClosureNorTheOriginalBodies`), `ExportClosureWalkerTest`, `ExportRegistryGoldenTest` (golden unchanged).
- [ ] 5.2 A JSON resource or file-origin entity that changes or disappears after planning is exported as read and does not fail the export. Missing: an API test with a resource changed after planning; the unit level is `ExportArchiveTest` and `ExportClosureWalkerTest`.
- [x] 5.3 Binary content (a File, the files of a Skill's pinned version) is streamed as stored with bounded memory, checked by length, etag and `sha256`; a change is listed in `changedDuringExport`. Tests: `ExportFileApiTest`, `ExportFileChangedDuringExportApiTest`, `ExportArchiveTest`.
- [x] 5.4 A failure before the first byte is a status: `400`, `403`, `404`, `409` (the one slot is taken), `413` (a limit), `500` (`Failed to prepare the export`: a body that cannot be redacted, no value echoed; `Failed to export the Applications`: the streamer fails before the head), `503` (no redactor configured; after the request checks and the plan), `504` (deadline). The order is: admin `403`, `400`, `409`, the plan results (`403`, `404`, `413`, `504`, `500`), `503`. Tests: `ExportSelectionApiTest` (`409`), `ExportSelectionStreamApiTest` (`500`, `503`), `ExportBodyRedactorApiTest`, `ExportClosureWalkerTest`, `AdminExportSlotLifecycleTest`, `AdminExportSlotTimeoutTest`.
- [x] 5.5 After the head a failure resets the response; a stalled client fails at the stall timeout; a disconnect stops the writer and the next export succeeds (`ZipResponseStreamer`). Tests: `ExportSelectionStreamFailureApiTest`, `ExportSelectionStreamApiTest`.
- [ ] 5.6 Findings do not fail the request: `missing`, `unsupported`, `external` and unresolved edges and incomplete discovery are in the registry with a `200` (spec: Dependency findings do not change the status). Tests at the unit level: `ExportFileApiTest`, `ExportRunnerFailureApiTest`; the API test that asserted this with a `503` is replaced in 8.4.

## 6. Content kinds

- [x] 6.1 ToolSet in `public/` and `platform/`: exported redacted. Test: `ExportPublicAuthorizationApiTest`.
- [x] 6.2 Model and Interceptor are exported as their definition only, with a warning (their references are followed, task 2.8). Tests: `ExportRegistryBuilderTest`, `ExportRegistryContractApiTest`.
- [x] 6.3 Prompt in `public/` is exported exactly as stored (only checked to be a JSON object), with a warning. Tests: `ExportPromptApiTest`, `ExportBodyRedactorTest`.
- [x] 6.4 File in `public/` (named by a fixed field, a catalog marker or `icon_url`) is exported as stored and listed in `files`; a folder or a user-bucket File is `unsupported`. Tests: `ExportFileApiTest`, `ExportFileChangedDuringExportApiTest`.
- [x] 6.5 Skill in `public/` is exported as the files of its pinned version in a logical folder, the Skill being the owner in `files`; a grouping folder is `unsupported`. Tests: `ExportSkillApiTest`, `service/resource/ComplexResourceServiceTest`.

## 7. Secrets and redaction

- [x] 7.1 `ExportBodyRedactor` removes annotated secrets (`SecretFieldProcessor.stripEncryptedFields`), `client_secret`, `code_verifier` and `client_secret_hint` of ToolSet and external-service `auth_settings`, and, in an application type schema, `dial:key`, `key`, `dial:secretExtraData`, `secretExtraData` at the route upstream path. Tests: `ExportBodyRedactorTest`, `ExportBodyRedactorCoverageTest`, `ExportBodyRedactorApiTest`.
- [x] 7.2 Everything else is exported as stored and not inspected: `application_properties` (scanned for references, not redacted), `function.env`, `default_headers`, URLs, free text, a string that starts with `ENC[` in an unannotated field. The registry and the metadata entries hold no redaction information. Tests: `ExportBodyRedactorTest`, `ExportBodyRedactorApiTest`, `ExportFileEntitiesRedactionTest`, `ExportRegistryContractApiTest`.
- [x] 7.3 Ciphertext in annotated fields is removed with the field; a Prompt and File bytes are exported as stored; `credentials/` and `encryption_keys/` content is never in the package. Tests: `ExportBodyRedactorApiTest`, `ExportTypePolicyCoverageTest` (`credentials` and `encryption_keys` have no policy row).

## 8. Limits (shipped; values and capacity are deferred)

- [x] 8.1 `adminExport` settings with code defaults: roots, nodes, depth, references, size of one body, retained size, deadline, one cluster-wide slot; exceeding one fails the whole export (`413`, `504`, `409`). `maxRetainedMiB` is enforced on the stored bodies read and, separately, on the redacted bytes held, so the heap for bodies is up to about twice the limit plus the largest transient body. Tests: `ExportClosureWalkerTest` (`testBodiesThatEachFitButTogetherExceedTheRetainedLimitFail`), `ExportLimitsApiTest`, `AdminExportSlotLifecycleTest`, `AdminExportSlotTimeoutTest`. Docs: `README.md` table, `docs/admin-export.md` "Limits".
- [ ] 8.2 Release the slot when a success handler throws (a failure in the handler that runs after the plan or after the preparation must not leave the slot held for the lease). Test: `AdminExportSlotLifecycleTest`.
- [ ] 8.3 `AdminExportSettingsTest`: the defaults, `validate` (stall timeout and deadline against the lock period, positive limits) and the mapping to `ExportLimits`.
- [ ] 8.4 Replace the API tests that assert `503` (no redactor) to show that findings do not fail the request with tests that run with a redactor and assert `200` and the findings in the registry (see 5.6).
- [ ] 8.5 `413` API test for each limit with a redactor, and unit tests of the two sums of `maxRetainedMiB` separately: stored bodies over the limit, and redacted bytes over the limit while the stored bodies are not (multi-byte text, a `file`-origin entity). Tests: `ExportLimitsApiTest`, `ExportClosureWalkerTest`.

## 9. Open tests

- [ ] 9.1 API-level matrix over the types and origins (`file`, `platform`, `public`) of the policy table, including the negative locations (user bucket, app `Keys/<x>/`, `appdata`, hidden folder, `public/deployments/`, `public/publications/`, review bucket, folder), with a `ResourceService` spy showing that nothing unsupported is read. The policy level exists in `ExportTypePolicyCoverageTest`.
- [ ] 9.2 Denial by a real `AccessService` rule of a public dependency (File, Prompt, Skill; code in `ExportClosureWalker`): `403` before the first byte and no read. `ExportPublicAuthorizationApiTest` covers only the caller without admin access.
- [ ] 9.3 Re-measure the peak heap and the time of one Application with a large resource and of a graph of about 100 resources, with the stored bodies counted and all redacted JSON bytes held up to `maxRetainedMiB`, and record the result in design "Per-request cost". The measuring test classes were removed from the tree, so the recorded figures cannot be reproduced from it.
- [ ] 9.4 Run on Linux CI the suites that do not run on Windows (`ComplexResourceServiceTest`, `SkillResourceApiTest`, the large-File test) and record the result.

## 10. Documentation and OpenAPI

- [x] 10.1 `@ApiOperation` of `AdminExportController` describes the roots-only request and the responses; `docs/open_api_core.yaml` is generated, not edited by hand.
- [x] 10.2 `docs/admin-export.md` describes request, authorization, what is exported, the registry, the package, secrets, responses, limits and known limitations; `README.md` lists the `adminExport` settings.
- [x] 10.3 `docs/error_codes.md` and `docs/admin-export.md` describe the one-read JSON handling, the traversal of Model, Interceptor and ToolSet, the statuses and their order (no strict mode, no `422`, no bodyless `409`).
- [ ] 10.4 Final documentation pass: `docs/admin-export.md` matches every spec scenario and states the Security limitations of the secret policy.

## 11. Release gates (decisions, not work)

- [ ] 11.1 Maintainer and Security approval of the secret policy and its limits: credentials in URLs, free text, `application_properties`, `function.env`, `default_headers` and other unclassified fields are exported as stored. Items and wording: design, secret policy, "Approvals" table. Blocks release.
- [ ] 11.2 Security acknowledgement that File and Skill bytes and Prompt bodies are exported unredacted. Markers inside `application_properties` are scanned, so a Quick App makes them reachable. Blocks release.
- [ ] 11.3 Sign-off of the v1 scope as implemented: explicit admin only, `public/` roots and the public dependency types of the policy table, no snapshot promise and no bucket locks, folders not expanded.
- [ ] 11.4 Confirm the classification of every resource type that the policy table leaves `unsupported` or unverified, and the ToolSet-as-root question (roots stay Application only).
- [ ] 11.5 Release check: `./gradlew checkstyleMain checkstyleTest`, every `Export*` and `AdminExport*` test class, `AdminSystemExportApiTest`, `AdminSystemExportServiceTest`, and `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`. The gates above are ticked only when an approval is recorded by name and date.

## 12. Deferred (not v1; each ships as its own change)

- Production readiness (capacity workstream): approved limit values, `capped` status, `N` cluster slots as a setting, a size-bounded read of one body in `ResourceService`, capacity benchmarking.
- Platform-level outbound HTTP for the Runner schema download: allow-list, address checks, body deadline, size bound, proxy decision.
- Project as a dependency type.
- Origin index in `MergedConfigStore` (task 3.7).
- Import of the package, secret re-wrapping, user and app buckets, audit and metrics, typed references.
