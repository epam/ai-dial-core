## Why

An administrator who wants to move or back up a few Applications has one option in Core, `POST /v1/admin/system-export`. It dumps
every blob under `public/` and `platform/` with no selection and no dependency information. Core has no API that says which other
resources an Application needs, so a client has to guess: schema-marked files, prompts, skills, toolsets, other applications,
interceptors, the application type schema, `dependencies`, and short deployment names whose origin (file config or platform blob)
Core does not record.

This change adds an export of selected Applications together with the dependencies the caller may read, and says explicitly what
could not be included and why.

## What Changes

- `POST /v1/admin/export` is a new route (`RouteTemplate.ADMIN_EXPORT`, `ControllerSelector`, a new `AdminExportController`; it does
  not exist on `development`). It takes a body with `roots`, a list of Applications in `public/` or `platform/`, and returns a ZIP.
  An empty body, an unknown field or a root that is not such an Application is `400`. The export of whole buckets stays
  `POST /v1/admin/system-export`.
- The ZIP has the layout of the system export: the technical manifest first, the dependency registry second
  (`.dial-export/dependency-registry.json`), each resource at its storage path after its metadata entry, entities of the file
  configuration as fragments, and a verification entry last.
- Dependencies are found from the fixed fields and from `dial:resource` and `dial:file` markers of every Deployment
  (Application, Model, Interceptor and ToolSet) and followed recursively through all four. Schemas, Prompt, File and Skill are
  included when referenced and are leaves: their content is not examined.
- Short names are resolved against the merged configuration with their origin (file or platform), using read-only signals.
  A name whose origin cannot be established is reported, never guessed. Remote application type schemas are fetched live
  through the existing Runner download.
- Marker discovery is a new, additive, non-throwing method on `ApplicationSchemaService` and `CatalogSchemaService`; discovery
  goes on after an invalid reference and incomplete discovery is shown in the registry.
- Supported types: Application, ToolSet, Model, Interceptor and the two schema kinds in `platform/`; Application, ToolSet,
  Prompt, File and Skill in `public/`. Any other type, location or a folder is `unsupported` and listed, not read.
- Discovery reads the fixed fields and the markers (`dial:resource`, `dial:file`) of `catalog_properties` and, for an Application,
  of `application_properties` against its application type schema. The schema is built once per schema id and export; a schema
  that cannot be built is an unresolved edge and an incomplete discovery.
- Findings never fail the request: missing, unsupported, external and unresolved dependencies and incomplete discovery are listed
  in the registry and the package is still written. Only a condition that prevents a safe archive is a status
  (`400`, `403`, `404`, `409`, `413`, `500`, `503`, `504`).
- Each JSON resource is read once, redacted, and kept as redacted bytes that the archive writes; a JSON resource that changes
  after planning no longer fails the export. Files and the files of a Skill are streamed as stored with bounded memory and
  checked by length and etag.
- Secrets: annotated secret fields (ciphertext included), ToolSet and external-service auth secrets and, in an application type
  schema, the route key are removed. Every other value, including `application_properties` (which is scanned for references but exported as stored), `function.env`, `default_headers`,
  URLs, free text and a string that starts with `ENC[` in a field that is not annotated, is exported as stored and not
  inspected, and so are the bytes of a File and the body of a Prompt. `credentials/` and `encryption_keys/` are never exported.
- Limits are enforced with code defaults (`adminExport` settings): roots, resources, depth, references, size, deadline and one
  cluster-wide slot. The values are not approved. `maxRetainedMiB` is enforced twice, on the summed string length of the stored
  bodies that were read and on the summed byte length of the redacted bytes held until the archive is written; either above the
  limit is `413`. The heap for bodies is therefore up to about twice the limit, plus the largest transient body.

## Out of Scope

- Import of the package, secret re-wrapping, user and app buckets, audit and metrics, typed references.
- Roots other than Applications; Project as a dependency type; folder expansion; scanning the content of schema documents, Prompts, Files and Skills for references.
- A strict mode, request flags (`include`, `onUnresolved`) and a `422` answer: the request holds `roots` only.
- A change of the output of `POST /v1/admin/system-export`, of `MergedConfigStore` (an origin index is a documented alternative), of
  the request-time schema factory and of the legacy Admin Backend.
- Network safeguards for the Runner schema download (allow-list, address checks, size and time bounds): platform-level, not
  part of this feature.

## Capabilities

### New Capabilities

- `resource-export`: export of selected Applications with their available dependencies as a ZIP described by a technical manifest
  and a dependency registry: request and response contract, dependency discovery, identity and origin, unresolved-dependency
  reporting, secret exclusion, authorization, consistency.

### Modified Capabilities

No delta spec is written for another capability. The change does touch shared code (see Impact), and the items there that alter
existing behavior are not described by a delta of `config-store`, `system-export` or another capability. Related capabilities
are read and reused: `system-export` (the archive layout and the metadata entry), `config-store` (the merged configuration that
names are resolved against), `resource-access-control` (the check that authorizes a public resource).

## Impact

- API: `POST /v1/admin/export` is added (marked `x-preview`); `docs/open_api_core.yaml` is generated from the annotations. No
  existing route changes its meaning.
- Code that only this feature uses: `AdminExportController`, `ExportSlot`, `DeadlineInterrupt`, `ZipResponseStreamer` and the
  `Export*`, `DeploymentEdgeDiscovery`, `ShortNameResolver` and `SchemaReferenceResolver` services in `server`; the `validation`
  marker classes (`MarkerCollection`, `MarkerRecorder` and the issue types); the `adminExport` settings.
- Shared code that changes (each item is in `git diff development`):
  - `SecretFieldProcessor.applyStrip` now also matches the `@JsonAlias` names of a field. The existing caller,
    `ConfigResourceController.projectInvalidItem` (the projection of an invalid stored entity), now also removes an aliased
    spelling of an encrypted field; this is a change of an existing read path.
  - `AdminSystemExportController` now streams through `ZipResponseStreamer`, shared with the new controller, and
    `AdminSystemExportService.BlobMetadata` is package-private instead of private. The output, status codes and failure behavior
    of the system export are meant to be unchanged. `AdminSystemExportApiTest` and `AdminSystemExportServiceTest` exist and
    pass with this branch; `AdminSystemExportServiceTest` changed in one line, to refer to the top-level `DiscardableZipOutputStream`
    that replaced a nested copy.
  - `ComplexResourceService.getVersionFileStream` is added; no existing method changes.
  - `ApplicationSchemaService`: the shared `resolveSchema` is split around a private `buildSchema`, and the export gets
    `buildExportSchema`, `collectMarkerReferences` and its own schema factory. Request-time behavior is unchanged: the download takes a
    `quiet` flag that only the export sets, so request processing keeps its log text, its exception, its wrapping of an interrupt and
    the status of the inner `HttpException`.
  - `CatalogSchemaService` gets `collectMarkerReferences` and a schema factory of its own; `DialFileKeyword`,
    `DialResourceKeyKeyword` and `CatalogFileKeyword` call `MarkerRecorder.record`, which does nothing unless a recorder is
    registered.
  - OpenAPI: `@ApiOperation` gets `description` and `requestBodyRequired` (default true; no endpoint sets it to false), with
    matching changes in `AnnotationEndpointCollector`, `EndpointMetadata`, `OpenApiRequestBodyBuilder` and `SpecAssembler`.
  - `Proxy`, `AiDial`, `ControllerSelector` and `RouteTemplate`: the settings, the redactor and the route.
- Docs: `docs/admin-export.md`, `docs/error_codes.md`, `README.md` (settings), `docs/open_api_core.yaml` (generated).
- Settings: the `adminExport` group, all with code defaults.
- Release: blocked by the Security and Maintainer approvals listed under the release gates in tasks.md (secret policy,
  unredacted File, Skill and Prompt content, scope).
- Separate changes, not started: import of the package; production readiness of the endpoint (approved limit values, a
  `capped` result, more than one slot, capacity benchmarking); platform-level outbound HTTP for Runner schemas.
