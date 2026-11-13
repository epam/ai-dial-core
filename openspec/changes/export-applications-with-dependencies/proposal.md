## Why

An administrator who wants to move or back up a few Applications has one option in Core, `POST /v1/admin/system-export`. It dumps
every blob under `public/` and `platform/` with no selection and no dependency information. Core has no API that says which other
resources an Application needs, so a client has to guess: schema-marked files, prompts, skills, toolsets, other applications,
interceptors, the application type schema, `dependencies`, and short deployment names whose origin (file config or platform blob)
Core does not record.

This change adds an export of selected Applications together with the dependencies the caller may read. It writes each resource the
way the admin config API shows it, one resource at a time, so that its memory does not grow with the size of the graph.

## What Changes

- `POST /v1/admin/export` is a new route (`RouteTemplate.ADMIN_EXPORT`, `ControllerSelector`, a new `AdminExportController`; it does
  not exist on `development`). It takes a body with `roots`, a list of Applications in `public/` or `platform/`, and returns a ZIP.
  An empty body, an unknown field or a root that is not such an Application is `400`. The export of whole buckets stays
  `POST /v1/admin/system-export`.
- The export works as a stream, one resource at a time: read the resource from storage, bind it to its entity (Application, Model,
  Interceptor, ToolSet), send the references found in the entity to a queue, decrypt the secrets that the admin GET shows a hint of,
  serialize the entity, remove the `auth_settings` secrets, write the entry, forget the resource. Each resource is marked as met before it
  is queued, so a cycle or a diamond is exported once. Memory is the queue, the set of resources met and the resource in hand; a File or a
  file of a Skill goes through a 32 KiB buffer.
- The ZIP holds exactly the manifest and the resources: `.dial-export/manifest.json` first, then, in breadth-first order, one entry
  per resource at its storage path (entities of the file configuration as `.dial-export/config/<type>/<segment>.json`). It holds no
  list of references, no checksum and no metadata entry. It is not in the layout of the system export and the reader and the importer of
  the system export cannot read it.
- Dependencies are found from the fixed fields and from `dial:resource` and `dial:file` markers of every Deployment
  (Application, Model, Interceptor and ToolSet) and followed through all four. Schemas, Prompt, File and Skill are exported when
  referenced and are leaves: their content is not examined.
- Short names are resolved against the merged configuration with their origin (file or platform), using read-only signals.
  A name whose origin cannot be established is not followed, never guessed. Remote application type schemas are fetched live
  through the existing Runner download.
- A reference that is not followed (missing, unsupported, external, unresolved short name, unknown origin, unavailable schema) is one
  record in the server log with the resource key, the JSON pointer and the reason, never the value. The archive does not list it.
- A File, or a Skill, that changes while it is copied but is written whole is one WARN record in the server log (the resource key and
  the ETag before and after, never a value), and the export goes on. A File that is gone, a stream that ends early or is longer than its
  length, or a failed read cuts the stream.
- The export takes the bucket locks of `public/` and `platform/` before the response head and holds them to the last byte, as the
  system export does. A held lock is `409`. A public root that the caller cannot read is `403` and a missing root is `404`, both before
  the head.
- After the head the deadline, a client that does not take the data within `stallTimeout`, a body that cannot be bound, a File that
  vanished or any other failure cuts the stream: the client sees a transport error and a ZIP without its central directory, and must
  treat the export as failed.
- Secrets: an Application or ToolSet is written as the projection of its entity, which holds no encrypted field; `client_secret` and
  `code_verifier` of every `auth_settings` are removed and the masked `client_secret_hint` is kept, as the admin GET shows it. In an
  application type schema the upstream keys are removed. A Prompt and the bytes of a File and of a Skill are written as stored.
- The `adminExport` settings have code defaults: a `deadline` and a `stallTimeout` that together fit in the lease of the bucket locks.

## Out of Scope

- Import of the package (it has its own layout, which the system import does not read), secret re-wrapping, user and app buckets, a
  structured audit event and metrics, typed references.
- Roots other than Applications; Project as a dependency type; folder expansion; scanning the content of schema documents, Prompts, Files and Skills for references.
- A strict mode, request flags (`include`, `onUnresolved`) and a `422` answer: the request holds `roots` only.
- A list of the references that were not found, in the archive or anywhere but the server log; a checksum list and a per-entry
  metadata entry; identical bytes for two exports of one state.
- A change of the output of `POST /v1/admin/system-export`, of `MergedConfigStore` (an origin index is a documented alternative), of
  the request-time schema factory and of the legacy Admin Backend.
- Network safeguards for the Runner schema download (allow-list, address checks, size and time bounds): platform-level, not
  part of this feature.

## Capabilities

### New Capabilities

- `resource-export`: export of selected Applications with their available dependencies as a ZIP: request and response contract, the
  streaming flow, the archive layout, dependency discovery, identity and origin, findings in the server log, secret exclusion,
  authorization, bucket locks, the deadline, failure behaviour.

### Modified Capabilities

No delta spec is written for another capability. The change does touch shared code (see Impact), and the items there that alter
existing behavior are not described by a delta of `config-store`, `system-export` or another capability. Related capabilities
are read and reused: `system-export` (the bucket locks and the audit records), `config-store` (the merged configuration that names
are resolved against), `resource-access-control` (the check that authorizes a public resource).

## Impact

- API: `POST /v1/admin/export` is added (marked `x-preview`); `docs/open_api_core.yaml` is generated from the annotations. No
  existing route changes its meaning.
- Code that only this feature uses: `AdminExportController`,
  `ZipResponseStreamer` and the `ExportStreamer`, `ExportResourceProjector`, `ExportArchive`, `ExportTypePolicy`,
  `ExportFileEntities`, `ExportRequestParser`, `DeploymentEdgeDiscovery`, `ShortNameResolver` and `SchemaReferenceResolver` services in
  `server`; the `validation` marker classes (`MarkerCollection`, `MarkerRecorder` and the issue types); the `adminExport` settings.
- Shared code that changes:
  - `ConfigEntityRedactor` holds the projection and the removal of `auth_settings` secrets that `ConfigResourceController` and the export
    share; the controller's answers are the same as without the export.
  - `DiscardableZipOutputStream` is a class of its own instead of a nested class of `AdminSystemExportService`, and the streaming of the
    response moves from `AdminSystemExportController` into `ZipResponseStreamer`, so that the system export and the export share both.
    The archive and the status codes of the system export are unchanged; when the executor refuses its task it answers `500` instead of
    throwing out of the handler.
  - `ComplexResourceService.getVersionFileStream` is added; no existing method changes.
  - `ApplicationSchemaService`: the shared `resolveSchema` is split around a private `build`, and the export gets
    `buildExportSchema`, `collectMarkerReferences` and its own schema factory. Request-time behavior is unchanged: the download takes a
    `quiet` flag that only the export sets, so request processing keeps its log text, its exception, its wrapping of an interrupt and
    the status of the inner `HttpException`.
  - `CatalogSchemaService` gets `collectMarkerReferences` and a schema factory of its own; `DialFileKeyword`,
    `DialResourceKeyKeyword` and `CatalogFileKeyword` call `MarkerRecorder.record`, which does nothing unless a recorder is
    registered.
  - OpenAPI: `@ApiOperation` gets `description`, which `AnnotationEndpointCollector`, `EndpointMetadata` and `SpecAssembler` carry to the
    operation; the operation is also added to the controllers that the collector reads.
  - `Proxy`, `AiDial`, `ControllerSelector` and `RouteTemplate`: the settings and the route.
- Admin writes: while an export runs, the writes that take the bucket locks of `public/` and `platform/` (config API writes,
  `/v1/admin/apply`, file migration, config rebuild, a publication approval, a Skill write or copy, a move) wait for it. A plain write of a Prompt or a File takes no such lock and is not blocked.
- Docs: `docs/admin-export.md`, `docs/error_codes.md`, `README.md` (settings), `docs/open_api_core.yaml` (generated).
- Settings: the `adminExport` group, all with code defaults.
- Release: blocked by the Security and Maintainer approvals listed under the release gates in tasks.md (secret policy,
  File, Skill and Prompt content as stored, scope).
- Separate changes, not started: import of the package; production readiness of the endpoint (a size-bounded
  read of one body, capacity benchmarking); platform-level outbound HTTP for Runner schemas.
