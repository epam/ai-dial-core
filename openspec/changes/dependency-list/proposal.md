## Why

Core stores references between resources: an Application names Models, Interceptors, schemas, Files, Skills, Prompts and other
Applications, by short name, by URL, in `dependencies`, in `interceptors` and under `dial:resource` / `dial:file` markers in its
properties. No API answers the question "what does this Application need". Each flow that touches a reference finds a part of them for
its own purpose (copy, share, request-time access checks, the validation of a `PUT`), and none of them reports the result or follows
references through other resources.

This change adds one read-only operation that answers, for each selected Application, with the flat list of the resources it reaches. Any user may ask, and the
answer holds only what that caller may see. It is the single implementation of the dependency graph.

## What Changes

- `POST /v1/ops/dependency/list` is a new route. It takes `roots`, a list of `{"type":"application","url":...}`, and answers `200` with
  `{"roots":[...]}`. The route and every class that serves it are new.
- The answer has one entry per root, with the root's `type`, `url`, `name` and `origin` and a flat `dependencies` list of everything the root
  reaches; each item has the same four fields. A dependency is listed once in a root's list, also in a cycle or a diamond. A resource that two
  roots reach is in both lists, and a root that another root needs is also an item in that root's list.
- The graph is explored once, breadth first, for all roots: each resource is read once and only what it refers to is kept, never its body.
  Each root's list is then taken from those references.
- The reference discovery is added: it finds references in the fixed fields and under the schema markers of an Application, a Model and an
  Interceptor, resolves short names against the merged configuration with their origin, and locates schemas by `$id`.
- Two access policies share one walk and one answer format. An explicit admin walks `public/`, `platform/` and the file configuration.
  Any other authenticated caller walks the resources they may read (their own bucket, resources shared with them, `public/` under the folder
  rules) and the deployments their roles allow, and an element they may not see is left out without a trace. A caller that holds a per-request
  key gets `403`.
- The walk takes no bucket lock. It reads the merged configuration and the skipped-entity records together, under the lock that every
  configuration publication holds, so that short names resolve against one publication. It therefore waits for a rebuild or an apply that is in
  progress.
- The answer never holds a body, a secret, an endpoint, a key, a role or an absolute storage path. A reference that is not followed is one
  server log record with the resource key, the JSON pointer and a reason, never the value.

The shape of the request and the answer, with an example, is in `design.md` (D1).

## Out of Scope

- A response that lists references (edges), warnings, a per-element reason or a count. The answer is each root with its flat list of dependencies.
- Roots other than Applications.
- A limit on the number of elements. The walk is bounded by the size of the graph that the caller may reach.
- Copy and share on the same walk. Those flows keep their own collectors.
- Reverse lookup ("who uses this resource") and folder expansion.
- Scanning the content of schemas, Prompts, Files and Skills for references.
- A change of `MergedConfigStore` or of request-time schema retrieval.

## Capabilities

### New Capabilities

- `dependency-listing`: the dependencies of selected Applications: the request and the answer, the identity of a resource, the flat list of
  dependencies under each root, the walk and what it follows, the two access policies, what the answer and the log never hold.

### Modified Capabilities

No delta spec is written for another capability. The change reads and reuses `resource-access-control` (the check that authorizes a
resource in a bucket), `config-store` (the merged configuration that short names resolve against), `deployment-origin` (the origin of a
deployment) and `resource-dependencies` (the initiator's reach). It does not change them.

## Impact

- API: `POST /v1/ops/dependency/list` (marked `x-preview`); `docs/open_api_core.yaml` is generated from the annotations. No existing
  route changes meaning.
- New code in `server`: `DependencyListController`, `DependencyWalker`, `DependencyRequestParser`, `DependencyAccessPolicy` with an admin
  and an initiator implementation, `DependencyResource`, `DependencyRootEntry`, `DependencyListResponse`, `DependencyTypePolicy`,
  `DependencyResourceNames`, `FileConfigEntities`, `DeploymentEdgeDiscovery`, `DiscoveredEdge`, `DiscoveryResult`, `ShortNameResolver`,
  `ShortNameResolution`, `SchemaReferenceResolver`, and the `validation` marker classes (`MarkerCollection`, `MarkerRecorder`, the issue types).
- Existing code changed, additively: `ApplicationSchemaService` (`buildReferenceSchema`, `collectMarkerReferences`, a `quiet` flag on the
  schema download that only this feature sets) and `CatalogSchemaService.collectMarkerReferences`; `DialFileKeyword`,
  `DialResourceKeyKeyword` and `CatalogFileKeyword` call `MarkerRecorder.record`, which does nothing unless a recorder is registered.
  Request-time behavior of these classes is unchanged. `ControllerSelector`, `RouteTemplate` and the OpenAPI collector get the route.
- Admin writes: none waits for a bucket lock of the walk, because it takes none. A configuration write waits for the two reads that the walk makes
  under the publication lock, and the walk waits for a rebuild or an apply that holds that lock.
- Docs: `docs/dependency-list.md`, `docs/error_codes.md`; `docs/open_api_core.yaml` is generated and committed by CI. No setting is added.
- Motivation: the need to see what an Application depends on before it is moved, copied or shared. This change covers the list and the reference discovery.
