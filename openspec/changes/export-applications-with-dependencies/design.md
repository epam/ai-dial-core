## Context

This document describes the selective export as the code on this branch does it. Figures marked as estimates follow from reading the
code and were not run. Class names are in `server/src/main/java/com/epam/aidial/core/server`.

`POST /v1/admin/export` lets an administrator name Applications and receive one ZIP with those Applications and the
resources they depend on, together with a registry that says what the package holds and what it lacks. The whole-bucket
export is a different endpoint, `POST /v1/admin/system-export`; this change does not alter its output.

### What exists today (before this change)

- **System export.** `AdminSystemExportController` and `AdminSystemExportService` write `.dial-system-export/manifest.json`
  first, then for each blob a sidecar `.dial-system-export/metadata/<path>` followed by the blob at its storage path. It
  streams through a 1 MiB `BoundedPipe`, takes both bucket locks with `tryLockBuckets` (300 s, not renewed) and answers
  409 when they are busy. It has no entity model and no dependency information.
- **Admin gate.** `AdminRoleAuthorizationService.isAdmin` calls `hasExplicitAdminAccess`, which is false when no
  `access.admin.rules` exist and for a per-request key. For an explicit admin `AccessService.getAdminAccess`
  gives ALL access on `public/`, review buckets and public source folders, and nothing on `platform/`.
- **Bucket lock and write-behind.** The bucket lock excludes only writers that take it. `ResourceController` and
  `UploadFileController` take none. While a write is pending, a new resource has an empty stub blob and an update leaves
  stale bytes; `ResourceService` reads Redis first and avoids both.
- **No dependency resolver.** Schema markers are collected by validating the properties with the keywords `dial:file` and
  `dial:resource` (`ApplicationSchemaService.getApplicationResources`). `dial:meta` only carries the property kind
  (`server` or `client`) and collects nothing. The collector is fail-fast: one validation message loses every reference,
  the first missing target throws, a value that is not a resource URL is dropped silently, and an absolute URL in a marked
  field throws because `UrlUtil.decodePath` rethrows `URISyntaxException` as `RuntimeException`. It keeps no pointer and
  no marker kind. Six production flows use it and must keep failing fast: per-request attachments
  (`BaseRequestFunction`), PUT validation (`ResourceController`), chained access (`AccessService`), delete and copy
  (`ApplicationService`) and share (`ShareService`). `acceptableResourceTypes` is declared and never enforced.
- **No record of origin.** After `MergedConfigStore.rebuildMergedConfig` the merged `Config` holds one entity per name and
  no record of whether it came from the file or from a platform blob. `getFileSourcedConfig()`, `getInvalidEntities()` and
  `ResourceService.hasResource` can be composed to answer it.
- **Merged configuration publication.** `config` and `invalidEntities` are two separate volatile writes made under
  `rebuildLock` (the `ApiKeyStore` mutation lock). Settings writes publish `config` alone. An empty invalid map is the shared
  `Map.of()`.
- **Public resources.** An Application or ToolSet in `public/` is never in the merged `Config`: config loading and the
  replica handler look at the `platform` bucket only, and a public resource is reached by URL (`DeploymentService.findDeployment`
  falls back to `ApplicationService.getApplication`). A URL-form read returns the stored blob and does not consult the merged
  configuration (`MergedConfigStoreReplicaUpdateTest` public-bucket cases).
- **Secrets** have three encodings: `@EncryptedField` values (platform CEK), toolset `auth_settings`, and external-service
  secrets (base64 plus AAD). `Application.applicationProperties` is a plain map with no secret mechanism.
- **Request-time schema factory.** `ApplicationSchemaService.SCHEMA_FACTORY` sets no schema loaders. With the library
  defaults it fetches any absolute `$ref` through `URLConnection` (http, https with up to 5 redirects, `file:`, `classpath:`,
  `jar:`, `ftp:`, `jrt:` were all attempted), and a server that never answers blocks the compiling thread with no timeout
  (a scratch experiment against Core's compiled factory, networknt 1.5.2). A factory with `DisallowSchemaLoader`
  refused every scheme and still compiled `quickapp2.json`, which has an absolute `$id` and local `$defs` references.
  The library's refusal message contains the IRI. This exposure is outside this change.
- **Replica path.** `MergedConfigStore.applyReplicaEvent` and the in-place paths have no cross-type duplicate guard. The guard
  exists in `ConfigResourceController` (single write), `ConfigManifestSupport` (apply and validate) and the full rebuild
  (`ConfigPostProcessor.skipOnDuplicate`).
- **Locks.** `LockService.tryLock` leases a key for `LOCK_PERIOD` (300 s), does not renew it, and `unlock` succeeds only for
  the owner.

## Goals / Non-Goals

**Goals**
- Export selected Applications and the dependencies available to the caller as one self-describing package.
- Make every gap visible: what was not included, why, and at which field.
- Apply one secret policy to every JSON resource (Decision 5) and say plainly what it does not cover.
- Reuse existing Core services; change shared code only additively.

**Non-Goals**
- Import, target writes, overwrite policy, rollback.
- Roots other than public or platform Applications; user buckets, `Keys/<x>/` app buckets and `appdata` as content;
  folder expansion of any folder, including `function.source_folder`.
- Project (deferred), and every type or location that Decision 8 does not list.
- Discovering dependencies from schema documents, and from the content of Prompts, Files and Skills. (`application_properties` is scanned; Decision 7.)
- Secret re-wrapping with an export key, audit and metrics, typed references, reconciliation with `origin/rd/*`.
- Changing the request-time schema factory, the behaviour of the six fail-fast collector callers, or the legacy Admin Backend.

### Vocabulary

| Term | Meaning |
|---|---|
| Resource, node | One exported item, identified by Decision 6. A node is a resource in the closure. |
| Closure | The set of nodes reached from the roots, and the edges between them (`ExportClosureWalker.Closure`). |
| Edge, dependency | One reference found in a body, kept with its JSON Pointer. The registry calls it a dependency of the resource that holds it. |
| Origin | Where the effective entity comes from: `file` (the loaded `aidial.config.json`), `platform` (the `platform/` bucket) or `public` (the `public/` bucket). Type and origin are independent. |
| Planning, streaming | Planning builds the closure, with every JSON body read, redacted and kept, before the first response byte. Streaming writes the archive from what planning kept. |
| Deployment | Application, Model, Interceptor and ToolSet: the four types that extend `Deployment` and share its reference fields (`interceptors`, `dependencies`, `icon_url`, `catalog_schema_id`, `catalog_properties`). |
| Registry | `.dial-export/dependency-registry.json`: the graph, identities, origins, files, discovery state, summary and warnings. |
| Technical manifest | `.dial-system-export/manifest.json`, the first entry, in the layout of the system export. |
| Fragment | The archive entry of a resource of origin `file`; it has no blob, so it is not written at a storage path. |
| Registry status | `included` (the reference ends in a resource of the package, with its key), `unsupported`, `external`, `unresolved`. |
| Internal outcome | `MISSING`, `UNSUPPORTED`, `EXTERNAL`, `UNRESOLVED_SHORT_NAME`, `ORIGIN_UNKNOWN`, `SCHEMA_UNAVAILABLE` (`DiscoveredEdge.EdgeStatus`). Decision 12 maps them to registry statuses. |
| Content | What the export does with a body (`ExportTypePolicy.Content`): `EXAMINED` (the four Deployment types), `SCHEMA` (both schema types), `AS_STORED` (Prompt), `STREAMED` (File), `COMPLEX` (Skill). "Definition only" is a separate trait of Model and Interceptor. |

## Decisions

### Decision 1: One endpoint, `POST /v1/admin/export`; the body is `roots` and nothing else

The export is a new route (`RouteTemplate.ADMIN_EXPORT`, `AdminExportController`). The whole-bucket export is
`POST /v1/admin/system-export` and is unchanged. The request body is required: an empty body is 400. The body has one field,
`roots`, a list of `{type: "application", url}` (`ExportRequestParser`, three records). Any other field, an unknown value, a
malformed URL, a folder, a root that is not an Application and a root outside `public/` or `platform/` is 400. The message never
echoes a submitted value, because a value can be a credential: a root is named by its index (`roots[2] must be an Application...`),
`roots` by its name, and a body that is not valid JSON or holds an unknown field gets one fixed message
(`ExportRequestParser`). The whole body is read and validated before the first response byte, and the explicit-admin gate runs before
the body is read.

No request flag controls dependency traversal, file inclusion, the handling of unresolved dependencies or secrets. Traversal is
always on, Files and Skills are always exported, and an unresolved dependency never changes the response (Decision 12).

The family `/v1/admin/` follows from the gate: it is explicit-admin by convention. The same endpoint is meant to export other
kinds of items later, and Application selection is its first mode; that is why the selective export has the plain name and
the bucket export has the specific one.

*Rejected:* a query parameter or a second path to choose a mode (the mode is not a property of a request); request flags
`include` and `onUnresolved` (they need a typed failure body and a retry contract that nothing asked for, see Decision 12).

*Cost:* `ApiOperation` documents the added responses and a description (`description` attribute, new). The OpenAPI generator
also gained a `requestBodyRequired` attribute (default true); no endpoint sets it to false now.

### Decision 2: JSON is read once and written as the redacted bytes that were read; File and Skill are streamed

**Planning** (no byte is written; holds the one slot, under the deadline): authorize the roots, read the configuration pair
(Decision 10), compute the closure. Each stored JSON resource is read once through `ResourceService.getResourceWithMetadata`, which
returns the body with its creation and update times, parsed once, redacted (Decision 5), and the node keeps its identity, the
redacted bytes, their `sha256` and the times. The stored (unredacted) body of a Deployment is held only until its references have been
examined (`ExportClosureWalker.keepIfExamined`). A `file`-origin entity has no stored text: its tree comes from
`ProxyUtil.MAPPER.valueToTree`, it is redacted, and its node keeps bytes and `sha256` the same way, with no times.

**Streaming** (the head is sent): write the technical manifest and the registry, then each node in closure order, one at a time. A JSON
node writes the bytes that planning kept, with its sidecar built from the kept times; nothing is read again. A File or a Skill is
streamed from storage (Decision 14). The verification entry comes last and lists the `sha256` that planning computed.

*Why once.* The registry is built during planning and written first, so it must describe the bytes written later; writing the very
bytes the registry was built from guarantees that without a second read, a second redaction or a digest comparison. A JSON resource that
changes after planning does not fail the export, and the failure window of the stream does not grow with the number of examined
nodes. Reading is Redis-first (`ResourceService`), so a stub or stale blob is never seen by the export.

**Content kinds.** Each node has one kind of content (`ExportNodeContent`, sealed):

| Kind | Types | Planning keeps | Streaming does | Checked by |
|---|---|---|---|---|
| `Json` | Application, ToolSet, Model, Interceptor, Prompt, schemas, and every `file`-origin entity | identity, redacted bytes (a Prompt: the stored bytes), `sha256`, times | writes the kept bytes | nothing: the bytes are the ones the registry describes |
| `StreamedFile` | File | identity, length, etag, content type, times | `getResourceStream`, copied through a fixed buffer | length, etag after the last byte (Decision 14) |
| `Complex` | Skill | identity, pinned version, marker etag, files with relative path and length | each file of the pinned version as a stream | length, marker etag after the last file (Decision 14) |

`ExportArchive` writes the three kinds with a `switch` on the variant. `ExportTypePolicy.contentOf(type)` says which `Content` a type
has; the walker and the registry builder read it and keep no list of types of their own.

**Memory.** `maxRetainedMiB` (32 MiB by default) is enforced on two sums, and either one above it fails the export with `413` before
the first byte (`ExportClosureWalker.retain` and `keep`). The first is the string length of the stored bodies that were read
(`retainedChars`): it is counted when a body is read, because the stored body of a Deployment that is not yet examined is held until its
turn, and it is not reduced when the body is released. The second is the byte length of the redacted bytes (`retainedBytes`): they are
held until the stream has written them. A `file`-origin entity has no stored text and counts in the second sum only. Both sums are
bounded, so the heap for bodies is up to about twice the limit, plus the transient memory of the one body being read, whatever the
number of nodes up to `maxNodes`. The real peak has not been measured for this design and must be (open task).

**Snapshot.** The export takes no bucket lock: the lock excludes only some writers and blocks config rebuilds and admin writes
cluster-wide for up to 300 s. Reads still take the short per-resource lock `ResourceService` takes on a cache miss. The archive
reflects the state of each JSON resource at the moment planning read it, while the stream can run up to the deadline (180 s by
default); a resource that changes meanwhile is exported as planned and does not fail the export. A File or Skill that changes is
recorded (Decision 14). A package can mix versions of related resources and promises no cross-resource snapshot.

*Rejected:* reading each JSON resource twice and writing it only if the digest of its redacted bytes is unchanged (a second read, parse
and redaction per resource, a layer of code for the second source, and a failure after the head whenever a resource changes while the
stream runs); a registry written last (a failure before the end loses it and a reader cannot stream-check it); both bucket locks (cost
without a guarantee).

### Decision 3: Marker collection is a new non-throwing API next to the existing getters

`ApplicationSchemaService` and `CatalogSchemaService` get `collectMarkerReferences`, which never throws for a recoverable problem
and returns `MarkerCollection(references, issues)`. The existing getters (`getFiles`, `getPrompts`, `getSkills`, `getDeployments`,
`getServerFiles`, `ListCollector`) are unchanged and fail fast, because six production flows depend on that.

- **Mechanism.** The three marker keywords (`DialFileKeyword`, `DialResourceKeyKeyword`, `CatalogFileKeyword`) call
  `MarkerRecorder.record` once per marked value. It does nothing unless a recorder is registered in the `CollectorContext`, so
  request-time validation neither allocates nor behaves differently. `MarkerCollection.collect` registers a recorder, validates, and
  turns every violated location and keyword into one issue.
- **Result.** A reference keeps its marker kind (`RESOURCE` or `FILE`), an RFC 6901 pointer relative to the properties and the
  original value. It is not de-duplicated by value: two pointers are two edges even when they name one resource; visits of the same
  kind, pointer and value (several `oneOf` branches on one location) collapse into one. Resolution, authorization, type checks and
  existence checks stay out of the collector; they belong to the export layer.
- **Issues carry no message and no value.** The kinds are `SCHEMA_NOT_FOUND`, `SCHEMA_UNAVAILABLE` and `INSTANCE_INVALID` (the
  properties violate the schema). The collector's message is internal and never reaches the registry or a log. Every issue means that
  references may have been missed; there is no harmless kind, so the registry has no per-issue completeness flag.
- **Completeness.** `discovery.complete` in the registry is true when there is no issue. The two schema kinds are
  shown by the schema edge and not repeated in `discovery.issues`, so a condition is never shown twice.
  A missing schema makes `complete` false for an Application whether or not it has `application_properties`, but for `catalog_schema_id`
  only when the Deployment has `catalog_properties`: `collectMarkerReferences` has nothing to scan without them and returns no issue, so the
  schema edge is `unresolved` and `complete` stays true.
- **Which collections the export uses.** `catalog_properties` of every Deployment is evaluated with
  `CatalogSchemaService.collectMarkerReferences(Deployment, Config)` and its references become edges. For the Application schema the
  export builds the schema live with `ApplicationSchemaService.buildExportSchema`, once per schema id and export, and collects
  `application_properties` against it with `ApplicationSchemaService.collectMarkerReferences(Application, JsonSchema)`. Its references
  become edges at `/application_properties/<path>` (Decision 7). An Application without a schema, or with empty properties, has nothing
  to collect.
- **Errors.** No blanket `RuntimeException` catch. An infrastructure failure is not turned into an issue and ends the export (Decision 12).

*Rejected:* a wrapper around the existing collector (it returns nothing after a validation failure and yields no pointer or kind);
a refactor of the existing collector with an error mode (it widens the change over six callers); a separate walk of schema and
instance (it diverges from request-time semantics: `$ref` siblings, `oneOf`, `anyOf`); an edge status for an invalid Application
(an invalid Application is not a resolution outcome and a fabricated edge would misstate what it references).

*Not verified:* what the schema library collects when another property violates the schema, in a failing `oneOf` branch, and
under `$ref`, `anyOf` and `allOf`. Focused tests exist for `$ref` into `$defs`, `oneOf`, `anyOf`, `allOf`, an invalid property and
a refused `$ref`; a full characterization of the library is deferred.

### Decision 4: Bodies are built from the stored JSON, not from in-memory objects

`Application.getApplication` and the schema-modified copy are lossy or overlaid: the copy constructor drops `responsesEndpoint`,
`maxRetryAttempts`, `dependencies`, `viewerUrl` and `editorUrl`; `ProxyUtil.MAPPER` would emit toolset `auth_settings` ciphertext
because those fields are not `WRITE_ONLY`; an in-memory `Config` entity holds plaintext secrets. The export reads the
stored JSON through `ResourceService` and strips by rule. The only exception is an entity of the file configuration, which has no
stored JSON (Decision 15). Computed fields (`invalid`, `client_secret_hint`, auth statuses) are not in the stored form.

*Rejected:* serializing the in-memory object (it leaks or loses fields).

### Decision 5: One secret policy, by removal; everything else is exported as stored

Core cannot wrap a secret with a passphrase: `KeyManagementService` has only fixed-key `encrypt` and `decrypt`, and ciphertext is
bound to the blob path and CEK, so it is not portable. The export removes. `ExportBodyRedactor` is the only redactor, applies to every
JSON resource once, when it is read, works on the stored JSON, reuses `SecretFieldProcessor.stripEncryptedFields` and the wire-name
constants of `ResourceAuthSettings`, and adds no detector, annotation or classifier. `stripEncryptedFields` now also matches the
`@JsonAlias` spellings of a field, because stored JSON may use any spelling Jackson accepts (it is shared with
`ConfigResourceController`).

| Value | Handling |
|---|---|
| Every `@EncryptedField` value (`Upstream.key`, `secretExtraData`, `UpstreamInterface` keys, `Key.key`), on a Model, Interceptor, ToolSet and Application | **Removed** (`stripEncryptedFields`) |
| `client_secret`, `code_verifier` and `client_secret_hint` of a ToolSet's `auth_settings` and of every `external_services.*.auth_settings` (ToolSet and Application) | **Removed**. The hint is a computed display field. |
| `dial:key`, `key`, `dial:secretExtraData` and `secretExtraData` at `dial:applicationTypeRoutes[*].dial:upstreams[*]` of an application type schema, and in the interfaces of such an upstream | **Removed**. These are the schema-document spellings of the two `@EncryptedField` properties of `Upstream`. A `dial:key` anywhere else is kept; `dial:extraData` is not annotated and is kept. |
| A Prompt | **Exported exactly as stored**: the stored bytes, after the body is checked to be a JSON object |
| File and Skill bytes | **Copied as stored**; never parsed or redacted (Decision 14) |
| `application_properties`, `applicationProperties`, `function.env`, `default_headers`, `defaultHeaders` | **No rule**: out of scope of v1, exported as stored |
| Everything else, including every URL (user information, path, query, fragment), free text, `defaults`, `responses_defaults`, `catalog_properties`, `override_paths` and a string that starts with `ENC[` | **Kept as stored, not inspected** |

*Refusals.* A body that is not valid JSON or not a JSON object, and a type that has no policy, end the export before the first byte
with `500 Failed to prepare the export`. The exception names the resource and the rule and never a value; the walker logs it once
(`Export refused: ...`). Nothing falls back to the stored body. "Fail-closed" means exactly this: no body without a redactor
(503 when none is configured), no fallback, and a refusal of a body that cannot be redacted. It does not mean that a field no rule
names is refused or removed.

*No trace of removal.* The registry and the technical manifest do not list what was removed: no pointer, reason, count or `redacted`
flag. The only consumer of such a list would have been the manifest, and a second walk over each body is avoided.
After an import the removed secrets must be entered again. `client_secret_hint` is computed on read for managers, read-only, never
persisted or accepted from a client; an omitted `client_secret` is preserved from the stored resource on write, never from the hint.
What a create without a secret does has not been verified.

*Limitations, stated and not hidden.* There is no generic detector. A credential in a URL, in free text, in `defaults`,
`catalog_properties`, `override_paths`, in an ordinary field of a kind no row names, or in `application_properties`, `function.env`
or `default_headers` is neither detected nor removed. There is no second check for an annotated field that `stripEncryptedFields`
misses; the completeness of that helper, its tests and a reflection test over the entity classes are what protect such a field.
Tests: `ExportBodyRedactorTest`, `ExportBodyRedactorCoverageTest`, `ExportFileEntitiesRedactionTest`, `ExportBodyRedactorApiTest`,
`ExportPromptApiTest`.

*Rejected:* exporting ciphertext (it works only where path and CEK match); refusing the export when a named container is non-empty
(it kept Quick Apps out); removing `application_properties`, `function.env` and `default_headers` whole (a rule that depends on field
names and not on a Core classification; Core has no mechanism that classifies their content, so they are left to a later decision);
a refusal of any string that starts with `ENC[` (a legitimate value must not be touched); inspecting URLs for credentials.

*Approvals.* The repository records no Maintainer or Security approval of this policy. It is a release gate for the endpoint.

| Item to approve | From |
|---|---|
| The overall policy and its limitation: a credential in any URL part, free text, `defaults`, `catalog_properties`, `override_paths`, `application_properties`, `function.env` or `default_headers` is exported | Security |
| Removal of the schema route `dial:key` family, restricted to the exact path above | Maintainers, Security |
| Exclusion of `client_secret_hint`; removal of `client_secret` and `code_verifier` | Maintainers |
| A Prompt exported exactly as stored | Maintainers, Security |
| Acknowledgement that File bytes, Skill files and Prompt bodies leave unredacted (a separate release gate) | Security |
| Triage of an existing finding: on `development` the application type schema list endpoint returns `dial:applicationTypeRoutes` verbatim and the single-schema endpoint removes only the endpoint-type keywords, so a stored `dial:key` is not removed by those read paths (read, not run) | Security |

### Decision 6: Identity is type, origin, location and path; the key is `<type>:<origin>:<segment>`

`ExportResourceIdentity(type, origin, bucketLocation, path, schemaId)` identifies a resource. The type has one name per kind
(`application`, `toolset`, `model`, `interceptor`, `application-type-schema`, `catalog-schema`, and the URL segment for the rest), so the URL
segment and the blob group of a schema (`schemas` against `app_type_schemas`) are not both in use. A location other than `public/`
and `platform/` is reported as origin `other` without its location, because such a target is never read.

- **Key.** `<type>:<origin>:<segment>`. The segment is the escaped path (it holds the version of a versioned resource), or the first 16
  bytes of the SHA-256 of the `$id` for a schema. Escaping keeps `A-Z a-z 0-9 . _ - /` and writes every other byte as `~` and two hex
  digits, so it is injective, and a type and an origin never hold `:`, so a key splits at its first two colons. A reader treats the key as
  opaque. **The bucket is not part of the key**: for `public/` and `platform/` it follows from the origin and the type binding
  (`ExportTypePolicy.isBound`). A schema reference that finds two valid blobs with one `$id` is `origin-unknown` and never enters the
  closure. A file of a Skill has the key `skill-file:<origin>:<segment>/<escaped relative path>`.
- **Entry name.** The entry name is not derived from the key. A resource with a blob is named by its storage path
  (`ResourceDescriptor.getAbsoluteFilePath()`); a resource of origin `file` by its fragment path (Decision 15).
- **Path safety.** A path segment that is empty, `.` or `..`, also encoded (`%2e%2e`), is refused when a reference is classified
  (`ShortNameResolver.classify`: the reference is `malformed`), and again when a node is prepared (`requireSafeRelativePath`, also for
  every file of a Skill). So a malformed path never fails while the archive is being written.
- **Visited set.** The closure is keyed by the resolved `ResourceDescriptor`, so a platform entity reached by short name and by
  `applications/platform/<name>` is one node (the short-name result carries the same canonical id).

*Not proved:* uniqueness of file-origin keys rests on the map key of `Config` and the separation by type; no dedicated collision test exists.

### Decision 7: Traversal follows the references of every Deployment; discovery reads fixed fields and catalog markers

`ExportClosureWalker` runs a worklist with a visited set. It examines the body of every found Application, Model, Interceptor and ToolSet
with `DeploymentEdgeDiscovery` (`Content.EXAMINED`) and follows every candidate that is one of these four types. All four extend
`Deployment` and share its reference fields, so one discovery serves them; the stored body is bound to the class of its descriptor type
(Application, Model, Interceptor or ToolSet). Schemas (`SCHEMA`), Prompts, Skills and Files are read or listed and their content is never
examined. Following Applications alone would leave a Model's or ToolSet's own `dependencies`, `interceptors` and icon out of a package
that needs them.

- **What is discovered** in every exported Deployment (the roots, and every Deployment reached from them): `interceptors`,
  `dependencies`, `icon_url`, `catalog_schema_id`, and the values of `dial:file` and `dial:resource` markers in `catalog_properties`
  (`CatalogSchemaService.collectMarkerReferences`). An Application alone also has `application_type_schema_id` (the schema is built
  by `buildExportSchema`), the values of the markers of that schema in `application_properties`, and `function.source_folder`. Each reference is its own edge with its JSON Pointer. Discovery continues after a
  malformed or unresolved reference and reports completeness.
- **`application_properties` is scanned, and still exported as stored.** Discovery and redaction are separate. For an Application with
  `application_type_schema_id` and non-empty properties, `DeploymentEdgeDiscovery` builds the schema once per schema id and export
  (`ApplicationSchemaService.buildExportSchema`) and collects the properties against it, as `catalog_properties` is collected against
  its schema. Every value that a `dial:resource` or `dial:file` marker holds is a reference at `/application_properties/<path>` and is
  classified like a marked value of `catalog_properties` (Decisions 8 and 10). Included ToolSets, Models, Interceptors and Applications
  are followed; Prompts, Skills and Files stay leaves. A schema that cannot be found or evaluated is an `unresolved` schema edge and an
  incomplete discovery, never a failure of the export. A violation of the schema by the properties is an `instance-invalid` issue and
  makes `discovery.complete` false. Redaction is unchanged: the container has no secret rule (Decision 5) and leaves as stored.
  The reason for the scan: a Quick App names most of its tools, skills and models only here. On a real Quick App 2.0 (schema
  `quickapps2`) 21 marked references sat in `application_properties`. Without the scan none was found and the registry said
  `complete=true`; with it all 21 are edges, unresolved where the target is not in storage and included where it exists.
- **`icon_url`** is a fixed field. A `files/...` URL is a file reference; an absolute URL is `external`; any other value is
  `unresolved` with value kind `malformed`. Nothing is fetched.
- **`function.source_folder`** is an `unsupported` folder dependency: no folder is followed or read, including the admin-only
  `public/deployments/<id>/` folders that a published code app points at.
- **Interceptor lists.** An `interceptors` entry of any Deployment is a short name of an interceptor and is followed, although Core does not
  evaluate the stored `interceptors` list of an Interceptor at request time: the export carries what the body declares, not what Core runs.
- **Not examined:** Roles (`user_roles`), translators (`interfaces.*.translator`), inline `routes`, schema documents, and the content of
  Files, Skills and Prompts. Unmarked references in properties are not detected.
- **Value classification.** A value is classified first (`ShortNameResolver.classify`): an absolute URL (`UrlUtil.isAbsoluteUrl`, any
  `scheme://`) is `external`; a value that parses as a resource URL carries a descriptor and is classified by Decision 8 without the
  short-name signals; a slash-free value is a short name (Decision 10); anything else, including a blank value, is `malformed`. An edge
  never holds the raw value. It has the pointer, the status and, when known, the target identity or only the value kind (`external-url`,
  `short-name`, `malformed`), because a secret can look like a name.
- **Fixed and marked fields differ on purpose.** A short name in `dependencies` or `interceptors` that nothing holds is `missing`
  (Core rejects it at write). The same name in a marked field is `unresolved-short-name` (Core drops it silently at request time). Both
  are shown as `unresolved` in the registry.
- **Limits.** `maxEdges` bounds the references of one body and of all bodies, and nothing is cut. Only the marker references of
  `application_properties` and `catalog_properties` together are checked as a whole before any is classified; `interceptors` and `dependencies` entries are counted one by one
  (`DeploymentEdgeDiscovery.checkRoom`), so the export fails at the first reference past the limit, and the walker counts the edges of all
  bodies together. `maxDepth` and `maxNodes` fail the export too (Decision 12). Following more types makes closures larger, so the limits
  are reached sooner than with Applications alone.
- **Cost.** Each examined body is parsed twice (a tree for redaction, a bound object for discovery). The scan of `application_properties`
  adds one build of the schema per schema id and export (a Runner download when the schema declares an endpoint) and one storage probe per
  marked short name. A body that does not bind to its
  type fails the export like an invalid Application (Decision 12).

*Rejected:* following only Applications (a package whose Models, Interceptors and ToolSets lose their own dependencies); examining
schemas, Prompts, Skills and Files (they are not Deployments and have no reference fields). The legacy Admin Backend resolver is
evidence of what to avoid: a single pass whose completeness depends on step order, a caller-supplied dependency set copied to every
child, silent nulling of references, and no report of dangling references.

### Decision 8: Scope of types and locations

One table gives, for each type, where it can come from, what the export does with its body, how it is authorized and how it is found.
`ExportTypePolicy` is the only place that holds it; a type with no row is unsupported, so a new `ResourceTypes` value starts
unsupported (`ExportTypePolicyCoverageTest` enforces that every value has a disposition). Each answer is a statement about this export, not a
general policy for the type.

| Type | `file` | `platform/` | `public/` | Content | Authorization | Found by |
|---|---|---|---|---|---|---|
| Application | yes | yes | yes | `EXAMINED`; the only root type | platform: gate; public: `AccessService` READ | roots, `dependencies`, short names, URL |
| ToolSet | yes | yes | yes | `EXAMINED`, redacted; never a root | as above | URL, short name, catalog marker |
| Model, Interceptor | yes | yes | `unsupported` | `EXAMINED`, definition only, redacted | gate | short name, `interceptors` |
| Application type schema, catalog schema | yes | yes | `unsupported` | `SCHEMA`, redacted | gate | `$id` fields |
| Prompt | no | `unsupported` | yes | `AS_STORED` | `AccessService` READ | catalog marker, URL |
| File | no | `unsupported` | yes | `STREAMED` (Decision 14) | `AccessService` READ | catalog marker, `icon_url`, URL |
| Skill | no | `unsupported` | yes | `COMPLEX` (Decision 14) | `AccessService` READ | catalog marker, URL |
| Project, Conversation, Role, ProjectKey and every other value | no | `unsupported` | `unsupported` | not exported | not read | not examined |

- **Only the exact locations `public/` and `platform/` are eligible.** `isPublic()` is equality with `public/`; `public/deployments/`,
  `public/publications/`, review buckets, function folders, user buckets, `Keys/<x>/`, `appdata`, hidden `.name/` folders and every
  folder are `unsupported`. The binding check (`ExportTypePolicy.isBound`, which uses `EntityBucketBinding`) runs before anything is read
  or probed. A Skill is bound to `public` here because `EntityBucketBinding` has no entry for a complex resource.
- **Origin `file`** is supported for Model, Interceptor, ToolSet, Application and both schemas, and for no other type (Decision 15).
- **A Model or Interceptor is exported as its definition only**: the endpoint it names, a tokenizer, model weights and any other
  external artifact are not exported, and the registry warns.
- **A reference to an unsupported target** is an edge with status `unsupported`: nothing is read, no existence check is made and no
  authorization is applied. It never changes the response.
- **ToolSet roots** are not accepted (the root check is `ExportTypePolicy.isRoot`); whether to accept them is open.
- **Why public dependencies are exported** (`public/` Application, ToolSet, Prompt, File, Skill): the package would otherwise lose
  content an Application needs. Each is authorized individually, separately from the root (Decision 9). A public resource is never in the
  merged `Config`, so it cannot clash with a short name; it is reached by URL only.
- **Files and prompts cannot live in `platform/`** (`EntityBucketBinding`); models, interceptors, schemas and the other configuration
  entities cannot live in `public/`. The binding is what makes "bound" a property of the type and the bucket, not of the reference.

### Decision 9: Authorization follows the location of each resource

Roots and dependencies are classified by their decrypted bucket location before anything is read, and the descriptor's type must be allowed
in its bucket (`EntityBucketBinding.isAllowed`) before a read.

| Resource | Check |
|---|---|
| Any caller | the request gate `isAdmin` (`hasExplicitAdminAccess`), before the body is read. A per-request key and an unset `access.admin.rules` give 403. |
| Anything in `platform/` (root, dependency) | the gate alone. `AccessService` is not consulted. |
| A root in `public/` | the gate, then `AccessService` READ (`AdminExportController.canReadPublic`), in `ExportPlanner.plan` before anything is read |
| A dependency in `public/` | `AccessService` READ, in `ExportClosureWalker.resolve`, before it is read. The root's authorization does not extend to it. |
| `file`-origin entities | the gate alone; they are read from the configuration, not from a bucket |

A denied public root or dependency answers 403 before the first byte, and nothing is read or disclosed. The role check of
`DeploymentService.findDeployment` and its URL-fallback access check are not used (the export does not check `user_roles`).

*Why a denial cannot happen for the callers that pass the gate.* The gate is `adminRulesConfigured && hasAdminAccess(context)`.
`getAdminAccess` returns ALL for every resource whose `isPublic()` is true when the same `hasAdminAccess(context)` holds. Both read the
same final `adminRules` and the same request context, the permission rules only add permissions (union), and there is no deny rule and
no per-resource restriction of `public/` for an admin. The walker's 403 is a defensive guard for a future change to the rules.
It is re-opened if `AccessService` gains a deny rule or a per-resource restriction on `public/`, or if a caller other than an explicit
admin can reach the export. Regular-user behaviour was not examined. That a reference parses, resolves or exists is never authorization.

An explicit admin has no `AccessService` rights on user buckets, so user-bucket dependencies are `unsupported` for every caller, and a
strict-looking answer is the same whoever asks.

### Decision 10: Short names resolve with read-only signals (B-strong); no origin index in the store

**Problem.** A short name in `dependencies`, `interceptors` or a marked field can be held by a file entity, a platform blob, or both. After a
rebuild the merged `Config` holds one entity per name and no record of its origin. An exporter that guessed ("a blob exists, so platform")
would export the wrong entity or omit one.

**What the resolver relies on** (tests: `MergedConfigStoreOriginSignalsTest`, `ShortNameResolverTest`):
- The merged `Config` holds the file configuration plus the `platform` bucket for every managed type, never `public/`.
- The four deployment types (Model, Application, Interceptor, ToolSet) share one flat name space. A full rebuild removes the later type in
  the order models, applications, interceptors, toolsets, in `skip` mode records it as skipped, and in `abort` mode (the default) fails the
  rebuild and keeps the previous `Config`. The single-write, apply and validate paths are written to reject a cross-type name with 409 or a
  per-entry failure (code only, no test). The replica path, the in-place updates and the resurrection of a skipped model
  (`resurrectInvalidModels`, which stores a model under its canonical-id key) have no guard, so two maps can hold a name until the next
  rebuild. Such a duplicate is an implementation gap, not a supported state.
- Same type and name in the file and in a platform blob: the blob replaces the file entity in the merged map; the file entity is still
  readable from `getFileSourcedConfig()`. An unparsable blob does not shadow the file entity; a blob that fails decryption is rolled back
  to it; a partial delete of a shadowing blob removes the name until the next rebuild.
- Merged entities are the file `Config`'s own instances (in-place `setName`, shallow map copies), so identity comparison works inside one
  generation. This is pinned by a test for models and applications, and holds by code for interceptors and toolsets.
- The runtime order of `Config.selectDeployment` (applications, models, toolsets, interceptors) differs from the rebuild order, so neither is
  a property of a reference. The export prefers neither.
- Type hints: only `interceptors` fixes the type (an unknown name there is 400 on write). `dependencies` and marked fields give no type
  (`acceptableResourceTypes` is never read), and the type is never inferred from the name.

**Algorithm** (`ShortNameResolver.resolve`; the interceptor hint restricts the scope to the interceptor map, otherwise all four maps):

1. Take the pair once per export (mechanism M1 below).
2. **Effective holders.** Look the name up in the scope. More than one map holding it gives `ORIGIN_UNKNOWN`: no holder is exported and none
   is the target. Exactly one is the candidate. The lookup does not stop at the first map: it collects the holders of every type of the scope
   before deciding, and never picks among them by map order or type priority.
3. **Candidate found.** Signals: S1 the merged entity is the same instance as the file `Config`'s; S2 `ResourceService.hasResource` on the
   platform descriptor of that type and name; S3 an invalid record of the same type and name.
   - S1 true, S2 false: `FILE`. S1 true, S2 true, S3 present: `FILE` (the blob is invalid).
   - S1 true, S2 true, no S3: `ORIGIN_UNKNOWN` (blob written, merged not rebuilt yet).
   - S1 false, S2 true: `PLATFORM`, or `FILE` with the shadowed-entity flag when the file `Config` also holds the name (Decision 15).
   - S1 false, S2 false: `ORIGIN_UNKNOWN` (stale after a delete or a file reload). No file `Config`: `ORIGIN_UNKNOWN`.
4. **Not found.** An S3 record gives `SKIPPED`; otherwise a valid platform blob that the merged map does not hold (lag, or a resurrected
   model) gives `ORIGIN_UNKNOWN`; otherwise `NOT_FOUND`.

Skipped entities of another type are never consulted. They are only listed on the edge (`skipped`: type and source; the reason is free
text that can hold an exception message and is not exported).

**Mechanism M1: one snapshot per export.** `ExportPlanner.plan` locks `ApiKeyStore.getMutationLock()` (the lock that every publication of
`MergedConfigStore` holds), reads `getInvalidEntities()` and then `get()`, and releases the lock before anything is resolved. Because every
publication holds the lock, none can fall between the two reads. The wait includes a whole rebuild, so this runs on the worker thread and
never on the event loop. A sandwich read (`config`, `invalidEntities`, `config` again) is rejected: it cannot prove one generation, because the
two writes are separate and an empty invalid map is the shared `Map.of()`. The file-sourced config belongs to another store that replaces its
`Config` on every poll (default 60 s) while the merged config follows after a 500 ms debounce, so it is read afterwards and is not part of the
guarantee. The merged `Config` is the primary source of origin; the file-sourced config is only signal S1.

**Schemas.** A schema named by `application_type_schema_id` or `catalog_schema_id` is keyed in the merged `Config` by its body `$id`, and its
blob name is arbitrary, so S2 cannot be `hasResource` on a name-derived descriptor. `SchemaReferenceResolver` keeps S1 and S3 and replaces S2 by
a scan: one paged `ResourceService.getFolderMetadata` of the platform folder of the schema type per export and type, reading one body at a time
under the deadline and keeping only its `$id`. The outcomes follow the short-name algorithm: the file instance alone is exported as a
file-origin resource; the file instance next to a valid blob, or a valid blob the merged config does not hold, is `origin-unknown`; a skipped
blob that nothing else holds is `missing` with the skipped blob listed; one valid blob is included; when the merged config holds the blob and the file config holds a schema of that `$id`, the file schema is exported and the blob is shadowed (flagged); more
than one valid blob with one `$id` is `origin-unknown`. The resolver adds no reference: the two fixed fields are the only schema edges. The scan
reads outside the snapshot, so a blob written after it is seen by the scan and not by the merged config, which gives `origin-unknown` (the safe
direction), and it can show a write-behind stub (not verified).

**Behaviour for the exporter** (`DeploymentEdgeDiscovery.fromResolution`, `classifyInterceptor`):

| Situation | Outcome |
|---|---|
| Nothing holds the name, nothing skipped | `missing` for a fixed field, `unresolved-short-name` for a marked field |
| Platform-origin entity | included |
| File-origin entity | included with origin `file`; a platform blob of the same type and name is shadowed and the registry warns (see Decision 15, Precedence) |
| Same name, one effective entity, a skipped entity of another type | the effective entity resolves; the skipped one is listed and not consulted |
| Referenced entity skipped and nothing holds the name | `missing`; the skipped entity is listed by type and source |
| Origin cannot be established, including a valid platform blob absent from the merged map | `origin-unknown`; nothing exported |
| More than one map holds the name | `origin-unknown`; nothing exported |
| An `interceptors` entry | the hint restricts the scope to interceptors, so a Model or Application of that name does not compete |
| `abort` mode with a collision | no merged `Config` is built; the exporter sees the previous one. Not an exporter concern |

**URL form.** A reference that names a type, a bucket and a name is read as the stored blob, whatever the merged configuration or
`getInvalidEntities()` holds under the short name. This is what Core's request-time fallback reads too, so the export carries what the
reference means. It is exported under its own identity, so an Application and a Model of one short name are two resources
(`ExportUrlFormDependencyApiTest`).

**Accepted limitations.** (1) The origin of a name is that of the current effective configuration. A skipped deployment of another type can
still compete for the name after a repair or at a later rebuild (an earlier type claims the name at every rebuild, valid or not, and the write
check does not look at skipped entities), so the meaning of a name can change after an export. This change does not predict that. (2) Around a
file reload, signal S1 fails for file-defined names: with no platform blob the result is `origin-unknown` (and the entity is defined in the
file), with a platform blob it can be `platform`; the window is the debounce plus the rebuild time after each poll, estimated at about 1 % of the
time at the default period (an estimate), and the registry does not mark it. (3) A resurrected model gives `origin-unknown` until the next
rebuild. (4) S1 relies on merged entities being the file `Config`'s own instances, an implementation detail of `rebuildMergedConfig`; a refactor
that clones entities would silently turn `FILE` into `PLATFORM`, so guard tests pin it.

*Alternatives.* **A** the merged `Config` alone: effective entity and type, no origin. **B-weak** blob presence decides origin: wrong for an
unparsable blob, a decrypt failure, a cross-type skip, the window after a file reload and a partial delete. **C** an origin index
(`{origin, shadowsFile}` per entity) published with the `Config` and `invalidEntities` as one object: exact at each publication and one read, but
it changes `MergedConfigStore` (rebuild, partial updates, cascade, resurrection, replica path), a class every config write uses, for roughly 9 to
12 engineering days against about 4 for B-strong (an estimate). C is a documented alternative; reconsider it if `ORIGIN_UNKNOWN` proves
frequent, if instance-identity dependence proves too fragile, or if the per-name `hasResource` reads matter. An accessor on `MergedConfigStore`
adds nothing that the shared lock does not give.

### Decision 11: A remote application type schema is evaluated through Core's existing download, with no network safeguard of its own

An application type schema may declare `dial:applicationTypeSchemaEndpoint`. To know whether the Application's schema can be used, Core makes a
live request to that endpoint at export time through the download it already has (`ApplicationSchemaService.downloadAppSchema`: a bare `GET`, a
5-second request timeout, the optional proxy selector, status 200 and a JSON object required). No TTL cache is added. If the runner is down, times
out or answers with something unusable, the edge is `schema-unavailable`, shown as `unresolved`, and `discovery.complete` is false.

- **Mechanism.** `resolveSchema` is split: a private `buildSchema(String storedSchema, boolean quiet)` builds the schema from its stored document
  and, when it declares the endpoint, from `downloadAppSchema` and `merge`, without reading or writing the shared cache. `resolveSchema` calls it
  with `quiet` false and keeps its cache read, `put`, `remove` and exceptions. `buildExportSchema(URI, Config)` takes the stored schema from the
  snapshot `Config` and calls it with `quiet` true, so a failed download raises an exception that carries no endpoint and no cause. A failure is
  `ApplicationTypeSchemaProcessingException`.
- **What the download does, accepted as it is:** one request per schema id and export (the export memoizes the built schema, so Applications
  that share a schema make one request; a runner that is down is waited for once per schema id); the response is read whole with no size bound; the request timeout bounds the wait for the response
  head and not the body; no redirect policy is set (the JDK default does not follow redirects); no caller header or credential is sent.
- **Log hygiene.** A failed download logs a fixed message and, on a non-200 answer, the status code at DEBUG: no endpoint, response body, header or
  exception message at any level. `downloadAppSchema` and its callers were changed for this; it is a change to shared logging and not a network
  safeguard.
- **Remote `$ref` is a different thing and is refused.** `$ref` resolution is initiated by the schema library's loader while it compiles a document.
  The export builds its own factories (`EXPORT_SCHEMA_FACTORY` in `ApplicationSchemaService` and `CatalogSchemaService`) with the same meta-schema and
  keywords as the request-time factory and `DisallowSchemaLoader` registered. A `$ref` is local when it is fragment-only or resolves to the document's
  own base identifier. This is the one safeguard the feature keeps, because it belongs to the collector the feature adds. A schema with a refused `$ref`
  cannot be evaluated: its edge is `schema-unavailable`. The library's refusal message and ERROR log carry the IRI, so neither is exported or forwarded.

  | `$ref` target | request-time factory | export factory |
  |---|---|---|
  | `http` local server; `http` then 302 then `http` | loaded, redirect followed | refused, no request |
  | `file:`, `classpath:`, `jar:file:` | loaded | refused |
  | `jrt:`, `ftp:` | fetch attempted | refused |
  | any server that never answers | blocked with no timeout | refused |
  | `quickapp2.json` (absolute `$id`, local `$defs`) | compiled | compiled |

  Tests pin the `http` and `file:` rows and the local row; the others rest on scratch experiments. The claim is only that the export factory
  fetches nothing; it is not a statement that the shared request-time path is safe.

*Platform-level concerns, outside this change and not dependencies:* an allow-list of origins for stored endpoints; DNS checks, address pinning,
rebinding protection and filtering of loopback, private, link-local and cloud-metadata destinations; a body deadline, a size bound and a concurrency
bound on downloads; the proxy selector for these calls; and the request-time exposure of the shared factory (remote `$ref`, no loader timeout,
`classpath:` and `file:` loading). Any of them can be adopted later without changing the export, because the export calls the same download.

*Rejected:* a second HTTP client or download implementation (it would duplicate the timeouts, proxy handling and error wrapping); reading or
populating the shared schema cache (the export must never see a stale schema or replace the one request processing uses); an allow-list and
destination validation for this feature.

### Decision 12: A dependency problem never changes the response; only a condition that prevents a safe archive is an error

There is no strict mode, no request flag and no 422. When a reference is unsupported, external, missing, an unresolved short name, of unknown
origin or hidden by an unavailable schema, or when discovery is incomplete, the response is 200, the archive holds every root and every dependency
that can be safely resolved, authorized, read and written, and the registry lists each finding. A 200 is therefore not a statement that the package
is complete: `summary.edges` counts the findings, `discovery.complete` and `discovery.issues` show incomplete discovery, and `warnings` say what was
not examined.

**Registry statuses.** `ExportRegistryBuilder.publicStatus` maps the internal outcomes:

| Registry status | Internal outcomes | Meaning |
|---|---|---|
| `included` | (resolved) | the reference ends in a resource of the package; the dependency has its `key` |
| `unsupported` | `UNSUPPORTED` | the target is identified and not exported (Decision 8) |
| `external` | `EXTERNAL` | an absolute URL outside Core |
| `unresolved` | `MISSING`, `UNRESOLVED_SHORT_NAME`, `ORIGIN_UNKNOWN`, `SCHEMA_UNAVAILABLE` | nothing holds the name, the target is missing or skipped, the origin is unknown or the name has several holders, or the schema could not be evaluated so references may be hidden |

The registry has no field that says why a reference is `unresolved`, no `inaccessible` status and no `capped` status. `summary.edges` has exactly the
keys `unsupported`, `external` and `unresolved`, computed from the dependency records so it cannot disagree with them.

**Warnings** (`ExportRegistryBuilder.warningsFor`): a Model or Interceptor is exported as its definition only; a Prompt body is exported as
stored; the content of a File and the files of a Skill are copied as stored and not examined; only fixed and marked fields were examined
(added only when an `unresolved-short-name` exists); a file entity shadows a platform entity. No warning says that a Deployment body was
not examined, because none is skipped. Nothing is added to every export. The shadowing warning reads "A file entity shadows a platform entity
of the same type and name; the platform entity is not exported." It does not say which of the two the running system serves (Decision 15,
Precedence). (The builder also has a text for a platform entity that shadows a file entity; the resolvers never produce that case.)

**Diagnostics expose no raw value.** No dependency, issue or response body contains a raw reference value, a validation message, an exception
message, a library message or an IRI (the `$id` of a schema is its `schemaId` and the one IRI the registry holds), and the schema download of an export logs no endpoint, header, body or exception message. A value is described by its
identity or its kind only.

**HTTP statuses.** A failure before the head gets a status; a failure after the head resets the connection (`ZipResponseStreamer`) and leaves an archive
without the central directory, never a complete ZIP.

| Status | Cause |
|---|---|
| 200 | the archive is streamed (`application/zip`, `Content-Disposition` with a UTC timestamp) |
| 400 | the body is missing, has a field other than `roots`, or is not a valid selection; more than `maxRoots` roots |
| 403 | the caller is not an explicit admin, or a `public/` root or dependency is not readable |
| 404 | a selected Application does not exist |
| 409 | another export holds the one cluster-wide slot |
| 413 | a limit of the `adminExport` settings is exceeded (resources, references, depth, size of one resource or total size); nothing is exported |
| 500 | `Failed to prepare the export`: a body that cannot be parsed, bound or redacted, a duplicate entry name, an unsafe path, an unexpected infrastructure failure, a file-origin entity that cannot be read. `Failed to export the Applications`: the streamer failed before the head was written (`AdminExportController`) |
| 503 | no redaction policy is configured, so no body may leave the system; answered after the request checks and the plan (production always configures one) |
| 504 | the deadline passed before the first byte |

**Order of the statuses.** The admin gate answers 403 before the body is read; then the body is parsed (400). `ExportSlot.acquire` runs
before the plan, so a held slot answers 409 before a 404 or a public 403 can be found. Then the plan decides: 403 (denied public resource),
404 (missing root), 413, 504 or 500. A missing redactor (503) is checked last, after the plan.

After the head: a File that disappeared, ended early or is longer than its
length, a read or write failure, a client that is too slow for the stall timeout, and the deadline all reset the connection and release the slot.
A selected root that does not exist is the only missing-resource case that is a status; a missing dependency is a finding.

**A stored body that is not valid JSON** ends the export in a fixed 500, because it cannot be redacted. **Valid JSON that does not bind to its type** (such as an object where
`endpoint` is a string) ends the export in 500 for a selected Application, but not for a dependency: a Model, an Interceptor, a ToolSet or an Application that is only a dependency
is exported as redacted JSON, its references are not examined, and the registry lists an `instance-invalid` issue at the pointer `""` that hides references, so a resource that
a later field change made unbindable cannot fail the export of an Application that merely names it. The walker logs only the resource URL at WARN. The body is bound with `ProxyUtil.MAPPER` directly and not with `ProxyUtil.convertToObject`,
because that shared method logs the Jackson exception at WARN: Jackson's source excerpt is redacted by its default settings, but the message
names a field and a path and, for a scalar of the wrong type, quotes that scalar (checked against Jackson 2.18). The shared method is not changed.

*Rejected:* a strict mode with `onUnresolved: fail`, a typed 422 body and a `retryable` flag per item (a contract surface with a mapping that no API owner approved);
an edge status for an invalid Application; `include` flags that change statuses.

### Decision 13: The registry and the archive follow the layout of the system export

Two layouts for one kind of archive would force every reader to know both, so the export takes the physical layout of the system export,
whose helpers it reuses.

Entries, in this order (`ExportArchive.writeTo`):

1. `.dial-system-export/manifest.json`, the technical manifest: `formatVersion` **2**, `coreVersion`, `createdAt`, `mode: "selective"`, nothing else. Version 1 is
   the system export, so a reader of that export that accepts only 1 refuses a selective archive instead of restoring redacted bodies over real ones.
2. `.dial-export/dependency-registry.json`. It sits outside `.dial-system-export/` because the system-export reader rejects any other entry under that prefix.
3. For each node in closure order: for a resource with a blob, its sidecar `.dial-system-export/metadata/<path>` immediately followed by its bytes at its real
   storage path, for example `public/applications/folder/app__1.0.0`, `platform/toolsets/tools`, `public/files/icons/a.png`; for a resource of origin `file`, its
   fragment (Decision 15), which has no sidecar.
4. `.dial-export/verification.json`: the SHA-256 of every entry before it (for a JSON resource, the digest computed when it was read); a File or file of a Skill also lists its `length` and `etag`; `changedDuringExport` lists
   the changes of Decision 14.

The same input gives the same entries in the same order; entries carry a fixed time (1980-01-01), and only `createdAt` differs between two exports of one state.
The registry cannot be streamed after the bytes it describes, so it is written first and the verification entry last.

**Sidecar.** It has the format of the system export (`AdminSystemExportService.BlobMetadata`, widened from `private` to package-private and reused). It describes the
bytes that are written: a JSON resource has `contentType` `application/json`, no `contentEncoding` (the stored blob is gzip, the entry is not) and no `userMetadata`
(it holds the author), plus the dates of the stored resource when known. A File and each Skill file carry their stored content type. Only content that is never
redacted can be "original bytes", and that is binary content.

**Registry shape** (`ExportRegistry`, `ExportRegistryBuilder`): `kind` `dial-export-dependency-registry`, its own `formatVersion` (1), `roots` (keys, in request
order), `resources` (a JSON object by key: identity fields without a nested `identity`, `role` `selected` or `dependency`, `entryName`, `sourcePointer` for origin
`file`, `dependencies`, `requiredByRoots`), `files`, `discovery` (`complete`, `issues` by resource key), `summary` (`resources`, `files`, `warnings`, `edges`) and `warnings`.

- **One entry per reference.** `dependencies` has one entry per reference, in discovery order, also when several lead to one resource. An `included` entry has `key` and
  `kind`; the others keep the status, pointer, value kind, target identity and skipped entities, and have no key. An entry never names a resource that is not in the package.
- **`requiredByRoots`** lists the roots from which a resource is reached through `included` dependencies, in request order; a root is not listed for itself. It is computed from
  the strongly connected components of the graph (iterative Tarjan) with one bit set per root, so cycles end and no traversal is made per root: time `O((V + E) * R / 64)`.
- **`files`** lists streamed entries: `key`, `owner` (the key of the Skill, for a Skill file), `path`, `entryName`. A File and a Skill are not records of `resources`.
- **The key of a resource** is Decision 6; the registry, the sidecar and the entry carry no redaction information (Decision 5).

**Type to storage:**

| Type | Group (blob path) | Entry name |
|---|---|---|
| Application | `applications` | `<location>applications/<path>` |
| ToolSet | `toolsets` | `<location>toolsets/<path>` |
| Model, Interceptor | `models`, `interceptors` | `platform/models/<name>`, `platform/interceptors/<name>` |
| Application type schema | `app_type_schemas` (URL segment `schemas`) | the blob's own path; identity is the `$id` |
| Catalog schema | `catalog_schemas` | `platform/catalog_schemas/<name>` |
| Prompt | `prompts` | `public/prompts/<path>` |
| File | `files` | `public/files/<path>` |
| Skill | `skills` (marker `.dial-resource`, files under `v/<id>/`) | `public/skills/<path>/<relative path>` |
| Any type of origin `file` | none | `.dial-export/config/<type>/<segment>.json` |

Public folder rules (`rules`) and `complex_resource_refs` are not a dependency of an Application and are not exported.

Import is out of scope, so no importer is changed; `formatVersion` 2 and the registry are the only contract for a future one.

### Decision 14: Files and Skills are streamed as stored, with length and etag checks

The content of a File and the files of a Skill are copied byte for byte and never parsed, redacted, converted or examined for references. Memory holds a 32 KiB copy
buffer and the 1 MiB pipe, whatever the file size.

- **File.** Planning reads `getResourceMetadata` (length, etag, content type, times) and no content. Streaming opens `getResourceStream`, writes the sidecar, copies through the
  fixed buffer computing the `sha256`, and checks that exactly the length at open was copied. The etag is read again after the last byte. An etag that is not the one read in planning, or that
  changed during the copy, is listed in `changedDuringExport` with `etagBefore` and `etagAfter`; a newer complete version is written whole and listed, not failed. A File that is gone, a stream
  that ends early or is longer than its length, or a failed read fails the export.
- **Skill.** The version is pinned from the marker read in planning (`ComplexResourceService.getMarker`: current version, aggregate etag, the files with size and etag). The files of
  `v/<versionId>/` are immutable, so a version cannot be torn. Streaming copies each file of the pinned version in the order of its relative path
  (`ComplexResourceService.getVersionFileStream`, new, additive) below the logical folder `public/skills/<path>/`, without the marker and the version folder. A file that is gone or has another
  length fails the export. The marker is read again after the last file; a different aggregate etag, or a Skill that is gone, is listed in `changedDuringExport` and does not fail the export,
  because the version copied is whole. A Skill with no current version, no files or no marker is `missing`. `ComplexResourceService.downloadArchive` buffers a whole archive and is not used.
  The Skill counts as one node; the number of its files is bounded by the existing `ComplexResourceService` limits.
- **Etag evidence.** For a File, the etag in Redis write-behind and the `etag` user metadata of the synced blob must be the same value, or an etag change between the reads would be an artifact of
  the sync. An integration test against a real embedded Redis and a filesystem blob store showed the same value before the sync, with Redis ahead of the blob, after the sync, for a file above `maxSizeToCache` and for a JSON resource read after eviction. That test pinned the behaviour of `ResourceService`, which this change does not modify, and was dropped from the branch. Limits of that evidence: the filesystem provider only, one process, the sync run with a 1 ms delay, no multi-pod or Redis-failover case.
- **Torn reads.** An object store returns one version per read. `BlobStorage` exposes no version id, and the filesystem provider used in development can tear a read when a file is overwritten; the
  length check and the final etag catch most of it and the rest is documented.
- **No byte bound.** Files have no byte bound of their own beyond the upload limit and the deadline.
- **Unredacted.** These bytes leave as stored; Security's acknowledgement is a release gate (Decision 5).

*Rejected:* hashing binary content in planning (a second read of a stream); converting binary content to text; sniffing the content type; a redactor for binary content.

### Decision 15: An entity of the file configuration is exported as a fragment, never as a blob

An entity that exists only in the file configuration (Model, Interceptor, ToolSet, Application and both schemas) has no blob. Leaving it out would make an Application that depends on a
configured model unusable as a unit, and the source of an entity is not a reason to leave content out.

- **Representation.** `ProxyUtil.MAPPER.valueToTree(entity)`, the mapper of the config API (as `FileConfigController` uses for an entity of the file configuration): the tree of the entity with the spelling of its own annotations
  (camel case for a Model and Interceptor, snake case for a ToolSet and Application). It drops the `@EncryptedField` values (`WRITE_ONLY`), so an annotated secret never enters the tree; the redactor stays the
  rule and the check. A schema is held by `Config` as the text of its document; that text is the configured form and its one parse is the tree. A hand-written projection is rejected (a second description of every
  entity). The tree still holds the plaintext `auth_settings` secrets, which are not annotated, until the redactor removes them, so it is never logged, retained or written.
- **Fidelity.** For each of Model, Interceptor, ToolSet and Application the redacted bytes of two productions are equal, the tree parses back into an entity with the same tree,
  and a plaintext upstream key and `client_secret` are in the tree and in no redacted byte. The written form holds every field of the entity with defaults materialized (`maxRetryAttempts`,
  empty `defaults`) and explicit nulls; `author`, `createdAt`, `updatedAt` and `reference` are null, because a file entity has none.
- **Naming.** The node has a stand-in descriptor with the bucket location `file/` (`ExportFileEntities.descriptorOf`), so its key is `<type>:file:<name>`. A schema has no usable name, so its
  stand-in name is the digest of its `$id`, which is also its key segment. The entry is `.dial-export/config/<type>/<segment>.json` with the redacted tree of that one entity; the registry records
  `origin: file` and an RFC 6901 `sourcePointer` (`/models/<name>`, `/applications/<name>`, `/toolsets/<name>`, `/interceptors/<name>`, `/applicationTypeSchemas/<digest>`,
  `/catalogSchemas/<digest>`). The whole `aidial.config.json` is never exported, and the `$id` of a schema stays out of the archive.
- **Precedence.** When a file entity and a platform blob of a Model, Interceptor, ToolSet, Application or schema share a type and name, the export takes the file entity
  (`ShortNameResolver.resolveHeld`, `SchemaReferenceResolver`: origin `file`, shadowing flagged). This is a deliberate, earlier decision, specified once in the
  requirement "A file entity wins over a platform blob of the same name". At runtime the merged configuration serves the blob and is not changed, so the
  package carries a file entity with origin `file` where the running system serves the blob, and an import of the package can differ from the running system.
  The file configuration is the operator's declared source, which is why it wins. The registry warns of the shadowing; the warning does not say which entity
  the running system serves.
- **One read of the configuration.** Planning converts the entity, redacts it and keeps the bytes and their `sha256`; the stream writes those bytes and does not read `getFileSourcedConfig()` again, so a reload during the stream changes nothing in the archive.
- **No snapshot API, no lock, no copy.** A `MergedConfigStore.snapshot()`, a bucket lock or a copy of the configuration made for one admin endpoint would change how the configuration is published for every
  request. The export keeps mechanism M1 for the merged pair and adds nothing. *Provided:* discovery and the archive use the same bytes, and an unreadable entity ends the export. *Not provided:* the merged
  and the file-sourced configuration are updated independently and read one after the other, so they can disagree; no atomic cross-store view is claimed, and a name whose signals disagree stays
  `origin-unknown` and is not exported.

### Decision 16: Duplicate descriptors are not merged

No mechanism merges duplicates; the existing code already gives what the export needs.

- A resource reached by several paths or reference forms is **one node**: the walker keeps one map keyed by `ResourceDescriptor` (equality covers type, name, parent folders, bucket name, bucket location and the
  folder flag); a later reference to a known node is an `included` edge with no second read. A root named twice is one root.
- **Every reference stays its own dependency**: two references to one resource are two entries with one key and different pointers. Nothing is collapsed.
- **A duplicate archive entry name fails the export** (`ExportArchive.prepare`, `IllegalStateException`), which also rejects a duplicate key because key and entry name carry the same information.
- Nothing is merged or overwritten silently, and no code path picks one of two conflicting descriptors.

*Assumption, not a guarantee:* two unequal descriptors with one key were not shown to arise. `ResourceDescriptorFactory.fromDecoded` splits the path, so name and parent folders are canonical, and
`ExportTypePolicy.isBound` fixes the bucket. This was not proved for every producer of a descriptor; if such descriptors reach `prepare`, the export fails.

*Rejected:* a registry that merges by key (it adds rules without a demonstrated case); first-wins or last-wins (a silent loss); a change of the key format. Optional hardening tests not written: two unequal
descriptors with one key through the walker, equal descriptors for equivalent percent-encoded forms.

## Per-request cost

The export adds no route that other endpoints share. The work added on another endpoint's request path is one map lookup and a type test per marked value in
the three marker keywords (nothing when no recorder is registered) and one extra method call when the request-time schema is resolved. Existing behaviour that
changes is listed under "Impact on existing capabilities".

**Per selective export** (`O(roots + edges)` reads), for each resource:

| Resource | Planning | Streaming |
|---|---|---|
| Application, ToolSet, Model, Interceptor | one `getResourceWithMetadata`, one parse for the tree (redaction), one bind to the entity class (discovery), one serialization, one `sha256` | one write of the kept bytes |
| Prompt, schema | one read, one parse (a Prompt: only checked to be an object), one serialization (a Prompt: none), one `sha256` | one write of the kept bytes |
| File-origin entity | one `valueToTree` (a schema: one parse), redaction, serialization, `sha256`; a bind for the four Deployment types | one write of the kept bytes |
| File | one `getResourceMetadata` | one `getResourceStream` copied once, one `getResourceMetadata` for the etag |
| Skill | one marker read | one stream per file, one marker read |

Also per export: one `hasResource` per resolved short name (up to four more when a name is absent from the merged map, because each candidate type is probed); one paged listing of the platform blobs of each schema
type that a schema reference touches, with one body read per blob; one runner request per schema id whose schema declares an endpoint (the built schema is memoized for the export, with no TTL cache); one wait for the publication lock at
the start. Only one export runs in the cluster at a time.

**Memory.** Planning keeps the redacted bytes of every JSON node until the stream has written them; a Deployment's unredacted body is held only until its
references are examined. `maxRetainedMiB` (32 MiB by default) bounds two sums, the string length of the stored bodies read and the byte length of the redacted
bytes held (Decision 2), so the bodies of an export take up to about twice that, whatever the number of nodes. Streaming adds a 32 KiB copy buffer and the 1 MiB
pipe. The stored body is read in full before `maxResourceMiB` can reject it, so these limits do not bound one oversized read; the largest read is bounded by
`resources.maxSize` (64 MiB by default). The peak of this design (about twice the limit plus the largest transient) was not measured and must be (open task).

**Measured** (transient factor `c` = highest sampled peak minus the retained size, divided by the body size; production redactor; one Application with a large body; Java 21, Windows 11, a 512 MiB test JVM):

| Shape of the body | Size | `c` |
|---|---|---|
| One string | 2 / 8 / 16 MiB | 3.7 / 4.1 / 4.1 |
| Many small strings | 2 / 8 / 16 / 32 MiB | 9.7 / 8.6 / 8.3 / 8.1 |

A body of many small strings costs about twice the working set of one string, because the parsed tree holds one node per value. These runs measured one read, parse and redaction of one body, which planning still does; they did not
measure the retained bytes of a whole graph. **Not measured:** a body of 64 MiB (the embedded Redis of the test base timed out at 64 MiB, a
harness limit; by extrapolation (an estimate) one such body would peak at about 9 times its size, about 580 MiB), the native memory of the deflater, networked storage, concurrent exports, a real schema-validated body. By
reading the code, reading allocates about five times the stored size (compressed bytes, decompressed bytes with buffer growth, the string) before the size check, so one refused body of 64 MiB can still need about 320 MiB for a moment (an estimate).
**Finding:** Jackson limits one JSON string value to 20,000,000 characters by default, which Core does not change, so `ProxyUtil.convertToObject` cannot bind an Application with a longer string whatever the size limit; the export
fails with the generic 500. A body near 64 MiB can therefore only be many strings. These figures are not performance guarantees.

**Short-name probes** (call counts exact and asserted; latencies local observations on one machine with the embedded Redis and the filesystem store, nothing asserted):

| Case | `hasResource` calls |
|---|---|
| A name that the merged config holds, platform or file | 1 per occurrence |
| A name nothing holds | 4 (one per candidate type); 1 with the interceptor hint |
| A skipped name | 0 |
| A name that occurs again | 1 more each time; the resolver keeps no memo |

For 100 distinct names: written through the API (Redis holds them) about 106 ms of planning, 36 % in `hasResource` (0.36 to 0.38 ms each); blob only 151 to 163 ms (first run 424 ms); absent 290 to 382 ms, 85 % in
`hasResource` (400 calls). Absent names are the most expensive case, and probes are sequential. The latency over a networked Redis and a remote blob store was not measured, nor was the latency of the schema scan (only
its call count, one listing per schema type per export, is asserted by `SchemaReferenceResolverTest`).

## Risks / Trade-offs

- **A 200 can be an incomplete package.** `unresolved-short-name`, `unsupported` and `external` do not change the response. The registry counts them and warns, and a short name in a marked field that
  nothing holds is expected to be common (Quick Apps hold short names in marked deployment fields). Whether a mode that fails on findings is wanted later is a product decision.
- **Cost of scanning `application_properties`.** Each export builds the schema of every Application type that it meets, one Runner request per schema id, and probes storage for every marked short name. References to resources that are not in storage are listed as `unresolved`, and a Quick App can have many (Decision 7).
- **Plaintext credentials and unannotated fields.** No Core mechanism covers `application_properties`, `function.env`, `default_headers`, URLs or free text; they leave as stored (Decision 5). Security's approval of the
  policy and of that limitation is required before release.
- **Unredacted binary and Prompt content.** File bytes, Skill files and Prompt bodies leave as stored (Decisions 5 and 14); Security's acknowledgement is a release gate.
- **File-config skew (accepted).** Around a file reload a reference to a file-defined name can be `origin-unknown` (or classified `platform` when a blob holds the name) (Decision 10).
- **Skipped entities are not considered (accepted).** The meaning of a name can change after an export without a write to the exported entity (Decision 10).
- **Dependence on instance sharing.** Signal S1 is valid only while merged entities are the file `Config`'s own instances; guard tests pin it for every short-name category. Option C would remove the dependence and change a
  store every config write uses.
- **Pod-local configuration view.** The merged configuration is per pod while storage is cluster-shared, so an export on one pod can see a configuration that predates a write made through another pod. B-strong reports a
  disagreement as `origin-unknown`. Multi-pod timing tests are excluded.
- **Replica-path duplicates.** The replica path has no cross-type duplicate guard, so two maps can hold a name until the next rebuild; the resolver reports `origin-unknown`. A writer checks against its own pod's live `Config`, so a
  write whose check ran before the previous write's replica event reached that pod can store a conflicting blob (an estimate).
- **No snapshot.** A package can mix versions of related resources (Decision 2).
- **The archive reflects plan-time state.** The stream can run up to the deadline (180 s by default), and a JSON resource that changes meanwhile is exported as planning read it; the export does not fail (Decision 2).
- **Peak memory holds all redacted JSON bytes.** Up to `maxRetainedMiB` of redacted bytes stay in memory for the whole stream, and the stored bodies read are
  counted against the same limit, so the heap for bodies is up to about twice the limit plus the transient memory of the largest read; the real peak is not measured
  for this design (open task).
- **A package can differ from the running system.** A file entity wins over a platform blob of the same name while the merged configuration serves the blob (Decision 15).
- **Larger closures.** Following Models, Interceptors and ToolSets adds nodes and depth, so `maxNodes` and `maxDepth` are reached sooner; they stay unapproved defaults.
- **Live runner calls.** Every export that touches an Application with a remote schema sends a request to a URL stored in configuration, once per schema id, through the existing download with no added safeguard. The shared
  request-time factory still fetches remote `$ref`; the export factory does not (Decision 11).
- **A stored body that is not valid JSON, or a selected Application that cannot be bound,** ends the whole export in 500, the answer does not say which resource (Decision 12). A dependency that cannot be bound is exported and reported as an incomplete discovery.
- **Manifest first, checksums last.** A reader must read to the end of the archive to verify it; a truncated archive has no central directory anyway.
- **Authorization gap.** User-bucket dependencies are `unsupported` for every caller; roots are limited to `public` and `platform` for the same reason.
- **Limits and the lease.** The limits are unapproved defaults; the slot lease is 300 s and not renewed, so after a pod failure the slot is unavailable until it expires (Deferred workstreams).
- **Preview.** The endpoint is marked preview, like the other admin endpoints.

## Migration Plan

Additive. `POST /v1/admin/export` is a new route; `POST /v1/admin/system-export` is unchanged. The `adminExport` settings have code defaults and rows in `aidial.settings.json` and the README; no setting is required.
`AdminSystemExportService.BlobMetadata` is widened to package-private and the streaming plumbing of both export controllers is shared (`ZipResponseStreamer`) with no change of behaviour or output. Rollback is removing
the route; no data is written. A reader of the system export that accepts only `formatVersion` 1 refuses a selective archive.

## Open Questions

| # | Question | Owner | Recommendation |
|---|---|---|---|
| 1 | Approval of the secret policy and its limitation, and the acknowledgement of unredacted File, Skill and Prompt content (Decision 5) | Maintainers, Security | None recorded; the endpoint is not released until both are given |
| 2 | Sign-off that explicit admin only may export, including public dependencies and the selection of a public root | Security | As implemented |
| 3 | Sign-off that the package promises no snapshot and reflects plan-time state while the stream runs (Decision 2), and that a 200 can be incomplete (Decision 12) | Product | As implemented |
| 4 | Accept ToolSet roots, public or platform? (the request accepts Application roots only) | Product | Keep Application roots only until asked; accepting ToolSet roots changes the request contract |
| 5 | List `user_roles`, `interfaces.*.translator` and inline `routes` as `unsupported` edges, so the package shows what it lacks? | API owner | Not now; today they are not examined and no warning names them |
| 9 | Re-measure the peak heap with all redacted JSON bytes held up to `maxRetainedMiB` (Decision 2) | Maintainers | Measure before the limit values are approved |
| 7 | Report the request-time exposure of the shared schema factory (remote `$ref`, no loader timeout, `classpath:` and `file:` loading) | Maintainers, Security | Report it as its own issue; this change does not touch it |
| 8 | Is an audit event required before release? | Security | Out of scope unless Security requires it, in which case a gate is added |

## Deferred workstreams

Not part of this change's requirements. Nothing here is an accepted architectural decision.

### Capacity, limits and operational readiness

A separate production-readiness workstream: the values of every limit, timeout and the number of slots, supported by measurements or a documented capacity analysis. Candidate values are unapproved.
Intent of the deferred requirements: bound the retained content by a budget and report a dependency over budget; fail a request whose selected root is over the budget, whatever the mode; limit concurrent exports
cluster-wide with the lock service (default one, 409 when full, released on every exit); make the lease outlive the deadline (Core refuses to start otherwise); abort an export that passes its deadline (a status before the
head, a reset after). The limits that Core already had stay in force and are not part of the deferral: `resources.maxSize`, the Jackson string limit, `adminExport.stallTimeout` of the bounded pipe, and the bounded-memory streaming.

Code for this exists with unapproved defaults: `adminExport` settings `maxRoots` 100 (400), `maxNodes` 500, `maxDepth` 10, `maxEdges` 10000, `maxResourceMiB` 4, `maxRetainedMiB` 32 (on the stored bodies read and, separately, on the redacted bytes held; all 413), `deadline` 180000 ms (504),
`stallTimeout` 60000 ms; one cluster-wide slot `export-selection` (`AdminExportController.SELECTION_SLOT`, 409 when busy); a thread interrupt when the deadline passes (`DeadlineInterrupt`) and a timer that aborts
the stream; `AdminExportSettings.validate` requires `deadline + stallTimeout` below the 300 s lock period. A limit that is exceeded fails the whole export and no `capped` status exists, so a registry representation of a root over
budget is not decided. Not implemented: cancellation through `response.closeHandler` (a client that disconnects fails the writer at its next write), `N` slots, a size-limited read of one body (it needs a shared path in
`ResourceService`), and any `Retry-After`.

### Approvals and limits for binary content

The behaviour of File and Skill content (one-pass copy through a fixed buffer, length and etag checks, `changedDuringExport`, the Skill folder structure, unredacted
bytes) is specified in the requirements of this change and implemented (Decision 14). What is deferred is the approval workstream (Security's acknowledgement of
unredacted bytes, a release gate) and the limits workstream (a byte bound for Files, approved values, capacity measurements).

### Full characterization of the schema library

The focused correctness tests for `$ref` into `$defs`, `oneOf`, `anyOf`, `allOf`, an invalid property and a refused `$ref` stay; a test of every JSON Schema combination is not required. No code beyond those tests exists.

### Platform-level outbound-HTTP concerns

Listed in Decision 11. Not started.

### Other deferred items

Option C, an origin index in `MergedConfigStore` (Decision 10); tests of the write-path duplicate guards (single write, apply, validate and migrate have none); audit and metrics; secret re-wrapping with an export
key; typed references and enforcement of `acceptableResourceTypes`; reconciliation with `origin/rd/*`; and a secret rule for `application_properties`, `function.env` and `default_headers`.

## Impact on existing capabilities

**Shared code that changes** (additive unless stated):

| Area | Change | Effect on other callers |
|---|---|---|
| `ApplicationSchemaService` | `resolveSchema` split into `resolveSchema` and a private `buildSchema(String, boolean quiet)`; new `buildExportSchema`, `collectMarkerReferences`, `EXPORT_SCHEMA_FACTORY`; the schema download takes a `quiet` flag, true only for the export: it then logs no endpoint, body, header or exception message, raises an exception without endpoint and cause, and restores and rethrows an interrupt | request processing passes `quiet` false and behaves exactly as before: its log text, its exception (message and cause), its wrapping of an interrupt and the status of the inner `HttpException` are unchanged and pinned by `ApplicationSchemaServiceTest` |
| `CatalogSchemaService` | new `collectMarkerReferences`, `EXPORT_SCHEMA_FACTORY` | none |
| `DialFileKeyword`, `DialResourceKeyKeyword`, `CatalogFileKeyword` | one `MarkerRecorder.record` call each | a map lookup and a type test per marked value; nothing when no recorder is registered |
| `SecretFieldProcessor.applyStrip` (behind `stripEncryptedFields`) | also finds a field under its `@JsonAlias` spellings | `ConfigResourceController.projectInvalidItem`, the only other caller, now also removes an aliased spelling of an encrypted field from the payload of an invalid stored entity (a change of an existing read path: it only removes more, and `SecretFieldProcessorTest` covers the aliases) |
| `ComplexResourceService` | new `getVersionFileStream` | none |
| `AdminSystemExportService` | `BlobMetadata` and helpers widened to package-private | none |
| `AdminSystemExportController` | streaming moved into `ZipResponseStreamer`, shared with the export | meant to give the same status, headers, bytes and failure behaviour; `AdminSystemExportApiTest` and `AdminSystemExportServiceTest` exist and pass with this branch; `AdminSystemExportServiceTest` changed in one line, to refer to the top-level `DiscardableZipOutputStream` that replaced a nested copy |
| OpenAPI generator and annotations | `ApiOperation` gets `description` and `requestBodyRequired` (default true); `AnnotationEndpointCollector`, `EndpointMetadata`, `OpenApiRequestBodyBuilder` and `SpecAssembler` follow | existing operations render as before; no endpoint sets `requestBodyRequired` to false; `OpenApiRequestBodyBuilderTest` covers the attribute |
| `Proxy`, `AiDial`, `ControllerSelector`, `RouteTemplate` | the `adminExport` settings, the redactor, the route `POST /v1/admin/export` (new; it does not exist on `development`) | none for existing routes |

The serialized `Config` shape of every response and the output of `POST /v1/admin/system-export` are meant to be unchanged.

**Left unchanged on purpose:** `MergedConfigStore` and every consumer of it (27 files in `server/src/main` mention it; interface consumers call only `get()`; several controllers cast the store and call `applyEntityWrite`,
`applyBatch`, `getInvalidEntities`, `getFileSourcedConfig`); the six fail-fast marker-collector callers; `ConfigApplyService`, `ConfigManifestSupport`, `PolicyController`, `ApiKeyStore` (the export only takes its
mutation lock through the existing `getMutationLock()`); the request-time schema factory; `downloadAppSchema`'s request, timeouts and result. No other module (`config`, `storage`, `credentials`) references
`MergedConfigStore`. The serialized `Config` shape of every response is unchanged.

**Risks to watch in existing code, not changed here:** the replica path and in-place updates have no cross-type duplicate guard; `resurrectInvalidModels` stores a model under its canonical-id key and
`MergedConfigStorePartialUpdateTest.interceptorWriteResurrectsPreviouslySkippedModel` pins it (changing it would break that test); `ConfigFileMigrateController.collectManaged` decides "already in a blob" with `hasResource`, so an unparsable blob probably counts as migrated (an estimate); the request-time schema factory fetches remote `$ref`.

**Public resources.** A `public/` resource is a logical bucket whose physical form is the path prefix `public/` in one blob container (`ResourceDescriptor.PUBLIC_BUCKET`, `isPublic()` is exact equality with the
location). Sub-locations (`public/deployments/<id>/`, `public/publications/...`) are not `public/` and are admin-only special cases in `AccessService`. Applications and ToolSets in `public/` are written through the Resource
API (`PUT /v1/applications/public/<path>`, admin bulk apply, publication approval), stored under `public/applications/<path>`, never loaded by config loading, and never pushed into the merged `Config`
(`ConfigApplyService.applyApplication` deliberately skips it and does not check deployment-id uniqueness). They are reached only by URL. So a public name cannot clash with a short name (a bare name is not a URL), a
platform Application and a public Application of one name are distinct resources with distinct keys, and none of the short-name signals applies to a public target: it is read by descriptor, authorized by `AccessService`, and
included with origin `public`. Which types may live in which bucket is `EntityBucketBinding`: applications and toolsets in `public` and `platform`; files, prompts and conversations in `public` or a user bucket; models,
interceptors, translators, roles, keys, routes, schemas, catalog schemas, policies and settings in `platform` only.
