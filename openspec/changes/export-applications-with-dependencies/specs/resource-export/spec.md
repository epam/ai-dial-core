## ADDED Requirements

### Requirement: The selective export is POST /v1/admin/export
Core SHALL serve the export of selected Applications on `POST /v1/admin/export`. The request body is required. No other path serves the selective export. The export of whole buckets stays `POST /v1/admin/system-export`; its behavior and its archive do not change.

#### Scenario: System export is not affected
- **WHEN** an explicit admin sends `POST /v1/admin/system-export`
- **THEN** the response is the archive of both buckets as before

#### Scenario: No second path
- **WHEN** a caller sends any method to `/v1/admin/export/applications`
- **THEN** the response is `404`, because no such route exists

### Requirement: A successful export is a ZIP attachment
A successful response SHALL be `200` with `Content-Type: application/zip` and `Content-Disposition: attachment; filename="dial-export-<yyyyMMdd'T'HHmmss'Z'>.zip"`, where the time is the start of the export in UTC. The head SHALL be sent only after the request, the locks and the roots have been checked.

#### Scenario: Body that selects Applications
- **WHEN** an explicit admin sends `POST /v1/admin/export` with `{"roots":[{"type":"application","url":"applications/public/a__1.0.0"}]}` and the Application exists
- **THEN** the response is `200` with `Content-Type: application/zip` and a `Content-Disposition` file name that starts with `dial-export-`

### Requirement: The request names the selected Applications
The request body SHALL be a JSON object that holds `roots` only: a non-empty list of `{"type": "application", "url": "<resource URL>"}`. Each URL SHALL name an Application, not a folder, in the `public` or the `platform` bucket (for example `applications/public/folder/name__1.0.0`). The same root named twice is exported once.

#### Scenario: Root named twice
- **WHEN** the same Application is named twice in `roots`
- **THEN** it is exported once, with one entry in the archive

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
Core SHALL check the caller's right to read every root and dependency in `public/`, each resource on its own: selecting a public root does not authorize its dependencies. A public root that the caller may not read SHALL be answered with `403` before the response head, with nothing read. A public dependency that the caller may not read SHALL NOT be read or written, and SHALL end the export (see "A failure after the head cuts the stream").

#### Scenario: Public root
- **WHEN** an explicit admin selects `applications/public/a__1.0.0`
- **THEN** the caller's right to read the root is checked, an explicit admin holds full access to `public/`, and the root is exported

#### Scenario: Public root that the caller may not read
- **WHEN** a selected root is in `public/` and the caller may not read it
- **THEN** the response is `403` before the head, nothing is read and no archive is produced

#### Scenario: Public dependency is authorized on its own
- **WHEN** an explicit admin exports an Application that depends on `applications/public/b__1.0.0` by URL
- **THEN** the caller's right to read the dependency is checked and it is read, and the selection of the root does not authorize it

#### Scenario: Public dependency that the caller may not read
- **WHEN** an Application refers to a public resource that the caller may not read
- **THEN** the resource is not read, nothing of it is written, and the stream is cut

### Requirement: The roles of a deployment are not checked
Core SHALL NOT check the `user_roles` of a deployment when it resolves a name.

#### Scenario: Restricted deployment
- **WHEN** a short name resolves to a deployment whose `user_roles` the exporting admin does not hold
- **THEN** it resolves and is exported, and it is not reported as forbidden

### Requirement: Each resource is exported in one pass and forgotten
For each resource Core SHALL read it from storage (or, for an entity of the file configuration, take it from the file-sourced configuration), bind it to its entity (Application, Model, Interceptor, ToolSet), send the references found in the entity to a queue, decrypt the secrets that the admin GET shows a hint of, serialize the entity, remove the `auth_settings` secrets, write the entry, and drop the resource. Core SHALL NOT read a resource a second time.

#### Scenario: One read per resource
- **WHEN** a Model is exported
- **THEN** its stored text is read once, and the entry is written from the entity that was bound from that text

#### Scenario: Resource just written through the API
- **WHEN** a dependency was written through the API just before the export
- **THEN** the archive holds its full body, not an empty stub

### Requirement: A resource is exported exactly once
Core SHALL mark each resource as met before it queues it, so that a resource reached by several paths, by several reference forms, in a cycle, or named twice as a root is read and written once. A reference to a resource that is already met SHALL add nothing.

#### Scenario: Cycle between a ToolSet and an Interceptor
- **WHEN** a ToolSet names an Interceptor in `interceptors` and that Interceptor names the ToolSet in `dependencies`
- **THEN** each appears once in the archive and the export finishes

#### Scenario: Diamond
- **WHEN** two Applications of one export name the same ToolSet
- **THEN** the ToolSet is read once and has one entry

#### Scenario: Cycle between Applications
- **WHEN** Application `a` depends on `b` and `b` on `a`, and `a` is selected
- **THEN** each appears once and the export finishes

### Requirement: Memory does not depend on the size of the graph
Core SHALL hold in memory only the queue of resources to write, the set of resources met, and the resource in hand. A File and each file of a Skill SHALL be copied through a buffer of 32 KiB, whatever its size.

#### Scenario: Entries are not kept
- **WHEN** an export of many resources is written
- **THEN** nothing of an entry is kept after it is written

#### Scenario: Large File
- **WHEN** a public File of many MiB is exported
- **THEN** it is copied through the fixed buffer and never loaded whole

### Requirement: The traversal is breadth first and follows every Deployment
Core SHALL write the roots in the order of the request and then the queued resources in the order in which they were queued. It SHALL discover the references of every Application, Model, Interceptor and ToolSet it writes and follow a reference to any of these four types that is exportable, from `public/`, `platform/` or the file configuration. A schema, Prompt, File or Skill SHALL be written and its own content SHALL NOT be examined for references.

#### Scenario: Dependency Application is followed
- **WHEN** a root Application depends on a platform Application that has its own platform dependencies
- **THEN** the dependency Application and its eligible dependencies are in the archive

#### Scenario: ToolSet to Interceptor to File
- **WHEN** a root Application depends on a platform ToolSet whose `interceptors` names an Interceptor, whose `icon_url` is `files/public/a/t.png`, and the Interceptor names a public File in `catalog_properties`
- **THEN** the ToolSet, the Interceptor and both Files are in the archive

#### Scenario: Model to Interceptor
- **WHEN** a root Application depends on a platform Model whose `interceptors` names a platform Interceptor
- **THEN** the Model and the Interceptor are in the archive

#### Scenario: Prompt, Skill and File are not examined
- **WHEN** an exported Prompt, Skill file or File holds text that names an Application
- **THEN** that Application is not added to the export

### Requirement: The roots are checked before the response head
Core SHALL, before the response head, check that every public root is readable by the caller (`403`) and that every root exists (`404`). A root that exists when it is checked and is gone when it is written SHALL end the export (see "A failure after the head cuts the stream").

#### Scenario: Root does not exist
- **WHEN** a root names an Application that does not exist
- **THEN** the response is `404` and carries no archive

### Requirement: The export holds the bucket locks to the last byte
Before the response head Core SHALL take the bucket locks of `public/` and `platform/` without waiting, as the system export does, and SHALL hold them until the last byte is written, the export fails or the client is lost. A lock held by another export, by the system export or by an admin write SHALL be answered with `409` before any byte. The locks SHALL be released once.

#### Scenario: Another export holds the locks
- **WHEN** an export, the system export or an admin write holds a bucket lock and an admin sends a valid request
- **THEN** the response is `409`, and after the other ends a new request is served

#### Scenario: Locks after every ending
- **WHEN** an export ends, fails before the head, fails after the head, or the client disconnects
- **THEN** the locks are released

#### Scenario: Admin write during the export
- **WHEN** an admin writes a configuration entity or applies a manifest while an export runs
- **THEN** the write does not run before the export releases the locks, so the administered entities of one export are a consistent set

#### Scenario: Plain user resource write
- **WHEN** a user writes a Prompt or a File in `public/` while an export runs
- **THEN** the write is not blocked, because that write takes no bucket lock

#### Scenario: Write that takes the bucket lock
- **WHEN** a Skill is written or copied into `public/`, a publication is approved, or a resource is moved while an export runs
- **THEN** the write waits for the lock, as an admin write does

### Requirement: Statuses are decided in a fixed order before the head
Core SHALL decide the status in this order: `403` for a caller that is not an explicit admin, `400` for an invalid request, `409` when a bucket lock is held, `403` for a public root that the caller may not read, `404` for a missing root, and `500` (`Failed to prepare the export`) for any other failure of these checks. The head is sent with the first bytes of the archive: a failure before them is `500` (`Failed to export the Applications`), after them no status is sent.

#### Scenario: Lock held and root missing
- **WHEN** a bucket lock is held and a request names a root that does not exist
- **THEN** the response is `409`, not `404`

#### Scenario: Request invalid and lock held
- **WHEN** a bucket lock is held and the body is not a valid selection
- **THEN** the response is `400`

#### Scenario: Writer fails before the head
- **WHEN** the roots are checked and the writer then fails before the head is written
- **THEN** the response is `500` with the message `Failed to export the Applications`

### Requirement: A failure after the head cuts the stream
A failure after the response head SHALL end the connection without the central directory of the ZIP, so that the bytes received are not a valid archive, and SHALL be logged at WARN with the kind of the failure, the duration, the key of the resource in hand (`-` when none) and, for a bound, its name, never a value. A client SHALL treat a transport error as a failed export.

#### Scenario: Failures that cut the stream
- **WHEN** after the head a limit or the deadline is exceeded, a stored body cannot be bound or is not a JSON object, a root or File is gone, a File is not copied completely, a path is unsafe, a public dependency is unreadable, or a read or write fails
- **THEN** the connection is reset and the bytes received are not a valid ZIP

#### Scenario: Failure after the first entry
- **WHEN** reading a resource fails after the first entry has been streamed
- **THEN** the connection is reset and the bytes received are not a valid ZIP

#### Scenario: Failure about a resource
- **WHEN** a root or File is gone, a File or a file of a Skill is not copied completely or has another length than listed, a path is unsafe, or a body cannot be bound
- **THEN** the message of the failure carries the key of that resource and no value

#### Scenario: Body that cannot be bound
- **WHEN** the stored body of a dependency or of a selected Application is valid JSON that cannot be bound to its type
- **THEN** the stream is cut, the log and the message name the resource by its key (not by its URL) and neither the log nor the response holds a value

#### Scenario: File that vanished
- **WHEN** a File that the export has queued is gone when it is copied
- **THEN** the stream is cut and the abort record and its message hold the key of that File

#### Scenario: Client disconnects
- **WHEN** the client closes the connection during the export
- **THEN** the writer stops at its next write and the locks are released

#### Scenario: Slow client
- **WHEN** a client does not take the data within `stallTimeout`
- **THEN** the stream is cut

### Requirement: The export leaves an audit record at INFO
As the system export does, the export SHALL write an INFO record when a request is refused because a bucket lock is held, when the archive starts (with the number of roots) and when it is complete (with the duration and the number of resources written). A record SHALL NOT hold a root, a URL, a body or a secret.

#### Scenario: Lock held
- **WHEN** a request is refused with `409` because a bucket lock is held
- **THEN** the log holds one INFO record that the export was rejected, and no root

#### Scenario: Successful export
- **WHEN** an export of two roots is streamed to the last byte
- **THEN** the log holds an INFO record `Export started` with `roots=2` and an INFO record `Export completed` with a duration and a number of resources, and neither holds a root, a URL or a body

### Requirement: The graph is bounded in resources, depth and references
The export SHALL write at most `maxNodes` (500) resources, follow at most `maxDepth` (10) references from a root, and count at most `maxEdges` (10000) references in one body and in all bodies together. A bound that is exceeded SHALL cut the stream with a log record that names the bound. A body whose marker references of both property sets together exceed `maxEdges` SHALL be refused before any is classified.

#### Scenario: Too many resources
- **WHEN** the export would write more than `maxNodes` resources, Models, Interceptors and ToolSets included
- **THEN** the stream is cut and the log names the bound and no value

#### Scenario: Too deep
- **WHEN** a chain of references from a root, through an Application, a ToolSet and an Interceptor for example, is longer than `maxDepth`
- **THEN** the stream is cut

#### Scenario: Too many references
- **WHEN** one body, or all bodies together, hold more than `maxEdges` references
- **THEN** the stream is cut

#### Scenario: Too many catalog markers
- **WHEN** the application and catalog properties of one body together hold more marker references than `maxEdges`
- **THEN** the stream is cut before any of them is classified, so no storage is probed for them

### Requirement: One body is bounded in size
`maxResourceMiB` (4) SHALL bound the string length of one stored body, in MiB; a longer body SHALL cut the stream. A stored body is read in full before this check, so the limit does not bound the memory of one read.

#### Scenario: Body too large
- **WHEN** one stored body is longer than `maxResourceMiB`
- **THEN** the stream is cut and the log names the bound

### Requirement: The export has a deadline
The export SHALL end within `deadline` (180000 ms) from the start to the last byte: a deadline that passes after the head SHALL cut the stream. Core SHALL refuse to start when `deadline` plus `stallTimeout` (default 60000 ms) is not below the 300000 ms lease of the bucket locks, which is not renewed, or when `stallTimeout` alone is not.

#### Scenario: Deadline passes after the head
- **WHEN** the deadline passes while the archive is written
- **THEN** the stream is cut

#### Scenario: Settings that do not fit the lease
- **WHEN** `adminExport.deadline` plus `adminExport.stallTimeout` is not below 300000 ms
- **THEN** Core does not start and the message names the setting

### Requirement: A type that is not listed is unsupported
Core SHALL treat a reference to any resource type that these requirements do not list as unsupported: it is not read and not written, and the reference is logged. Only the exact bucket locations `public/` and `platform/` are eligible; the `file` origin is the loaded `aidial.config.json`. A support SHALL NOT be inferred from the existence of a bucket or of a type.

#### Scenario: Eligible locations only
- **WHEN** a reference points at `public/publications/<id>/`, `public/deployments/<id>/`, a review bucket, a function folder, a user bucket or an app bucket
- **THEN** nothing is read, the reference is logged as unsupported, whatever the caller's access, and a reference that merely parses is not treated as eligible

#### Scenario: Folder
- **WHEN** a reference names a folder, or `function.source_folder` is set
- **THEN** no folder content is exported and the reference is logged as unsupported

### Requirement: Applications and ToolSets are supported
An Application SHALL be supported as a root and as a dependency in `platform/`, `public/` and the `file` origin. A ToolSet SHALL be supported as a dependency in the same three places and never as a root. Both are written as the projection of their entity and their references are followed.

#### Scenario: Supported public dependencies
- **WHEN** an Application names a public Application, ToolSet, File, Skill or Prompt that exists and the caller may read
- **THEN** the resource is exported, and it is never a root unless it is an Application

### Requirement: Models and Interceptors are supported in platform and file only
A Model and an Interceptor SHALL be supported as dependencies in `platform/` and the `file` origin, never in `public/` and never as roots. Each is written as the projection of its entity, with no endpoint content, tokenizer or model weights, and its references are followed.

#### Scenario: Unsupported public Model and Interceptor
- **WHEN** an Application names `models/public/m` or `interceptors/public/i`
- **THEN** the reference is logged as unsupported and nothing is read

### Requirement: Schemas are supported in platform and file only
An application type schema and a catalog schema SHALL be supported as dependencies in `platform/` and the `file` origin, never in `public/` and never as roots. Each is written from its parsed JSON document and its content is not read for references.

#### Scenario: Schema is a leaf
- **WHEN** an exported application type schema holds text that names an Application
- **THEN** that Application is not added to the export

### Requirement: File, Skill and Prompt are supported in public only
A File, a Skill and a Prompt SHALL be supported as dependencies in `public/` only. A File is written as its bytes as stored, a Skill as the files of its current version as stored, and a Prompt as its body as stored. A Skill is bound to the `public` bucket only.

#### Scenario: Skill outside the public bucket
- **WHEN** an Application names a Skill in a bucket other than `public`
- **THEN** the reference is logged as unsupported and nothing is read

### Requirement: Other types are not exported and not read
Project, Conversation, Role, Route, Router and Upstream resources, `ProjectKey` and admin consent, SHALL NOT be exported or read: a reference to a Project, a Conversation or a Router is not followed. The fields `user_roles`, `interfaces.*.translator` and inline `routes` of an Application SHALL NOT be examined, so they give no reference.

#### Scenario: Unsupported conversation
- **WHEN** an Application names `conversations/public/c`
- **THEN** the reference is logged as unsupported and nothing is read

#### Scenario: Not-examined fields
- **WHEN** an Application's `user_roles`, `interfaces.*.translator` or inline `routes` name other resources
- **THEN** no reference is produced and nothing is read

### Requirement: Every resource has an entry name
The entry name of a blob-backed resource SHALL be its storage path, derived from its resource descriptor: bucket location, the group of its type (`applications`, `toolsets`, `models`, `interceptors`, `app_type_schemas`, `catalog_schemas`, `files`, `skills`, `prompts`) and its path. That of a `file`-origin resource SHALL be its fragment path.

#### Scenario: Schema group differs from the URL segment
- **WHEN** a platform application type schema is exported
- **THEN** its entry is under `platform/app_type_schemas/`

#### Scenario: Resource reached by several paths
- **WHEN** one resource is reached by several paths or by several reference forms
- **THEN** it has one entry in the archive

### Requirement: A path that cannot be an entry name is refused
Core SHALL refuse a resource path, and the relative path of a Skill file, that has an empty, `.` or `..` segment. A reference whose path has such a segment, also percent-encoded, SHALL be malformed and not read. A stored resource or Skill file with such a path SHALL cut the stream, and nothing of that Skill is written.

#### Scenario: Unsafe path of a reference
- **WHEN** a reference is `files/public/a/../b` or `files/public/a/%2e%2e/b`
- **THEN** the reference is logged as unresolved, nothing is read and no answer repeats the value

#### Scenario: Unsafe path inside a Skill
- **WHEN** a stored file of a Skill has a relative path with an empty, `.` or `..` segment
- **THEN** the stream is cut before the first file of that Skill, and the message holds the key of the Skill and not the path of the file

### Requirement: Dependencies come from fixed fields and schema markers
For every Application, Model, Interceptor and ToolSet it exports, Core SHALL discover references from the fields `interceptors`, `dependencies`, `catalog_schema_id`, `icon_url`, and the values under `dial:resource` and `dial:file` markers of `catalog_properties`, as the catalog schema defines them. Every reference SHALL have its JSON pointer: the same value at two pointers is two references. An invalid, external, dangling or malformed reference SHALL NOT stop discovery of the others.

#### Scenario: Fixed fields of an Application
- **WHEN** an Application has `application_type_schema_id`, `interceptors: ["i1"]` and `dependencies` with a platform ToolSet URL
- **THEN** the schema, `i1` and the ToolSet are exported when they exist, and the others are logged

#### Scenario: Fixed fields of a Model, Interceptor and ToolSet
- **WHEN** a Model, an Interceptor and a ToolSet that are exported each hold `interceptors`, `dependencies` or `icon_url` that name other resources
- **THEN** each reference is followed from its holder or logged with its own pointer

#### Scenario: Marker in catalog properties
- **WHEN** a catalog property under a `dial:file` marker names `files/public/a/logo.svg`, and the file exists
- **THEN** the file is exported

#### Scenario: Dangling and valid references
- **WHEN** an Application holds one reference whose target does not exist and two valid platform URLs
- **THEN** the two valid resources are in the archive and the dangling one is logged

### Requirement: Application-only fields and application properties
An Application, and no other type, SHALL also have `application_type_schema_id`, `function.source_folder` and `application_properties` examined. The properties SHALL be scanned for the `dial:resource` and `dial:file` markers of the application type schema, as `catalog_properties` is, and a value that a marker holds is a reference like any other, at `/application_properties/<path>`. Discovery SHALL NOT parse a resource that is not a Deployment, and SHALL NOT authorize or fetch anything.

#### Scenario: Application properties are scanned
- **WHEN** `application_properties` names a Skill, a File and a public ToolSet through markers
- **THEN** each is exported when it exists, and a reference that nothing holds is logged with a pointer that starts with `/application_properties`

#### Scenario: Application without schema or properties
- **WHEN** an Application has no `application_type_schema_id`, or empty `application_properties`
- **THEN** no reference comes from `application_properties`

#### Scenario: One build per schema
- **WHEN** several Applications of one export name the same schema id
- **THEN** the schema is built once, with at most one Runner download

#### Scenario: Schema id of a Model
- **WHEN** a Model body holds a field that looks like `application_type_schema_id`
- **THEN** it is not read as a reference

### Requirement: A reference value is classified in a fixed order
Core SHALL classify a value of `dependencies`, of a marker or of `icon_url` in this order: a blank value is malformed; an absolute URL is external and is never fetched; a value with an empty, `.` or `..` path segment, also percent-encoded, is malformed and is never read; a value that parses as a resource URL names its resource; a value without `/` is a short name; any other value is malformed.

#### Scenario: Malformed marked value
- **WHEN** a marked value holds `/` but does not parse as a resource URL and is not an absolute URL
- **THEN** the reference is logged as unresolved and nothing is read

#### Scenario: External URL
- **WHEN** a marked value is `https://example.com/x`
- **THEN** nothing is fetched or exported and the reference is logged at INFO as external

### Requirement: A name that nothing holds is not followed
A short name or a malformed value that no entity holds SHALL NOT be followed and SHALL be logged. An entry of `interceptors` is always a short name of an interceptor; a value with `/` there is malformed.

#### Scenario: Marked short name that resolves
- **WHEN** a marked value is `gpt-4o` and a platform Model of that name exists
- **THEN** the Model is exported

#### Scenario: Short name that nothing holds
- **WHEN** a marked value is a short name that no entity holds, no record skipped, and no platform blob has
- **THEN** nothing is exported for it and one WARN record names the resource, the pointer and the reason `unresolved-short-name`

#### Scenario: Interceptor entry with a slash
- **WHEN** an `interceptors` entry holds `/`
- **THEN** the reference is logged and nothing is read

### Requirement: icon_url is a fixed field
For `icon_url`, Core SHALL treat a `files/...` URL as a file reference, an absolute URL as external, and any other value as malformed. Core SHALL NOT fetch anything for it.

#### Scenario: Icon that is a public file
- **WHEN** an Application's `icon_url` is `files/public/a/icon.png` and the file exists
- **THEN** the file is in the archive at `public/files/a/icon.png`

#### Scenario: Icon that is an external URL
- **WHEN** `icon_url` is `https://example.com/i.png`
- **THEN** nothing is fetched or exported

#### Scenario: Icon that is neither
- **WHEN** `icon_url` is neither an absolute URL nor a `files/...` URL
- **THEN** the reference is logged and nothing is read

### Requirement: A URL-form reference reads the stored resource it names
A reference in URL form (`applications/platform/x`) SHALL be read as the stored resource that its type, bucket and name identify, and SHALL be exported under that identity, whatever the merged configuration or the skipped-entity records hold under the same short name. The short-name rules do not apply to it. A URL-form target that does not exist SHALL be logged and not exported.

#### Scenario: Application whose short name a Model holds
- **WHEN** an Application's `dependencies` names `applications/platform/x` and a Model holds the short name `x`
- **THEN** the Application and the Model are two resources with their own entries, and the result is the same on every run

#### Scenario: Blob skipped in the merged configuration
- **WHEN** a URL-form reference names a platform blob that the merged configuration skipped or does not hold
- **THEN** the stored blob is read and exported

#### Scenario: Missing target
- **WHEN** a reference names `applications/public/gone__1.0.0` or a public File and no such resource exists
- **THEN** nothing is written for it, one WARN record names the referring resource and the pointer, and the export goes on

### Requirement: A short name is resolved against one configuration pair
Core SHALL resolve a short name from `dependencies`, `interceptors` or a marker against the merged configuration and the skipped-entity records that were published together, read once for the whole export under the lock of every publication, and SHALL read the file-sourced configuration after them. Core does not promise a view that is atomic across the merged and the file-sourced configuration.

#### Scenario: Platform-only entity
- **WHEN** a short name is held only by a platform blob that the merged configuration holds
- **THEN** it resolves with origin `platform` and the resource is exported

#### Scenario: File-only entity
- **WHEN** a short name is held only by the file configuration, whatever its type among Model, Interceptor, ToolSet and Application
- **THEN** the resource is exported with origin `file`

#### Scenario: Stores read at different times
- **WHEN** a file reload completes between the read of the merged configuration and the read of the file configuration
- **THEN** each is used as it was read, and a name whose signals disagree is not exported

### Requirement: A short name is looked up by type hint
A short name without a type hint SHALL be looked up in the four deployment maps (Application, Model, ToolSet, Interceptor); an `interceptors` entry SHALL be looked up among interceptors only. The type SHALL NOT be inferred from the name.

#### Scenario: Type hint of an `interceptors` entry
- **WHEN** an Application's `interceptors` names `x`, and a Model named `x` is also held
- **THEN** only interceptors are searched and the interceptor is the candidate

#### Scenario: No type hint
- **WHEN** a name is used in `dependencies` or in a marked property
- **THEN** all four deployment maps are searched and the name resolves only if exactly one holds it

### Requirement: A platform blob wins over a file entity of the same name
When a valid platform blob and a file entity of a Model, Interceptor, ToolSet, Application or schema share a type and name (for a schema, the `$id`), Core SHALL export the platform blob as a platform resource and SHALL NOT read or export the file entity. A file entity that no valid platform blob replaces SHALL still be exported with origin `file`.

#### Scenario: Platform blob and file entity share a name
- **WHEN** a valid platform blob and a file entity of a Model, Interceptor, ToolSet or Application share a short name
- **THEN** the platform blob is exported at its storage path with origin `platform`, followed further, and the file entity is not exported

#### Scenario: Merged entity is the file instance
- **WHEN** the merged entity is the file configuration's instance and a valid platform blob exists for the same name, with no skipped record
- **THEN** the platform blob is exported and the file entity is not

#### Scenario: File entity that no blob replaces
- **WHEN** a file entity has no platform blob of its type and name
- **THEN** it is exported with origin `file`

### Requirement: A name with more than one holder is not followed
Core SHALL choose no candidate by map order, type priority or any other rule. A short name held in more than one deployment map SHALL NOT be followed, and nothing is exported for it.

#### Scenario: Name held by two maps
- **WHEN** a replica event has put an Application into the merged configuration under a name that a Model already holds
- **THEN** nothing is exported for the name and one WARN record gives the reason `origin-unknown`

### Requirement: A name whose origin cannot be established is not followed
When exactly one entity holds a name, Core SHALL establish its origin from the entity against the file-sourced configuration, the existence of the platform blob and the skipped-entity records: a valid platform blob gives origin `platform`, otherwise the file configuration's instance gives `file`. Any other name SHALL NOT be followed. Core SHALL NOT infer origin from the presence of a blob alone.

#### Scenario: Merged entity matches neither signal
- **WHEN** the merged entity is not the file configuration's instance, for example older than a file reload, and no platform blob exists for the name
- **THEN** no resource is exported and the reason is `origin-unknown`

#### Scenario: Valid blob that the merged configuration does not hold
- **WHEN** a valid platform blob exists for a name that the merged configuration does not hold, with no skipped record
- **THEN** nothing is exported for the name

#### Scenario: Invalid blob next to a file entity
- **WHEN** a platform blob with the same name as a file entity cannot be parsed
- **THEN** the name resolves to the file entity with origin `file`

### Requirement: A skipped entity is never consulted
A skipped entity SHALL NOT make a name ambiguous and SHALL NOT be exported, and its reason SHALL NOT be written. A referenced entity that was skipped and that nothing else holds SHALL NOT be followed and is logged as missing.

#### Scenario: Skipped entity of another type
- **WHEN** an Application holds a short name and a Model of that name was skipped
- **THEN** the name resolves to the Application and it is exported

#### Scenario: Referenced entity was skipped
- **WHEN** an `interceptors` entry names an interceptor that was skipped as invalid and nothing else holds the name
- **THEN** nothing is exported for it and the reason is `missing`

### Requirement: A schema is located by its `$id`
A schema is identified by its body `$id`, and its blob name is arbitrary. For `application_type_schema_id` and `catalog_schema_id`, Core SHALL list the platform blobs of that schema type once per export, read one body at a time for its `$id`, and then resolve the schema with the same origin rules as a short name, with the blob in place of the descriptor. No path SHALL be derived from the `$id`.

#### Scenario: Blob name differs from the schema id
- **WHEN** an Application's `application_type_schema_id` names a schema that the merged configuration holds and whose platform blob has another name
- **THEN** the schema is located by its `$id` and exported

#### Scenario: One scan per schema type
- **WHEN** two exported Applications name application type schemas and one of them names a catalog schema
- **THEN** Core lists the platform application type schema blobs once and the platform catalog schema blobs once

#### Scenario: Schema id is not a storage path
- **WHEN** `application_type_schema_id` is `https://dial.example/schemas/s` and a platform blob named like its last segment carries another `$id`
- **THEN** that blob is not the schema

### Requirement: The outcome of a schema reference follows the origin rules
A schema that nothing holds SHALL NOT be followed. Exactly one valid blob with the `$id` SHALL be exported as a platform schema, also when the file configuration holds that `$id`. A schema held only by the file configuration SHALL be exported as a `file`-origin resource named by the digest of its `$id`. Two valid blobs with one `$id`, or a valid blob that the merged configuration does not hold, SHALL NOT be followed.

#### Scenario: Two blobs hold the same schema id
- **WHEN** two valid platform blobs of one schema type carry the same `$id`
- **THEN** no schema is exported and the reason is `origin-unknown`

#### Scenario: Platform schema and file schema share an id
- **WHEN** exactly one valid platform blob carries the `$id` and the file configuration holds a schema with that `$id`, whichever body the merged configuration holds
- **THEN** the platform blob is exported at its storage path and the file schema is not exported

#### Scenario: Two blobs and a file schema
- **WHEN** two valid platform blobs carry the `$id` that the file configuration also holds
- **THEN** no schema is exported and the reason is `origin-unknown`

#### Scenario: Schema in the file configuration only
- **WHEN** the schema named by a fixed field exists only in the file configuration
- **THEN** the schema is exported as a `file`-origin resource

#### Scenario: Blob that the merged configuration does not hold
- **WHEN** a valid platform blob carries the `$id` that a fixed field names and the merged configuration does not hold that schema
- **THEN** no schema is exported

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
- **THEN** nothing is fetched or read through it, the schema is unusable, and no library message is written

#### Scenario: Local reference
- **WHEN** a schema with an absolute `$id` uses `$ref` values of the form `#/$defs/x`
- **THEN** the schema is evaluated normally

### Requirement: A schema that cannot be evaluated does not fail the export
A failed request, a non-success status, a document that is not a JSON object, a refused `$ref`, or a schema that cannot be built SHALL make the references that depend on the schema unreliable: the schema reference is logged with the reason `schema-unavailable`, the export goes on, and Core makes no retry.

#### Scenario: Runner fails
- **WHEN** the runner does not answer within 5 seconds, is gone, or answers with any non-success status or a document that is not a JSON object
- **THEN** the export goes on, and one WARN record names the Application, `/application_type_schema_id` and the reason `schema-unavailable`

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

### Requirement: What is not followed is logged and never fails the export
A reference that is missing, unsupported, external, an unresolved short name, of unknown origin, or hidden by an unavailable schema SHALL NOT change the status and SHALL NOT end the export: the archive holds every root and every dependency that could be resolved, authorized, read and written. Each such reference SHALL be one server log record with the key of the referring resource, the JSON pointer and the reason, at WARN, except an external URL, which is INFO. The archive SHALL NOT list it.

#### Scenario: Findings do not fail the request
- **WHEN** an Application has a missing dependency, an unsupported one, an external URL, an unresolved short name and a schema that cannot be loaded
- **THEN** the response is `200` and complete for what could be resolved, and the log holds one record for each with the resource key, the pointer and the reason

#### Scenario: Archive does not report findings
- **WHEN** an export has references that were not followed
- **THEN** no entry of the archive lists them

### Requirement: Findings hold no raw value or message
A log record of the export SHALL hold the resource key, the pointer and a reason from a fixed set (`missing`, `unsupported`, `external`, `unresolved-short-name`, `origin-unknown`, `schema-unavailable`, or a kind of discovery issue), and SHALL NOT hold a reference value, credential, schema body, validation or exception message, IRI, or text of a stored body.

#### Scenario: External URL with credentials
- **WHEN** a marked property holds `https://user:secret@example.com/x?token=abc`
- **THEN** the log holds the resource key, the pointer and the reason `external`, and neither `secret` nor `token=abc`

#### Scenario: Token-like unresolved name
- **WHEN** a marked value is `sk-abc123` and no entity holds it
- **THEN** the log record holds the pointer and the reason `unresolved-short-name` and not the value

#### Scenario: Library message carrying an IRI
- **WHEN** a schema document holds a refused `$ref` whose library message would contain the IRI
- **THEN** the IRI appears in no entry, response or log line of the export

#### Scenario: Body that is not valid JSON
- **WHEN** the stored body of a dependency is not valid JSON
- **THEN** the log holds the resource and the rule, and no part of the body

### Requirement: A resource key is unambiguous
Core SHALL name a resource in a log record and in the message of a failure by the key `<type>:<origin>:<segment>`, where the segment is the path with every byte outside `A-Z a-z 0-9 . _ - /` written as `~` and two hex digits (a schema of the file configuration: a digest of its `$id`). The key SHALL hold no bucket location. The type names are `application`, `toolset`, `model`, `interceptor`, `application-type-schema` and `catalog-schema`, and the URL segment for another type.

#### Scenario: Characters that a key escapes
- **WHEN** a path holds `:`, `~` or `%`
- **THEN** the key stays unambiguous and two paths never give one key

#### Scenario: Bucket is not in the key
- **WHEN** a resource in `platform/` or `public/` is logged
- **THEN** its key has three parts and holds no bucket location

### Requirement: Incomplete discovery is logged
When the references of a Deployment may be incomplete, for example because its `application_properties` or `catalog_properties` violate their schema, Core SHALL log one WARN record with the resource key, the kind of the issue and the pointer, and the export SHALL go on. A missing or unusable schema is shown by its own reference record and not twice.

#### Scenario: Properties violate the schema
- **WHEN** `application_properties` do not satisfy their schema
- **THEN** the export goes on and one WARN record gives the kind `instance-invalid` and a pointer that starts with `/application_properties`

### Requirement: A Deployment is exported as the projection of its entity
Core SHALL write an Application, a Model, an Interceptor and a ToolSet as the JSON that `ProxyUtil.MAPPER.valueToTree` makes of the entity that was bound from the stored text, so that no encrypted field is serialized. The entry SHALL hold what the admin GET of the same entity holds, less the `name` and `status` that the API adds. The entity of the file configuration SHALL pass the same projection.

#### Scenario: Entry equals the admin GET
- **WHEN** a platform Application is exported and read through the config API as an admin
- **THEN** the entry equals the GET body without `name` and `status`

### Requirement: Secrets that leave are those of the admin GET
Core SHALL decrypt the external service secrets of an Application and the `auth_settings` secrets of a ToolSet, to derive a masked hint, and then remove `client_secret` and `code_verifier` from each `auth_settings` of the projected tree and keep the masked `client_secret_hint`. A secret that cannot be decrypted SHALL be dropped, with no hint, and the export goes on. Nothing of a secret but that hint SHALL leave. An entity of the file configuration holds no ciphertext and is not decrypted.

#### Scenario: ToolSet and external-service authorization
- **WHEN** a ToolSet, or an Application's external service, holds an encrypted `client_secret` and a `code_verifier`
- **THEN** the entry holds neither, no `ENC[` value, and the masked `client_secret_hint` as the admin GET shows it

#### Scenario: Secret that cannot be decrypted
- **WHEN** the secret of an external service cannot be decrypted
- **THEN** the entry has no secret and no hint for it, and the other services keep theirs

### Requirement: No encrypted field leaves
Core SHALL NOT write the value of any field that Core stores encrypted in an Application, ToolSet, Model or Interceptor, whether stored as ciphertext or as plaintext, under any spelling that Core accepts for the field: the keys of a Model, an Interceptor, a route and an upstream are not in the projection.

#### Scenario: Upstream key
- **WHEN** a root Application has a route whose upstream key is stored encrypted, or a Model has an upstream key
- **THEN** neither the key nor its `ENC[` ciphertext is in any entry

#### Scenario: Secrets of the file configuration
- **WHEN** a `file`-origin Model has a plaintext upstream key and a `file`-origin ToolSet has a plaintext `client_secret`
- **THEN** neither value is in any entry

### Requirement: Route secrets of an application type schema are removed
Core SHALL parse an application type schema and remove `dial:key`, `key`, `dial:secretExtraData` and `secretExtraData` at `dial:applicationTypeRoutes[*].dial:upstreams[*]` and in the interfaces of such an upstream; the routes may be an object or an array. A catalog schema SHALL be written from its parse with no removal.

#### Scenario: Schema route secrets
- **WHEN** an application type schema holds `dial:key`, `key`, `dial:secretExtraData` or `secretExtraData` at a route upstream or in its interfaces
- **THEN** each is absent from the entry, an empty or absent key does not refuse the export, and `dial:extraData` and a `dial:key` anywhere else are written as parsed

### Requirement: Values that are not secrets of the entity are exported as they are
Core SHALL NOT scan a value for secrets, claim that one was found or removed, or refuse a body for being unusual. A URL (user information, path, query and fragment included), free text, a string that starts with `ENC[` in a field that is not encrypted, `application_properties`, `function.env`, `default_headers`, `defaults`, `catalog_properties` and `override_paths` SHALL leave as the projection holds them. Nothing in the archive says that a value was removed.

#### Scenario: Unclassified values are exported as they are
- **WHEN** a body holds a URL with user information, a query or a token in its path, a free-text credential, `application_properties`, `function.env`, `default_headers` or a string that starts with `ENC[` in a field that is not encrypted
- **THEN** each is exported as the projection holds it and nothing says that it was detected

#### Scenario: Removal leaves no sign
- **WHEN** a body is exported without an encrypted field or an `auth_settings` secret
- **THEN** no entry says that the field existed or was removed

### Requirement: A Prompt is exported as stored
Core SHALL write a public Prompt exactly as stored, with the stored bytes and no change.

#### Scenario: Prompt as stored
- **WHEN** an exported public Prompt is pretty-printed and holds an escape, free text, an `ENC[` string, a URL with user information and an unknown nested property
- **THEN** its entry holds exactly the stored bytes

### Requirement: A `file`-origin resource is a fragment
Core SHALL write a `file`-origin resource at `.dial-export/config/<type>/<segment>.json`, where the type is `application`, `toolset`, `model`, `interceptor`, `application-type-schema` or `catalog-schema`, and the segment is the escaped name or, for a schema, the digest of its `$id`. Core SHALL NOT write `aidial.config.json`, a section of it, or a path under `public/` or `platform/` for it. The fragment name SHALL hold no `:` and no `%`.

#### Scenario: File-only Model
- **WHEN** a dependency is a Model that exists only in the file configuration
- **THEN** the archive holds `.dial-export/config/model/<name>.json` with its projection, and no other entry for it

#### Scenario: Whole configuration is not exported
- **WHEN** an export holds `file`-origin resources
- **THEN** no entry is named `aidial.config.json` and no fragment holds another entity of the configuration

### Requirement: A File is streamed as stored
Core SHALL write the bytes of a public File exactly as stored, in one pass through the fixed buffer, and SHALL NOT parse, convert, load whole into memory or examine them. Before it copies, Core SHALL read only the metadata of the File, not its content.

#### Scenario: File that looks like JSON
- **WHEN** a public File has the content type `application/json` and holds `{"name":"example","value":1}`
- **THEN** its entry holds exactly the stored bytes

#### Scenario: Two Applications name one File
- **WHEN** two selected Applications name the same public File
- **THEN** the archive holds one entry for it

### Requirement: A File that cannot be copied completely cuts the stream
Core SHALL compare the length at open with the bytes copied and read the ETag again after the last byte. A File, or a file of the pinned version of a Skill, that is gone, whose stream ends early, is longer than its length (for a Skill file: whose length differs from the marker's), or fails SHALL cut the stream.

#### Scenario: Storage error mid-file
- **WHEN** a storage read fails after half of a File has been written
- **THEN** the archive has no central directory and the response is reset

#### Scenario: File gone after the check
- **WHEN** a File no longer exists when its entry is written
- **THEN** the stream is cut

#### Scenario: A file of the pinned version is gone
- **WHEN** a file of the pinned version of a Skill no longer exists, or has another length than the marker listed, when it is copied
- **THEN** the stream is cut with no central directory

### Requirement: A Skill is exported as the files of its pinned version
Core SHALL pin the current version of a Skill from its marker, and copy the files of that version as stored, in the order of their relative paths, as a File is copied, into `<storage path of the Skill>/<relative path>`, without the marker and the version folder. A Skill whose marker is missing, has no current version, or lists no file SHALL NOT be exported and SHALL be logged as missing. Core SHALL NOT examine the content of the files.

#### Scenario: Skill with a directory tree
- **WHEN** an Application's `dependencies` names `skills/public/s` whose current version holds `SKILL.md` and `scripts/run.py`
- **THEN** the archive holds `public/skills/s/SKILL.md` and `public/skills/s/scripts/run.py`, and no entry holds the marker or a version folder

### Requirement: A File or a Skill that changed but was written whole is logged
A File whose ETag changed since it was read or during the copy, and a Skill whose marker, read again after its last file, has a changed aggregate ETag or is gone, SHALL be recorded in one WARN log record with the key of the resource and the ETag before and after, never a value, and SHALL NOT fail the export, because what was copied is whole. The archive SHALL NOT list it.

#### Scenario: Newer complete version of a File
- **WHEN** a File is replaced between the read of its metadata and the copy and the new version is written whole
- **THEN** the export succeeds, the log holds one WARN record with the key of the File and both ETags, and no entry of the archive lists the change

#### Scenario: A newer version replaces the pinned one
- **WHEN** the Skill is replaced after its marker was read and the pinned version is still stored
- **THEN** the pinned version is written whole and the log holds one WARN record with the key of the Skill and both aggregate ETags

#### Scenario: Skill gone after the copy
- **WHEN** the marker no longer exists after the last file was copied
- **THEN** the log holds one WARN record with the key of the Skill and the export does not fail

### Requirement: The archive holds the manifest and one entry per resource
The archive SHALL be a ZIP whose first entry is `.dial-export/manifest.json`, followed by one entry for each resource written, in the order of writing, at its storage path or, for a `file`-origin resource, at its fragment path. No other entry is written: no list of references, no checksum, no metadata entry, and no resource that the export does not reach.

#### Scenario: Public Application
- **WHEN** an explicit admin exports `applications/public/folder/a__1.0.0`
- **THEN** the archive holds `.dial-export/manifest.json` followed by `public/applications/folder/a__1.0.0` and no other entry

#### Scenario: Platform ToolSet
- **WHEN** an Application depends on the platform ToolSet `t`
- **THEN** the archive holds the entry `platform/toolsets/t` after the entry of the Application

#### Scenario: Only the reached resources are written
- **WHEN** `public/` and `platform/` hold resources that the export does not reach
- **THEN** no entry names them

#### Scenario: Nothing else is written
- **WHEN** an export succeeds
- **THEN** no entry lists checksums, references or findings, and no entry has a name under `.dial-system-export/`

### Requirement: The manifest marks the archive as selective
`.dial-export/manifest.json` SHALL have exactly the fields `formatVersion` (`2`), `coreVersion`, `createdAt` (truncated to seconds, the same instant as the file name) and `mode` (`selective`). It SHALL hold no root, resource, reference, status or warning.

#### Scenario: Fields
- **WHEN** an export succeeds
- **THEN** the manifest has exactly `formatVersion` 2, `coreVersion`, `createdAt` and `mode` `selective`

### Requirement: The archive has a layout of its own
The archive SHALL NOT be in the layout of the system export (`.dial-system-export/` with a metadata entry per blob): the reader and the importer of the system export cannot read it, and the import of the package is outside this change. Each entry SHALL be the admin projection of its entity, or the stored bytes of a File, a Skill file or a Prompt, not the stored data of an entity.

#### Scenario: Layout differs from the system export
- **WHEN** an export succeeds
- **THEN** the manifest is at `.dial-export/manifest.json`, no entry is under `.dial-system-export/`, and the entry of an Application equals the admin GET body without `name` and `status`

### Requirement: The export is not a snapshot
Core SHALL take no snapshot of storage: a resource is exported as it is read, the stream can run up to the deadline, and related resources can be of different versions.

#### Scenario: Resource changed during the export
- **WHEN** a resource is rewritten after another resource was written and before it is read
- **THEN** the export succeeds and each entry holds the state at the time of its read
