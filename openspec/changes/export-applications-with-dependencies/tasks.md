# Tasks

State as of the code in the working tree. `[x]` means the code exists (and, where the item says so, a test); an item about a test that is
not written or not green yet is `[ ]`. Class names are in `server/src/main/java/com/epam/aidial/core/server`; tests are in
`server/src/test/java/com/epam/aidial/core/server/` (API tests at that level, unit tests under `service/`).

## 1. Endpoint, request and authorization

- [x] 1.1 `POST /v1/admin/export` selects Applications: `AdminExportController.exportSelection`, body parsed by `ExportRequestParser` (only `roots`; each root an Application URL in `public/` or `platform/`).
- [x] 1.2 Bad input is `400` and never echoes a submitted value: empty or blank body, empty `roots`, unknown field, non-Application root, user bucket, malformed URL.
- [x] 1.3 Only an explicit admin may export; a per-request key, or no `access.admin.rules`, is `403` before the body is read.
- [x] 1.4 Platform roots and dependencies pass the explicit-admin gate alone; a public root and every public dependency are authorized through `AccessService` before they are read.
- [x] 1.5 The export of whole buckets stays `POST /v1/admin/system-export`; its output is unchanged.
- [ ] 1.6 Tests: `400` for each bad input without the submitted value, `403` for a caller that is not an explicit admin, a per-request key and no admin rules, and `AdminSystemExportApiTest` and `AdminSystemExportServiceTest` green.

## 2. The streaming flow

- [x] 2.1 `ExportStreamer`: manifest, then a loop over a queue of resources; each resource is read, bound, its references sent to the queue, decrypted, serialized, written and forgotten.
- [x] 2.2 Each resource is marked as met before it is queued, so a cycle, a diamond and a repeated root give one read and one entry.
- [x] 2.3 Memory is the queue, the met set and the resource in hand; `ExportArchive` keeps nothing of an entry once it is written; Files and files of a Skill go through a 32 KiB buffer.
- [x] 2.4 Breadth-first order: roots in request order, then references in the order of their holder's discovery.
- [x] 2.5 One snapshot of the merged configuration and the skipped entities, read under the publication lock of `MergedConfigStore` (`ApiKeyStore.getMutationLock()`), with no change to the store; the file-sourced configuration is read afterwards.
- [x] 2.6 Tests: every resource once, a cycle (ToolSet and Interceptor, two Applications), a diamond, the order, and a counting fake that shows that no more than the resource in hand and the queue are held (`ExportStreamerTest`).
- [ ] 2.7 API test: a platform ToolSet that names an Interceptor and an icon File, and a Model that names an Interceptor, are exported with those dependencies.
- [ ] 2.8 API test: a resource that changes between two reads is exported as it was read, and the export does not fail.

## 3. Statuses, locks and the deadline

- [x] 3.1 Before the head: `403` (not admin), `400` (body), `409` (a bucket lock is held), `403` (public root not readable), `404` (root does not exist), `500` (`Failed to export the Applications`); `ExportStreamer.checkRoots` checks the roots on the task thread, after the locks are taken.
- [x] 3.2 `lockService.tryLockBuckets(MergedConfigStore.ADMIN_BUCKET_LOCATIONS)` is the first step of the one task that checks the roots and writes the archive, held to its end in a try-with-resources: released after the writer ends, a failed check, a failure of the stream and a lost client.
- [x] 3.3 After the head the deadline, a client that does not take the data within `stallTimeout`, a body that cannot be bound, a gone root or File, an unsafe path, an unreadable public dependency or a failed read or write cuts the stream (`ZipResponseStreamer` resets the connection) and is logged at WARN without a value.
- [x] 3.4 `adminExport` settings with code defaults: `stallTimeout` 60000 and `deadline` 180000; `AdminExportSettings.validate` requires `stallTimeout`, and `deadline` plus `stallTimeout`, to be positive and below the 300 s lock period.
- [x] 3.5 A timer aborts the pipe when the deadline passes, so the next write fails and the export ends.
- [ ] 3.6 Tests: a held bucket lock (system export, another export, an admin write) is `409` before any byte; the locks are released after a success, a missing root, a failure after the head and a client disconnect (`ExportSelectionStreamFailureApiTest`); a user resource write is not blocked.
- [ ] 3.7 Tests: roots `404` and `403` before the head; the deadline cuts the stream and releases the locks (`ExportDeadlineApiTest`; the client sees a transport error and no central directory); a body that cannot be bound and a File that vanished cut it.
- [x] 3.8 `AdminExportSettingsTest`: the defaults, `validate` (stall timeout and deadline against the lock period, positive values).

## 4. Discovery

- [x] 4.1 Edges of every Deployment come from the fixed fields (`dependencies`, `interceptors`, schema ids, `icon_url`) and from `dial:resource` and `dial:file` markers of `catalog_properties`; an Application also has `application_type_schema_id`, `function.source_folder` and `application_properties`, scanned against its schema (`DeploymentEdgeDiscovery`).
- [x] 4.2 Marker discovery goes on after an invalid reference: `ApplicationSchemaService.collectMarkerReferences` and its sibling in `CatalogSchemaService`, additive and non-throwing; existing callers keep their fail-fast behaviour.
- [x] 4.3 Reference forms are classified (external URL, resource URL, short name, malformed); an empty, `.` or `..` segment, also encoded, is malformed before any read or entry name.
- [x] 4.4 A URL-form reference reads the stored resource it names, whatever the merged configuration holds under the short name.
- [x] 4.5 The built schema is memoized per schema id: one `ApplicationSchemaService.buildExportSchema` per `application_type_schema_id` per export.
- [x] 4.6 Incomplete discovery (an `instance-invalid` issue, a schema that cannot be built) is logged as one record and never fails the export.
- [ ] 4.7 Tests: `DeploymentEdgeDiscoveryTest`, `ApplicationSchemaServiceTest`, `ExportSchemaMarkerDiscoveryApiTest` (the scan of `application_properties`), `ExportUrlFormDependencyApiTest`, the classification of reference forms, and one build per schema id.

## 5. Short-name and schema resolution

- [x] 5.1 A short name resolves to one effective entity with its origin (`file`, `platform`, skipped, not found); three read-only signals (merged config, file config, platform blob via `ResourceService.hasResource`) and `ORIGIN_UNKNOWN` when they do not establish one (`ShortNameResolver`).
- [x] 5.2 Candidates of a short name come from the effective holders only; a name held by two types, or a skipped entity, is never guessed.
- [x] 5.3 A schema named by a fixed field is found by its body `$id` with a per-export scan of the platform schema blobs (`SchemaReferenceResolver`).
- [x] 5.4 Remote application type schemas are fetched live through the existing Runner download (`ApplicationSchemaService.build(..., quiet)`), bypassing the shared schema cache; any Runner failure is a `schema-unavailable` reference.
- [x] 5.5 The export evaluates schemas with its own factory that refuses every non-local `$ref`; the schema download of an export logs no endpoint, body, headers or exception message.
- [x] 5.6 A valid platform blob wins over a file entity of the same type and name (for a schema, the `$id`): the blob is exported as a platform resource and followed, the file entity is neither read nor exported; a file entity that no valid blob replaces is still exported with origin `file`.
- [ ] 5.7 Tests: `ShortNameResolverTest`, `ShortNameResolverClassificationTest`, `MergedConfigStoreOriginSignalsTest`, `MergedConfigStoreReplicaUpdateTest`, `SchemaReferenceResolverTest`, `ApplicationSchemaServiceTest` (including log capture and the `$ref` table), `ExportRunnerFailureApiTest`.
- [ ] 5.8 Option C, an origin index in `MergedConfigStore`. Not in v1; a documented alternative to the read-only signals.
- [ ] 5.9 Characterize the write-path guard against a cross-type deployment name (tests only): single `PUT`, `apply` and `validate` of a taken name, a name used twice under two types, file migration, a name held only by a skipped entity.
- [x] 5.10 Tests of the precedence of a platform blob over a file entity: `ShortNameResolverTest` (blob over file entity for every short-name type, blob next to the file instance, invalid blob next to a file entity, file entity without a blob), `SchemaReferenceResolverTest`, `DeploymentEdgeDiscoveryTest`, `ExportStreamerTest` (the blob is read, the file entity is not) and `ExportPlatformOverFileApiTest` (the archive holds the platform content and no fragment of the replaced entity, and the fragment of a file-only entity).

## 6. Type policy and archive

- [x] 6.1 One policy table states the supported types by location and what is done with each body (`ExportTypePolicy`): Application (root and dependency), ToolSet, Model, Interceptor, application type and catalog schema in `platform/`; Application, ToolSet, Prompt, File and Skill in `public/`; a type with no row is unsupported.
- [x] 6.2 Archive layout (`ExportArchive`): `.dial-export/manifest.json` (`formatVersion` 2, `coreVersion`, `createdAt`, `mode: selective`) first, then one entry per resource at its storage path, in the order of writing. No other entry: no metadata entry, no checksum list, no list of references, nothing under `.dial-system-export/`.
- [x] 6.3 An entity of the file configuration is written as `.dial-export/config/<type>/<segment>.json`, from the live entity of the file-sourced `Config` (`ExportFileEntities`), through the same projection.
- [x] 6.4 The completed record is `Export completed: duration=<ms>, resources=<n>`, without a number of entries.
- [ ] 6.5 Tests: `ExportArchiveTest` (the manifest first at `.dial-export/manifest.json`, one entry per resource at its storage path, a fragment for a `file`-origin resource, no entry under `.dial-system-export/`, no verification or metadata entry), `ExportFileEntitiesTest`, `ExportResourceNamesTest` (key, escaping, entry names, unsafe segments), `ExportTypePolicyCoverageTest` (every `ResourceTypes` value has a disposition).

## 7. Projection and secrets

- [x] 7.1 `ConfigEntityRedactor` holds the projection (`valueToTree`) and the removal of `auth_settings` secrets; `ConfigResourceController` and `ExportResourceProjector` call it.
- [x] 7.2 `ExportResourceProjector`: bind to the entity, decrypt (Application external services and ToolSet `auth_settings`, lenient), project, remove `client_secret` and `code_verifier`, keep the masked `client_secret_hint`; Model and Interceptor are projected as they are; an entity of the file configuration is not decrypted.
- [x] 7.3 The upstream keys of an application type schema (`dial:key`, `key`, `dial:secretExtraData`, `secretExtraData` at `dial:applicationTypeRoutes[*].dial:upstreams[*]` and their interfaces) are removed; a catalog schema is written from its parse; a Prompt is written as stored.
- [x] 7.4 Tests: `ExportResourceProjectorTest` per type (encrypted fields gone, hint present, `client_secret` and `code_verifier` gone, route and upstream keys gone, schema upstream keys gone), `ExportStreamerTest` (a Prompt is written exactly as stored; the projector does not touch a Prompt) and `ConfigEntityRedactorTest`, which pins the controller's answers.
- [ ] 7.5 API tests: an entry equals the admin GET of the same entity without `name` and `status`; no `ENC[` and no plaintext secret in any entry, for stored entities and for entities of the file configuration; a secret that cannot be decrypted has no hint.
- [x] 7.6 `ConfigEntityRedactorCoverageTest` walks the types of Application, Model, Interceptor and ToolSet as the default mapper serializes them and fails, with the path of the property and what to do, for an `@EncryptedField` that the mapper would write, for a `ResourceAuthSettings` anywhere but the two places that `ConfigEntityRedactor` cleans, and for a text property with a secret-looking name that is neither removed nor in its short list of reviewed names.

## 8. Findings in the log

- [x] 8.1 A reference that is not followed is one log record with the resource key, the pointer and the reason (`missing`, `unsupported`, `external` at INFO, `unresolved-short-name`, `origin-unknown`, `schema-unavailable`); a dependency that disappears between the discovery and its read is logged with the key of its holder.
- [x] 8.2 No log record, exception message or response holds a value, a schema body, a parser message or an IRI.
- [ ] 8.3 API test with log capture: a missing dependency, an unsupported one, an external URL with credentials, an unresolved short name and an unavailable schema give `200`, one record each, and no value; the archive does not list them.
- [ ] 8.4 Test that a body that is not valid JSON or cannot be bound cuts the stream and that the log names the resource and no part of the body.
- [x] 8.5 Every failure after the head names the resource by its key `<type>:<origin>:<segment>`: the fixed messages of `ExportArchive` (a File or a file of a Skill gone, with another length, not copied completely), of `ExportStreamer` (a selected Application gone), of `ExportResourceNames` (an unsafe path) and of `ExportResourceProjector` (a body that cannot be bound), and the abort record of `ExportStreamer.stream`, which also holds the key of the resource in hand and the duration; only exceptions of ours have their message in the log.
- [x] 8.6 INFO records, as the system export writes: `Admin export rejected` when a bucket lock is held (`AdminExportController`, where the `409` is produced), `Export started: roots=<n>` and `Export completed: duration=<ms>, resources=<n>` (`ExportStreamer.stream`); no root, body or secret in any of them.
- [x] 8.7 Tests with log capture (`ExportApiSupport.Logs` in the API tests, the log appender of `ExportStreamerTest` for a vanished File, which an API test cannot race): the `409` record, the started and completed records of a success, the key in the record of a vanished File and of a body that cannot be bound, and the key (not the URL) in the message of a bind failure.
- [x] 8.8 A File whose ETag changed since it was read or during the copy, or a Skill whose marker changed or is gone after its files were copied, is written whole and logged as one WARN record with the key of the resource and the ETag before and after, never a value; the export goes on and the archive lists nothing (`ExportArchive.copyFile`, `copySkill`).
- [ ] 8.9 Tests: a File replaced between the metadata read and the copy gives `200`, one WARN record with the key and both ETags, and no entry that lists it; a Skill whose marker changed or is gone after the copy gives one WARN record; a File that is gone, ends early or is longer than its length still cuts the stream.

## 9. Content kinds

- [x] 9.1 ToolSet in `public/` and `platform/`: exported as the projection, followed further.
- [x] 9.2 Model and Interceptor are exported as their definition only, with their references followed.
- [x] 9.3 Prompt in `public/` is written exactly as stored.
- [x] 9.4 File in `public/` (named by a fixed field, a catalog marker or `icon_url`) is streamed as stored; a folder or a user-bucket File is `unsupported`.
- [x] 9.5 Skill in `public/` is exported as the files of its pinned version at `<storage path of the Skill>/<relative path>`; a grouping folder is `unsupported`.
- [ ] 9.6 Tests: `ExportPromptApiTest`, `ExportFileApiTest`, an API test of a File that changes during the copy (task 8.9), `ExportSkillApiTest`, `service/resource/ComplexResourceServiceTest`.
- [ ] 9.7 API-level matrix over the types and origins (`file`, `platform`, `public`) of the policy table, including the negative locations (user bucket, app `Keys/<x>/`, `appdata`, hidden folder, `public/deployments/`, `public/publications/`, review bucket, folder).
- [ ] 9.8 Denial by a real `AccessService` rule of a public dependency (File, Prompt, Skill): the stream is cut and nothing is read.

## 10. Documentation and OpenAPI

- [x] 10.1 `@ApiOperation` of `AdminExportController` describes the roots-only request, the archive in the order it is written (the manifest at `.dial-export/manifest.json`, then each resource at its storage path, with no metadata or verification entry), the form of each resource, the bucket locks, the statuses and the cut stream. The `description` and `summary` of an operation in `docs/open_api_core.yaml` are hand-written: `SpecMerger` keeps them and never regenerates them, so `checkOpenApiDiff` does not compare them, and they are kept equal to the annotation by hand.
- [x] 10.2 `docs/admin-export.md` describes request, authorization, flow, what is exported, findings in the log, the package, secrets, statuses, locks, settings and known limitations; `README.md` lists the `adminExport` settings; `docs/error_codes.md` lists the export statuses.
- [ ] 10.3 The `description` and the `400` text of the operation in `docs/open_api_core.yaml` equal the annotation (checked by reading both after the last edit), and `replaceSpec` followed by `checkOpenApiDiff` passes.
- [ ] 10.4 Final documentation pass: `docs/admin-export.md` matches every spec scenario and states the Security limitations of the secret policy.

## 11. Release gates (decisions, not work)

- [ ] 11.1 Maintainer and Security approval of the secret policy and its limits: credentials in URLs, free text, `application_properties`, `function.env`, `default_headers` and other unclassified fields are exported as the projection holds them; the masked `client_secret_hint` leaves.
- [ ] 11.2 Security acknowledgement that File and Skill bytes and Prompt bodies are exported as stored. Markers inside `application_properties` are scanned, so a Quick App makes them reachable. Blocks release.
- [ ] 11.3 Sign-off of the v1 scope as implemented: explicit admin only, `public/` roots and the public dependency types of the policy table, no snapshot of user content, the bucket locks held for the whole export, a cut stream after the head, folders not expanded.
- [ ] 11.4 Confirm the classification of every resource type that the policy table leaves `unsupported` or unverified, and the ToolSet-as-root question (roots stay Application only).
- [ ] 11.5 Release check: `./gradlew checkstyleMain checkstyleTest`, every `Export*` and `AdminExport*` test class, `AdminSystemExportApiTest`, `AdminSystemExportServiceTest`, `ConfigResourceController` tests, `checkOpenApiDiff`, and `npx -y @fission-ai/openspec@1.14.3 validate export-applications-with-dependencies --strict`. The gates above are decisions and are not closed by a test.
- [ ] 11.6 Run on Linux CI the suites that do not run on Windows (`ComplexResourceServiceTest`, `SkillResourceApiTest`, the large-File test) and record the result.
- [ ] 11.7 Measure the peak heap and the time of one Application with a large resource and of a graph of about 100 resources, and record the result in design "Settings".

## 12. Deferred (not v1; each ships as its own change)

- Production readiness (capacity workstream): a size-bounded read of one body in `ResourceService`, lease renewal, capacity benchmarking.
- Platform-level outbound HTTP for the Runner schema download: allow-list, address checks, body deadline, size bound, proxy decision.
- Project as a dependency type.
- Origin index in `MergedConfigStore` (task 5.8).
- A list of the references that were not followed inside the archive.
- Import of the package, secret re-wrapping, user and app buckets, a structured audit event and metrics, typed references.
