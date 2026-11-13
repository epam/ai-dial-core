# Admin export

`POST /v1/admin/export` exports **selected Applications and their dependencies** as one ZIP archive. It is an admin-only
preview endpoint (`x-preview`). The request body is required: an empty body is `400`. The export of the whole `public` and
`platform` buckets is `POST /v1/admin/system-export`, described in [Admin system export](admin-system-export.md).

The archive has a layout of its own: the manifest `.dial-export/manifest.json` first and then one entry per resource; see
[Package](#package). It is not in the layout of the system export (`POST /v1/admin/system-export`): the reader and the importer of the
system export cannot read it, and the import of the package is outside this feature. Each entry is the admin projection of its entity (or
the stored bytes of a File, a Skill file or a Prompt), not the stored data of the entity.

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

The body holds only `roots`. No field controls dependency traversal, file inclusion or secret handling, and a field that is not
`roots`, an unknown value or a malformed URL is a `400` whose message never echoes a submitted value: a root is named by its index, and a
body that is not valid JSON or has an unknown field gets one fixed message. The whole body is read and checked before the first
response byte.

### Authorization

- The caller must have explicit admin access (`access.admin.rules`). A caller without it, a per-request API key, and a
  Core with no admin rules all get `403`, before the body is read.
- A selected `public/` Application and every `public/` dependency are checked with the access rules (an explicit admin holds
  full access to `public/`). Resources in `platform/` are authorized by the admin check alone.
- Core does not check the roles of a deployment (`user_roles`) when it resolves a name.

### How the export works

The export is a stream. It does not build the graph first and write it afterwards: it writes each resource as it finds it. For one
resource it does this, in this order:

1. Read it from storage (or take it from the file configuration, for an entity that `aidial.config.json` defines).
2. Bind it to its entity: Application, Model, Interceptor or ToolSet.
3. Put the references that the entity holds into a queue. Each resource is marked as met before it is queued, so a resource that is
   reached twice, in a cycle or through a diamond, is exported once.
4. Decrypt the secrets that the admin API shows a hint of (the external services of an Application and the `auth_settings` of a
   ToolSet). A secret that cannot be decrypted is dropped, and the export goes on.
5. Serialize the entity as the admin GET does (every encrypted field is dropped), and remove the `client_secret` and `code_verifier`
   of every `auth_settings`, keeping the masked `client_secret_hint`.
6. Write the entry to the archive, and forget the resource.

Memory holds the queue, the set of resources met and the resource being written. A File and each file of a Skill go through a
32 KiB buffer whatever their size. The archive keeps nothing of an entry once it is written. The
resources are written breadth first: the roots in the order of the request, then what they reference.

### What is exported

The export starts at the roots and follows the references of every **Deployment** that it writes: Application, Model, Interceptor
and ToolSet. For each of them it reads `interceptors`, `dependencies`, `icon_url`, `catalog_schema_id` and the values of fields
marked `dial:resource` or `dial:file` in `catalog_properties`; an Application also has `application_type_schema_id`,
`function.source_folder` and the values of the same markers in `application_properties`. Application type and catalog schemas, Prompts,
Files and Skills are written and their content is not examined. `application_properties` is scanned against the application type
schema of the Application: every value that a marker holds is a reference at `/application_properties/<path>` and is classified like a
marked value of `catalog_properties`. An Application without a schema, or with empty properties, has nothing to scan, and a reference
in a property that no marker names is not detected. If the schema cannot be found or built, its reference is logged as `schema-unavailable`
and the export does not fail. If the properties violate the schema, the log holds an `instance-invalid` record at `/application_properties`
and its path. An `interceptors` entry is followed for every Deployment, although Core does not evaluate the stored `interceptors` of an
Interceptor at request time. A longer chain (Application, ToolSet, Interceptor, File) reaches `maxNodes` and `maxDepth` sooner.

`icon_url` is a fixed field: a `files/...` URL is a file reference (the file is exported when it is a public file that exists), an
absolute URL is external, and any other value is malformed. Nothing is fetched. A reference that has an empty, `.` or `..` path segment,
also encoded (`%2e%2e`), is malformed before anything is read.

| Dependency | Result |
|---|---|
| Platform Application, ToolSet, Model, Interceptor, application type schema, catalog schema | Exported; Applications, ToolSets, Models and Interceptors are followed further, schemas are not. |
| `public/` Application | Exported and followed. |
| `public/` ToolSet | Exported (its `auth_settings` secrets are removed, as for a platform ToolSet), with origin `public`, and followed further. Never a root. |
| `public/` Prompt, File, Skill | Exported as stored; their content is not examined. |
| Any other `public/` resource, a user, app, review or function location, a folder | `unsupported`, not read. |
| A Model, Interceptor, ToolSet, Application, application type schema or catalog schema that the configuration file defines | Exported with origin `file`; the four Deployment types are followed further. Not exported when a platform blob of the same type and name replaces it (see below). |

A short name is matched against the entities of the current effective configuration. Exactly one holder is exported, and its
origin must be established, otherwise the reference is `origin-unknown` and nothing is exported. Two holders of one name give
`origin-unknown` and none is exported. Skipped entities are not consulted. A reference in URL form (`applications/platform/x`) reads the
stored resource it names, whatever the short name means, and is exported under its own identity.

**A file entity and a platform blob of one name.** When a file entity of a Model, Interceptor, ToolSet, Application or schema and a
platform blob share a type and name (for a schema, the `$id`), the platform blob has the higher priority: the package carries the blob,
read like any platform resource and entered at its storage path, and does not carry the file entity, which is not read. This matches the
merged configuration, which serves the blob. A file entity that no valid platform blob replaces is still exported as a fragment; a blob
that is skipped as invalid does not replace it.

The merged and the file-sourced configuration are separate stores that are read one after the other, so the export does not promise a
view that is atomic across them.

### References that are not followed

A reference that does not end in an exported resource is **not listed in the archive**. It is one record in the server log, with the key
of the resource that holds it, the JSON pointer and the reason. The value of the reference is never logged.

| Reason | Meaning | Level |
|---|---|---|
| `missing` | Nothing holds the name or the target does not exist, or the entity was skipped as invalid. | WARN |
| `unsupported` | The target was identified, but the export does not take it (see above). | WARN |
| `unresolved-short-name` | A short name in a marked field that no entity holds. | WARN |
| `origin-unknown` | The entity's origin cannot be established, or the name has more than one holder. | WARN |
| `schema-unavailable` | The schema could not be loaded or built, so references may be hidden. | WARN |
| `external` | An absolute URL, not a Core resource. | INFO |

The record reads `Export: a reference is not exported: resource=<key> pointer=<pointer> reason=<reason>`; an external reference reads
`Export: a reference is external and not exported: resource=<key> pointer=<pointer>`, and an incomplete discovery reads
`Export: the references of <key> may be incomplete: <kind> at <pointer>`. The key is `<type>:<origin>:<segment>`, for example
`application:platform:my-app` or `model:file:gpt-4o`; the segment has every byte outside `A-Z a-z 0-9 . _ - /` written as `~` and two
hex digits. A dependency that exists when its holder is examined and is gone when it is read is logged the same way.

A reference that is not followed never changes the response: it is `200`, the archive holds every root and every dependency that could be
resolved, authorized, read and written. **A `200` is therefore not a statement that the package is complete.** Read the log of the server
that ran the export to see what is missing.

### Package

The ZIP holds the manifest and one entry per resource, in this order:

| Entry | Content |
|---|---|
| `.dial-export/manifest.json` | First entry: `formatVersion` (`2`), `coreVersion`, `createdAt` and `mode` (`selective`). Nothing else. |
| `<path>` | One entry for each resource that is stored in a bucket, in the order in which the resources are written, at its real storage path, for example `public/applications/folder/app__1.0.0`, `platform/toolsets/tools`, `public/files/icons/a.png` or `public/skills/team/s/scripts/run.py`. An Application, Model, Interceptor and ToolSet are written in the form of the admin GET of the entity (see [Secrets](#secrets)), an application type schema and a catalog schema as parsed JSON, and a Prompt as stored. A File, and each file of a Skill, is copied exactly as stored, in one pass through a fixed buffer. The files of the current version of a Skill are written below the storage path of the Skill, without the marker and the version folder. |
| `.dial-export/config/<type>/<name>.json` | For each resource of origin `file` (defined in `aidial.config.json`, so it is not stored in a bucket): the projection of that one entity. The configuration file itself is never exported. The type is `application`, `toolset`, `model`, `interceptor`, `application-type-schema` or `catalog-schema`; for a schema the last segment is a digest of its `$id`. |

Nothing else is in the archive: no list of the dependencies or of the references that were not followed, no checksum, no metadata entry
for a resource and nothing under `.dial-system-export/`. Entry names are storage paths, which can hold characters that Windows does not
allow in file names, such as `:`; read the archive with a ZIP library or extract it on Linux. Two exports of one state are not promised to
be byte-identical: ZIP entries carry no fixed time and `createdAt` is the start of the export.

**Files, Skills and Prompts are exported**: a public Prompt is written exactly as it is stored, and the bytes of a File and of the files of
a Skill are copied as stored. A File that is a folder, is outside `public/`, or does not exist is `unsupported` or `missing`, and nothing
is read for it. A File that is gone when it is copied, copied incompletely or longer than its length cuts the stream.

**A File or a Skill that changes while it is copied.** The export compares the ETag of a File read before the copy, of the stream that it
opens and the one read after the last byte (for a Skill, the aggregate ETag of its marker read before the first file and after the last).
If they differ but the bytes were copied completely (the length matches), the version that was copied is whole, so the export does not
fail: it writes one WARN record with the key of the resource and the ETag before and after, never a value, and goes on. The archive does
not say that the resource changed.

### Secrets

An Application, ToolSet, Model or Interceptor is written as the admin API shows the same entity, less the `name` and `status` that the
API adds. Every field that Core stores encrypted (the `key` and `secretExtraData` of upstreams and routes, the keys of a Model and an
Interceptor, and the like) is not in the entry, whether it is stored as ciphertext or as plaintext. The `client_secret` and
`code_verifier` of every `auth_settings` (a ToolSet, and the external services of an Application) are removed, and the masked
`client_secret_hint` stays, as in the admin GET. The `dial:key`, `key`, `dial:secretExtraData` and `secretExtraData` of a schema route
upstream and its interfaces (the schema-document spellings of two encrypted fields) are removed from an application type schema. No
`ENC[...]` value and no key leaves, and nothing in the package says that anything was removed. An entity of the file configuration passes the
same projection.

`application_properties`, `function.env` and `default_headers` are **out of scope of v1**: the export has no rule for them, so they are
exported as the projection holds them, and a credential kept in them is exported. `application_properties` is still scanned for dependencies
(see above), but a credential kept in it is not removed. A Prompt and the bytes of a File are exported exactly as stored. Everything else is
exported as it is, **including a string that starts with `ENC[` in a field that is not encrypted** and **every URL** (user information, path,
query and fragment: URLs are not inspected for credentials; URL-form resource references are still parsed to find dependencies), a credential in
a free-text field, and `defaults`, `catalog_properties` and `override_paths`, which Core cannot classify. The policy is not approved (a
release gate). The content of `credentials/` is never exported.

### Responses

Before the response starts the export answers with a status:

| Status | Meaning |
|---|---|
| `200` | The archive is streamed (`application/zip`, `Content-Disposition` with a UTC timestamp). |
| `400` | The body is missing, holds a field other than `roots`, or is not a valid selection. |
| `403` | The caller is not an explicit admin, or a `public/` root is not readable by the caller. |
| `404` | A selected Application does not exist. |
| `409` | A bucket lock is held by another export, by a system export or by an admin write; retry later. |
| `500` | The export failed before the archive started: `Failed to prepare the export`, or `Failed to export the Applications` when the writer fails before the first bytes reach the response. |

The status is decided in this order: `403` for a caller that is not an explicit admin (before the body is read), `400` for an invalid
body, `409` when a bucket lock is held (the locks are taken before the roots are checked, so a held lock answers `409` before a `404` or
a `403` for a public root), then `403` for a public root that the caller cannot read, `404` for a root that does not exist, and `500`.

**A failure after the response has started cuts the stream.** The head is sent with the first bytes of the archive, so a status can no
longer be sent. The connection is reset: the client sees a transport error (for `curl`, for example, an incomplete transfer) and a ZIP
without its central directory, which no ZIP reader accepts as a complete archive. **A client must treat any export that ends in a transport
error as failed and must not use the partial file.** The reason is in the server log, at WARN, with the kind of the failure, the key of the resource that was in
hand, the duration and the name of the limit, never a value (see [Log records](#log-records)). These failures cut the stream:

- a limit: `maxNodes`, `maxDepth`, `maxEdges` or `maxResourceMiB` (see Limits);
- the deadline, and a client that does not take the data within `stallTimeout`;
- a stored body that is not valid JSON, not a JSON object, or cannot be bound to its type (a root or a dependency);
- a root that existed when it was checked and is gone;
- a File that is gone, ends early or is longer than its length, a file of a Skill that is gone or has another length, and a failed read or write;
- a path that has an empty, `.` or `..` segment;
- a `public/` dependency that the caller cannot read (an explicit admin can read all of `public/`, so this is a guard).

Only the roots are checked before the response starts: whether a dependency exists, how large the graph is and whether a body can be bound
are known only while the export walks it.

### Log records

The export writes these records to the server log, none of which holds a root, a URL, a body or a secret:

| Record | Level | When |
|---|---|---|
| `Admin export rejected: a bucket lock is held ...` | INFO | The request is answered `409`. |
| `Export started: roots=<n>` | INFO | The archive starts. |
| `Export completed: duration=<ms>, resources=<n>` | INFO | The last byte of the archive is written. |
| `Export aborted after the response started: resource=<key>, duration=<ms>, <kind>[: <message>]` | WARN | A failure cuts the stream. `<key>` is the resource in hand, or `-`; the message is given only for an exception of Core (a bound, a resource that is gone or cannot be bound), and an exception of Core about a resource names it by its key, never by a body or a value. |
| `Export: a resource changed while it was copied and was written whole: resource=<key> etagBefore=<etag> etagAfter=<etag>` | WARN | A File or a Skill changed during the copy and was written whole; the export goes on (see [Package](#package)). |

For the records of the references that are not followed see [References that are not followed](#references-that-are-not-followed).

### Bucket locks

Before the response starts the export takes the bucket locks of `public/` and `platform/` without waiting, as the system export does,
and holds them until the last byte is written, the export fails or the client is lost. It then excludes the system export, another export
and every write that takes these locks: the config API writes (`PUT` and `DELETE`), `POST /v1/admin/apply`, the file migration, the
config rebuild, the approval of a publication into `public/`, the write, copy and delete of a Skill and the move of a resource. Those wait until the export has finished, so the administered entities of one export (Models, Interceptors,
ToolSets, schemas and platform Applications) are a consistent set. A long export therefore delays admin configuration writes, for up to the
`deadline`.

A plain write of a Prompt or a File through the resource API takes no bucket lock and is **not** blocked, so the
export has no snapshot of those: it holds each resource as it read it, and related resources can be of different versions.

The locks are a lease of 300 s that is not renewed, and they are released when the export ends, fails, is refused by the executor or
loses its client. `adminExport.deadline` and one `stallTimeout` together must stay below the lease, which Core checks at startup, so that the
locks do not expire while the export still works.

### Limits

The size of the export is bounded by the `adminExport` settings. A limit that is exceeded fails the whole export, so a package never
silently omits a resource: `maxRoots` is a `400`, and every other limit cuts the stream (see Responses):

| Setting | Default | Limit |
|---|---|---|
| `maxRoots` | 100 | Applications named in `roots` (`400`) |
| `maxNodes` | 500 | resources that the export writes |
| `maxDepth` | 10 | longest chain of references from a root |
| `maxEdges` | 10000 | references found in one body and in all bodies together; the marker references of `application_properties` and `catalog_properties` together are checked as a whole before any is classified, the other references are counted one by one, so the export fails at the first one past the limit |
| `maxResourceMiB` | 4 | string length of one stored body |
| `deadline` | 180000 | milliseconds from the start to the last byte; with `stallTimeout` it must be below the lock period of 300000 |
| `stallTimeout` | 60000 | milliseconds the export waits for a slow client to take the buffered data |

Memory does not depend on the number of resources: the export holds the queue, the set of resources met and one resource, and a File goes
through a 32 KiB buffer. A stored body is read in full before `maxResourceMiB` can reject it, so this limit does not bound the memory of one
read: the largest stored body (`resources.maxSize`, 64 MiB by default) is the bound of what one read can allocate, and reading allocates about
five times the stored size for a moment (an estimate, not measured). Provision the heap for that, for the parse and projection of a body that
passed the limit, and for the 1 MiB pipe; only one export runs at a time.

The platform schemas are looked up by `$id`, which only their bodies hold, so a schema reference makes the export read the platform schema
blobs once per schema type, one body at a time and under the deadline; no more than one body is held.

The deadline is enforced by a check before each resource, each reference, each schema read and each chunk of a copy, and by two timers: one
interrupts the thread that works when the deadline passes, so a read that blocks is ended if it can be interrupted, and one aborts the
stream, which ends a write that waits for a slow client. An operation that ignores an interrupt (a read of a resource or of a Runner schema
that does not return) is not stopped; the export then fails at the next check and the locks are released when the operation returns. The
lease cannot be renewed, so an operation that outlasts it lets another export start while it still runs.

### Release status and scope

- The endpoint must not be released until Security has signed off the secret policy of the section Secrets
  (a release gate).
- The public scope is specific to this feature and is not a general policy for public resources: selected `public/`
  Application roots and `public/` Application, ToolSet, Prompt, File and Skill dependencies are exported; other public dependencies and all other
  public types are `unsupported`.

### Known limitations

- The origin of a short name is that of the **current effective configuration**. A deployment that was skipped may become
  effective after a later rebuild, so the meaning of a name can change after an export. The configuration file is read after the
  merged configuration and can lag a reload by about 500 ms. A model resurrected after an interceptor write can give
  `origin-unknown` until the next rebuild.
- A stored body that is not valid JSON, whether a root or a dependency, and a body that cannot be bound (for example a field of the wrong
  type), cut the stream; the response does not say which resource it is, the server log does. The log names the resource by its key and the rule and no
  part of the body.
- There is no snapshot of storage: each resource is exported as it was read, the stream can run up to the deadline, and related resources can be
  of different versions. The bucket locks keep admin writes out, not user writes of Prompts, Files and Skills.
- The merged configuration and the configuration file are separate stores that are read one after the other, so an entity of the file can be
  seen in a state that the merged configuration does not yet show; no view that is atomic across them is promised.
- The limits are configuration defaults, not benchmarked capacity: speed on large graphs is not characterized. The 5-second timeout of the Runner
  schema download covers the wait for the response head, not the body; the deadline interrupts the thread that waits for it, but no hard
  cancellation of a read exists beyond that.
- An application type schema that declares `dial:applicationTypeSchemaEndpoint` is fetched live from the Runner, once per schema id and export (the
  built schema is reused by every Application that names it; it is also used to scan `application_properties`), through Core's existing schema
  download with its existing 5-second timeout (which bounds the wait for the response head, not the body). The endpoint comes only from a stored
  application type schema, which is separate from the loading of a remote `$ref`: the export evaluates the schema with its own factory that rejects
  every non-local `$ref`, and there is no TTL cache. No allow-list, DNS or address check, body deadline or size bound is added for the export; a
  failure is logged as `schema-unavailable`.
- Scanning `application_properties` adds one probe of storage for each marked short name, as for any other short name. A Quick App can name many
  resources that are not in storage; each is logged as `missing` or `unresolved-short-name`, and the response is still `200`.
- The export of one Application can delay the admin configuration writes of the whole cluster while it runs (see Bucket locks).
