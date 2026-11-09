# Proposal

## Why

Routers (change `router-entity`) come from the config files only: an administrator who wants to add, change or
remove one edits a file and waits for the reload, while every other deployment kind is also managed at run time
through the Configuration API and the admin validate/apply batch. The router resource design (phase 1) asks for
admin CRUD, and `router-entity` already registered the resource type `routers` for its events so that this step
needs no rollout of its own. This change makes a router a managed config type like an interceptor or a translator.

## What Changes

- `GET`, `PUT` and `DELETE /v1/routers/platform/{name}` and `GET /v1/metadata/routers/platform/{path}` join the
  config entity API, with the shared rules: admin role, `platform` bucket only (`404` elsewhere), blob-only reads
  with `ETag` and conditional headers, upsert `PUT` answering `{"name": ...}`, `POST` answering `405`, `DELETE`
  answering `204`/`404`/`412`.
- A router `PUT` runs the rules the rebuild enforces on a router (`router-deployment`, "Router config entry"):
  `422` with `validationWarnings` when the body declares `interceptors`, a legacy `endpoint` or `responsesEndpoint`,
  or breaks an interface rule; `409` when the name is already used by a model, application, toolset or interceptor;
  `400` when the body declares a field a router has no business with (`upstreams`, `limits`, `pricing`), as the
  file load rejects it today.
- Blob routers merge with file routers: a blob replaces the file router of the same short name as a whole, a
  write or delete is visible on return, other pods apply the resource event, and an invalid blob router follows the
  invalid-entity policy.
- The manifest kind `Router` (name `routers/platform/{name}`) joins `POST /v1/admin/validate` and
  `POST /v1/admin/apply`, ordered after `Translator` and before `Model` in a batch (a router's translated interface
  may reference a translator of the same batch; nothing references a router).
- `GET /v1/admin/config/file/routers[/{name}]` inspects file routers, and `POST /v1/admin/config/file/migrate`
  accepts type `routers`.
- The resource type `routers` resolves by name wherever a URL segment or a manifest names it, so the generic
  config entity, metadata and replica paths handle it (the system export already copies `routers/` blobs: it walks
  the blob store, not a type list).
- A blob router that fails its rules under `config.onInvalidEntity: skip` is recorded as an invalid entity, so
  `GET /v1/admin/health/config` reports it, as for every other managed type. File routers are unaffected: the file
  load validates every entity strictly whatever the policy.

No breaking change: a config without blob routers behaves as before, and the file-only routers of `router-entity`
keep loading. `router-entity` and this change may ship in one release or in two.

Out of scope, each a change of its own or a decision not yet taken:

- `customProperties` with an app-type schema on a router (the generic-router-service use case of the design).
- User routers in own buckets, sharing, publishing and the `public` bucket for routers (phase 2 of the design),
  and so the endpoint validation a user-supplied `baseUrl` would need.
- Any change to how a router is served, listed by `GET /v1/routers`, or decided for (`router-deployment` stays as
  it is apart from the write-path wording).

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `config-store`: `routers` joins the merged sources, the blob-wins rule, the invalid-entity policy, the
  deployment-id uniqueness check on write, the `422` rules on write (router invariants), the manifest name segments,
  the batch dependency order, the validate endpoint and the file migration types; one added requirement says a
  router write or delete is visible on return as a deployment.
- `request-routing`: `routers` joins the config entity routes, the metadata routes and the admin file config
  routes.
- `router-deployment`: "Router config entry" says a router comes from the config files or the `platform` bucket,
  and that the same rejections apply on a Configuration API write.

## Impact

Motivated by the router resource design (`design/router_resource.md`, documentation MR 211). Of its phase 1 this
change covers requirement 3 (admin CRUD) and the platform-bucket half of requirement 10; it leaves out
`customProperties` with an app-type schema (requirement 8) and all of phase 2. Depends on `router-entity` and
`router-decision-mode` (archived, PR #2232) and ships as its own PR.

Code (template: the translator commit that added translators to the admin config API, and today's interceptor
handling): `ResourceTypes.of`, `MergedConfigStore` (managed types, replica deserializer, partial update, rebuild
merge, blob scan), `PlatformEntityLocationStrategy`, `EntityBucketBinding`, `RouteTemplate`, `ConfigResourceController`,
`ConfigResourceMetadataController`, `FileConfigController`, `ConfigFileMigrateController`, the admin manifests
(`AdminRouterManifest`), `ConfigManifestSupport`, `ConfigValidationService`, `ConfigApplyService`,
`ConfigPostProcessor` (a single-router validator factored from the rebuild loop). Docs: `docs/dynamic-settings/routers.md`
gains the Configuration API paragraph, `docs/open_api_core.yaml` is regenerated. No new dependency, no migration,
no effect on the request path: the change touches config reads and writes only.
