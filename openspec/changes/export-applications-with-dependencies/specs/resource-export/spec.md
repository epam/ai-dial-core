## ADDED Requirements

### Requirement: The selective export is POST /v1/admin/export
Core SHALL serve the export of selected Applications on `POST /v1/admin/export`. The request body is required. No other path serves the selective export. The export of whole buckets stays `POST /v1/admin/system-export`; its behavior and its archive do not change.

#### Scenario: System export is not affected
- **WHEN** an explicit admin sends `POST /v1/admin/system-export`
- **THEN** the response is the archive of both buckets as before, whose manifest has exactly the fields `formatVersion`, `coreVersion` and `createdAt`

#### Scenario: No second path
- **WHEN** a caller sends any method to `/v1/admin/export/applications`
- **THEN** the response is `404`, because no such route exists

### Requirement: A successful export is a ZIP attachment
A successful response SHALL be `200` with `Content-Type: application/zip` and `Content-Disposition: attachment; filename="dial-export-<yyyyMMdd'T'HHmmss'Z'>.zip"`, where the time is the start of the export in UTC.

#### Scenario: Body that selects Applications
- **WHEN** an explicit admin sends `POST /v1/admin/export` with `{"roots":[{"type":"application","url":"applications/public/a__1.0.0"}]}` and the Application exists
- **THEN** the response is `200` with `Content-Type: application/zip` and a `Content-Disposition` file name that starts with `dial-export-`

### Requirement: The request names the selected Applications
The request body SHALL be a JSON object that holds `roots` only: a non-empty list of `{"type": "application", "url": "<resource URL>"}`. Each URL SHALL name an Application, not a folder, in the `public` or the `platform` bucket (for example `applications/public/folder/name__1.0.0`). The same root named twice is exported once.

#### Scenario: Root named twice
- **WHEN** the same Application is named twice in `roots`
- **THEN** it is exported once, and `roots` of the registry lists it once

### Requirement: An invalid request is refused with 400 before any byte
Core SHALL answer `400`, before it writes any archive byte, for an empty or whitespace-only body, a body that is not valid JSON, a field other than `roots`, a missing or empty `roots`, more roots than `maxRoots`, a `type` other than `application`, a URL that does not parse, a URL of another resource type, a folder, or a bucket other than `public` and `platform`. The message MAY name `roots` or the index of a root and SHALL hold no submitted value.

#### Scenario: Body that is not a selection
- **WHEN** an explicit admin sends no body, a body of only whitespace, a body that is not valid JSON, or `{"roots":[]}`
- **THEN** the response is `400` and carries no archive

#### Scenario: Field that is not part of the request
- **WHEN** the body holds a field besides `roots`, for example `"extra":"sk-abc123"`
- **THEN** the response is `400` and the message does not contain the submitted value

#### Scenario: Root that is not an eligible Application
- **WHEN** a root has `"type":"model"`, or its URL names a Model, a folder, or an Application in a user bucket
- **THEN** the response is `400` and carries no archive

#### Scenario: Root that is rejected
- **WHEN** a root has a URL that is rejected, for example one that holds a token in its path
- **THEN** the message may name the index of the root and does not contain the URL or any part of it

#### Scenario: Too many roots
- **WHEN** `roots` holds more Applications than `adminExport.maxRoots` (default 100)
- **THEN** the response is `400` and none of the roots is looked at

### Requirement: Only an explicit admin may export
Core SHALL allow the export only to a caller with explicit admin access (`access.admin.rules`), and SHALL answer every other caller with `403` before it reads the body, validates it or looks at any root. A caller that holds a per-request key (an application), a caller authenticated only by an API key, and every caller of a Core that has no admin rules SHALL get `403`.

#### Scenario: Caller is not an explicit admin
- **WHEN** an authenticated user without explicit admin access, a caller that holds a per-request key, or any caller of a Core with no `access.admin.rules` (an admin by role included) sends a valid request, or an empty body
- **THEN** the response is `403`, nothing about the roots is disclosed and no archive is produced

### Requirement: Every resource is classified by its location before it is read
Core SHALL classify the decrypted bucket location of every root and dependency before it reads anything, and SHALL check that the type is allowed in the bucket before it reads. A resource in `platform/` SHALL be authorized by the explicit-admin check that the request passed. That a reference parses, resolves or exists SHALL NOT be treated as authorization.

#### Scenario: Platform dependency
- **WHEN** an explicit admin exports an Application that depends on the platform ToolSet `t`
- **THEN** the ToolSet is read and exported without a check of the caller's right to read it

### Requirement: A public resource is authorized on its own
Core SHALL check the caller's right to read every root and dependency in `public/`, each resource on its own: selecting a public root does not authorize its dependencies. A resource that the caller may not read SHALL be answered with `403` before the first response byte, with nothing read and nothing disclosed.

#### Scenario: Public root
- **WHEN** an explicit admin selects `applications/public/a__1.0.0`
- **THEN** the caller's right to read the root is checked, an explicit admin holds full access to `public/`, and the root is exported

#### Scenario: Public dependency is authorized on its own
- **WHEN** an explicit admin exports an Application that depends on `applications/public/b__1.0.0` by URL
- **THEN** the caller's right to read the dependency is checked and it is read, and the selection of the root does not authorize it

#### Scenario: Public dependency that the caller may not read
- **WHEN** an Application refers to a public resource that the caller may not read
- **THEN** the response is `403` before the first byte, the resource is not read and no archive is produced

### Requirement: The roles of a deployment are not checked
Core SHALL NOT check the `user_roles` of a deployment when it resolves a name.

#### Scenario: Restricted deployment
- **WHEN** a short name resolves to a deployment whose `user_roles` the exporting admin does not hold
- **THEN** it resolves and is exported, and it is not reported as forbidden

### Requirement: The request is checked and the closure is built before the first byte
Core SHALL parse the request, authorize and confirm every root, and build the whole closure of resources, with every JSON body read, redacted and kept, before it writes the first response byte, so that these failures are plain statuses with no archive. A selected Application that does not exist SHALL be answered with `404`. A failure after the response head SHALL end the connection without the central directory of the ZIP, so that the bytes received are not a valid archive.

#### Scenario: Root does not exist
- **WHEN** a root names an Application that does not exist
- **THEN** the response is `404` and carries no archive

#### Scenario: Failure after the head
- **WHEN** reading a resource fails after the first entry has been streamed
- **THEN** the connection is reset and the bytes received are not a valid ZIP

### Requirement: Failures before the head are 500 or 503
Core SHALL answer `500` with the message `Failed to prepare the export`, which holds no value, when a stored body cannot be read, parsed, bound or redacted, a resource path is unsafe, or another failure happens before the head. It SHALL answer `500` with `Failed to export the Applications` when the response streamer fails before it writes the head, and `503` when no redaction policy is configured.

#### Scenario: Selected Application that cannot be bound
- **WHEN** the stored body of a selected Application is valid JSON that cannot be bound to its type
- **THEN** the response is `500` before the first byte and no archive is written

#### Scenario: Dependency that cannot be bound
- **WHEN** the stored body of a dependency (a Model, an Interceptor, a ToolSet or an Application) is valid JSON that cannot be bound to its type
- **THEN** the dependency is exported as redacted JSON, its references are not examined, the registry lists an issue of kind `instance-invalid` for it at the pointer `""`, `discovery.complete` is false, and the response is `200`

#### Scenario: No redaction policy
- **WHEN** no redaction policy is configured and the request passes its checks and the plan
- **THEN** the response is `503` and no body leaves the system

#### Scenario: Streamer fails before the head
- **WHEN** the archive is prepared and the response streamer then fails before it writes the head
- **THEN** the response is `500` with the message `Failed to export the Applications`

### Requirement: Statuses are decided in a fixed order
Core SHALL decide the status in this order: `403` for a caller that is not an explicit admin, `400` for an invalid request, `409` when the slot is held, then the result of the plan (`403` for a denied `public/` resource, `404` for a missing root, `413`, `504` or `500`), and last `503` when no redaction policy is configured. The slot is taken before the plan, so a held slot answers `409` before a `404` or a public `403`.

#### Scenario: Slot held and root missing
- **WHEN** the slot is held and a request names a root that does not exist
- **THEN** the response is `409`, not `404`

#### Scenario: Request invalid and slot held
- **WHEN** the slot is held and the body is not a valid selection
- **THEN** the response is `400`

### Requirement: Dependency findings and incomplete discovery never fail the export
A dependency that is missing, unsupported, external or unresolved, incomplete discovery, and a warning SHALL NOT change the status: the response is `200`, the package holds every root and every dependency that could be resolved, and the registry lists each finding. A `200` is not a complete package: it lacks every `unsupported` and `unresolved` dependency, which `summary.edges` counts.

#### Scenario: Dependency findings do not fail the request
- **WHEN** an Application has a missing dependency, an unsupported one, an external URL, an unresolved short name and a schema that cannot be loaded
- **THEN** the response is `200`, the archive holds every root and every dependency that could be resolved, the registry lists each finding with its status, and `summary.edges` counts the `unsupported`, `external` and `unresolved` ones

### Requirement: The closure is bounded in resources, depth and references
The closure SHALL hold at most `maxNodes` (500) resources and at most `maxDepth` (10) references from a root, counting every followed Application, Model, Interceptor and ToolSet; the references of one body and of all bodies together SHALL be at most `maxEdges` (10000). A bound that is exceeded answers `413` before the first byte and cuts nothing. A body whose marker references of both property sets together exceed `maxEdges` is refused before any is classified.

#### Scenario: Too many resources
- **WHEN** the closure holds more than `maxNodes` resources, Models, Interceptors and ToolSets included
- **THEN** the response is `413`, the message names the bound and no value, and nothing is exported

#### Scenario: Too deep
- **WHEN** a chain of references from a root, through an Application, a ToolSet and an Interceptor for example, is longer than `maxDepth`
- **THEN** the response is `413`

#### Scenario: Too many references
- **WHEN** one body, or all bodies together, hold more than `maxEdges` references
- **THEN** the response is `413` and nothing is cut

#### Scenario: Too many catalog markers
- **WHEN** the application and catalog properties of one body together hold more marker references than `maxEdges`
- **THEN** the response is `413` before any of them is classified, so no storage is probed for them

### Requirement: One body is bounded in size
`maxResourceMiB` (4) SHALL bound the string length of one stored body, in MiB; a longer body answers `413`. The sums of `maxRetainedMiB` are a separate requirement.

#### Scenario: Body too large
- **WHEN** one stored body is longer than `maxResourceMiB`
- **THEN** the response is `413` and nothing is exported

### Requirement: The export has a deadline
The export SHALL end within `deadline` (180000 ms) from the start to the last byte: a deadline that passes before the head answers `504`. `deadline` plus `stallTimeout` (default 60000 ms) SHALL stay below the 300000 ms of the lease that holds the slot, which is not renewed; Core checks this at startup.

#### Scenario: Deadline passes before the head
- **WHEN** the deadline passes while the closure is built
- **THEN** the response is `504`

#### Scenario: Deadline passes after the head
- **WHEN** the deadline passes while the archive is written
- **THEN** the connection is reset

### Requirement: One selective export runs at a time in the cluster
Core SHALL allow one selective export at a time in the cluster: a second request while the slot is held SHALL be answered with `409`, and the slot SHALL be released when the export ends, fails or loses its client.

#### Scenario: Second export while the slot is held
- **WHEN** a selective export is running and another admin sends a valid request
- **THEN** the second response is `409`, and after the first export ends a new request is served

#### Scenario: Slot after a failure
- **WHEN** an export ends with a missing root, a failure before the head, a failure after the head or a client that disconnects
- **THEN** the slot is released

### Requirement: A type that is not listed is unsupported
Core SHALL treat a reference to any resource type that these requirements do not list as `unsupported`. Only the exact bucket locations `public/` and `platform/` are eligible; the `file` origin is the loaded `aidial.config.json`. A support SHALL NOT be inferred from the existence of a bucket or of a type.

#### Scenario: Eligible locations only
- **WHEN** a reference points at `public/publications/<id>/`, `public/deployments/<id>/`, a review bucket, a function folder, a user bucket or an app bucket
- **THEN** nothing is read and the edge is `unsupported`, whatever the caller's access, and a reference that merely parses is not treated as eligible

#### Scenario: Folder
- **WHEN** a reference names a folder, or `function.source_folder` is set
- **THEN** no folder content is exported and the edge is `unsupported`

### Requirement: Applications and ToolSets are supported
An Application SHALL be supported as a root and as a dependency in `platform/`, `public/` and the `file` origin. A ToolSet SHALL be supported as a dependency in the same three places and never as a root. Both are written as redacted JSON and their references are followed.

#### Scenario: Supported public dependencies
- **WHEN** an Application names a public Application, ToolSet, File, Skill or Prompt that exists and the caller may read
- **THEN** the resource is included, and it is never a root unless it is an Application

### Requirement: Models and Interceptors are supported in platform and file only
A Model and an Interceptor SHALL be supported as dependencies in `platform/` and the `file` origin, never in `public/` and never as roots. Each is written as its definition in redacted JSON, and its references are followed.

#### Scenario: Unsupported public Model and Interceptor
- **WHEN** an Application names `models/public/m` or `interceptors/public/i`
- **THEN** the edge is `unsupported` and nothing is read

### Requirement: Schemas are supported in platform and file only
An application type schema and a catalog schema SHALL be supported as dependencies in `platform/` and the `file` origin, never in `public/` and never as roots. Each is written as redacted JSON and its content is not read for references.

#### Scenario: Schema is a leaf
- **WHEN** an exported application type schema holds text that names an Application
- **THEN** that Application is not added to the closure

### Requirement: File, Skill and Prompt are supported in public only
A File, a Skill and a Prompt SHALL be supported as dependencies in `public/` only. A File is written as its bytes as stored, a Skill as the files of its current version as stored, and a Prompt as its body as stored. A Skill is bound to the `public` bucket only.

#### Scenario: Skill outside the public bucket
- **WHEN** an Application names a Skill in a bucket other than `public`
- **THEN** the edge is `unsupported` and nothing is read

### Requirement: Other types are not exported and not read
Project, Conversation, Role, Route and Upstream resources, and `ProjectKey`, SHALL NOT be exported or read: a reference to a Project or a Conversation is `unsupported`. The fields `user_roles`, `interfaces.*.translator` and inline `routes` of an Application SHALL NOT be examined, so they give no edge.

#### Scenario: Unsupported conversation
- **WHEN** an Application names `conversations/public/c`
- **THEN** the edge is `unsupported` and nothing is read

#### Scenario: Not-examined fields
- **WHEN** an Application's `user_roles`, `interfaces.*.translator` or inline `routes` name other resources
- **THEN** no edge is produced and nothing is read

### Requirement: Identity includes the origin
Core SHALL identify every exported resource by type, origin, bucket location and path, and a schema by its body `$id`. The origin SHALL be `public`, `platform` or `file`; a target of an `unsupported` edge in any other location has origin `other` and no bucket location.

#### Scenario: Same path in different types and origins
- **WHEN** resources share a path but differ in type or origin
- **THEN** their keys differ and every reference uses its key

### Requirement: Every resource has a key
Core SHALL name each resource of the registry by the key `<type>:<origin>:<segment>`, where the segment is the path with every byte outside `A-Z a-z 0-9 . _ - /` written as `~` and two hex digits, or, for a schema, the first 32 hex digits of the SHA-256 of its `$id`. The bucket is not part of the key. The type names are `application`, `toolset`, `model`, `interceptor`, `application-type-schema` and `catalog-schema`.

#### Scenario: Bucket is not in the key
- **WHEN** a resource in `platform/` or `public/` is exported
- **THEN** its key has three parts and holds no bucket location

#### Scenario: Characters that a key escapes
- **WHEN** a path holds `:`, `~` or `%`
- **THEN** the key and the entry name stay unambiguous and two paths never give one key

### Requirement: Every resource has an entry name
The entry name of a blob-backed resource SHALL be its storage path; that of a `file`-origin resource SHALL be its fragment path (see "A `file`-origin resource is written at a fragment path").

#### Scenario: Repeated discovery of one resource
- **WHEN** one resource is reached by several paths or by several reference forms, or a selected root is named more than once
- **THEN** it is one resource with one entry in the archive, and each reference to it is its own dependency

### Requirement: Unsafe paths and duplicates fail before the first byte
Core SHALL refuse a resource path, and the relative path of a Skill file, that has an empty, `.` or `..` segment, before the first byte, and SHALL fail the export when two resources or files would have the same key or the same entry name.

#### Scenario: Two resources with one entry name
- **WHEN** two resources of a closure would have the same archive entry name
- **THEN** the export fails before the first byte and no archive is produced

#### Scenario: Unsafe path
- **WHEN** the path of a resource has an empty, `.` or `..` segment
- **THEN** the response is `500` before the first byte and no archive is produced

#### Scenario: Unsafe path inside a Skill
- **WHEN** a stored file of a Skill has a relative path with an empty, `.` or `..` segment
- **THEN** the response is `500` before the first byte, with no archive and no path in the message

### Requirement: Dependencies come from fixed fields and schema markers
For every Application, Model, Interceptor and ToolSet it exports, Core SHALL discover references from the fields `interceptors`, `dependencies`, `catalog_schema_id`, `icon_url`, and the values under `dial:resource` and `dial:file` markers of `catalog_properties`, as the catalog schema defines them. Every reference SHALL be its own edge with its JSON pointer: the same value at two pointers is two edges. An invalid, external, dangling or malformed reference SHALL NOT stop discovery of the others.

#### Scenario: Fixed fields of an Application
- **WHEN** an Application has `application_type_schema_id`, `interceptors: ["i1"]` and `dependencies` with a platform ToolSet URL
- **THEN** the schema, `i1` and the ToolSet are exported or listed with a status, each at the pointer of its reference

#### Scenario: Fixed fields of a Model, Interceptor and ToolSet
- **WHEN** a Model, an Interceptor and a ToolSet that are exported each hold `interceptors`, `dependencies` or `icon_url` that name other resources
- **THEN** each reference is an edge of its holder with its own pointer and is exported or listed with a status

#### Scenario: Marker in catalog properties
- **WHEN** a catalog property under a `dial:file` marker names `files/public/a/logo.svg`, and the file exists
- **THEN** the file is exported and the edge's pointer starts with `/catalog_properties`

#### Scenario: Dangling and valid references
- **WHEN** an Application holds one reference whose target does not exist and two valid platform URLs
- **THEN** the two valid resources are in the archive and the dangling one is listed as `unresolved`

### Requirement: Application-only fields and application properties
An Application, and no other type, SHALL also have `application_type_schema_id`, `function.source_folder` and `application_properties` examined. The properties SHALL be scanned for the `dial:resource` and `dial:file` markers of the schema, as `catalog_properties` is, and SHALL still be exported as stored. Discovery SHALL NOT parse a resource that is not a Deployment, and SHALL NOT authorize or fetch anything.

#### Scenario: Application properties are scanned
- **WHEN** `application_properties` names a Skill, a File and a public ToolSet through markers
- **THEN** each is an edge at `/application_properties/<path>` and is exported when it exists, and the exported Application holds `application_properties` as stored

#### Scenario: Application without schema or properties
- **WHEN** an Application has no `application_type_schema_id`, or empty `application_properties`
- **THEN** no edge comes from `application_properties`

#### Scenario: One build per schema
- **WHEN** several Applications of one export name the same schema id
- **THEN** the schema is built once, with at most one Runner download

#### Scenario: Schema id of a Model
- **WHEN** a Model body holds a field that looks like `application_type_schema_id`
- **THEN** it is not read as a reference

### Requirement: A reference value is classified in a fixed order
Core SHALL classify a value of `dependencies`, of a marker or of `icon_url` in this order: a blank value is `malformed`; an absolute URL is `external` (value kind `external-url`) and is never fetched; a value with an empty, `.` or `..` path segment, also percent-encoded (`%2e%2e`), is `malformed` and is never read; a value that parses as a resource URL names its resource; a value without `/` is a short name (value kind `short-name`); any other value is `malformed`.

#### Scenario: Path traversal
- **WHEN** a value or `icon_url` is `files/public/a/../b` or `files/public/a/%2e%2e/b`
- **THEN** the dependency is `unresolved` with value kind `malformed`, nothing is read, and no answer repeats the value

#### Scenario: Malformed marked value
- **WHEN** a marked value holds `/` but does not parse as a resource URL and is not an absolute URL
- **THEN** the dependency is `unresolved` with value kind `malformed`

### Requirement: A name that nothing holds is unresolved
A short name or a malformed value that no entity holds SHALL be `unresolved`. An entry of `interceptors` is always a short name of an interceptor; a value with `/` is `unresolved`, kind `malformed`.

#### Scenario: Marked short name that resolves
- **WHEN** a marked value is `gpt-4o` and a platform Model of that name exists
- **THEN** the Model is included and the dependency records the pointer of the value

#### Scenario: Short name that nothing holds
- **WHEN** a marked value is a short name that no entity holds, no record skipped, and no platform blob has
- **THEN** the dependency is `unresolved` with value kind `short-name` and nothing is included

#### Scenario: Interceptor entry with a slash
- **WHEN** an `interceptors` entry holds `/`
- **THEN** the dependency is `unresolved` with value kind `malformed`

### Requirement: icon_url is a fixed field
For `icon_url`, Core SHALL treat a `files/...` URL as a file reference, an absolute URL as `external`, and any other value as `unresolved` with value kind `malformed`. Core SHALL NOT fetch anything for it.

#### Scenario: Icon that is a public file
- **WHEN** an Application's `icon_url` is `files/public/a/icon.png` and the file exists
- **THEN** the file is in the archive at `public/files/a/icon.png`, listed in `files`, and the dependency at `/icon_url` is `included`

#### Scenario: Icon that is an external URL
- **WHEN** `icon_url` is `https://example.com/i.png`
- **THEN** the edge is `external` and nothing is fetched or exported

#### Scenario: Icon that is neither
- **WHEN** `icon_url` is neither an absolute URL nor a `files/...` URL
- **THEN** the dependency is `unresolved` with value kind `malformed` and nothing is read

### Requirement: A URL-form reference reads the stored resource it names
A reference in URL form (`applications/platform/x`) SHALL be read as the stored resource that its type, bucket and name identify, and SHALL be exported under that identity, whatever the merged configuration or the skipped-entity records hold under the same short name. The short-name rules do not apply to it. A URL-form target that does not exist SHALL be `unresolved`.

#### Scenario: Application whose short name a Model holds
- **WHEN** an Application's `dependencies` names `applications/platform/x` and a Model holds the short name `x`
- **THEN** the Application and the Model are two resources with their own identities and entries, and the result is the same on every run

#### Scenario: Blob skipped in the merged configuration
- **WHEN** a URL-form reference names a platform blob that the merged configuration skipped or does not hold
- **THEN** the stored blob is read and exported

#### Scenario: Missing target
- **WHEN** a reference names `applications/public/gone__1.0.0` or a public File and no such resource exists
- **THEN** the dependency is `unresolved` with no `key` and the identity of its target in `target`, nothing is written for it, and the response is `200`

### Requirement: A short name is resolved against one configuration pair
Core SHALL resolve a short name from `dependencies`, `interceptors` or a marker against the merged configuration and the skipped-entity records that were published together, read once for the whole export, and SHALL read the file-sourced configuration after them. Core does not promise a view that is atomic across the merged and the file-sourced configuration.

#### Scenario: Platform-only entity
- **WHEN** a short name is held only by a platform blob that the merged configuration holds
- **THEN** it resolves with origin `platform` and the resource is included

#### Scenario: File-only entity
- **WHEN** a short name is held only by the file configuration, whatever its type among Model, Interceptor, ToolSet and Application
- **THEN** the resource is exported with origin `file` and the dependency is `included`

#### Scenario: Stores read at different times
- **WHEN** a file reload completes between the read of the merged configuration and the read of the file configuration
- **THEN** each is used as it was read, and a name whose signals disagree is `unresolved`

### Requirement: A short name is looked up by type hint
A short name without a type hint SHALL be looked up in the four deployment maps (Application, Model, ToolSet, Interceptor); an `interceptors` entry SHALL be looked up among interceptors only. The type SHALL NOT be inferred from the name.

#### Scenario: Type hint of an `interceptors` entry
- **WHEN** an Application's `interceptors` names `x`, and a Model named `x` is also held
- **THEN** only interceptors are searched and the interceptor is the candidate

#### Scenario: No type hint
- **WHEN** a name is used in `dependencies` or in a marked property
- **THEN** all four deployment maps are searched and the name resolves only if exactly one holds it

### Requirement: A file entity wins over a platform blob of the same name
When a platform blob and a file entity of a Model, Interceptor, ToolSet, Application or schema share a type and name, Core SHALL export the file entity with origin `file` and SHALL NOT export the blob. At runtime the merged configuration serves the blob, so an import of the package can differ from the running system. This is deliberate: the file configuration is the operator's declared source. The registry warns of it.

#### Scenario: File entity and platform blob share a name
- **WHEN** a platform blob and a file entity of a Model, Interceptor, ToolSet or Application share a short name
- **THEN** the file entity is exported with origin `file` and the platform blob is not exported

### Requirement: A name with more than one holder is unresolved
Core SHALL choose no candidate by map order, type priority or any other rule. A short name held in more than one deployment map SHALL be `unresolved`, with nothing exported for it.

#### Scenario: Name held by two maps
- **WHEN** a replica event has put an Application into the merged configuration under a name that a Model already holds
- **THEN** the dependency is `unresolved` and nothing is exported for it

### Requirement: A name whose origin cannot be established is unresolved
When exactly one entity holds a name, Core SHALL establish its origin from the entity against the file-sourced configuration, the existence of the platform blob and the skipped-entity records. When the signals disagree the dependency SHALL be `unresolved` and nothing exported. Core SHALL NOT infer origin from the presence of a blob alone.

#### Scenario: Signals disagree
- **WHEN** the merged entity is the file configuration's instance and a valid platform blob exists for the same name, with no skipped record
- **THEN** the dependency is `unresolved` and no resource is exported

#### Scenario: Valid blob that the merged configuration does not hold
- **WHEN** a valid platform blob exists for a name that the merged configuration does not hold, with no skipped record
- **THEN** the dependency is `unresolved`

#### Scenario: Invalid blob next to a file entity
- **WHEN** a platform blob with the same name as a file entity cannot be parsed
- **THEN** the name resolves to the file entity with origin `file`

### Requirement: A skipped entity is listed and never consulted
A skipped entity SHALL be listed in `skipped` of a dependency that is not included, with its type and source, SHALL NOT make a name ambiguous, and its reason SHALL NOT be written. A referenced entity that was skipped and that nothing else holds SHALL be `unresolved`.

#### Scenario: Skipped entity of another type
- **WHEN** an Application holds a short name and a Model of that name was skipped
- **THEN** the name resolves to the Application, the dependency is `included`, and it has no `skipped` list

#### Scenario: Referenced entity was skipped
- **WHEN** an `interceptors` entry names an interceptor that was skipped as invalid and nothing else holds the name
- **THEN** the dependency is `unresolved` and the skipped interceptor is listed with its type and source

### Requirement: A schema is located by its `$id`
A schema is identified by its body `$id`, and its blob name is arbitrary. For `application_type_schema_id` and `catalog_schema_id`, Core SHALL list the platform blobs of that schema type once per export, read one body at a time for its `$id`, and then resolve the schema with the same origin rules as a short name, with the blob in place of the descriptor. No path SHALL be derived from the `$id`.

#### Scenario: Blob name differs from the schema id
- **WHEN** an Application's `application_type_schema_id` names a schema that the merged configuration holds and whose platform blob has another name
- **THEN** the schema is located by its `$id`, it is included, and its registry identity is the `$id`

#### Scenario: One scan per schema type
- **WHEN** two exported Applications name application type schemas and one of them names a catalog schema
- **THEN** Core lists the platform application type schema blobs once and the platform catalog schema blobs once

#### Scenario: Schema id is not a storage path
- **WHEN** `application_type_schema_id` is `https://dial.example/schemas/s` and a platform blob named like its last segment carries another `$id`
- **THEN** that blob is not the schema

### Requirement: The outcome of a schema reference follows the origin rules
A schema that nothing holds SHALL be `unresolved`. A schema held only by the file configuration SHALL be exported as a `file`-origin resource named by its `$id`. Two valid blobs with one `$id`, or a valid blob that the merged configuration does not hold, SHALL be `unresolved`.

#### Scenario: Two blobs hold the same schema id
- **WHEN** two valid platform blobs of one schema type carry the same `$id`
- **THEN** the dependency is `unresolved` and no schema is exported

#### Scenario: Schema in the file configuration only
- **WHEN** the schema named by a fixed field exists only in the file configuration
- **THEN** the schema is exported as a `file`-origin resource

#### Scenario: Blob that the merged configuration does not hold
- **WHEN** a valid platform blob carries the `$id` that a fixed field names and the merged configuration does not hold that schema
- **THEN** the dependency is `unresolved` and no schema is exported

### Requirement: A remote application type schema is fetched live
To evaluate a schema that declares `dial:applicationTypeSchemaEndpoint`, Core SHALL request it from that endpoint during the export, through the existing schema download of Core with its 5-second timeout and its existing HTTP client. The endpoint SHALL come only from a stored application type schema, never from the export request. An export SHALL NOT change what request processing later sees for the schema, and SHALL NOT reuse a schema fetched by an earlier export.

#### Scenario: Live request
- **WHEN** an export touches an Application whose type schema declares an endpoint
- **THEN** Core requests the schema from that endpoint during the export, and a second export makes its own request

#### Scenario: Request processing is not affected
- **WHEN** an export touches a remote schema that request processing has already cached
- **THEN** the schema that request processing uses is not replaced, and request-time retrieval behaves as before

#### Scenario: Endpoint from the request
- **WHEN** an export request carries a URL in any field
- **THEN** the request is refused as an unknown field and no URL is used as a schema endpoint

### Requirement: Schemas are evaluated with local references only
Core SHALL evaluate schemas so that every `$ref` that is not local to the document is refused, whatever its scheme.

#### Scenario: Remote reference
- **WHEN** a runner document contains a `$ref` to `http://...`, `file:...` or `classpath:...`
- **THEN** nothing is fetched or read through it, the dependency is `unresolved`, and no library message is exported

#### Scenario: Local reference
- **WHEN** a schema with an absolute `$id` uses `$ref` values of the form `#/$defs/x`
- **THEN** the schema is evaluated normally

### Requirement: A schema that cannot be evaluated is unresolved
A failed request, a non-success status, a document that is not a JSON object, a refused `$ref`, or a schema that cannot be built SHALL make the dependency on the schema `unresolved`; it does not fail the export, and Core makes no retry. Completeness follows "Completeness is false when references may be missed".

#### Scenario: Runner fails
- **WHEN** the runner does not answer within 5 seconds, is gone, or answers with any non-success status or a document that is not a JSON object
- **THEN** the dependency on the schema is `unresolved`, the response is `200`, and `discovery.complete` is false

### Requirement: Schema retrieval for an export writes no endpoint, content or header to a log
Core SHALL NOT write the endpoint URI, the response body, a response header or the message of an exception to a log at any level when an application type schema is retrieved for an export. A log line MAY carry the HTTP status code and a fixed message. Retrieval for request processing SHALL log as before.

#### Scenario: Error answer from the runner
- **WHEN** the runner answers with a non-200 status, a body and headers during an export
- **THEN** no log line contains the endpoint URI, the body, a header value or an exception message

#### Scenario: Exception that names the host
- **WHEN** the download fails during an export with an exception whose message contains the host or the endpoint
- **THEN** neither the message nor the endpoint appears in any log line

#### Scenario: Request processing is unchanged
- **WHEN** a request needs the schema and the download fails
- **THEN** request processing logs the endpoint and the cause and wraps the failure as it did before the export existed

### Requirement: The closure follows every Deployment and terminates
Core SHALL discover the references of every Application, Model, Interceptor and ToolSet it exports and SHALL follow a reference to any of these four types that is exportable, from `public/`, `platform/` or the file configuration. A schema, Prompt, File or Skill SHALL be included or listed, and its own content SHALL NOT be read for references. A resource reached by several paths, by several reference forms or in a cycle SHALL be exported once.

#### Scenario: Dependency Application is followed
- **WHEN** a root Application depends on a platform Application that has its own platform dependencies
- **THEN** the dependency Application and its eligible dependencies are in the archive

#### Scenario: ToolSet to Interceptor to File
- **WHEN** a root Application depends on a platform ToolSet whose `interceptors` names an Interceptor, whose `icon_url` is `files/public/a/t.png`, and the Interceptor names a public File in `catalog_properties`
- **THEN** the ToolSet, the Interceptor and both Files are in the archive and each reference is a dependency of its holder

#### Scenario: Model to Interceptor
- **WHEN** a root Application depends on a platform Model whose `interceptors` names a platform Interceptor
- **THEN** the Model and the Interceptor are in the archive and the Model lists the Interceptor as an `included` dependency

#### Scenario: Prompt, Skill and File are not examined
- **WHEN** an exported Prompt, Skill file or File holds text that names an Application
- **THEN** that Application is not added to the closure

#### Scenario: Cycle between a ToolSet and an Interceptor
- **WHEN** a ToolSet names an Interceptor in `interceptors` and that Interceptor names the ToolSet in `dependencies`
- **THEN** each appears once in the archive and the export finishes

### Requirement: Discovery issues are reported in the registry
The registry SHALL carry `discovery.complete` and `discovery.issues`. Any issue makes `discovery.complete` false, because references may have been missed. An issue has the key of a resource, a kind and a JSON pointer. The only kind listed is `instance-invalid`: properties that violate their schema, or a dependency body that cannot be bound to its type (pointer `""`). A missing or unusable schema is shown by its dependency, not in `issues`.

#### Scenario: Properties violate the schema
- **WHEN** `application_properties` or `catalog_properties` do not satisfy their schema
- **THEN** an `instance-invalid` issue is listed at `/application_properties` or `/catalog_properties` and its path, and `discovery.complete` is false

### Requirement: Completeness is false when references may be missed
`discovery.complete` SHALL be false when the registry lists an issue, and when the schema named by `application_type_schema_id` or `catalog_schema_id` cannot be found or evaluated, because no marker of it was evaluated. For an Application this holds with or without `application_properties`; for `catalog_schema_id` only when the Deployment has `catalog_properties`, because without them nothing is scanned.

#### Scenario: Missing application type schema
- **WHEN** an Application's `application_type_schema_id` names a schema that does not exist, whether or not the Application has `application_properties`
- **THEN** the dependency is `unresolved`, the response is `200`, `discovery.complete` is false, and `discovery.issues` has no entry for it

#### Scenario: Missing catalog schema with catalog properties
- **WHEN** a Deployment has `catalog_properties` and its `catalog_schema_id` names a schema that does not exist
- **THEN** the dependency is `unresolved`, the response is `200`, and `discovery.complete` is false

#### Scenario: Missing catalog schema without catalog properties
- **WHEN** a Deployment has no `catalog_properties` and its `catalog_schema_id` names a schema that does not exist
- **THEN** the dependency is `unresolved`, the response is `200`, and `discovery.complete` stays true

### Requirement: Diagnostics hold no raw value or message
Dependencies, discovery issues, warnings, the registry and error answers of the export SHALL NOT contain a reference value, credential, schema body, validation message, exception message, library message or IRI, except the `$id` of a schema as its `schemaId`. A dependency carries the identity of its target when it is known, and otherwise only the kind of the value (`external-url`, `short-name` or `malformed`).

#### Scenario: External URL with credentials
- **WHEN** a marked property holds `https://user:secret@example.com/x?token=abc`
- **THEN** the dependency is `external` with value kind `external-url`, and neither the registry nor any log holds `secret` or `token=abc`

#### Scenario: Token-like unresolved name
- **WHEN** a marked value is `sk-abc123` and no entity holds it
- **THEN** the dependency is `unresolved` with value kind `short-name` and no raw value

#### Scenario: Library message carrying an IRI
- **WHEN** a schema document holds a refused `$ref` whose library message would contain the IRI
- **THEN** the IRI appears in no entry, response or log line of the export

### Requirement: Logs of the export hold no value of a body
The logs of the export SHALL NOT contain the source text of a stored body, a value of it, or the message of a parser exception. A log MAY name a resource, a rule, a field and a path.

#### Scenario: Body that is not valid JSON
- **WHEN** the stored body of a dependency is not valid JSON
- **THEN** the log holds the resource and the rule, and no part of the body

#### Scenario: Body that cannot be bound
- **WHEN** the stored body of a dependency or of a selected Application is valid JSON that cannot be bound to its type, for example a string where an object is expected
- **THEN** the log holds the resource and no exception, and neither the log nor the answer holds the string

### Requirement: Every JSON resource is read once and written as redacted
Core SHALL read each JSON resource once from storage (or, for a `file`-origin resource, from the file-sourced configuration) when it builds the closure, redact it, and keep the redacted bytes, their `sha256` and the times of the stored resource. When it writes the entry, Core SHALL write those bytes and SHALL NOT read the resource again.

#### Scenario: One read
- **WHEN** a JSON resource is exported
- **THEN** its content is read once, when the closure is built, and writing its entry reads nothing

#### Scenario: Resource just written through the API
- **WHEN** a dependency was written through the API just before the export
- **THEN** the archive holds its full body, not an empty stub

### Requirement: A JSON resource that changes after planning does not fail the export
The archive SHALL hold each JSON resource as it was read when the closure was built. A JSON resource that changes or is deleted after that SHALL NOT fail the export. Core SHALL take no bucket lock; the only exclusion is the slot. No snapshot of storage is promised.

#### Scenario: Changed after the closure
- **WHEN** a JSON resource is rewritten with other content after the closure was built and before its entry is written
- **THEN** the export succeeds and the entry holds the content that was read when the closure was built

#### Scenario: Deleted after the closure
- **WHEN** a JSON resource is deleted after the closure was built
- **THEN** the export succeeds and the entry holds the content that was read

#### Scenario: Secret stored after the closure
- **WHEN** a value that the redaction removes is stored after the closure was built
- **THEN** the value is in no entry

### Requirement: The retained size is bounded twice
Core SHALL enforce `maxRetainedMiB` (32), in MiB, on two sums and answer `413` before the first byte when either is above it: the string length of all stored bodies read, counted when each is read, because the stored bodies of nodes not yet examined are held until their turn; and the byte length of all redacted bytes held until the archive is written. Heap for bodies is therefore up to about twice the limit plus the largest transient body.

#### Scenario: Stored bodies over the limit
- **WHEN** the stored bodies read for a closure are together longer than `maxRetainedMiB`
- **THEN** the response is `413` and no byte of a ZIP is written

#### Scenario: Redacted bytes over the limit
- **WHEN** the redacted bytes held for a closure are together longer than `maxRetainedMiB` while the stored bodies are not, for example because they hold multi-byte characters or are `file`-origin entities, which have no stored text
- **THEN** the response is `413` and no byte of a ZIP is written

### Requirement: A `file`-origin resource is a fragment
Core SHALL read a `file`-origin resource from the file-sourced configuration once, when it builds the closure, convert it once to its JSON representation, and use that for discovery and, after redaction, for the entry. The representation SHALL lose no configured field, SHALL be deterministic, and SHALL pass the same redaction as a stored body. Core SHALL NOT read the configuration again when it writes the entry. A failure to serialize it before the head SHALL be `500` without its content.

#### Scenario: File-only Model
- **WHEN** a dependency is a Model that exists only in the file configuration
- **THEN** the archive holds `.dial-export/config/model/<name>.json` with its redacted JSON, no metadata entry for it, and the registry lists it with origin `file`, `entryName` equal to that path and `sourcePointer` `/models/<name>`

#### Scenario: Secrets of the file configuration
- **WHEN** a `file`-origin Model has a plaintext upstream key and a `file`-origin ToolSet has a plaintext `client_secret`
- **THEN** neither value is in any entry

#### Scenario: Same JSON, same bytes
- **WHEN** a `file`-origin resource and a stored resource hold the same JSON
- **THEN** the redacted bytes and the `sha256` are equal

### Requirement: A `file`-origin resource is written at a fragment path
Core SHALL write a `file`-origin resource at `.dial-export/config/<type>/<segment>.json` with no sidecar, and SHALL NOT write `aidial.config.json`, a section of it, or a path under `public/` or `platform/` for it. The fragment name SHALL hold no `:` and no `%` and SHALL be unambiguous.

#### Scenario: Whole configuration is not exported
- **WHEN** an export holds `file`-origin resources
- **THEN** no entry is named `aidial.config.json` and no fragment holds another entity of the configuration

### Requirement: The registry gives a source pointer for a `file`-origin resource
The registry SHALL record for a `file`-origin resource its `sourcePointer`, an RFC 6901 pointer into the file configuration: `/models/<name>`, `/applications/<name>`, `/toolsets/<name>`, `/interceptors/<name>`, `/applicationTypeSchemas/<digest>` or `/catalogSchemas/<digest>`, where the digest is the segment of the schema's key.

#### Scenario: Schema pointer carries the digest, not the `$id`
- **WHEN** a `file`-origin application type schema is written
- **THEN** its `sourcePointer` ends with the digest of its `$id`, its key and entry name carry the digest, and the `$id` appears only as its `schemaId`

### Requirement: One redaction policy applies to every JSON resource
Core SHALL pass every JSON resource that it writes through one redaction policy, once, when the resource is read, whatever its type, origin or place in the closure, and SHALL apply it to the whole body, nested objects and arrays included. The policy SHALL work on the stored JSON, never on a decrypted or in-memory object. A removed value is not replaced by a marker.

#### Scenario: Dependency is redacted like a root
- **WHEN** a dependency is a platform Model with an upstream key, or a public ToolSet with an `auth_settings.client_secret`
- **THEN** the value is in no entry

### Requirement: Annotated secret fields are removed
The policy SHALL remove, with everything inside it, every field that Core stores encrypted in an Application, ToolSet, Model or Interceptor, whether its value is ciphertext or plaintext, under every spelling that Core accepts for the field.

#### Scenario: Upstream key
- **WHEN** a root Application has a route whose upstream key is stored encrypted, or a Model has an upstream key
- **THEN** neither the key nor its `ENC[` ciphertext is in any entry

#### Scenario: Secret under another accepted spelling
- **WHEN** a stored body holds a secret field or a container of secret fields under a spelling that Core also accepts for it, for example `auth_settings` for `authSettings`
- **THEN** the secret is not in any entry, as when the field has its usual name

### Requirement: Authorization secrets are removed
The policy SHALL remove `client_secret`, `code_verifier` and `client_secret_hint` of the `auth_settings` of a ToolSet and of every external service of a ToolSet or an Application.

#### Scenario: Toolset and external-service authorization
- **WHEN** a ToolSet, or an Application's external service, holds `auth_settings.client_secret`, `code_verifier` or `client_secret_hint`
- **THEN** none of them is in the exported body

### Requirement: Route secrets of an application type schema are removed
The policy SHALL remove `dial:key`, `key`, `dial:secretExtraData` and `secretExtraData` at `dial:applicationTypeRoutes[*].dial:upstreams[*]` of an application type schema and in the interfaces of such an upstream; the routes may be an object or an array.

#### Scenario: Schema route secrets
- **WHEN** an application type schema holds `dial:key`, `key`, `dial:secretExtraData` or `secretExtraData` at a route upstream or in its interfaces
- **THEN** each is absent from the exported body, an empty or absent key does not refuse the export, and `dial:extraData` and a `dial:key` anywhere else are exported as stored

### Requirement: Every other value is exported as stored
Core SHALL export every other value as stored: a URL (user information, path, query and fragment included), free text, a string that starts with `ENC[`, a `dial:extraData` or a `dial:key` elsewhere, an unknown field, `defaults`, `catalog_properties`, `override_paths`, `application_properties`, `function.env` and `default_headers`. Core SHALL NOT scan values for secrets, claim that one was found or removed, or refuse a body for being unusual.

#### Scenario: Unclassified values are exported as stored
- **WHEN** a body holds a URL with user information, a query or a token in its path, a free-text credential, `application_properties`, `function.env`, `default_headers`, an unknown field or a string that starts with `ENC[`
- **THEN** each is exported as stored and nothing says that it was detected

### Requirement: A body that cannot be redacted ends the export
A body that is not valid JSON or not a JSON object, or a type with no policy, SHALL end the export with `500` before the first byte. A failure SHALL NOT fall back to the stored body.

#### Scenario: Body that cannot be redacted
- **WHEN** a stored body is not valid JSON or not a JSON object
- **THEN** no byte of a ZIP is written and the response is `500` with a message that holds no value

### Requirement: Removal leaves no trace
Neither the registry nor any other metadata entry SHALL record that a value was removed: no pointer, reason, count or flag.

#### Scenario: Removal leaves no sign
- **WHEN** a body is exported without an annotated secret or an `auth_settings` secret
- **THEN** nothing in the registry or the verification entry says that the field existed or was removed

### Requirement: A Model or an Interceptor is exported as its definition only
Core SHALL export a Model or an Interceptor as its definition, as redacted JSON, with its references followed and with no endpoint content, tokenizer or model weights, and the registry warns of it.

#### Scenario: Model definition
- **WHEN** an Application depends on a platform Model
- **THEN** the archive holds the Model's redacted JSON at `platform/models/<name>` and no external artifact

### Requirement: A File is streamed as stored
Core SHALL write the bytes of a public File exactly as stored, in one pass and with memory that does not depend on the file size, and SHALL NOT parse, redact, convert, load whole into memory or examine them. A File is exported unredacted, so a secret that it embeds is exported with it, and the registry warns of it.

#### Scenario: File that looks like JSON
- **WHEN** a public File has the content type `application/json` and holds `{"name":"example","value":1}`
- **THEN** its entry is byte for byte equal to the stored file and its content is not changed

### Requirement: A File is listed in files and read as metadata first
While it builds the closure Core SHALL read only the metadata of the File (length, ETag, content type). The registry SHALL list the File in `files`, not in `resources`, with `key`, `path` and `entryName` and no `owner`. The entry's sidecar SHALL hold the stored content type.

#### Scenario: File in the registry
- **WHEN** two selected Applications name the same public File
- **THEN** `files` holds one record, the archive holds one entry for it, `summary.files` is `1`, `summary.resources` does not count it, and both dependencies carry the key of the File

#### Scenario: Closure reads no content
- **WHEN** the closure is built for an Application with a File
- **THEN** only metadata was read and no byte of the content

### Requirement: A File that cannot be copied completely fails the export
Core SHALL compare the length at open with the bytes copied and read the ETag again after the last byte. A File, or a file of the pinned version of a Skill, that is gone, whose stream ends early, is longer than its length (for a Skill file: whose length differs from the marker's), or fails SHALL fail the export after the head.

#### Scenario: Storage error mid-file
- **WHEN** a storage read fails after half of a File has been written
- **THEN** the archive has no central directory and the response is reset

#### Scenario: File gone after the closure
- **WHEN** a File no longer exists when its entry is written
- **THEN** the export fails after the head with the connection reset

#### Scenario: A file of the pinned version is gone
- **WHEN** a file of the pinned version of a Skill no longer exists, or has another length than the marker listed, when it is copied
- **THEN** the export fails after the head with the connection reset and no central directory

### Requirement: A Skill is exported as the files of its pinned version
Core SHALL pin the current version of a Skill from its marker alone when it builds the closure, and copy the files of that version as stored, as a File is copied, into `<storage path of the Skill>/<relative path>`, without the marker and the version folder. A Skill whose marker is missing, has no current version, or lists no file SHALL be `unresolved`. Core SHALL NOT read or examine the content of the files.

#### Scenario: Skill with a directory tree
- **WHEN** an Application's `dependencies` names `skills/public/s` whose current version holds `SKILL.md` and `scripts/run.py`
- **THEN** the archive holds `public/skills/s/SKILL.md` and `public/skills/s/scripts/run.py`, each after its sidecar, and no entry holds the marker or a version folder

### Requirement: The files of a Skill are listed in the registry
The registry SHALL list each file of a Skill in `files` with the key `skill-file:<origin>:<segment of the Skill>/<escaped path>`, the key of the Skill as `owner`, its `path` and `entryName`, SHALL NOT list the Skill in `resources`, and a dependency on the Skill SHALL carry the key of the Skill.

#### Scenario: Skill in the registry
- **WHEN** an Application's `dependencies` names `skills/public/s` whose current version holds `SKILL.md` and `scripts/run.py`
- **THEN** the registry lists both in `files` with the Skill as owner, and the dependency has kind `skills` and status `included`

### Requirement: A File or a Skill that changed but was written whole is recorded
A File whose ETag changed since the closure was built, or during the copy, and a Skill whose marker, read again after its last file, has a changed aggregate ETag or is gone, SHALL be listed in `changedDuringExport` of the verification entry with its name, `etagBefore` and `etagAfter`, and SHALL NOT fail the export, because what was copied is whole.

#### Scenario: Newer complete version of a File
- **WHEN** a File is replaced between the closure and the copy and the new version is written whole
- **THEN** the export succeeds and `changedDuringExport` lists the File

#### Scenario: A newer version replaces the pinned one
- **WHEN** the Skill is replaced between the closure and the copy and the pinned version is still stored
- **THEN** the pinned version is written whole and the verification entry lists the Skill in `changedDuringExport` with both aggregate ETags

#### Scenario: Skill gone after the copy
- **WHEN** the marker no longer exists after the last file was copied
- **THEN** the Skill is listed in `changedDuringExport` and the export does not fail

### Requirement: A Prompt is exported as stored
Core SHALL export a public Prompt exactly as stored, with the stored bytes and no rule of the redaction policy applied, as a resource of the registry, and the registry warns of it. A stored body that is not a JSON object SHALL end the export with `500` before the first byte.

#### Scenario: Prompt as stored
- **WHEN** an exported public Prompt is pretty-printed and holds an escape, free text, an `ENC[` string, a URL with user information and an unknown nested property
- **THEN** its entry is byte for byte the stored body

#### Scenario: Body that is not a JSON object
- **WHEN** the stored body of a Prompt is not a JSON object
- **THEN** the response is `500` before the first byte and the stored value is in no response

### Requirement: Every reference is a dependency with one of four statuses
Core SHALL list every reference of an exported resource as a dependency of that resource in the registry, with its JSON pointer and exactly one status: `included`, when it ends in a resource of the package, with the `key` of that resource; `unsupported`, when the target was identified and the export does not support it; `external`, for an absolute URL; or `unresolved`, for every other reason.

#### Scenario: Four statuses
- **WHEN** an export holds an included dependency and references that are unsupported, external, missing, an unresolved short name, of unknown origin, or hidden by an unavailable schema
- **THEN** the dependencies have exactly the statuses `included`, `unsupported`, `external` and `unresolved`

#### Scenario: Included dependency
- **WHEN** an Application's dependency ends in an exported resource
- **THEN** the dependency has status `included` and the key of that resource, and the resource is in `resources` or `files`

### Requirement: A dependency has a key only when its target is in the package
A dependency SHALL carry a `key` only when its status is `included`, that is when its target is in the package. A dependency that is not included SHALL have no `key`, and MAY carry, when known, its `kind` (the type of the target), its `valueKind` (`external-url`, `short-name` or `malformed`), the identity of its target in `target`, and the list `skipped`. The registry has no field that says why a reference is `unresolved`, and Core SHALL NOT drop an edge or change a reference in a body.

#### Scenario: Several references to one resource
- **WHEN** an Application references one Model by `dependencies` and by a marked property
- **THEN** it has two dependencies with the Model's key and different pointers, and the Model has one entry

#### Scenario: Unresolved reference has no key
- **WHEN** a reference is `unsupported`, `external` or `unresolved`
- **THEN** its dependency has no `key` and no resource is added for it

### Requirement: The registry counts resources, files, warnings and findings
The registry SHALL carry `summary` with the number of resources, the number of files and the number of warnings, and `edges`, which has exactly the keys `unsupported`, `external` and `unresolved`. Each value SHALL count the dependencies of the resources in the registry with that status, once each.

#### Scenario: Counts
- **WHEN** an export has two `unsupported` dependencies and one `unresolved` dependency
- **THEN** `summary.edges` shows `unsupported` 2, `external` 0 and `unresolved` 1, and no other key

### Requirement: The registry warns of what it did not do
The registry SHALL warn, each time only when its condition holds, that: a Model or an Interceptor is exported as its definition only; a Prompt body is exported as stored; the content of a File or of a Skill file is copied as stored and not examined; only fixed and marked fields were examined (a marked value that is a short name nothing holds, or is malformed, is unresolved); a file entity shadows a platform entity of the same type and name.

#### Scenario: Definition only
- **WHEN** the closure holds a Model or an Interceptor
- **THEN** the registry has the warning that a Model or an Interceptor is exported as its definition only

#### Scenario: Marked value that nothing holds
- **WHEN** a marked value is a short name that no entity holds
- **THEN** the registry has the warning that only fixed fields and marked fields were examined

#### Scenario: File entity shadows a platform entity
- **WHEN** a platform blob and a file entity of one type share a name, and the merged configuration serves the blob
- **THEN** the registry has the warning that a file entity shadows a platform entity, and the warning does not say which of the two the running system serves

#### Scenario: No subject, no warning
- **WHEN** the closure holds no Prompt, no File and no Skill
- **THEN** the registry has no warning about a Prompt, a File or a Skill

### Requirement: The registry has a fixed shape
`.dial-export/dependency-registry.json` SHALL have `kind` `dial-export-dependency-registry`, an integer `formatVersion` (`1`), `roots`, `resources`, `files`, `discovery`, `summary` and `warnings`. `roots` SHALL list the keys of the selected Applications in request order without duplicates.

#### Scenario: Registry fields
- **WHEN** an export succeeds
- **THEN** the registry has `kind`, `formatVersion`, `roots`, `resources`, `files`, `discovery`, `summary` and `warnings`, and a resource has its identity fields, `role`, `entryName`, `dependencies` and `requiredByRoots`

### Requirement: The registry lists every resource once
`resources` SHALL be an object by key holding every JSON resource once, with `type`, `origin`, `bucketLocation` (absent for a `file`-origin resource), `path`, `schemaId` (for a schema), `role` (`selected` or `dependency`), `entryName`, `sourcePointer` (only for origin `file`), `dependencies` (one entry per reference, never collapsed; empty for a resource with no reference fields) and `requiredByRoots`. The `entryName` SHALL name an entry of the archive.

#### Scenario: Selected Application that another one requires
- **WHEN** selected Application `a` depends on selected Application `b`
- **THEN** `b` has one entry with role `selected` and lists `a` in `requiredByRoots`

### Requirement: requiredByRoots lists the roots that reach a resource
`requiredByRoots` SHALL list the keys of the selected Applications from which the resource is reached through included dependencies, directly or through others, in request order, never the resource itself. A selected Application that another one requires keeps the role `selected`. The graph SHALL hold cycles as plain keys.

#### Scenario: Shared dependency
- **WHEN** two selected Applications reference one ToolSet
- **THEN** the ToolSet appears once in `resources`, lists both roots in `requiredByRoots`, and each Application has a dependency with the ToolSet's key

#### Scenario: Cycle
- **WHEN** Application `a` depends on `b` and `b` on `a`, and `a` is selected
- **THEN** each appears once, `b` lists `a` in `requiredByRoots`, `a` lists no root, and the export finishes

### Requirement: The archive entries come in a fixed order
The archive SHALL be a ZIP whose entries, in this order, are: `.dial-system-export/manifest.json`; `.dial-export/dependency-registry.json`; then, for each resource in the order of the closure, its sidecar `.dial-system-export/metadata/<storage path>` followed by its bytes at its storage path, or, for a `file`-origin resource, its fragment alone; and `.dial-export/verification.json` last. No other entry is written, and no resource that the closure does not reach.

#### Scenario: Public Application
- **WHEN** an explicit admin exports `applications/public/folder/a__1.0.0`
- **THEN** the archive holds `.dial-system-export/metadata/public/applications/folder/a__1.0.0` immediately followed by `public/applications/folder/a__1.0.0`

#### Scenario: Platform ToolSet
- **WHEN** an Application depends on the platform ToolSet `t`
- **THEN** the archive holds `.dial-system-export/metadata/platform/toolsets/t` immediately followed by `platform/toolsets/t`

#### Scenario: Only the closure is written
- **WHEN** `public/` and `platform/` hold resources that the closure does not reach
- **THEN** no entry names them

### Requirement: Storage paths follow the system export
Core SHALL derive the storage path of a blob-backed resource from its resource descriptor: bucket location, the group of its type (`applications`, `toolsets`, `models`, `interceptors`, `app_type_schemas`, `catalog_schemas`, `files`, `skills`, `prompts`) and its path. An application type schema is written at the path of the blob that holds it. The names of an entry and of its sidecar SHALL be equal to those of the system export for the same blob.

#### Scenario: Schema group differs from the URL segment
- **WHEN** a platform application type schema is in the closure
- **THEN** its entry is under `platform/app_type_schemas/` and its registry identity holds its `$id`

### Requirement: The technical manifest marks the archive as selective
`.dial-system-export/manifest.json` SHALL have exactly the fields `formatVersion` (`2`, so that a reader of the system export that accepts only version 1 does not take this archive for a backup of whole buckets), `coreVersion`, `createdAt` (truncated to seconds, the same instant as the file name) and `mode` (`selective`). It SHALL hold no root, resource, edge, status or warning.

#### Scenario: Fields
- **WHEN** an export succeeds
- **THEN** the technical manifest has exactly `formatVersion` 2, `coreVersion`, `createdAt` and `mode` `selective`

### Requirement: A sidecar describes the bytes that follow it
The sidecar of an entry SHALL have the format of the system export. For a JSON resource it SHALL hold `contentType` `application/json` and, when the source knows them, `creationDate` and `lastModified` of the stored resource, and SHALL NOT hold `contentEncoding` or `userMetadata`. For a File or a file of a Skill it SHALL hold the stored content type. A `file`-origin resource has no sidecar.

#### Scenario: Stored gzip is not claimed
- **WHEN** the stored blob of an exported Application is gzip-compressed
- **THEN** the entry holds the redacted JSON bytes and its sidecar has no `contentEncoding`

#### Scenario: No author
- **WHEN** the stored blob has user metadata with an author
- **THEN** the sidecar has no `userMetadata`

### Requirement: The verification entry is written last
`.dial-export/verification.json` SHALL list `entries`, each with `name` and the `sha256` of every other entry of the archive, including the manifest, the registry and the sidecars. The `sha256` of a JSON resource SHALL be that of the redacted bytes written, computed when the resource was read. A File and each file of a Skill SHALL also list `length` and `etag`. `changedDuringExport` SHALL be present only when a File or a Skill changed, as the requirements for Files and Skills describe.

#### Scenario: Checksums
- **WHEN** an export succeeds
- **THEN** the last entry lists a `sha256` for every other entry and each matches its content

#### Scenario: Streamed entry
- **WHEN** an export includes a public File
- **THEN** the verification entry holds its `sha256`, `length` and `etag`, which match the bytes of its entry

### Requirement: An export that cannot write a resource completely fails
A read or a write that fails, or a resource that ends early, SHALL fail the export after the head, with the connection reset and no central directory. A resource SHALL NOT be listed as written unless its entries were written completely. A client that does not take the data within `stallTimeout` SHALL fail the export.

#### Scenario: Client disconnects
- **WHEN** the client closes the connection during the export
- **THEN** the writer stops at its next write

### Requirement: Output is deterministic
Entry names SHALL be storage paths or fragment paths, bodies SHALL hold no export-added timestamp, and the order of the entries SHALL be stable. Two exports of the same state SHALL differ only in `createdAt` of the technical manifest and in the digest of that entry in the verification entry.

#### Scenario: Stable output
- **WHEN** the same roots are exported twice with no changes in between
- **THEN** the entries other than the technical manifest and the verification entry are identical, and the verification entry differs only in the digest of the technical manifest
