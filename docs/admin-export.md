# Admin export

`POST /v1/admin/export` exports **selected Applications and their dependencies** as one ZIP archive. It is an admin-only
preview endpoint (`x-preview`). The request body is required: an empty body is `400`. The export of the whole `public` and
`platform` buckets is `POST /v1/admin/system-export`, described in [Admin system export](admin-system-export.md).

The archive follows the layout of the system export, so one reader can open both; see [Package](#package).

## Export of selected Applications

The body selects Applications; the package holds them and what they depend on. Everything below is
read from the implementation; the endpoint is a preview and its contract can still change.

```bash
curl -X POST -H "Authorization: Bearer <admin token>" -H "Content-Type: application/json" -o apps.zip \
  -d '{"roots":[{"type":"application","url":"applications/platform/my-app"}]}' \
  https://<core>/v1/admin/export
```

### Request

| Field | Meaning |
|---|---|
| `roots` | Required, at least one. Each item is `{"type": "application", "url": "<resource URL>"}`. The Application must be in the `platform` bucket or in `public/` (not a folder, not a user, app or review location). |

The body holds only `roots`. No field controls dependency traversal, file inclusion, unresolved-dependency handling or secret
handling, and a field that is not `roots`, an unknown value or a malformed URL
is a `400` whose message never echoes a submitted value: a root is named by its index, and a body that is not valid JSON or has
an unknown field gets one fixed message. The whole body is read and checked before the first response byte.

### Authorization

- The caller must have explicit admin access (`access.admin.rules`). A caller without it, a per-request API key, and a
  Core with no admin rules all get `403`, before the body is read.
- A selected `public/` Application and every `public/` dependency are checked with the access rules (an explicit admin holds
  full access to `public/`). Resources in `platform/` are authorized by the admin check alone.
- Core does not check the roles of a deployment (`user_roles`) when it resolves a name.

### What is exported

The closure starts at the roots and follows the references of every **Deployment** that it finds: Application, Model, Interceptor
and ToolSet. For each of them it reads `interceptors`, `dependencies`, `icon_url`, `catalog_schema_id` and the values of fields
marked `dial:resource` or `dial:file` in `catalog_properties`; an Application also has `application_type_schema_id`,
`function.source_folder` and the values of the same markers in `application_properties`. Application type and catalog schemas, Prompts, Files and Skills are leaves: they are exported and their
content is not examined. `application_properties` is scanned against the application type schema of the Application: every value that
a marker holds is a reference at `/application_properties/<path>` and is classified like a marked value of `catalog_properties`. The
properties are still exported as stored. An Application without a schema, or with empty properties, has nothing to scan, and a reference
in a property that no marker names is not detected. If the schema cannot be found or built, the schema edge is `unresolved` and the
discovery is incomplete; the export does not fail. If the properties violate the schema, `discovery.issues` lists an `instance-invalid`
issue at `/application_properties` and its path, and `discovery.complete` is false. An `interceptors` entry is followed
for every Deployment, although Core does not evaluate the stored `interceptors` of an Interceptor at request time. A longer chain
(Application, ToolSet, Interceptor, File) reaches `maxNodes` and `maxDepth` sooner.

`icon_url` is a fixed field: a `files/...` URL is a file reference (the file is exported when it is a public file that exists), an absolute URL is `external`, and any other value is `unresolved` (value kind `malformed`). Nothing is fetched. A reference that has an empty, `.` or `..` path segment, also encoded (`%2e%2e`), is `malformed` before anything is read, so it can never fail while the archive is being written, and no answer repeats the value.

| Dependency | Result |
|---|---|
| Platform Application, ToolSet, Model, Interceptor, application type schema, catalog schema | Exported; Applications, ToolSets, Models and Interceptors are followed further, schemas are not. |
| `public/` Application | Exported and followed. |
| `public/` ToolSet | Exported, redacted (its `auth_settings` secrets are removed, as for a platform ToolSet), with origin `public`, and followed further. Never a root. |
| Any other `public/` resource (prompt, file, skill, ...), a user, app, review or function location, a folder | `unsupported`, not read. |
| A Model, Interceptor, ToolSet, Application, application type schema or catalog schema that the configuration file defines | Exported with origin `file`; the four Deployment types are followed further. It is read from the file configuration once, when the closure is built. A platform blob of the same type and name is not exported (see the warnings below). |

A short name is matched against the entities of the current effective configuration. Exactly one holder is exported, and its
origin must be established, otherwise the edge is `origin-unknown`. Two holders of one name give `origin-unknown` and none is
exported. Skipped entities are not consulted. A reference in URL form (`applications/platform/x`) reads the stored resource it
names, whatever the short name means, and is exported under its own identity.

### Unresolved dependencies and partial exports

Every reference of an exported Application is a **dependency** of its resource in the registry: an entry with the JSON pointer and
a status. A reference that ends in an exported resource has the status `included` and the `key` of that resource. Every other
reference has one of the statuses below and no `key`, and carries, when known, the kind of the value (`short-name`, `external-url`, `malformed`) or the target.
The reference value itself is never written, and the registry has no field that says why a reference is `unresolved`.

| Status | Meaning |
|---|---|
| `included` | The reference ends in an exported resource; the dependency has its `key`. |
| `unsupported` | The target was identified, but v1 does not export it (see above). |
| `external` | An absolute URL, not a Core resource. |
| `unresolved` | Nothing holds the name or the target is missing or was skipped as invalid, a short name in a marked field that no entity holds, the entity's origin cannot be established or the name has more than one holder, or the application type schema could not be loaded so that references may be hidden. |

A Model or Interceptor (exported as its definition only), a file entity that shadows a platform blob of the same name, and a short name that no entity holds each add a warning to the registry.

**A file entity and a platform blob of one name.** When a file entity of a Model, Interceptor, ToolSet, Application or schema and a platform blob share a type and name, the package carries the file entity with origin `file` and does not carry the blob. At runtime the merged configuration serves the blob, so an import of the package can differ from the running system. This is deliberate: the file configuration is the operator's declared source. The registry warning reads "A file entity shadows a platform entity of the same type and name; the platform entity is not exported." and does not say which entity the running system serves.

The merged and the file-sourced configuration are separate stores that are read one after the other, so the export does not promise a view that is atomic across them; a file entity is read once, when the closure is built.
A denied public dependency answers `403` before the first byte, like a denied public root.

The `summary.edges` counts have exactly the three keys `unsupported`, `external` and `unresolved`; there is no key for `included`.

A dependency problem never changes the response. When a reference is unsupported, external, missing, an unresolved short name, of
unknown origin or hidden by an unavailable schema, or when discovery is incomplete, the response is `200`, the archive holds every
root and every dependency that can be safely resolved, authorized, read and written, and the registry lists each finding with its
status and `discovery.issues`. Only a condition that prevents a safe archive is an error: an
invalid request, a forbidden caller, a missing selected root (`404` before the first byte), a stored body that cannot be safely
processed, an unexpected infrastructure failure, an unsafe archive path, a limit or a read or write failure, or a resource that
fails while it is written (the connection is reset after the head).

### Package

The ZIP holds, in this order:

| Entry | Content |
|---|---|
| `.dial-system-export/manifest.json` | First entry, as in the system export: `formatVersion` (`2`, so that a reader of the system export that accepts only `1` refuses this archive), `coreVersion`, `createdAt` and `mode` (`selective`). Nothing else. |
| `.dial-export/dependency-registry.json` | `kind` `dial-export-dependency-registry`, `formatVersion`, `roots` (the keys of the selected Applications, in the order of the request), `resources` (a JSON object by resource key: `type`, `origin`, `bucketLocation` and `path`, `schemaId` for a schema, `role` `selected` or `dependency`, `entryName`, `sourcePointer` for a resource of origin `file`, `dependencies` and `requiredByRoots`), `files` (the files whose bytes are in the archive: `key`, `owner` (the key of the Skill, for a file of a Skill), `path`, `entryName`), `discovery` (`complete`, and `issues`, a list of `{resource, kind, pointer}` where `resource` is a resource key), `summary` (resources, files and warnings, and `edges` with the three keys `unsupported`, `external` and `unresolved`) and `warnings`. |
| `.dial-system-export/metadata/<path>` then `<path>` | For each resource that has a blob: its sidecar, immediately followed by its bytes at its real storage path, for example `public/applications/folder/app__1.0.0`, `platform/toolsets/tools`, `public/files/icons/a.png` or `public/skills/team/s/scripts/run.py`. A JSON resource is redacted; its sidecar has the format of the system export and holds `contentType` `application/json` and the dates of the stored resource, so it does not claim the `gzip` of the stored blob and does not hold the author. A File, and each file of a Skill, is copied exactly as stored, in one pass through a fixed buffer: it is never parsed, redacted or examined for references, and its sidecar holds its stored `contentType`. The files of the current version of a Skill are written below the storage path of the Skill, without the marker and the version folder; the version is pinned when the closure is built. |
| `.dial-export/config/<type>/<name>.json` | For each resource of origin `file` (defined in `aidial.config.json`, so it has no blob): the redacted JSON of that one entity. The configuration file itself is never exported. The registry gives its `sourcePointer`, for example `/models/<name>`; for a schema the last segment is a digest of its `$id`. |
| `.dial-export/verification.json` | Last entry: the SHA-256 of every entry before it. An entry that is a File or a file of a Skill also lists its `length` and `etag`, and `changedDuringExport` lists a File whose etag changed since the closure was built or during the copy, and a Skill whose marker changed after its files were copied, with the name, `etagBefore` and `etagAfter`; a newer complete version of a File is written whole and listed, not failed, and a replaced Skill is written at the version that was pinned. |

The `entryName` of a resource in the registry is the name of its entry. A JSON resource is read once and redacted when the closure is built; the redacted bytes are kept and
the entry writes exactly those bytes, so the registry describes the package. A resource appears once in `resources`, under the key `<type>:<origin>:<path>`, in which the path is escaped (a schema
is named by a digest of its `$id`). The key is opaque to a reader, who takes the identity from the fields of the resource. A
`dependencies` entry is `{"key", "kind", "status": "included", "pointer"}` for a reference to a resource of the package and
`{"kind"?, "status", "pointer", "valueKind"?, "target"?, "skipped"}` for any other; every reference is its own entry, also when several
lead to one resource, and only an `included` entry has a `key`: an entry that is not included may carry the identity of its target in `target`. `requiredByRoots` lists the selected
Applications, in the order of the request, from which the resource is reached through `included` dependencies, directly or through
others; a resource is never listed for itself, and a selected Application that another selected Application reaches lists that
Application. The graph holds cycles as plain keys.

The same input gives the same entries in the same order; only `createdAt` differs. **Files, Skills and Prompts are exported**:
a public Prompt is exported exactly as it is stored, with nothing removed, emptied or changed, and the registry warns of it. A File that is a folder, is outside `public/`, or does not exist is `unsupported` or `missing`, and nothing is read for it. **The bytes of Files and Skills, and the body of a Prompt, are exported unredacted, which Maintainers and Security have not acknowledged (a release gate): this mode is not released.** A File that is gone after the closure was built, copied incompletely or longer than its length fails the export. A JSON resource that changes or is deleted after the closure was built does not: the archive holds it as it was read. Entry names are storage paths, which can hold
characters that Windows does not allow in file names, such as `:`; read the archive with a ZIP library or extract it on Linux.

### Secrets

Values of annotated secret fields (`key` and `secretExtraData` of upstreams, and the like; a value that is ciphertext is removed with the field) and the `client_secret`,
`code_verifier` and `client_secret_hint` of toolset and external-service authorization are removed from the exported
body; neither metadata file lists them. The `dial:key`, `key`, `dial:secretExtraData` and `secretExtraData` of a schema route upstream
(the schema-document spellings of two annotated fields) are removed too, and nothing says in the package that anything was removed.
`application_properties`, `function.env` and `default_headers` are **out of scope of v1**: the export has no rule for them, so they are
exported as stored, and a credential kept in them is exported. `application_properties` is still scanned for dependencies (see above), but a credential kept in it is not removed. A Prompt and the bytes of a File are exported exactly as stored. Everything else is exported as stored, **including a string that starts with `ENC[` in a field that is not annotated** and **every URL** (user
information, path, query and fragment: URLs are not inspected for credentials and are not changed by redaction; URL-form resource references are still parsed to find dependencies), a credential in a free-text field, and `defaults`, `catalog_properties`
and `override_paths`, which Core cannot classify. Only a body that is not valid JSON or not a JSON object, or a type without a policy, ends the
export in `500`. The policy is not approved (a release gate). The content of `credentials/` and `encryption_keys/` is never exported.

### Responses

| Status | Meaning |
|---|---|
| `200` | The archive is streamed (`application/zip`, `Content-Disposition` with a UTC timestamp). |
| `400` | The body is missing, holds a field other than `roots`, or is not a valid selection. |
| `403` | The caller is not an explicit admin, or a `public/` root is not readable by the caller. |
| `404` | A selected Application does not exist. |
| `409` | Another export of Applications holds the one cluster-wide slot; retry later. |
| `413` | The export exceeds a limit of the `adminExport` settings (see Limits); nothing is exported. |
| `500` | The export failed before the archive started: `Failed to prepare the export`, or `Failed to export the Applications` when the streamer fails before it writes the head. |
| `503` | No redactor is configured, so no body may be exported. Answered after the request checks and the plan. |
| `504` | The export did not finish within `adminExport.deadline`. Before the first byte it is a status; after it the connection is reset. |

The status is decided in this order: `403` for a caller that is not an explicit admin (before the body is read), `400` for an invalid body, `409` when the slot is held (the slot is taken before the plan, so a held slot answers `409` before a `404` or a `403` for a public resource), then the result of the plan (`403`, `404`, `413`, `504`, `500`), and last `503`.

A failure after the head is written resets the connection: a File or Skill file that disappears, ends early or is longer than its
length, a read or write failure, a client that is too slow, and the deadline. A JSON resource that changes or is deleted after the
closure was built does not fail the export; the archive holds it as it was read, and the stream can run up to the deadline, so the
archive reflects the state at planning time. The selection does not take the bucket locks; its `409` is the slot.

### Limits

A selective export reads each JSON resource once. The read builds the closure: the body is redacted at once, and the redacted
bytes and their SHA-256 are kept until the entry is written, so the registry describes exactly the bytes in the archive. The stored
(unredacted) body of a Deployment is held until its references have been examined, which for a body that waits in the queue is
until its turn. The size of the closure is bounded by the
`adminExport` settings, and a limit that is exceeded fails the whole export before the first byte, so a package never
silently omits a resource:

| Setting | Default | Limit |
|---|---|---|
| `maxRoots` | 100 | Applications named in `roots` (`400`) |
| `maxNodes` | 500 | resources in the closure (`413`) |
| `maxDepth` | 10 | longest chain of references from a root (`413`) |
| `maxEdges` | 10000 | references found in one body and in all bodies together (`413`), and nothing is cut; the marker references of `application_properties` and `catalog_properties` together are checked as a whole before any is classified, the other references are counted one by one, so the export fails at the first one past the limit |
| `maxResourceMiB` | 4 | string length of one stored body (`413`) |
| `maxRetainedMiB` | 32 | in MiB, enforced twice (`413`): the summed string length of the stored bodies that were read, and the summed byte length of the redacted bytes held until the archive is written |
| `deadline` | 180000 | milliseconds from the start to the last byte (`504`); with `stallTimeout` it must be below the lock period of 300000 |

Only one selective export runs in the cluster at a time: it holds a Redis lease of 300 s that is not renewed, and releases it
when it ends, fails, is refused by the executor or loses its client. The `deadline` and one `stallTimeout` together must stay
below that lease, which Core checks at startup, so that the slot does not expire while the export still works.

Three quantities bound the heap, and they are not the same:

- **The retained bodies** (`maxRetainedMiB`) are of two kinds, and each kind is bounded by the limit. The stored bodies are counted by
  string length when they are read, because a stored body is held until it is examined; the count is not reduced when it is released,
  so it bounds all bodies read. The redacted bytes of all JSON resources stay in memory from the moment the resource is read until the
  stream has written it, and are counted by byte length. Together they bound the memory that the closure holds to about twice the
  limit, whatever the number of resources up to `maxNodes`.
- **The transient memory** is what one body needs while it is read or parsed. Parsing a body that passed `maxResourceMiB` needs about
  ten times its length. Reading allocates, before the size can be checked, about five times the stored size of the body (the
  compressed bytes, the decompressed bytes with their buffer growth, and the string), and a stored body can be as large as
  `resources.maxSize` (64 MiB by default), so one body that is refused afterwards can still need about 320 MiB for a moment.
- **The peak** is up to about twice `maxRetainedMiB` (stored bodies plus redacted bytes), plus the largest transient body and the
  1 MiB pipe: "the retained bytes plus the largest transient" understates it. It has not been measured (an open task). Provision the
  heap for about twice the retained limit plus the transient peak; only one export runs at a time.

The platform schemas are looked up by `$id`, which only their bodies hold, so a schema reference makes the export read the
platform schema blobs once per schema type, one body at a time and under the deadline; no more than one body is held.

The factors are estimates from reading the code and from the measurements recorded in the design, not a new measurement. The size of
one body cannot be limited before it is read without a size-limited read in `ResourceService`, which is a shared path that this
feature does not change: until then the largest stored body (`resources.maxSize`) is the bound of what one read can allocate, and the
heap must be provisioned for the peak above. The export is bounded in the number of resources, references, depth and time, and not
in the memory of one oversized read.

The deadline is enforced by a check before each resource, each reference, each schema read and each entry, and by two timers: one
interrupts the thread that plans when the deadline passes, so a read that blocks is ended if it can be interrupted, and one aborts
the stream, which ends a write that waits for a slow client. An operation that ignores an interrupt (a read of a resource or of a
Runner schema that does not return) is not stopped; the export then fails at the next check and the slot is released when the
operation returns. The lease cannot be renewed, so an operation that outlasts it lets another export start while it still runs.

### Release status and scope

- The endpoint must not be released until Security has signed off the fail-closed redaction policy of the section Secrets
  (a release gate).
- The public scope is specific to this feature and is not a general policy for public resources: selected `public/`
  Application roots and `public/` Application dependencies are exported; other public dependencies and all other
  public types are `unsupported`.

### Known limitations

- The origin of a short name is that of the **current effective configuration**. A deployment that was skipped may become
  effective after a later rebuild, so the meaning of a name can change after an export. The configuration file is read after the
  merged configuration and can lag a reload by about 500 ms. A model resurrected after an interceptor write can give
  `origin-unknown` until the next rebuild.
- A stored body that is not valid JSON, whether a root or a dependency, and a selected Application that cannot be bound (for example a
  field of the wrong type), end the export in `500`; the answer does not say which resource it is, the server log does. For a body that
  cannot be bound the log holds the Jackson message, which names a field and a path and, for a scalar of the wrong type, quotes that
  scalar; Jackson's source excerpt is redacted. A dependency that
  cannot be bound is exported as redacted JSON without examining its references, and the registry lists an `instance-invalid` issue for it
  and sets `discovery.complete` to `false`.
- There is no snapshot of storage: the archive holds each JSON resource as it was read when the closure was built, a File or Skill as it is read while streaming, and the stream can run up to the deadline, so related resources can be of different versions.
- The merged configuration and the configuration file are separate stores that are read one after the other, so an entity of the file can be seen in a state that the merged configuration does not yet show; no view that is atomic across them is promised.
- The limits are configuration defaults chosen from the earlier measurements, not benchmarked capacity: speed on large graphs
  is not characterized. The 5-second timeout of the Runner schema download covers the wait for the response head, not the body;
  the deadline interrupts the thread that waits for it, but no hard cancellation of a read exists beyond that.
- An application type schema that declares `dial:applicationTypeSchemaEndpoint` is fetched live from the Runner, once per
  schema id and export (the built schema is reused by every Application that names it; it is also used to scan
  `application_properties`), through Core's existing schema download with its existing 5-second timeout (which bounds the wait for the
  response head, not the body). The endpoint comes only from a stored application type schema, which is separate from the loading
  of a remote `$ref`: the export evaluates the schema with its own factory that rejects every non-local `$ref`, and there is no
  TTL cache. No allow-list, DNS or address check, body deadline or size bound is added for the export; a failure is
  `schema-unavailable`.
- Scanning `application_properties` adds one probe of storage for each marked short name, as for any other short name. A Quick App
  can name many resources that are not in storage; each is listed as `unresolved`, and the response is still `200`.
