## What shipped

Additive v1 content and metadata routes for skill/project files, per-file conditions, explicit upload MIME, owning-resource
metadata and X-Resource-ETag. Copy/move supports complex file URLs across files/skills/projects, atomic same-resource
rename, destination-first moves, validated source restoration after deletion failures, root permissions and existing events.
Existing plain/whole-resource paths remain. Controller annotations, generated OpenAPI, universal-file documentation,
README limits and org-unit/storage documentation are updated. Windows test cleanup is active and failures are not suppressed.

## Verification

- Passed `./gradlew checkstyleMain checkstyleTest`.
- Passed affected server HTTP classes: UniversalFileApiTest, FileApiTest, ResourceOperationApiTest, SkillResourceApiTest,
  ProjectResourceApiTest, OrgUnitSkillApiTest, SkillPublicationApiTest and ProjectPublicationApiTest.
- Passed affected server unit classes: ComplexResourceServiceTest, FileOperationServiceTest, ResourceDescriptorFactoryTest,
  RouteTemplateTest, FileTargetTest, UniversalFileControllerTest, UniversalFileMetadataControllerTest and FileUtilTest.
- Passed affected storage classes: ResourceServiceTest, LockServiceTest, LockServiceFailureTest, FileUtilTest,
  FileMetadataTest and BlobStorageUploadTest.
- Passed affected OpenAPI classes: EndpointValidatorTest, EndpointMetadataTest and DtoSchemaGeneratorTest.
  Passed `./gradlew replaceSpec -Plint`; schema generation and checkstyle were verified against the latest development updates.
- Passed OpenSpec change/all strict validation and diff whitespace checks. Independent production verification confirmed
  the corrected stale-read, rename-collision and uncertain-marker-commit scenarios, and source compensation.
- Passed the opt-in HTTP latency measurement: OpenJDK 25.0.2, Windows 11, embedded Redis, real JClouds transient backing
  store behind the filesystem wrapper; four files per resource, 1024-byte text/plain target, three warmups and ten samples.
  Cache expiry/sync period/sync delay were 1000ms. Cold setup persisted the exact marker and invalidated cache outside the
  timed request. Timed requests were not retried; filesystem retry fixtures were disabled for this measurement.

Local latency distributions in milliseconds; these are not production-storage results:

| Group | Method | Cache | v1 p50 | v1 p95 | v2 p50 | v2 p95 |
|---|---|---|---:|---:|---:|---:|
| skills | GET | warm | 9.631 | 12.602 | 3.855 | 4.112 |
| skills | GET | cold | 9.539 | 10.695 | 5.905 | 8.406 |
| skills | PUT | warm | 57.450 | 68.311 | 33.295 | 37.613 |
| skills | PUT | cold | 36.217 | 52.840 | 28.566 | 40.861 |
| projects | GET | warm | 3.565 | 3.808 | 2.424 | 3.006 |
| projects | GET | cold | 3.848 | 4.719 | 5.073 | 6.192 |
| projects | PUT | warm | 42.885 | 47.496 | 23.672 | 27.208 |
| projects | PUT | cold | 22.775 | 24.276 | 25.483 | 37.321 |

Operation-count tests verify no ancestor marker walk, no native-map provider listing for v1 metadata, one same-resource
commit and no source/marker lock reentry. Upload receiving is bounded; no second full-file buffering layer was added.

## Drift from design.md

- Current v2 file GET already returns a file ETag and ignores conditions; preserved rather than changing it to the source
  document's aggregate description. Legacy move SHARE requirements and conflict statuses remain scoped to legacy paths.
- A filesystem scan omitted committed names during sync. New metadata pages use captured marker members and logical cursors;
  COW copies marker members rather than raw ghosts. Existing v2 metadata remains unchanged.
- New locked reads use master-consistent helpers; a master lock alone did not prevent a stale replica snapshot. Actual
  copied sizes are revalidated. Removed/replaced copy paths are omitted from fresh-version preparation for valid renames.
- Uncertain marker acknowledgements retain the potentially committed version; existing GC checks the master. Error-only
  compensation restores source files from the committed destination. Filesystem plain deletion verifies actual absence;
  no cloud HEAD or production GC was added.
- Approved Windows folder-separator normalization and bounded test-only filesystem retries were needed. Retained payloads
  are released before cleanup. Windows-invalid timestamp staging tests and the latency run use a real transient backend,
  without changing production staging names, retrying HTTP assertions or removing cleanup. Decisions are updated in place.

## Not done

The full suite is left to CI as requested. No live S3/GCS/Azure or real Redis replica-cluster benchmark was run; stale-read
and lost-ack windows use deterministic fault tests. Persistent infrastructure outages can prevent reads or compensation;
Core retains the committed destination and propagates recovery errors. Local transient latency does not establish cloud
latency or filesystem persistence performance. Existing Windows colon-containing staging paths are not changed by this PR.
