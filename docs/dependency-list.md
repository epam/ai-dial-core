# Dependency list

`POST /v1/ops/dependency/list` answers one question: what does an Application need. For each Application you name, it returns the flat list of
the resources the Application reaches through its references: other Applications, Models, Interceptors, application type and catalog schemas,
Files, Skills and Prompts. The call only reads. It copies, moves and changes nothing.

The endpoint is a preview (`x-preview`) and is described in the [OpenAPI specification](open_api_core.yaml). Every authenticated caller can
use it, and what the answer holds depends on who asks (see [Who sees what](#who-sees-what)).

## Request

```json
{
  "roots": [
    { "type": "application", "url": "applications/public/team/support-bot__1.0" },
    { "type": "application", "url": "applications/platform/summarizer" }
  ]
}
```

`roots` is the only field and must not be empty. Each root is `{"type": "application", "url": "<resource URL>"}`, and the URL names an
Application, not a folder. The same root named twice is listed once. Any other field, a type other than `application`, a URL that does not
parse or a URL of another resource type is a `400`.

## Answer

The answer has one entry per root, in the order of the request. Each entry holds the identity of the root and its `dependencies`:

```json
{
  "roots": [
    {
      "type": "application", "url": "applications/public/team/support-bot__1.0", "name": "support-bot__1.0", "origin": "public",
      "dependencies": [
        { "type": "application", "url": "applications/platform/summarizer", "name": "summarizer", "origin": "platform" },
        { "type": "interceptor", "url": null, "name": "pii-filter", "origin": "file" },
        { "type": "file", "url": "files/public/team/support-bot/icon.png", "name": "icon.png", "origin": "public" },
        { "type": "model", "url": "models/platform/gpt-4o", "name": "gpt-4o", "origin": "platform" }
      ]
    },
    {
      "type": "application", "url": "applications/platform/summarizer", "name": "summarizer", "origin": "platform",
      "dependencies": [
        { "type": "interceptor", "url": null, "name": "pii-filter", "origin": "file" },
        { "type": "model", "url": "models/platform/gpt-4o", "name": "gpt-4o", "origin": "platform" }
      ]
    }
  ]
}
```

| Field | Where | Value |
|---|---|---|
| `type` | root entry and item | `application`, `model`, `interceptor`, `application-type-schema`, `catalog-schema`, `file`, `skill` or `prompt` |
| `url` | root entry and item | The URL of the resource, which is also valid as a root of a later request; for a resource in a user bucket the bucket is in its encrypted form. `null` for an entity that only `aidial.config.json` defines and, for a caller who is not an administrator, for a Model or an Application of the configuration |
| `name` | root entry and item | The last segment of the path, with a version suffix such as `__1.0` left in. For an entity of the file configuration the name of the deployment, and for a schema of the file configuration the digest of its `$id` |
| `origin` | root entry and item | `public`, `platform`, `file` (an entity of `aidial.config.json`) or `private` (a user bucket) |
| `dependencies` | root entry only | The flat list of everything the root reaches. Empty when the root references nothing |

The list of a root follows these rules:

- A resource is listed once, however many paths, reference forms or cycles lead to it. The order is breadth first, in the order reached.
- The root itself is never in its own list, also when the Application names itself or is part of a cycle.
- A resource that two roots reach is in the list of each. A root that another root needs is an item in that root's list and also an entry of
  its own.
- A reference that points at a resource that does not exist, at a folder, at an absolute URL or at a type the list does not support gives no
  item. It does not fail the request.

The answer has no flag, no count and no warning, and it never holds the body of a resource, a secret, an endpoint, a key, a role or an
absolute storage path.

## Who sees what

An administrator (a caller that matches `access.admin.rules`) and any other caller get the same walk and the same answer, but not the same
resources.

**An administrator** may name roots in `public/` and `platform/` and gets the dependencies in `public/`, in `platform/` and in the file
configuration. The `user_roles` of a deployment are not checked. A resource in a user bucket is never listed, and a root in a user bucket is a
`400`.

**Any other caller** may name a root in their own bucket, in a bucket shared with them or in `public/`, and only when they may read it. Each
dependency is checked on its own, so naming a root never opens its dependencies:

- A resource in `public/`, in the caller's own bucket or in a shared bucket is listed when the caller may read it. The right to read comes
  from the caller's own bucket, what is shared with them and the rules of the public folders. A global-reader or administrator standing and the
  grants of a calling key do not widen the list. The `origin` of such a resource is `public`, or `private` in a user bucket.
- A Model or an Application of the configuration (a short name or a `platform/` URL) is listed only when its `user_roles` admit one of the
  roles of the caller, and its references are then followed, so the list shows what the Application uses at run time. It has `url` `null`.
- An Interceptor and a schema of the configuration are listed.
- A resource that the caller may not read, or a deployment that their roles do not admit, is left out without a trace: it is not read, not
  checked for existence, and the status does not change. A root is checked for the right to read before it is looked up, so a root the caller
  may not read is a `403` whether or not it exists, and a `404` only follows a root that the caller may read.

## What the list follows

For an Application, a Model and an Interceptor Core reads these references:

| Field | Reference |
|---|---|
| `interceptors` | short names of Interceptors |
| `dependencies` | a URL or a short name |
| `icon_url` | a URL of a file in `files/` |
| `catalog_schema_id` | the `$id` of a catalog schema |
| values under `dial:resource` and `dial:file` markers of `catalog_properties` | a URL or a short name |

For an Application Core also reads `application_type_schema_id` and the marked values of `application_properties`.

A short name is looked up among the deployments of the configuration. A name that nothing holds, that more than one type holds, or whose origin
Core cannot establish is not followed. Schemas, Files, Skills and Prompts are listed when they exist and are never read for references.
`function.source_folder`, `user_roles`, `interfaces.*.translator`, inline `routes`, Routers, Projects and Conversations are not examined.

A reference that is not followed is one record in the server log with the key of the referring resource, the JSON pointer of the reference
and a reason (`missing`, `unsupported`, `unresolved-short-name`, `origin-unknown` or `schema-unavailable`). The log never holds the value.

## Statuses

| Status | When |
|---|---|
| `200` | The answer above. Findings about references do not change it |
| `400` | The body is missing, empty or not JSON, holds a field other than `roots`, or a root is not a valid Application. The message names `roots` or the index of the root, never the submitted value |
| `403` | The caller authenticates with a per-request key (an application, not a person), or is not an administrator and may not read a selected Application, or names an Application in `platform/` |
| `404` | A selected Application does not exist (for a caller who is not an administrator, only one that they may read) |
| `500` | The walk failed |

Every status is decided before the body of the answer. The walk takes no bucket lock, so it does not wait for an administrative write that
holds the bucket locks, and such a write does not wait for it. It does take, for two reads, the lock that every configuration publication
holds (the merged configuration and the skipped-entity records are read together, so that short names resolve against one publication). It
therefore waits for any publication that is in progress: a configuration rebuild, an apply, or the write or delete of one configuration entity.
A publication that starts meanwhile waits only for those two reads. The list is not a snapshot: a write that lands while it runs may or may not
be in it.

## Remote application type schemas

An application type schema that declares `dial:applicationTypeSchemaEndpoint` is requested from that endpoint during the call, so that the
references under its markers can be found. The endpoint comes from the stored schema and never from the request, and a schema is requested at
most once per call. If the request fails, answers with an error status or returns a document that cannot be used, the references that depend
on the schema are not followed, one record with the reason `schema-unavailable` is logged, and the answer is still `200`.
