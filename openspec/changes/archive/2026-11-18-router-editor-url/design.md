# Design

## Context

See proposal.md for motivation. Facts on `development`:

- `Router.java` declares `applicationTypeSchemaId`, `applicationProperties`, `mode` and `decision`, each with
  `@JsonInclude(NON_NULL)`. It has no naming strategy, so it serializes camel case.
- `ConfigEntityCodec.BLOB_MAPPER` keeps Jackson's default `FAIL_ON_UNKNOWN_PROPERTIES`, and
  `ConfigEntityCodec.treeToEntity` reports an unknown field as `Failed to parse Router at "<field>": unrecognized
  field`. A `PUT /v1/routers/platform/{name}` with `editorUrl` answers `400` today. `FileConfigStore.java:loadConfig` converts
  the whole file tree to `Config` with a mapper that also fails on an unknown field, so a file router with it stops the
  file load. `BLOB_MAPPER` sets no serialization inclusion, so a field without `@JsonInclude` is written as `null`.
- `Application.java` declares `String editorUrl` with `@JsonAlias({"editorUrl", "editor_url"})` and no validation.
  `ApplicationData.java:toData` copies it to the listings as `editor_url`. Core never fills it from the type schema;
  only `viewerUrl` comes from the schema (`DeploymentController.java:resolveLocalApplication`).
- `RouterData.java:toData` copies named fields only, so a new `Router` field reaches no listing unless it is copied
  there.
- `GET /v1/routers/platform/{name}` returns the stored blob plus `name` and `status`.
  `FileConfigController.java:handleSingle` serves `GET /v1/admin/config/file/routers/{name}` from `config.getRouters()`,
  serializing the `Router` with `ProxyUtil.MAPPER`. The system export copies platform blobs as stored.
- The OpenAPI `Router` schema in `docs/open_api_core.yaml` is generated from the class
  (`./gradlew :openapi-generator:replaceSpec`).

## Goals / Non-Goals

**Goals:**

- One optional field on a router, readable wherever an administrator reads the router.

**Non-Goals:**

- Any request-path behaviour, validation or listing exposure. See proposal.md, Out of scope.

## Decisions

### D1. `editorUrl` on `Router`, as `Application` declares it, stored only when set

`Router` gains `String editorUrl` with `@JsonAlias({"editorUrl", "editor_url"})` and
`@JsonInclude(JsonInclude.Include.NON_NULL)`. Without the inclusion rule every router blob would gain an
`"editorUrl": null` key, and an older Core would then skip every router written after the upgrade. The class javadoc
names the field next to the type schema.

Rejected: the field on `Deployment`. Models, interceptors and toolsets would then accept it with nothing behind it.
Rejected: a new name such as `adminUiUrl`. DIAL Admin already reads `editorUrl` from an application, and its editor
code can then serve a router page unchanged.

### D2. Not in the listings

`RouterData` stays as it is. Only administrators manage routers in phase 1, and DIAL Admin reads routers through the
Configuration API, which returns the stored blob. The new requirement's "Editor URL not listed" scenario pins the
absence in every listing.

Rejected: `editor_url` in `RouterData`, as `ApplicationData` has it. It would hand an admin URL to every user who can
call the router, for no client that reads it today.

### D3. No validation of the value

The field is a plain string, as `Application.editorUrl` is. In phase 1 only administrators write routers, so the
value comes from the same people who set every other router url.

Rejected: a `URI` type or an `http(s)` check. It would differ from the application field that DIAL Admin treats the
same way.

### D4. No editor URL from the type schema

Core does not resolve `dial:applicationTypeEditorUrl` for a typed router. It does not do so for an application either,
and DIAL Admin already prefers the schema's editor URL over the entity's when it has the schema.

## Effect per request

None. No request path reads the field: no extra Core pass, body copy or parse, and no Redis or blob round trip.
Measured by inspection: the field has a getter and no caller in `server`. Each `Router` object holds one more
reference.

## Risks / Trade-offs

- [Rollback] → a Core without this change rejects a router that sets `editorUrl`: a file router stops the file load,
  and a stored router is handled by `config.onInvalidEntity`. Routers without the field are unaffected (D1). The docs
  say to remove the field before a downgrade.
- [The URL is an iframe source in DIAL Admin] → only administrators can write it, through the Configuration API or the
  config files, as for an application in the platform bucket.

## Migration Plan

None. Rollback note as above, in `docs/dynamic-settings/routers.md`.
