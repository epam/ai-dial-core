# Spec Delta

## Purpose

The list of resources that selected Applications reach through their references: what the request and the answer look like, how one resource is identified, how the dependencies of each root are listed, what the walk follows, and what each kind of caller may see in the answer.

## ADDED Requirements

### Requirement: The dependency list is POST /v1/ops/dependency/list
Core SHALL serve the list on `POST /v1/ops/dependency/list` and on no other path. The request body is required.

#### Scenario: The route answers
- **WHEN** an authenticated user sends `POST /v1/ops/dependency/list` with a valid body
- **THEN** the response is `200` with `Content-Type: application/json`

### Requirement: The request names the root Applications
The body SHALL be a JSON object that holds `roots` only: a non-empty list of `{"type": "application", "url": "<resource URL>"}`. Each URL SHALL name an Application, not a folder. The same root named twice SHALL be listed once.

#### Scenario: Root named twice
- **WHEN** the same Application is named twice in `roots`
- **THEN** the answer holds one root entry for it

#### Scenario: Several roots
- **WHEN** `roots` names two Applications
- **THEN** the answer holds two root entries, in the order of the request

### Requirement: An invalid request is refused with 400
Core SHALL answer `400` for an empty or whitespace-only body, a body that is not valid JSON, a field other than `roots`, a missing or empty `roots`, a `type` other than `application`, a URL that does not parse, a URL of another resource type, and a folder. The message SHALL name at most `roots` or the index of a root and SHALL hold no submitted value.

#### Scenario: Body that is not a selection
- **WHEN** a caller sends no body, a body of only whitespace, a body that is not valid JSON, or `{"roots":[]}`
- **THEN** the response is `400`

#### Scenario: Field that is not part of the request
- **WHEN** the body holds a field besides `roots`, for example `"extra":"sk-abc123"`
- **THEN** the response is `400` and the message does not contain the submitted value

#### Scenario: Root that is not an Application
- **WHEN** a root has `"type":"model"`, or its URL names a Model or a folder
- **THEN** the response is `400` and the message does not contain the URL

### Requirement: A caller that holds a per-request key is refused
Core SHALL answer `403` to a caller that authenticates with a per-request key (an application, not a person), before it reads the body. Every other authenticated caller SHALL be served, and the caller's standing decides what the answer holds.

#### Scenario: Per-request key
- **WHEN** an application calls the endpoint with its per-request key and a valid body
- **THEN** the response is `403` and nothing about the roots is disclosed

### Requirement: The answer holds one entry per root, each with its dependencies
A successful answer SHALL be `{"roots": [...]}`: one entry per root, in the order of the request. A root entry SHALL hold `type`, `url`, `name`, `origin` and `dependencies`. An item of `dependencies` SHALL hold `type`, `url`, `name` and `origin`, and no other field. The answer SHALL hold no flag, no list of references, no count and no warning. An element is a root entry or an item.

#### Scenario: Shape of the answer
- **WHEN** an Application with one Model dependency is the root
- **THEN** `roots` holds one entry with the four fields and `dependencies`, and `dependencies` holds one item with exactly four fields

#### Scenario: Application without dependencies
- **WHEN** the root references nothing
- **THEN** its entry has `dependencies` as an empty list

#### Scenario: Two roots with shared dependencies
- **WHEN** `support-bot` (which depends on `summarizer`, a prompt, a file, `pii-filter`, `gpt-4o` and `gpt-4o-mini`) and `summarizer` (which depends on `pii-filter`, `gpt-4o` and `gpt-4o-mini`) are the two roots
- **THEN** `roots` holds the `support-bot` entry and then the `summarizer` entry, the first with six items and the second with three

### Requirement: The dependencies of a root are every resource it reaches, each once
The `dependencies` of a root SHALL list every resource reachable from that root through references, once, however many paths, reference forms or cycles reach it, breadth first in the order reached. The root itself SHALL NOT be in its own list. Each root's list SHALL be computed on its own, so a resource that two roots reach is in both lists, and Core SHALL read that resource once.

#### Scenario: Cycle between two Applications
- **WHEN** Application `a` depends on `b`, `b` on `a`, and `a` is the root
- **THEN** the `dependencies` of `a` hold `b` once and do not hold `a`

#### Scenario: Cycle between a Model and an Interceptor
- **WHEN** a Model names an Interceptor in `interceptors` and that Interceptor names the Model in `dependencies`, and an Application names the Model
- **THEN** the `dependencies` of the Application hold the Model once and the Interceptor once

#### Scenario: Diamond
- **WHEN** a root depends on two Applications, and both name the same Model
- **THEN** the Model is in the `dependencies` of the root once

#### Scenario: Reference to itself
- **WHEN** an Application names itself in `dependencies`
- **THEN** its `dependencies` are empty

#### Scenario: Root that another root needs
- **WHEN** Application `a` depends on Application `b` and both are roots
- **THEN** `b` is an item in the `dependencies` of `a` and is also a root entry with its own `dependencies`

#### Scenario: Resource that two roots reach
- **WHEN** two roots reach the same Model, one directly and the other through an Application
- **THEN** the Model is in the `dependencies` of each

#### Scenario: A shared dependency is read once
- **WHEN** two roots both depend on the same Model
- **THEN** the body of the Model is read once, and the Model is in the `dependencies` of each

#### Scenario: Order
- **WHEN** a root depends on `x` and `y`, and `x` depends on `z`
- **THEN** its `dependencies` list `x`, `y`, `z` in that order

### Requirement: url is the resource URL the caller can send back
`url` SHALL be the URL of the resource, in the form that the endpoint accepts as a root: for a resource in a user bucket the bucket is in its encrypted form, never the decrypted location. `url` SHALL be `null` for an entity of the file configuration and for a deployment of the configuration that is listed for a caller who is not an explicit admin.

#### Scenario: Root URL round trip
- **WHEN** the `url` of a listed Application is sent as a root of a second request
- **THEN** the second request is accepted and lists that Application

#### Scenario: Entity of the file configuration
- **WHEN** a dependency is a Model that only `aidial.config.json` defines
- **THEN** its element has `url` null and `origin` `file`

#### Scenario: No decrypted bucket
- **WHEN** a resource in a user bucket is listed
- **THEN** no field of the answer holds the decrypted bucket location or the user id

### Requirement: origin says where a resource comes from
`origin` SHALL be `public` for a resource in `public/`, `platform` for one in `platform/`, `file` for an entity of the file configuration, and `private` for one in a user bucket, the caller's own or one shared with the caller.

#### Scenario: One origin per resource
- **WHEN** an Application depends on a public Application, a platform Model and a file Interceptor
- **THEN** the three elements have `origin` `public`, `platform` and `file`

### Requirement: name is the last segment of the path
`name` SHALL be the last segment of the resource path, with a version suffix such as `__1.0.0` left in. For an entity of the file configuration it SHALL be the name of the deployment, and for a schema of the file configuration the digest of its `$id`.

#### Scenario: Versioned Application
- **WHEN** `applications/public/team/bot__1.0.0` is listed
- **THEN** its `name` is `bot__1.0.0`

### Requirement: The walk follows Deployments and stops at the other types
Core SHALL examine the references of an Application, a Model and an Interceptor it lists. It SHALL list an application type schema, a catalog schema, a File, a Skill and a Prompt and SHALL NOT examine their content. A reference to any other type, to a folder or to an alias SHALL NOT produce an element.

#### Scenario: Model to Interceptor to File
- **WHEN** a root Application depends on a platform Model whose `interceptors` names an Interceptor and whose `icon_url` is `files/public/a/t.png`
- **THEN** the Model, the Interceptor and the File are listed

#### Scenario: A leaf is not examined
- **WHEN** a listed Prompt, Skill or File holds text that names an Application
- **THEN** that Application is not listed

#### Scenario: Other types
- **WHEN** an Application names a Project, a Conversation or a Router
- **THEN** no element is produced for it

### Requirement: A leaf is checked for existence only
For an application type schema, a catalog schema, a File, a Skill and a Prompt, Core SHALL check that the resource exists and SHALL NOT read its body. A resource that does not exist SHALL NOT be listed.

#### Scenario: Missing File
- **WHEN** an Application's `icon_url` names `files/public/a/gone.png` and no such file exists
- **THEN** the answer is `200` and holds no element for it

### Requirement: References come from fixed fields and schema markers
For every Deployment it examines, Core SHALL find references in `interceptors`, `dependencies`, `catalog_schema_id`, `icon_url` and the values under `dial:resource` and `dial:file` markers of `catalog_properties`. For an Application it SHALL also examine `application_type_schema_id` and the markers of `application_properties`. It SHALL NOT examine `function.source_folder`, `user_roles`, `interfaces.*.translator` or inline `routes`.

#### Scenario: Fixed fields of an Application
- **WHEN** an Application has `application_type_schema_id`, `interceptors: ["i1"]` and `dependencies` with a platform Model URL
- **THEN** the schema, `i1` and the Model are listed when they exist

#### Scenario: Marker in application properties
- **WHEN** an `application_properties` value under a `dial:file` marker names `files/public/a/logo.svg`, and the file exists
- **THEN** the file is listed

#### Scenario: Fields that are not examined
- **WHEN** an Application sets `function.source_folder` and `user_roles`
- **THEN** no element is produced from them

#### Scenario: Schema id of a Model
- **WHEN** a Model body holds a field that looks like `application_type_schema_id`
- **THEN** it is not read as a reference

### Requirement: A reference value is classified in a fixed order
Core SHALL classify a value of `dependencies`, of a marker or of `icon_url` in this order: a blank value is malformed; an absolute URL is external and is never fetched; a value with an empty, `.` or `..` path segment, also percent-encoded, is malformed and never read; a value that parses as a resource URL names its resource; a value without `/` is a short name; any other value is malformed.

#### Scenario: External URL
- **WHEN** a marked value is `https://example.com/x`
- **THEN** nothing is fetched and no element is produced

#### Scenario: Unsafe path
- **WHEN** a reference is `files/public/a/../b` or `files/public/a/%2e%2e/b`
- **THEN** nothing is read and no element is produced

#### Scenario: icon_url
- **WHEN** `icon_url` is `files/public/a/icon.png` and the file exists
- **THEN** the file is listed; for an absolute URL or for any other value no element is produced

### Requirement: A URL-form reference names the stored resource
A reference in URL form, such as `applications/platform/x`, SHALL be taken as the stored resource that its type, bucket and name identify, whatever the merged configuration holds under the same short name. A target that does not exist SHALL NOT be listed.

#### Scenario: URL form beside a Model of the same name
- **WHEN** an Application's `dependencies` names `applications/platform/x` and a Model holds the short name `x`
- **THEN** the Application `x` is listed and the Model is not

### Requirement: A short name is resolved against one configuration pair
Core SHALL resolve a short name of `dependencies`, `interceptors` or a marker against the merged configuration and the skipped-entity records of one publication, read together, and the file-sourced configuration read after them. It SHALL look up an `interceptors` entry among interceptors only and any other short name among the Deployment types.

#### Scenario: Platform-only entity
- **WHEN** a short name is held only by a platform blob that the merged configuration holds
- **THEN** the resource is listed with `origin` `platform`

#### Scenario: File-only entity
- **WHEN** a short name is held only by the file configuration
- **THEN** the resource is listed with `origin` `file`

#### Scenario: Type hint of an interceptors entry
- **WHEN** an Application's `interceptors` names `x`, and a Model named `x` is also held
- **THEN** only interceptors are searched

### Requirement: A name that cannot be placed is not followed
Core SHALL NOT follow a short name that no entity holds, that more than one Deployment type holds, or whose origin the file configuration, the platform blob and the skipped-entity records do not establish. It SHALL NOT choose a candidate by map order or type priority. A skipped entity SHALL NOT make a name ambiguous and SHALL NOT be listed.

#### Scenario: Name that nothing holds
- **WHEN** a marked value is a short name that no entity holds
- **THEN** no element is produced and the answer is `200`

#### Scenario: Name held by two types
- **WHEN** an Application and a Model of one name are both held
- **THEN** a reference by that name produces no element

#### Scenario: Skipped entity of another type
- **WHEN** an Application holds a short name and a Model of that name was skipped as invalid
- **THEN** the name resolves to the Application

### Requirement: A platform blob wins over a file entity of the same name
When a valid platform blob and an entity of the file configuration share a type and a name (for a schema, the `$id`), Core SHALL list the platform blob with `origin` `platform` and SHALL NOT list the file entity. A file entity that no valid blob replaces SHALL be listed with `origin` `file`.

#### Scenario: Blob and file entity share a name
- **WHEN** a valid platform Model blob and a file Model share a short name
- **THEN** one element is listed with `origin` `platform`

#### Scenario: File entity that no blob replaces
- **WHEN** a file entity has no platform blob of its type and name
- **THEN** it is listed with `origin` `file`

### Requirement: A schema is located by its $id
A schema SHALL be identified by its body `$id`; its blob name is arbitrary. For `application_type_schema_id` and `catalog_schema_id` Core SHALL list the platform blobs of that schema type once per walk, read one body at a time for its `$id`, and then apply the origin rules of a short name.

#### Scenario: Blob name differs from the schema id
- **WHEN** an Application's `application_type_schema_id` names a schema whose platform blob has another name
- **THEN** the schema is located by its `$id` and listed

#### Scenario: One scan per schema type
- **WHEN** two Applications of one walk name application type schemas and one names a catalog schema
- **THEN** Core lists the platform application type schema blobs once and the platform catalog schema blobs once

### Requirement: A remote application type schema is fetched live and read with local references only
To evaluate a schema that declares `dial:applicationTypeSchemaEndpoint`, Core SHALL request it from that endpoint during the walk through the existing schema download, never from the request, and SHALL NOT change what request processing later sees for the schema. Core SHALL refuse every `$ref` that is not local to the document.

#### Scenario: Live request
- **WHEN** a walk touches an Application whose type schema declares an endpoint
- **THEN** Core requests the schema during the walk, and a second walk makes its own request

#### Scenario: Remote reference
- **WHEN** a runner document contains a `$ref` to `http://...`, `file:...` or `classpath:...`
- **THEN** nothing is fetched through it and the schema is unusable

### Requirement: A schema that cannot be evaluated does not fail the walk
A failed request, a non-success status, a document that is not a JSON object, a refused `$ref` or a schema that cannot be built SHALL make the references that depend on it unreliable. Core SHALL log that, go on, and answer `200` with what it could resolve. It SHALL NOT write the endpoint, the response body, a header or an exception message to a log.

#### Scenario: Runner fails
- **WHEN** the runner does not answer within 5 seconds or answers with a non-success status
- **THEN** the answer is `200` and holds the elements that do not depend on the schema

#### Scenario: Log of a failed download
- **WHEN** the download fails with an exception whose message contains the host
- **THEN** neither the message nor the endpoint is in any log line

### Requirement: A reference that is not followed never changes the answer
A reference that is missing, unsupported, external, an unresolved short name, of unknown origin, or hidden by an unavailable schema SHALL NOT change the status and SHALL NOT end the walk. Each SHALL be one log record with the key of the referring resource, the JSON pointer and a reason, at WARN, and INFO for an external URL, and SHALL hold no reference value, credential, schema body or exception message.

#### Scenario: Findings do not fail the request
- **WHEN** an Application has a missing dependency, an unsupported one, an external URL, an unresolved short name and a schema that cannot be loaded
- **THEN** the response is `200` and the log holds one record for each with the key, the pointer and the reason

#### Scenario: Token-like value
- **WHEN** a marked value is `sk-abc123` and no entity holds it
- **THEN** the log record holds the pointer and the reason `unresolved-short-name` and not the value

### Requirement: An explicit admin walks public, platform and the file configuration
For a caller with explicit admin access (`access.admin.rules`), Core SHALL accept roots in `public/` and `platform/` and SHALL list dependencies in `public/`, in `platform/` and in the file configuration. It SHALL NOT check the `user_roles` of a deployment. Any other location, such as a user bucket, a review bucket or an app bucket, SHALL be unsupported.

#### Scenario: Platform dependency
- **WHEN** an explicit admin lists an Application that depends on the platform Model `m`
- **THEN** the Model is listed with `origin` `platform`

#### Scenario: Restricted deployment
- **WHEN** a short name resolves to a deployment whose `user_roles` the admin does not hold
- **THEN** it is listed

#### Scenario: User bucket for an admin
- **WHEN** an explicit admin names a root, or an Application references a resource, in a user bucket
- **THEN** a root is refused with `400` and a reference produces no element

### Requirement: Any other caller walks what they may read
For a caller who is not an explicit admin, Core SHALL accept a root in the caller's own bucket, in a bucket shared with the caller, or in `public/`, when the caller may read it, and SHALL list a resource by URL only when the caller may read it. A resource that the caller may not read SHALL be left out without a trace: not read, not checked for existence, and with no change to the status.

#### Scenario: Own bucket
- **WHEN** a user lists an Application in their own bucket that depends on a file in that bucket
- **THEN** both are listed with `origin` `private`

#### Scenario: Shared resource
- **WHEN** a user lists an Application shared with them that depends on a public Application
- **THEN** the shared Application is listed with `origin` `private` and the public Application with `origin` `public`

#### Scenario: Dependency the caller may not read
- **WHEN** a user's Application names a file in another user's bucket that is not shared with them
- **THEN** the answer is `200` and holds no element for the file

#### Scenario: Public folder rules
- **WHEN** a dependency is in a public folder whose rules do not allow the caller
- **THEN** it is left out

### Requirement: A caller sees the deployments their roles allow and the interceptors
For a caller who is not an explicit admin, Core SHALL list a Model or an Application of the configuration that a short name or a platform URL resolves to only when the deployment's `user_roles` admit the caller, and SHALL follow the references of a listed deployment. It SHALL list an Interceptor and follow it. The deployments SHALL have `url` null and the `origin` that an admin would see.

#### Scenario: Role allows
- **WHEN** a user's Application names a Model whose `user_roles` the user holds
- **THEN** the Model is listed with `url` null

#### Scenario: Role does not allow
- **WHEN** the named Model's `user_roles` do not include any role of the user
- **THEN** the Model is not listed and its references are not followed

#### Scenario: Through a platform Model
- **WHEN** a user's Application names a platform Model that the user's roles admit, and the Model names a public File in `icon_url` that the user may read
- **THEN** the Model and the File are listed

#### Scenario: Interceptor
- **WHEN** an Application names an Interceptor
- **THEN** the Interceptor is listed

### Requirement: A root is authorized before it is looked up
For a caller who is not an explicit admin, Core SHALL answer `403` for a root the caller may not read, whether or not the resource exists, and SHALL answer `404` only for a root the caller may read and that does not exist. A root in `platform/` SHALL be refused with `403`.

#### Scenario: Another user's Application
- **WHEN** a user names an Application in a bucket that is not theirs and not shared with them
- **THEN** the response is `403`, the same whether the Application exists or not

#### Scenario: Missing root the caller may read
- **WHEN** a user names `applications/public/gone` that the folder rules let them read, and it does not exist
- **THEN** the response is `404`

#### Scenario: Platform root
- **WHEN** a user names `applications/platform/x`
- **THEN** the response is `403`

### Requirement: Only the caller's own standing widens the list
For a caller who is not an explicit admin, Core SHALL decide the right to read a resource from the caller's own bucket, the resources shared with the caller and the public folder rules only. The standing of a global reader and the grants of a calling key SHALL NOT widen the list.

#### Scenario: Global reader
- **WHEN** a caller who holds only global-reader access lists an Application that depends on a platform Model their roles do not admit
- **THEN** the Model is not listed

### Requirement: The answer holds no body, secret, endpoint or role
An element SHALL NOT carry the body of a resource, a secret, an endpoint, an upstream, a key, a role, or an absolute storage path. Nothing of the answer or of a log record SHALL depend on a value of a resource that the caller may not read.

#### Scenario: Application with an endpoint and upstream keys
- **WHEN** a listed Application has an `endpoint`, routes with upstream keys and `user_roles`
- **THEN** no field of the answer holds them

### Requirement: The walk takes no bucket lock
Core SHALL NOT take a bucket lock for the walk, and a write of an administered resource SHALL NOT wait for it and SHALL NOT make it fail. The list is not a snapshot: a write that lands while it runs may or may not be in it.

#### Scenario: Concurrent admin write
- **WHEN** an admin write that takes the bucket locks runs while a walk is in progress
- **THEN** neither waits for the other, and the walk answers `200`
