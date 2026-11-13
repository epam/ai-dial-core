## Context

This document describes the selective export as the code on this branch does it. Figures marked as estimates follow from reading the
code and were not run. Class names are in `server/src/main/java/com/epam/aidial/core/server`.

`POST /v1/admin/export` lets an administrator name Applications and receive one ZIP with those Applications and the
resources they depend on. Each resource is written the way the admin config API shows the same entity, without its secrets. The
whole-bucket export is a different endpoint, `POST /v1/admin/system-export`; this change does not alter its output.

### What the export builds on

- **System export.** `AdminSystemExportController` and `AdminSystemExportService` write every blob of both buckets in a layout of their
  own (a manifest and a metadata entry per blob), which the selective export does not use. It streams through a 1 MiB `BoundedPipe`,
  takes both bucket locks with `tryLockBuckets` (300 s, not renewed) and answers 409 when they are busy. It has no entity model and no
  dependency information.
- **Admin gate.** `AdminRoleAuthorizationService.isAdmin` calls `hasExplicitAdminAccess`, which is false when no
  `access.admin.rules` exist and for a per-request key. For an explicit admin `AccessService.getAdminAccess`
  gives ALL access on `public/`, review buckets and public source folders, and nothing on `platform/`.
- **Bucket locks and write-behind.** The bucket locks exclude only writers that take them: the admin write paths and
  `MergedConfigStore` (`underBucketLocks(ADMIN_BUCKET_LOCATIONS)`), and the move of a resource. `ResourceController` and
  `UploadFileController` take none. While a write is pending, a new resource has an empty stub blob and an update leaves
  stale bytes; `ResourceService` reads Redis first and avoids both.
- **The admin GET of one entity.** `ConfigResourceController` reads a stored entity, binds it (`ConfigEntityCodec.treeToEntity`),
  decrypts the external service secrets of an Application and the `auth_settings` secrets of a ToolSet for the hint
  (`decryptExternalServiceSecretsForResponse`, `decryptAuthSettingsForResponse`; lenient, a secret that cannot be decrypted is
  dropped), serializes with `ProxyUtil.MAPPER.valueToTree` (which drops every `@EncryptedField`, because they are `WRITE_ONLY`) and
  removes `client_secret` and `code_verifier` from `auth_settings`, with the masked `client_secret_hint` as the only trace.
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
  falls back to `ApplicationService.getApplication`). A URL-form read returns the stored resource and does not consult the merged
  configuration (`MergedConfigStoreReplicaUpdateTest` public-bucket cases).
- **Secrets** have three storage forms: `@EncryptedField` values (platform CEK), toolset `auth_settings`, and external-service
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
- Export selected Applications and the dependencies available to the caller as one ZIP, each resource in the form of the admin GET.
- Hold in memory the queue, the set of resources met and one resource, whatever the size of the graph.
- Leave no secret in the archive but the masked hint that the admin GET shows.
- Make the archive hold exactly the manifest and the resources, so that nothing but the resources has to be kept while it is written.
- Say plainly in the server log what was not followed, and what a failure after the head does to the stream.
- Reuse existing Core services; change shared code only additively.

**Non-Goals**
- Import, target writes, overwrite policy, rollback.
- Roots other than public or platform Applications; user buckets, `Keys/<x>/` app buckets and `appdata` as content;
  folder expansion of any folder, including `function.source_folder`.
- Project (deferred), and every type or location that "Scope of types and locations" does not list.
- Discovering dependencies from schema documents, and from the content of Prompts, Files and Skills. (`application_properties` is scanned.)
- A report of the references that were not followed inside the archive, a checksum list, a metadata entry per resource, and identical bytes for two exports of one state.
- Compatibility with the reader and the importer of the system export, and the import of the package.
- Secret re-wrapping, a structured audit event and metrics (the INFO records of the log exist), typed references, reconciliation with `origin/rd/*`.
- Changing the request-time schema factory, the behaviour of the six fail-fast collector callers, or the legacy Admin Backend.

### Vocabulary

| Term | Meaning |
|---|---|
| Resource | One exported item: a stored or configured entity, a File or a Skill. Its key is `<type>:<origin>:<segment>`. |
| Reference, dependency | One value of a body that names another resource, kept with its JSON Pointer while the body is examined. |
| Queue, met set | The resources still to write (`ExportStreamer.Run.queue`), and every resource that was ever queued (`met`). |
| Origin | Where the effective entity comes from: `file` (the loaded `aidial.config.json`), `platform` (the `platform/` bucket) or `public` (the `public/` bucket). Type and origin are independent. |
| Head | The `200` status line and headers of the response, sent with the first bytes of the archive. Before it a failure is a status; after it the stream is cut. |
| Deployment | Application, Model, Interceptor and ToolSet: the four types that extend `Deployment` and share its reference fields (`interceptors`, `dependencies`, `icon_url`, `catalog_schema_id`, `catalog_properties`). |
| Projection | The JSON that `ConfigEntityRedactor.toJsonWithoutEncrypted` makes of an entity: the form of the admin GET, without `name` and `status`, and without any encrypted field. |
| Manifest | `.dial-export/manifest.json`, the first entry: `formatVersion` (2), `coreVersion`, `createdAt`, `mode` (`selective`). |
| Fragment | The archive entry of a resource of origin `file`; it has no blob, so it is not written at a storage path. |
| Reason | Why a reference is not followed: `missing`, `unsupported`, `external`, `unresolved-short-name`, `origin-unknown`, `schema-unavailable` (`DiscoveredEdge.EdgeStatus`). |
| Content | What the export does with a body (`ExportTypePolicy.Content`): `EXAMINED` (the four Deployment types), `SCHEMA` (both schema types), `AS_STORED` (Prompt), `STREAMED` (File), `COMPLEX` (Skill). |

## The flow

`AdminExportController` parses the request, and `ZipResponseStreamer` runs one task on a task thread. The task takes the bucket locks,
has `ExportStreamer.checkRoots` check the roots, and then `ExportStreamer.stream` writes the archive into a 1 MiB bounded pipe that feeds
the response, so a slow client slows the writer instead of growing memory. The task releases the locks when it ends.

`ExportStreamer.stream` writes the manifest and then runs a loop over a queue. The roots are marked as met and queued in the
order of the request. For each queued resource the loop writes it, by content:

1. **A Deployment** (`EXAMINED`): read from storage with `resourceService.getResource` (Redis first, blob as the fallback;
   the stored text), or taken from the file-sourced `Config` for an entity of origin `file`; bind to the entity
   (`ExportResourceProjector.bind`); discover its references (`DeploymentEdgeDiscovery.discover`); for each reference follow or log it
   (see "Following a reference"); then decrypt, serialize, remove the `auth_settings` secrets, and write the entry
   (`ExportResourceProjector.projectDeployment`, `ExportArchive.writeJson`). The references are sent to the queue before the entity is
   decrypted and serialized, so the entity is not held longer than that.
2. **A schema** (`SCHEMA`): the stored or configured text is parsed to a tree, the upstream keys are removed from an application type
   schema, and the tree is written. A catalog schema is written from its parse.
3. **A Prompt** (`AS_STORED`): the stored text is written as its UTF-8 bytes.
4. **A File** (`STREAMED`): the metadata is read, then the bytes are copied through the 32 KiB buffer (`ExportArchive.copyFile`).
5. **A Skill** (`COMPLEX`): the marker is read and the files of the pinned version are copied (`ExportArchive.copySkill`).

After the loop `ExportArchive.finish` writes the end of the ZIP. `ExportStreamer.stream` does not close the
output; on a failure it logs one WARN line (the kind of the failure and, for the exceptions of the export, its message, which names a
resource and never a value), rethrows, and discards the archive so that nothing more is written to the stream.

**Memory.** The run holds the queue (`ArrayDeque` of resource, referrer and pointer), the met set (`HashSet` of descriptors),
the count of resources written and the resource in hand. The archive keeps nothing of an entry once it is written. A File or a file of a Skill goes through
one 32 KiB buffer whatever its size.

**Exactly once.** A resource is added to the met set before it is queued (`follow`, and the loop for the roots), so a resource reached
by several paths, by several reference forms, in a cycle or as a repeated root is queued once and written once. A resource that is
reached through a short name and through its URL is one resource, because the short-name result carries the same descriptor. Two
resources of one name and different types are two resources.

**Order.** The archive is breadth-first: the roots in request order, then each reference in the order of its holder's discovery
(application type schema, `interceptors`, `dependencies`, catalog schema, `icon_url`, `function.source_folder`, application markers,
catalog markers).

## Decisions

### One endpoint, `POST /v1/admin/export`; the body is `roots` and nothing else

The export is a new route (`RouteTemplate.ADMIN_EXPORT`, `AdminExportController`). The whole-bucket export is
`POST /v1/admin/system-export` and is unchanged. The request body is required: an empty body is 400. The body has one field,
`roots`, a list of `{type: "application", url}` (`ExportRequestParser`, three records). Any other field, an unknown value, a
malformed URL, a folder, a root that is not an Application and a root outside `public/` or `platform/` is 400. The message never
echoes a submitted value, because a value can be a credential: a root is named by its index (`roots[2] must be an Application...`),
`roots` by its name, and a body that is not valid JSON or holds an unknown field gets one fixed message. The whole body is read and
validated before the first response byte, and the explicit-admin gate runs before the body is read.

No request flag controls dependency traversal, file inclusion, the handling of unresolved dependencies or secrets. Traversal is
always on, Files and Skills are always exported, and a reference that is not followed never changes the response.

The family `/v1/admin/` follows from the gate: it is explicit-admin by convention. The same endpoint is meant to export other
kinds of items later, and Application selection is its first mode; that is why the selective export has the plain name and
the bucket export has the specific one.

*Rejected:* a query parameter or a second path to choose a mode (the mode is not a property of a request); request flags
`include` and `onUnresolved` (they need a typed failure body and a retry contract that nothing asked for).

### Statuses before the head, a cut stream after it

Everything that can be decided without writing is a status: `403` for a caller that is not an explicit admin (before the body is
read), `400` for an invalid body, `409` when a bucket lock is held, `403` for a public root that the caller may not read, `404` for a
root that does not exist (`ExportStreamer.checkRoots`, with the public roots checked before the existence of any),
and `500` (`Failed to export the Applications`) for any other failure of these steps. The head is written with the first byte of the archive.

The head is written with the first bytes that reach the response. A failure before it, in the first bytes of the archive, is answered by
`ZipResponseStreamer` with `500` (`Failed to export the Applications`). A failure after it cannot change the status, so
`ZipResponseStreamer` resets the connection: the client sees a transport error and a ZIP without its central directory. These
failures are:

- the deadline and a client that does not take the data within `stallTimeout`: the pipe is aborted and the next write fails;
- a stored body that is not valid JSON or not a JSON object, or that cannot be bound to its type (`ExportResourceProjector.bind`
  throws an `IllegalStateException` that names the resource by its key and never the parser's message);
- a root that existed when it was checked and is gone;
- a File that vanished, ended early or is longer than its length, a file of a Skill with another length, and a failed read or write;
- a path with an empty, `.` or `..` segment (`ExportResourceNames.requireSafePath`, `ExportArchive.copySkill`);
- a public dependency that the caller may not read (`HttpException` 403 that is never sent; for an explicit admin it cannot happen,
  because an explicit admin has ALL access to `public/`).

Each of these is one WARN record, `Export aborted after the response started`, with the key of the resource in hand (`ExportStreamer.Run`
tracks it), the duration and the kind of the failure; only an exception of ours (`HttpException`, `IllegalStateException`) has its message in
it, and the message of an exception about a resource carries the key `<type>:<origin>:<segment>` (never a body or a value; a bind failure carries the key, not the URL, as the log of a reference does). The audit records at INFO are the
system export's counterparts: `Admin export rejected` where `AdminExportController` produces the `409`, `Export started: roots=<n>` and
`Export completed: duration=<ms>, resources=<n>` in `ExportStreamer.stream`; none holds a root, a URL or a body. A structured audit event and
metrics stay deferred.

A failure after the head is not a clean answer: a client learns it from a truncated stream and from the server log, not from a status. A
client must treat any export that ends in a transport error as failed and must not read the partial ZIP. Only the roots are checked before
the head: the existence of a dependency, the deadline and whether a body can be bound are not known before the walk.

*Rejected:* a trailer or a final entry that says the export failed (a consumer that stops reading at the first error would not see it, and
a cut ZIP is already unambiguous).

### Bucket locks are held from before the head to the last byte

`AdminExportController.lockBuckets` calls `lockService.tryLockBuckets(MergedConfigStore.ADMIN_BUCKET_LOCATIONS)` (`public/` and
`platform/`) as the first step of the task, before the roots are checked, as `AdminSystemExportService.export` does. A lock held by
another export, by the system export or by an admin write is `409` before any byte, whatever the roots. The task holds the lock in a
try-with-resources and releases it when it ends: after the last byte, after a failure of the check or of the writer, or when the client
is lost. A task that the executor refuses or drops before it runs has taken no lock.

Effects, by reading the code that takes the same locks (`underBucketLocks(ADMIN_BUCKET_LOCATIONS)` in `ConfigResourceController` PUT and
DELETE, `AdminApplyController`, `ConfigFileMigrateController`, `PolicyController`, and the debounced rebuild and the reload of
`MergedConfigStore`; `PublicationController` (the approval of a publication into `public/`), `ComplexResourceService` (write, copy and
delete of a Skill in the bucket it writes), `ResourceOperationService` (a move) and the share and invitation paths, which take the lock
of the bucket they touch): while the stream runs these writes to `public/` and `platform/` wait for the locks, so the administered
entities of one export (the Models, Interceptors, ToolSets, schemas and platform Applications) are a consistent set. A plain write of
a Prompt or a File through `ResourceController` or `UploadFileController` takes no bucket lock, so it is not blocked, and the export
has no snapshot of those. The export also excludes the system export and another export. A long export delays admin configuration writes for up to `adminExport.deadline`.

The lease is `LockService.LOCK_PERIOD` (300 s) and is not renewed, so `adminExport.deadline` (default 180 s) plus one `stallTimeout`
(default 60 s) must stay below it; `AdminExportSettings.validate` refuses to start otherwise.

*Rejected:* renewing the lease (the system export does not, and a stalled holder could keep the locks).

### The projection of the entity is the entry

A Deployment is exported the way the admin GET of the same entity answers, in `ExportResourceProjector` and `ConfigEntityRedactor`, which the
controller and the export share:

1. `bind`: `ConfigEntityCodec.BLOB_MAPPER.readTree` of the stored text, then `ConfigEntityCodec.treeToEntity` to Application, Model,
   Interceptor or ToolSet. Encrypted fields are still ciphertext in the entity.
2. `projectDeployment` for an Application: `applicationService.decryptExternalServiceSecretsForResponse` (lenient: a secret that cannot
   be decrypted is dropped, so it has no hint), then `ConfigEntityRedactor.toJsonWithoutEncrypted(entity)` (`ProxyUtil.MAPPER.valueToTree`, the last
   serialization of the entity before the archive; it drops every `@EncryptedField`, which are `WRITE_ONLY`: the keys of a Model, an
   Interceptor, a route and an upstream), then `ConfigEntityRedactor.redactExternalServiceSecrets(tree, true)`.
3. For a ToolSet the same with `toolSetService.decryptAuthSettingsForResponse` and `redactAuthSettingsSecrets(tree, true)`.
4. A Model and an Interceptor need no decrypt: their secrets are `WRITE_ONLY` and are not in the projection.
5. `redactSecretFields` removes `client_secret` and `code_verifier` of an `auth_settings` (`ResourceAuthSettings.removeSecretFields`) and
   puts back the masked `client_secret_hint` that `ResourceAuthSettings.hintFor` derives from the removed secret, as the admin GET does.
   The `auth_settings` secrets are not annotated, because the services persist through the plain mapper and `WRITE_ONLY` would drop
   them from storage too; they are removed from the projected tree, which is a fresh copy, so the entity is never changed.
6. The tree is written with `ProxyUtil.MAPPER.writeValueAsBytes`. It has no `name` and no `status`, which the API adds.

An entity of the file configuration passes steps 2 to 6 without the decrypt, because it holds plaintext and is the live instance of the
`Config`: it is projected into a fresh tree and never changed. It is read once when it is queued.

An application type schema is a plain JSON document without an entity class. `ConfigEntityRedactor.removeSchemaUpstreamKeys` removes
`dial:key`, `key`, `dial:secretExtraData` and `secretExtraData` at `dial:applicationTypeRoutes[*].dial:upstreams[*]` and in
`dial:interfaces[*]` of such an upstream, under the spellings that `Route` and `Upstream` accept (`dial:upstreams` and `upstreams`,
`dial:interfaces` and `interfaces`); the routes may be an object or an array. A catalog schema is written from its parse. A Prompt is
written as the stored text.

**What is not a secret of the entity leaves as the projection holds it:** a URL (user information, path, query, fragment), free text,
a string that starts with `ENC[` in a field that is not encrypted, `application_properties`, `function.env`, `default_headers`,
`defaults`, `catalog_properties` and `override_paths`. The export does not scan values, does not claim that anything was removed and
leaves no marker. A credential kept in those fields is exported. Nothing about a secret leaves except the masked hint: no `ENC[...]`
value and no key. The projection is not the stored form, so an entry of a Deployment is not equal to the stored data.

Nothing that holds a value is logged or thrown by the projector: a failure names the resource only, and the parser's message, which can
quote a piece of a body, is dropped.

*Rejected:* a hand-written projection per type (a second description of every entity); replacing a removed value by a marker (it would say
what existed).

### The archive holds the manifest and one entry per resource

The ZIP has `.dial-export/manifest.json` first (`formatVersion` 2, `coreVersion`, `createdAt`, `mode` `selective`) and then one entry for
each resource, in the order in which `ExportStreamer` writes them, at the storage path of the resource or, for an entity of the file
configuration, at its fragment path. Nothing else is in it: no list of references, no checksum, no metadata entry per resource and no
entry that is written last.

- **Why nothing else.** The export writes a resource and forgets it. A checksum list, a registry of the references that were not followed
  or a metadata entry that must be written before its resource would each need something kept for every entry until the end, or a second
  read of the resource. The archive is the resources; what was not followed is in the server log, which the operator who runs the export reads.
- **Not the layout of the system export.** The system export writes a manifest under `.dial-system-export/` and a metadata entry before every
  blob, for a reader that restores whole buckets. The selective export has its own layout, so the reader and the importer of the system export
  cannot read it, and no claim of compatibility is made. `formatVersion` is 2 because the layout differs from the system export's (1). The import
  of the package is outside this change.
- **No promise of identical bytes.** ZIP entries carry no fixed time and `createdAt` of the manifest is the start of the export, so two
  exports of one state are not promised to be byte-identical. The order of the entries follows from the traversal.
- **A File that changed while it was copied.** When the ETag of a File differs between its metadata read, the open stream and the read after the
  last byte, but the bytes are complete (the length matches), the export logs one WARN record (resource key, ETag before and after, never a
  value) and goes on, because a whole newer version is a valid copy. The same holds for a Skill whose marker changed or is gone after its pinned
  files were copied. A File that is gone, a stream that ends early or is longer than its length, or a failed read cuts the stream: the
  bytes are not whole.

*Rejected:* a checksum entry written last (it needs the digest of every entry kept to the end), a metadata entry for each resource (a second
description of what the resource holds, written before it), a list of the changed files in the archive (it would have to be written after the files).

### Identity, key and entry name

A resource is identified by type, origin, bucket location and path, and a schema by its body `$id`. The origin is `public`, `platform` or
`file`; a target of an unsupported reference in any other location has origin `other` (`ExportResourceNames`). The key
`<type>:<origin>:<segment>` is used in the log and to check the path: the segment is the path with every byte outside
`A-Z a-z 0-9 . _ - /` written as `~` and two hex digits, so two paths never give one key; a schema of the file configuration is named
by the first 32 hex digits of the SHA-256 of its `$id`. The type names are `application`, `toolset`, `model`, `interceptor`,
`application-type-schema` and `catalog-schema`.

- **Entry name.** A resource that has a blob is written at its storage path, from its descriptor: bucket location, the group of its type
  (`applications`, `toolsets`, `models`, `interceptors`, `app_type_schemas`, `catalog_schemas`, `files`, `skills`, `prompts`) and its path.
  An entity of the file configuration is written as the fragment `.dial-export/config/<type>/<segment>.json`.
- **Path safety.** A path segment that is empty, `.` or `..`, also encoded (`%2e%2e`), is malformed when a reference is classified
  (`ShortNameResolver.classify`) and is never read. A stored resource with such a path, and a Skill file with such a relative path,
  cut the stream (`ExportResourceNames.requireSafePath`, `requireSafeRelativePath`); every path of a Skill is checked before its first file is
  written.
- **Visited key.** The met set is keyed by the resolved `ResourceDescriptor`, whose equality covers type, name, parent folders, bucket name,
  bucket location and the folder flag, so no merge of duplicates is needed. A duplicate archive entry name cannot arise from two
  distinct descriptors in the supported locations, which `ExportTypePolicy.isBound` fixes; this was not proved for every producer of a
  descriptor.

### Traversal follows every Deployment

`DeploymentEdgeDiscovery.discover` examines the bound entity of every Application, Model, Interceptor and ToolSet that is written, and the
loop follows every candidate that is one of these four types. All four extend `Deployment` and share its reference fields, so one
discovery serves them. Schemas, Prompts, Skills and Files are written and their content is never examined. Following Applications alone
would leave a Model's or ToolSet's own `dependencies`, `interceptors` and icon out of a package that needs them.

- **What is discovered** in every written Deployment: `interceptors`, `dependencies`, `icon_url`, `catalog_schema_id`, and the values of
  `dial:file` and `dial:resource` markers in `catalog_properties` (`CatalogSchemaService.collectMarkerReferences`). An Application alone also
  has `application_type_schema_id` (the schema is built by `ApplicationSchemaService.buildExportSchema`), the values of the markers of that
  schema in `application_properties`, and `function.source_folder`. Each reference has its JSON Pointer. Discovery continues after a malformed
  or unresolved reference.
- **`application_properties` is scanned.** For an Application with `application_type_schema_id` and non-empty properties, discovery builds the
  schema once per schema id and export (memoized in `DeploymentEdgeDiscovery.builtSchemas`) and collects the properties against it, as
  `catalog_properties` is collected against its schema. Every value that a `dial:resource` or `dial:file` marker holds is a reference at
  `/application_properties/<path>` and is classified like a marked value of `catalog_properties`. Included ToolSets, Models, Interceptors and
  Applications are followed; Prompts, Skills and Files stay leaves. A schema that cannot be found or evaluated is a schema reference that is
  logged as `schema-unavailable` or `missing`, never a failure of the export. A violation of the schema by the properties is a discovery issue
  (`instance-invalid`) that is logged. The reason for the scan: a Quick App names most of its tools, skills and models only here; on a real
  Quick App 2.0 (schema `quickapps2`) 21 marked references sat in `application_properties`.
- **`icon_url`** is a fixed field. A `files/...` URL is a file reference; an absolute URL is external; any other value is malformed.
  Nothing is fetched.
- **`function.source_folder`** is an unsupported folder reference: no folder is followed or read, including the admin-only
  `public/deployments/<id>/` folders that a published code app points at.
- **Interceptor lists.** An `interceptors` entry of any Deployment is a short name of an interceptor and is followed, although Core does not
  evaluate the stored `interceptors` list of an Interceptor at request time: the export carries what the body declares, not what Core runs.
- **Not examined:** Roles (`user_roles`), translators (`interfaces.*.translator`), inline `routes`, Routers (a name that only a Router holds is not found), schema documents, and the content of
  Files, Skills and Prompts. Unmarked references in properties are not detected.
- **Value classification.** A value is classified first (`ShortNameResolver.classify`): an absolute URL (`UrlUtil.isAbsoluteUrl`, any
  `scheme://`) is external; a value that parses as a resource URL carries a descriptor and is classified by the scope table without the
  short-name signals; a slash-free value is a short name; anything else, including a blank value, is malformed. A reference never holds the
  raw value in a record: a secret can look like a name.
- **Fixed and marked fields differ on purpose.** A short name in `dependencies` or `interceptors` that nothing holds is `missing` (Core
  rejects it at write). The same name in a marked field is `unresolved-short-name` (Core drops it silently at request time).
- **One build of the schemas per export**, one `hasResource` per resolved short name, and one listing of the platform schema blobs per schema type.

*Rejected:* following only Applications (a package whose Models, Interceptors and ToolSets lose their own dependencies); examining
schemas, Prompts, Skills and Files (they are not Deployments and have no reference fields). The legacy Admin Backend resolver is
evidence of what to avoid: a single pass whose completeness depends on step order, a caller-supplied dependency set copied to every
child, silent nulling of references, and no report of dangling references.

### Following a reference

`ExportStreamer.Run.follow` classifies each reference of a Deployment that `discover` returns:

1. A reference with a reason is logged (`recordNotFollowed`) and not followed.
2. A reference with no target is logged as `unsupported`.
3. A target that is already met adds nothing.
4. A target whose type is not allowed in its bucket (`ExportTypePolicy.resolveContent`, `isBound`) is logged as `unsupported`; nothing is read.
5. A target in `public/` must be readable by the caller (`AccessService`), otherwise the stream is cut with `403` (never sent).
6. The target is marked met and queued with the key of its holder and the pointer.

A queued resource that turns out not to exist is logged with the key of its holder and the pointer (a root is different: it ends the
export, because the request checked it). The record is one line: `Export: a reference is not exported: resource=<key> pointer=<ptr>
reason=<reason>` at WARN, `Export: a reference is external and not exported` at INFO for an absolute URL, and `Export: the references of
<key> may be incomplete: <kind> at <ptr>` for a discovery issue. There is no list of them in the archive; the log is the only report.

### Scope of types and locations

One table gives, for each type, where it can come from, what the export does with its body, how it is authorized and how it is found.
`ExportTypePolicy` is the only place that holds it; a type with no row is unsupported, so a new `ResourceTypes` value starts
unsupported (`ExportTypePolicyCoverageTest` enforces that every value has a disposition). Each answer is a statement about this export, not a
general policy for the type.

| Type | `file` | `platform/` | `public/` | Content | Authorization | Found by |
|---|---|---|---|---|---|---|
| Application | yes | yes | yes | `EXAMINED`; the only root type | platform: gate; public: `AccessService` READ | roots, `dependencies`, short names, URL |
| ToolSet | yes | yes | yes | `EXAMINED`; never a root | as above | URL, short name, catalog marker |
| Model, Interceptor | yes | yes | `unsupported` | `EXAMINED`; the definition only | gate | short name, `interceptors` |
| Application type schema, catalog schema | yes | yes | `unsupported` | `SCHEMA` | gate | `$id` fields |
| Prompt | no | `unsupported` | yes | `AS_STORED` | `AccessService` READ | catalog marker, URL |
| File | no | `unsupported` | yes | `STREAMED` | `AccessService` READ | catalog marker, `icon_url`, URL |
| Skill | no | `unsupported` | yes | `COMPLEX` | `AccessService` READ | catalog marker, URL |
| Project, Conversation, Role, ProjectKey and every other value | no | `unsupported` | `unsupported` | not exported | not read | not examined |

- **Only the exact locations `public/` and `platform/` are eligible.** `isPublic()` is equality with `public/`; `public/deployments/`,
  `public/publications/`, review buckets, function folders, user buckets, `Keys/<x>/`, `appdata`, hidden `.name/` folders and every
  folder are `unsupported`. The binding check (`ExportTypePolicy.isBound`, which uses `EntityBucketBinding`) runs before anything is read
  or probed. A Skill is bound to `public` here because `EntityBucketBinding` has no entry for a complex resource.
- **Origin `file`** is supported for Model, Interceptor, ToolSet, Application and both schemas, and for no other type.
- **A Model or Interceptor is exported as its definition only**: the endpoint it names, a tokenizer, model weights and any other
  external artifact are not exported.
- **A reference to an unsupported target** is logged: nothing is read, no existence check is made and no authorization is applied. It never
  changes the response.
- **ToolSet roots** are not accepted (the root check is `ExportTypePolicy.isRoot`); whether to accept them is open.
- **Why public dependencies are exported** (`public/` Application, ToolSet, Prompt, File, Skill): the package would otherwise lose
  content an Application needs. Each is authorized individually, separately from the root. A public resource is never in the
  merged `Config`, so it cannot clash with a short name; it is reached by URL only.
- **Files and prompts cannot live in `platform/`** (`EntityBucketBinding`); models, interceptors, schemas and the other configuration
  entities cannot live in `public/`. The binding is what makes "bound" a property of the type and the bucket, not of the reference.

### Authorization follows the location of each resource

| Resource | Check |
|---|---|
| Any caller | the request gate `isAdmin` (`hasExplicitAdminAccess`), before the body is read. A per-request key and an unset `access.admin.rules` give 403. |
| Anything in `platform/` (root, dependency) | the gate alone. `AccessService` is not consulted. |
| A root in `public/` | the gate, then `AccessService` READ (`AdminExportController.hasPublicReadAccess`), in `ExportStreamer.checkRoots`, before the head |
| A dependency in `public/` | `AccessService` READ, in `ExportStreamer.Run.follow`, before it is queued. The root's authorization does not extend to it. |
| `file`-origin entities | the gate alone; they are read from the configuration, not from a bucket |

The role check of `DeploymentService.findDeployment` and its URL-fallback access check are not used (the export does not check
`user_roles`). *Why a denial cannot happen for the callers that pass the gate.* The gate is `adminRulesConfigured && hasAdminAccess(context)`.
`getAdminAccess` returns ALL for every resource whose `isPublic()` is true when the same `hasAdminAccess(context)` holds; the permission
rules only add permissions and there is no deny rule and no per-resource restriction of `public/` for an admin. The 403 of a public
dependency is a defensive guard for a future change to the rules: it is re-opened if `AccessService` gains a deny rule or a per-resource
restriction on `public/`, or if a caller other than an explicit admin can reach the export. After the head it cuts the stream and is logged.

An explicit admin has no `AccessService` rights on user buckets, so user-bucket dependencies are `unsupported` for every caller, and the
answer is the same whoever asks.

### Short names resolve with read-only signals; no origin index in the store

**Problem.** A short name in `dependencies`, `interceptors` or a marked field can be held by a file entity, a platform blob, or both. After a
rebuild the merged `Config` holds one entity per name and no record of its origin. An exporter that guessed ("a blob exists, so platform")
would export the wrong entity or omit one.

**What the resolver relies on** (tests: `MergedConfigStoreOriginSignalsTest`, `ShortNameResolverTest`):
- The merged `Config` holds the file configuration plus the `platform` bucket for every managed type, never `public/`.
- The four deployment types (Model, Application, Interceptor, ToolSet) share one flat name space. A full rebuild removes the later type in
  the order models, applications, interceptors, toolsets, in `skip` mode records it as skipped, and in `abort` mode (the default) fails the
  rebuild and keeps the previous `Config`. The single-write, apply and validate paths are written to reject a cross-type name with 409 or a
  per-entry failure. The replica path, the in-place updates and the resurrection of a skipped model (`resurrectInvalidModels`, which stores a
  model under its canonical-id key) have no guard, so two maps can hold a name until the next rebuild. Such a duplicate is an
  implementation gap, not a supported state.
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

1. Take the pair once per export (see "One snapshot of the merged configuration").
2. **Effective holders.** Look the name up in the scope. More than one map holding it gives `ORIGIN_UNKNOWN`: no holder is exported and none
   is the target. Exactly one is the candidate. The lookup does not stop at the first map: it collects the holders of every type of the scope
   before deciding, and never picks among them by map order or type priority.
3. **Candidate found.** Signals: S1 the merged entity is the same instance as the file `Config`'s; S2 `ResourceService.hasResource` on the
   platform descriptor of that type and name; S3 an invalid record of the same type and name.
   - A valid platform blob (S2 true, no S3) is `PLATFORM`, whether the merged entity is the blob's or the file `Config`'s own instance (blob
     written, merged not rebuilt yet), and whether or not the file `Config` also holds the name: the platform blob has the higher priority.
   - No valid blob and S1 true (S2 false, or S3 present because the blob is invalid): `FILE`.
   - No valid blob and S1 false (stale after a delete or a file reload): `ORIGIN_UNKNOWN`. No file `Config`: `ORIGIN_UNKNOWN`.
4. **Not found.** An S3 record gives `SKIPPED`; otherwise a valid platform blob that the merged map does not hold (lag, or a resurrected
   model) gives `ORIGIN_UNKNOWN`; otherwise `NOT_FOUND`.

Skipped entities of another type are never consulted.

**One snapshot of the merged configuration.** `ExportStreamer.stream` locks `ApiKeyStore.getMutationLock()` (the lock that every publication
of `MergedConfigStore` holds), reads `getInvalidEntities()` and then `get()`, and releases the lock before anything is resolved. Because every
publication holds the lock, none can fall between the two reads. The wait includes a whole rebuild, so this runs on the task thread and
never on the event loop. A sandwich read (`config`, `invalidEntities`, `config` again) is rejected: it cannot prove one generation, because the
two writes are separate and an empty invalid map is the shared `Map.of()`. The file-sourced config belongs to another store that replaces its
`Config` on every poll (default 60 s) while the merged config follows after a 500 ms debounce, so it is read afterwards and is not part of the
guarantee. The merged `Config` is the primary source of origin; the file-sourced config is only signal S1 and the source of the entities of
origin `file`.

**Schemas.** A schema named by `application_type_schema_id` or `catalog_schema_id` is keyed in the merged `Config` by its body `$id`, and its
blob name is arbitrary, so S2 cannot be `hasResource` on a name-derived descriptor. `SchemaReferenceResolver` keeps S1 and S3 and replaces S2 by
a scan: one paged `ResourceService.getFolderMetadata` of the platform folder of the schema type per export and type, reading one body at a time
under the deadline and keeping only its `$id`. The outcomes follow the short-name algorithm: exactly one valid blob with the `$id` is exported as a
platform schema, also when the merged body is the file instance and also when the file config holds a schema of that `$id`; the file schema is
exported only when no valid blob carries the `$id` and the merged body is the file instance; more than one valid blob with one `$id`, or a valid blob
the merged config does not hold, is `origin-unknown`; a skipped blob that nothing else holds is `missing`. The
resolver adds no reference: the two fixed fields are the only schema references. The scan reads outside the snapshot, so a blob written after
it is seen by the scan and not by the merged config, which gives `origin-unknown` (the safe direction), and it can show a write-behind stub
(not verified).

**Behaviour for the exporter** (`DeploymentEdgeDiscovery.classifyResolution`, `classifyInterceptor`):

| Situation | Outcome |
|---|---|
| Nothing holds the name, nothing skipped | `missing` for a fixed field, `unresolved-short-name` for a marked field |
| Platform-origin entity | exported at its storage path and followed, also when a file entity of the same type and name exists, which is not read or exported |
| File-origin entity | exported with origin `file` when no valid platform blob of the same type and name exists (see "An entity of the file configuration is a fragment") |
| Same name, one effective entity, a skipped entity of another type | the effective entity resolves; the skipped one is not consulted |
| Referenced entity skipped and nothing holds the name | `missing` |
| Origin cannot be established, including a valid platform blob absent from the merged map | `origin-unknown`; nothing exported |
| More than one map holds the name | `origin-unknown`; nothing exported |
| An `interceptors` entry | the hint restricts the scope to interceptors, so a Model or Application of that name does not compete |
| `abort` mode with a collision | no merged `Config` is built; the exporter sees the previous one. Not an exporter concern |

**URL form.** A reference that names a type, a bucket and a name is read as the stored resource, whatever the merged configuration or
`getInvalidEntities()` holds under the short name. This is what Core's request-time fallback reads too, so the export carries what the
reference means. It is exported under its own identity, so an Application and a Model of one short name are two resources.

**Accepted limitations.** (1) The origin of a name is that of the current effective configuration. A skipped deployment of another type can
still compete for the name after a repair or at a later rebuild, so the meaning of a name can change after an export. (2) Around a
file reload, signal S1 fails for file-defined names: with no platform blob the result is `origin-unknown`; a valid platform blob still gives
`platform`; the window is the debounce plus the rebuild time after each poll, estimated at about 1 % of the time at the default period
(an estimate). (3) A resurrected model gives `origin-unknown` until the next rebuild. (4) S1 relies on merged entities being the file
`Config`'s own instances, an implementation detail of `rebuildMergedConfig`; a refactor that clones entities would silently turn `FILE` into
`ORIGIN_UNKNOWN`, so guard tests pin it.

*Alternatives.* **A** the merged `Config` alone: effective entity and type, no origin. **B-weak** blob presence decides origin: wrong for an
unparsable blob, a decrypt failure, a cross-type skip, the window after a file reload and a partial delete. **C** an origin index
(`{origin, shadowsFile}` per entity) published with the `Config` and `invalidEntities` as one object: exact at each publication and one read, but
it changes `MergedConfigStore` (rebuild, partial updates, cascade, resurrection, replica path), a class every config write uses, for roughly 9 to
12 engineering days against about 4 for the read-only signals (an estimate). C is a documented alternative; reconsider it if `ORIGIN_UNKNOWN`
proves frequent, if instance-identity dependence proves too fragile, or if the per-name `hasResource` reads matter.

### A remote application type schema is evaluated through Core's existing download, with no network safeguard of its own

An application type schema may declare `dial:applicationTypeSchemaEndpoint`. To know whether the Application's schema can be used, Core makes a
live request to that endpoint at export time through the download it already has (`ApplicationSchemaService.downloadAppSchema`: a bare `GET`, a
5-second request timeout, the optional proxy selector, status 200 and a JSON object required). No TTL cache is added. If the runner is down, times
out or answers with something unusable, the schema reference is logged as `schema-unavailable`.

- **Mechanism.** `resolveSchema` is split: a private `build(String source, boolean quiet)` builds the schema from its stored document
  and, when it declares the endpoint, from `downloadAppSchema` and `merge`, without reading or writing the shared cache. `resolveSchema` calls it
  with `quiet` false and keeps its cache read, `put`, `remove` and exceptions. `buildExportSchema(URI, Config)` takes the stored schema from the
  snapshot `Config` and calls it with `quiet` true, so a failed download raises an exception that carries no endpoint and no cause. A failure is
  `ApplicationTypeSchemaProcessingException`.
- **What the download does, accepted as it is:** one request per schema id and export (the export memoizes the built schema, so Applications
  that share a schema make one request; a runner that is down is waited for once per schema id); the response is read whole with no size bound; the
  request timeout bounds the wait for the response head and not the body; no redirect policy is set (the JDK default does not follow redirects); no
  caller header or credential is sent.
- **Log hygiene.** A failed download logs a fixed message and, on a non-200 answer, the status code at DEBUG: no endpoint, response body, header or
  exception message at any level. `downloadAppSchema` and its callers were changed for this; it is a change to shared logging and not a network
  safeguard.
- **Remote `$ref` is a different thing and is refused.** `$ref` resolution is initiated by the schema library's loader while it compiles a document.
  The export builds its own factories (`EXPORT_SCHEMA_FACTORY` in `ApplicationSchemaService` and `CatalogSchemaService`) with the same meta-schema and
  keywords as the request-time factory and `DisallowSchemaLoader` registered. A `$ref` is local when it is fragment-only or resolves to the document's
  own base identifier. This is the one safeguard the feature keeps, because it belongs to the collector the feature adds. A schema with a refused `$ref`
  cannot be evaluated: its reference is `schema-unavailable`. The library's refusal message and ERROR log carry the IRI, so neither is written nor forwarded.

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

### A reference that is not followed never changes the response

There is no strict mode, no request flag and no 422. When a reference is unsupported, external, missing, an unresolved short name, of unknown
origin or hidden by an unavailable schema, or when discovery is incomplete, the response is 200, the archive holds every root and every dependency
that can be safely resolved, authorized, read and written, and the server log holds one record for each reference that was not followed. A 200 is
therefore not a statement that the package is complete: the log is the only place that says what is missing.

- **The log record.** Resource key, JSON pointer and reason (the fixed set of the vocabulary), at WARN; an external URL is INFO because it is not a
  resource of the system. No reference value, no schema body, no validation message and no exception message: a secret can look like a name.
- **Why not in the archive.** A list inside the archive would need every reference of every resource kept until the end, or a second pass. The
  stream keeps nothing of a written resource, and the operator who runs the export reads the log of that server.
- **What fails the export instead** is a condition that prevents a safe archive: an invalid request, a forbidden caller, a missing root, a held lock
  (statuses before the head), and after the head the deadline, an unbindable body, an unsafe path, a gone File or a failed read or write.

### Files and Skills are streamed as stored, with length and ETag checks

The content of a File and the files of a Skill are copied as stored and never parsed, converted or examined for references. Memory holds a 32 KiB
copy buffer and the 1 MiB pipe, whatever the file size.

- **File.** `ExportStreamer` reads `getResourceMetadata` (the metadata, no content). `ExportArchive.copyFile` opens `getResourceStream`, copies
  through the fixed buffer, and checks that exactly the length at open was copied. The ETag is read again after the last byte. An ETag that is not
  the one read before the copy, or that changed during the copy, is one WARN record (resource key, ETag before and after) and the export goes on,
  because a newer complete version is written whole. A File that is gone, a stream that ends early or is longer than its length, or a failed read
  cuts the stream.
- **Skill.** The version is pinned from the marker (`ComplexResourceService.getMarker`: current version, aggregate ETag, the files with size and ETag).
  The files of `v/<versionId>/` are immutable, so a version cannot be torn. `ExportArchive.copySkill` copies each file of the pinned version in the order
  of its relative path (`ComplexResourceService.getVersionFileStream`) to `<storage path of the Skill>/<relative path>`, for example
  `public/skills/s/scripts/run.py`, without the marker and the version folder. A file that is gone or has another length cuts the stream. The marker is
  read again after the last file; a different aggregate ETag, or a Skill that is gone, is one WARN record and does not fail the export, because the
  version copied is whole. A Skill with no current version, no files or no marker is `missing`. `ComplexResourceService.downloadArchive` buffers a whole
  archive and is not used. The number of its files is bounded by the existing `ComplexResourceService` limits.
- **ETag evidence.** For a File, the ETag in Redis write-behind and the `etag` user metadata of the synced blob must be the same value, or an ETag change
  between the reads would be an artifact of the sync. An integration test against a real embedded Redis and a filesystem blob store showed the same value
  before the sync, with Redis ahead of the blob, after the sync, for a file above `maxSizeToCache` and for a JSON resource read after eviction. That test
  pinned the behaviour of `ResourceService`, which this change does not modify, and was dropped from the branch. Limits of that evidence: the filesystem
  provider only, one process, the sync run with a 1 ms delay, no multi-pod or Redis-failover case.
- **Torn reads.** An object store returns one version per read. `BlobStorage` exposes no version id, and the filesystem provider used in development can
  tear a read when a file is overwritten; the length check and the final ETag catch most of it and the rest is documented.
- **No byte bound.** Files have no byte bound of their own beyond the upload limit and the deadline.
- **As stored.** These bytes leave as stored; Security's acknowledgement is a release gate.

*Rejected:* converting binary content to text; sniffing the content type; failing the export for a File that changed but was copied whole.

### An entity of the file configuration is a fragment, never a blob

An entity that exists only in the file configuration (Model, Interceptor, ToolSet, Application and both schemas) has no blob. Leaving it out would make an
Application that depends on a configured model unusable as a unit, and the source of an entity is not a reason to leave content out.

- **Representation.** The projection of the live entity (`ExportFileEntities.findDeployment` and `ConfigEntityRedactor.toJsonWithoutEncrypted`): the tree of the entity with the
  spelling of its own annotations (camel case for a Model and Interceptor, snake case for a ToolSet and Application). It drops the `@EncryptedField` values
  (`WRITE_ONLY`), so an annotated secret never enters the tree, and the `auth_settings` secrets are removed from it as for a stored entity. No decrypt is
  made, because the configuration holds plaintext. A schema is held by `Config` as the text of its document (`ExportFileEntities.findSchemaText`); that text is
  parsed and written as a stored schema is. A hand-written projection is rejected (a second description of every entity).
- **Naming.** The resource has a stand-in descriptor with the bucket location `file/` (`ExportFileEntities.newDescriptor`), so its key is `<type>:file:<name>`.
  A schema has no usable name, so its stand-in name is the digest of its `$id`, which is also its key segment. The entry is
  `.dial-export/config/<type>/<segment>.json`. The whole `aidial.config.json` is never exported, and the `$id` of a schema stays out of
  the archive.
- **Precedence.** When a valid platform blob and a file entity of a Model, Interceptor, ToolSet, Application or schema share a type and name (for a
  schema, the `$id`), the platform blob has the higher priority: the export takes the blob (`ShortNameResolver.resolveHeld`, `SchemaReferenceResolver`:
  origin `platform`), reads it through the resource service like any platform resource, writes it at its storage path and follows it further, and
  does not read or export the file entity. The blob is what the running system serves and what an administrator wrote through the API, so the package
  matches the running system. A file entity that no valid platform blob replaces is still exported as a fragment; an invalid (skipped) blob does not replace it.
- **One read of the configuration.** The file-sourced `Config` is read once when the export starts and the entity is projected when it is queued, so a
  reload during the stream changes nothing for an entity that was already written.
- **No snapshot API, no copy.** A `MergedConfigStore.snapshot()` or a copy of the configuration made for one admin endpoint would change how the
  configuration is published for every request. *Provided:* the merged pair is one publication and the admin writes are excluded by the bucket locks.
  *Not provided:* the merged and the file-sourced configuration are updated independently and read one after the other, so they can disagree; no atomic
  cross-store view is claimed, and a name whose signals disagree stays `origin-unknown` and is not exported.

### Settings

The `adminExport` settings (`AdminExportSettings`) are:

| Setting | Default | What it sets | When exceeded |
|---|---|---|---|
| `deadline` | 180000 ms | the time from the start to the last byte | the stream is cut |
| `stallTimeout` | 60000 ms | the wait for a slow client | the stream is cut |

The deadline is enforced by a timer that aborts the pipe when it passes: the next write fails and the export ends, as it does for a client
that does not take the data within `stallTimeout`.

**Heap.** The export holds the queue, the met set and one resource. A stored body is read in full; the largest read is bounded by `resources.maxSize` (64 MiB by default). By reading the code,
reading allocates about five times the stored size (compressed bytes, decompressed bytes with buffer growth, the string), so a body of 64 MiB can need
about 320 MiB for a moment (an estimate), and parsing and projecting a body needs several times its length more. Jackson limits one JSON string value to 20,000,000 characters by default, which Core does not change, so a body with a longer string cannot
be bound and cuts the stream. These figures are not measured and are not performance guarantees.

## Per-request cost

The export adds no route that other endpoints share. The work added on another endpoint's request path is one map lookup and a type test per marked
value in the three marker keywords (nothing when no recorder is registered) and one extra method call when the request-time schema is resolved. Existing
behaviour that changes is listed under "Impact on existing capabilities".

**Per selective export** (`O(roots + references)` reads), for each resource:

| Resource | Work |
|---|---|
| Application, ToolSet, Model, Interceptor | one `getResource`, one parse to a tree and one bind to the entity, discovery, a decrypt for an Application and a ToolSet, one `valueToTree`, one serialization |
| Prompt | one read |
| Schema | one read (or the configured text), one parse, one serialization |
| File-origin entity | discovery, one `valueToTree`, one serialization |
| File | one `getResourceMetadata`, one `getResourceStream` copied once, one `getResourceMetadata` for the etag |
| Skill | one marker read, one stream per file, one marker read |

Also per export: one `hasResource` per resolved short name (up to four more when a name is absent from the merged map, because each candidate type is
probed); one paged listing of the platform schema blobs of each schema type that a schema reference touches, with one body read per blob; one runner request
per schema id whose schema declares an endpoint (the built schema is memoized for the export, with no TTL cache); one wait for the publication lock at the
start; and the bucket locks. Only one export runs in the cluster at a time, because it holds the locks that the system export and the next export need.

**Short-name probes** (call counts exact and asserted):

| Case | `hasResource` calls |
|---|---|
| A name that the merged config holds, platform or file | 1 per occurrence |
| A name nothing holds | 4 (one per candidate type); 1 with the interceptor hint |
| A skipped name | 0 |
| A name that occurs again | 1 more each time; the resolver keeps no memo |

Absent names are the most expensive case, and probes are sequential. The latency over a networked Redis and a remote blob store was not measured, nor was
the latency of the schema scan (only its call count, one listing per schema type per export, is asserted by `SchemaReferenceResolverTest`).

## Risks / Trade-offs

- **A 200 can be an incomplete package.** A reference that is not followed does not change the response, and only the server log says what is missing. A
  short name in a marked field that nothing holds is expected to be common (Quick Apps hold short names in marked deployment fields). Whether a mode that
  fails on findings is wanted later is a product decision.
- **A late failure is a truncated ZIP, not a status.** After the head the deadline, a body that
  cannot be bound, a gone File and a failed read cut the stream. A client must treat a transport error as failure; the reason is in the server log only.
- **A long export delays admin writes.** The bucket locks are held up to `adminExport.deadline`, so config writes, `/v1/admin/apply`, file migration and
  the config rebuild wait. The system export and another export get `409`. After a pod failure the locks stay until the lease ends (300 s).
- **No snapshot of user content.** A plain write of a Prompt or a File in `public/` is not blocked (a Skill write, a publication approval and a move are); a package can mix versions of related resources, and
  the stream can run up to the deadline.
- **Cost of scanning `application_properties`.** Each export builds the schema of every Application type that it meets, one Runner request per schema id,
  and probes storage for every marked short name. References to resources that are not in storage are logged, and a Quick App can have many.
- **Plaintext credentials and unencrypted fields.** No Core mechanism covers `application_properties`, `function.env`, `default_headers`, URLs or free text; they
  leave as the projection holds them. Security's approval of the policy and of that limitation is required before release.
- **The masked hint leaves.** `client_secret_hint` of a ToolSet and of an external service is in the archive, as in the admin GET.
- **Unexamined binary and Prompt content.** File bytes, Skill files and Prompt bodies leave as stored; Security's acknowledgement is a release gate.
- **File-config skew (accepted).** Around a file reload a reference to a file-defined name can be `origin-unknown` (or classified `platform` when a blob holds
  the name).
- **Skipped entities are not considered (accepted).** The meaning of a name can change after an export without a write to the exported entity.
- **Dependence on instance sharing.** Signal S1 is valid only while merged entities are the file `Config`'s own instances; guard tests pin it for every short-name
  category. Option C would remove the dependence and change a store every config write uses.
- **Pod-local configuration view.** The merged configuration is per pod while storage is cluster-shared, so an export on one pod can see a configuration that
  predates a write made through another pod. The signals report a disagreement as `origin-unknown`. Multi-pod timing tests are excluded.
- **Replica-path duplicates.** The replica path has no cross-type duplicate guard, so two maps can hold a name until the next rebuild; the resolver reports
  `origin-unknown`. A writer checks against its own pod's live `Config`, so a write whose check ran before the previous write's replica event reached that pod
  can store a conflicting blob (an estimate).
- **A package can differ from the running system.** The merged and the file-sourced configuration are read one after the other, so around a file reload a
  file entity can be reported `origin-unknown` or exported from a configuration that is newer than the merged one. A platform blob that replaces a file
  entity is exported, as the running system serves it.
- **Live runner calls.** Every export that touches an Application with a remote schema sends a request to a URL stored in configuration, once per schema id,
  through the existing download with no added safeguard. The shared request-time factory still fetches remote `$ref`; the export factory does not.
- **The archive is not an import format.** Entries are projections, not stored data, and the layout is not that of the system export, so the
  reader and the importer of the system export cannot read it.
- **No checksums.** A reader cannot verify an entry against a digest; a truncated archive has no central directory anyway.
- **Authorization gap.** User-bucket dependencies are `unsupported` for every caller; roots are limited to `public` and `platform` for the same reason.
- **Preview.** The endpoint is marked preview, like the other admin endpoints.

## Migration Plan

Additive. `POST /v1/admin/export` is a new route; `POST /v1/admin/system-export` is unchanged. The `adminExport` settings have code defaults and rows in
`aidial.settings.json` and the README; no setting is required. Rollback is removing the route; no data is written. The archive has a layout of its
own, so a reader of the system export does not accept it.

## Open Questions

| # | Question | Owner | Recommendation |
|---|---|---|---|
| 1 | Approval of the secret policy and its limitation, and the acknowledgement of File, Skill and Prompt content as stored | Maintainers, Security | None recorded; the endpoint is not released until both are given |
| 2 | Sign-off that explicit admin only may export, including public dependencies and the selection of a public root | Security | As implemented |
| 3 | Sign-off that the package promises no snapshot, that a 200 can be incomplete, and that a failure after the head is a cut stream | Product | As implemented |
| 4 | Accept ToolSet roots, public or platform? (the request accepts Application roots only) | Product | Keep Application roots only until asked; accepting ToolSet roots changes the request contract |
| 5 | List `user_roles`, `interfaces.*.translator` and inline `routes` as unsupported references, so the log shows what the package lacks? | API owner | Not now; today they are not examined |
| 6 | Report the request-time exposure of the shared schema factory (remote `$ref`, no loader timeout, `classpath:` and `file:` loading) | Maintainers, Security | Report it as its own issue; this change does not touch it |
| 7 | Is a structured audit event required before release? | Security | The INFO records of the log (rejected, started, completed) exist; a structured event is out of scope unless Security requires it, in which case a gate is added |
| 8 | Measure the peak heap of one large resource and of a graph of about 100 resources | Maintainers | Record the measurement in "Settings" |

## Deferred workstreams

Not part of this change's requirements. Nothing here is an accepted architectural decision.

### Capacity and operational readiness

A separate production-readiness workstream: the values of the timeouts, supported by measurements or a documented capacity analysis, a
size-limited read of one body (it needs a shared path in `ResourceService`), cancellation through `response.closeHandler` (a client that disconnects
fails the writer at its next write), and lease renewal. The limits that Core already had stay in force and are not part of the deferral: `resources.maxSize`,
the Jackson string limit, `adminExport.stallTimeout` of the bounded pipe, and the bounded-memory streaming.

### Approvals for binary and Prompt content

The behaviour of File and Skill content (one-pass copy through a fixed buffer, length and ETag checks, the WARN record of a File that changed but was
copied whole, the Skill folder structure, bytes as stored) is specified in the requirements of this change and implemented. What is deferred is
Security's acknowledgement of content as stored (a release gate) and a byte bound for Files.

### Full characterization of the schema library

The focused correctness tests for `$ref` into `$defs`, `oneOf`, `anyOf`, `allOf`, an invalid property and a refused `$ref` stay; a test of every JSON Schema
combination is not required.

### Platform-level outbound-HTTP concerns

Listed under "A remote application type schema is evaluated through Core's existing download". Not started.

### Other deferred items

Option C, an origin index in `MergedConfigStore`; tests of the write-path duplicate guards (single write, apply, validate and migrate have none); a structured audit event and
metrics; secret re-wrapping with an export key; typed references and enforcement of `acceptableResourceTypes`; a list of the references that were not
followed in the archive; reconciliation with `origin/rd/*`; and a secret rule for `application_properties`, `function.env` and `default_headers`.

## Impact on existing capabilities

**Shared code that changes** (additive unless stated):

| Area | Change | Effect on other callers |
|---|---|---|
| `ConfigEntityRedactor` | new; the projection (`valueToTree`) and the removal of `auth_settings` secrets, shared by `ConfigResourceController` and the export | the controller calls it for the Application and ToolSet GET; its answers are unchanged |
| `ApplicationSchemaService` | `resolveSchema` split into `resolveSchema` and a private `build(String, boolean quiet)`; new `buildExportSchema`, `collectMarkerReferences`, `EXPORT_SCHEMA_FACTORY`; the schema download takes a `quiet` flag, true only for the export: it then logs no endpoint, body, header or exception message, raises an exception without endpoint and cause, and restores and rethrows an interrupt | request processing passes `quiet` false and behaves exactly as before: its log text, its exception (message and cause), its wrapping of an interrupt and the status of the inner `HttpException` are unchanged and pinned by `ApplicationSchemaServiceTest` |
| `CatalogSchemaService` | new `collectMarkerReferences`, `EXPORT_SCHEMA_FACTORY` | none |
| `DialFileKeyword`, `DialResourceKeyKeyword`, `CatalogFileKeyword` | one `MarkerRecorder.record` call each | a map lookup and a type test per marked value; nothing when no recorder is registered |
| `ComplexResourceService` | new `getVersionFileStream` | none |
| `DiscardableZipOutputStream`, `ZipResponseStreamer` | `DiscardableZipOutputStream` moves out of `AdminSystemExportService` into a class of its own; the streaming of the response (pipe, headers, reset on failure) moves out of `AdminSystemExportController` into `ZipResponseStreamer`, which the system export and the export share. The system export gets a `500` instead of an exception when the executor refuses its task | none: the same code, statuses and archive |
| OpenAPI generator and annotations | `ApiOperation` gets `description`; `AnnotationEndpointCollector` (it also reads `AdminExportController`), `EndpointMetadata` and `SpecAssembler` carry it to the operation | existing operations render as before: none of them sets a description, and `EndpointMetadata.Endpoint` keeps its nine-argument constructor |
| `Proxy`, `AiDial`, `ControllerSelector`, `RouteTemplate` | the `adminExport` settings and the route `POST /v1/admin/export` (new; it does not exist on `development`) | none for existing routes |

The serialized `Config` shape of every response and the output of `POST /v1/admin/system-export` are meant to be unchanged.

**Left unchanged on purpose:** `MergedConfigStore` and every consumer of it (interface consumers call only `get()`; several controllers cast the store and call
`applyEntityWrite`, `applyBatch`, `getInvalidEntities`, `getFileSourcedConfig`); the six fail-fast marker-collector callers; `ConfigApplyService`,
`ConfigManifestSupport`, `PolicyController`, `ApiKeyStore` (the export only takes its mutation lock through the existing `getMutationLock()`); the
request-time schema factory; `downloadAppSchema`'s request, timeouts and result. No other module (`config`, `storage`, `credentials`) references
`MergedConfigStore`.

**Risks to watch in existing code, not changed here:** the replica path and in-place updates have no cross-type duplicate guard; `resurrectInvalidModels` stores
a model under its canonical-id key and `MergedConfigStorePartialUpdateTest.interceptorWriteResurrectsPreviouslySkippedModel` pins it (changing it would break
that test); `ConfigFileMigrateController.collectManaged` decides "already in a blob" with `hasResource`, so an unparsable blob probably counts as migrated (an
estimate); the request-time schema factory fetches remote `$ref`.

**Public resources.** A `public/` resource is a logical bucket whose physical form is the path prefix `public/` in one blob container
(`ResourceDescriptor.PUBLIC_BUCKET`, `isPublic()` is exact equality with the location). Sub-locations (`public/deployments/<id>/`, `public/publications/...`) are
not `public/` and are admin-only special cases in `AccessService`. Applications and ToolSets in `public/` are written through the Resource API
(`PUT /v1/applications/public/<path>`, admin bulk apply, publication approval), stored under `public/applications/<path>`, never loaded by config loading, and
never pushed into the merged `Config` (`ConfigApplyService.applyApplication` deliberately skips it and does not check deployment-id uniqueness). They are reached
only by URL. So a public name cannot clash with a short name (a bare name is not a URL), a platform Application and a public Application of one name are distinct
resources with distinct keys, and none of the short-name signals applies to a public target: it is read by descriptor, authorized by `AccessService`, and
exported with origin `public`. Which types may live in which bucket is `EntityBucketBinding`: applications and toolsets in `public` and `platform`; files, prompts and
conversations in `public` or a user bucket; models, interceptors, translators, roles, keys, routes, schemas, catalog schemas, policies and settings in `platform` only.
