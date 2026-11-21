# Design

## Context

See proposal.md for motivation. An application carries `applicationTypeSchemaId` and `applicationProperties`
(`Application.java`, `@JsonAlias` with camel and snake case; `Application` serializes snake case through
`@JsonNaming`, `Router` and `Deployment` have no naming strategy and serialize camel case).

- `ApplicationSchemaService.resolveSchema` reads a schema by `$id` from the merged config
  (`Config.getCustomApplicationSchema`). When the schema names `dial:applicationTypeSchemaEndpoint`, it downloads it
  (`downloadAppSchema`, blocking, 5 s) and merges the result. It caches the entry (`CachedSchema`: raw text, parsed
  node, compiled schema) and refreshes it only on `getSchema(id, true)`. That refresh happens only from
  `ResourceController.validateSchemaBasedApplication` and `ApplicationTypeSchemaController`, so a schema written or
  deleted through the Configuration API is not seen until then, on any pod.
- `consumeMetadataProperties(Application, consumer)` validates the properties and returns the top-level properties
  that are present and whose subschema carries `dial:meta`, client and server kind (`filterProperties` with
  `MetaDataCollectorType.ALL`). It also reports whether `dial:appendApplicationPropertiesHeader`
  (`MetaSchemaHolder.APPLICATION_TYPE_APPEND_APPLICATION_PROPERTIES`) allows the header.
- On the file load, `FileConfigStore`'s mapper validates `Config` (`ValidationModule`), and
  `CustomApplicationsConformToTypeSchemasValidator` validates file applications' properties against file schemas.
  The merged rebuild and the Configuration API path for platform applications do not validate properties.
- The properties header is set in `BaseDeploymentPostController.sendProxyRequest` and
  `DeploymentFeatureController`. A client-sent header of that name is excluded there and in
  `RouterDecisionClient.send`.
- `sendProxyRequest` runs inside `createProxyRequest(...).onSuccess(...)`. Vert.x 4.5 `FutureImpl.onSuccess` only
  reports an exception thrown by its handler (`context.reportException`), so a throw there leaves the client
  without an answer. That is what a schema-rich application gets today when its properties fail at request time.
- `decideIfRouter` runs in all four request controllers, for every request, before any origin connection. A failed
  future from it ends the request with that status, for example the existing `502` "Router re-entered".
- `ConfigEntityCodec.BLOB_MAPPER` sets no serialization inclusion, so a field without `@JsonInclude` is written as
  `null`. An older Core fails on an unknown property.

## Goals / Non-Goals

**Goals:**

- The two fields on a router, stored only when set, with the pairing rule on every path.
- Schema validation where applications get it (the file load) and on every Configuration API write.
- The properties header on the router's interfaces, feature endpoints and decide requests, built once, before any
  connection, failing as `502`.
- Reuse `ApplicationSchemaService` for resolution, download, caching and filtering; no second schema engine.

**Non-Goals:**

- Schema-derived endpoints, routes, MCP, files, prompts, skills, editor or viewer URLs for a router.
- Client-visible properties in the router listing.
- Properties on Responses item calls (`ResponsesApiClient`: retrieve, cancel, delete).
- A shared "schema-typed deployment" abstraction over `Application` and `Router`.

## Decisions

### D1. The application's field names, on `Router`, stored only when set

`Router` gains `URI applicationTypeSchemaId` and `Map<String, Object> applicationProperties`. Both carry
`@JsonAlias({"applicationTypeSchemaId", "application_type_schema_id"})` and
`@JsonAlias({"applicationProperties", "application_properties"})`, and both carry
`@JsonInclude(JsonInclude.Include.NON_NULL)`. Without the inclusion rule every router blob, typed or not, would
gain two `null` keys, and an older Core would skip every router written after the upgrade. A `ConfigRouterTest`
case pins the untyped blob's keys.

Rejected: the design document's `customProperties`. The document also says to reuse the app-type schema resource
and keep validators shared, and DIAL Admin and the schema contract already use the application's names. Rejected:
the fields on `Deployment`. Models and interceptors would then accept them with no behaviour behind them.

### D2. The pairing rule in `validateRouterInvariants`

A router with exactly one of the two fields non-null gets the warning `A router declares applicationTypeSchemaId
and applicationProperties together`, on the field that is set. `{}` counts as set. The rule runs wherever the other
router rules run: file load, rebuild, partial update, replica event, `PUT`, validate, apply. Rejected: properties
without a schema, forwarded unvalidated. `defaultHeaders` already serve untyped per-entry values, and an application
in that state sends nothing (`hasApplicationTypeSchemaId` guard).

### D3. Schema validation: file load and every Configuration API write

**File load.** `CustomApplicationsConformToTypeSchemasValidator` loops over `getRouters()` as it does over
`getApplications()`, reporting the property node `routers.<name>`. This is parity with applications at no runtime
cost.

**Writes.** `ApplicationSchemaService` gets
`validateProperties(Config config, URI schemaId, Map<String, Object> properties, List<ValidationWarning> warnings)`.
It reads the schema text from the given config and builds a schema entry the way `resolveSchema` does. The parse,
the optional download and merge, and the compile are extracted into one private `build(String schemaText)` that
both use. It then validates without touching the cache.

| Outcome | Warning field | Message |
|---|---|---|
| Unknown schema | `applicationTypeSchemaId` | `Unknown application type schema: <id>` |
| Download or compile failure | `applicationTypeSchemaId` | the exception message |
| Properties fail validation | `applicationProperties` | the `ValidationMessage::getMessage` texts joined with `, ` |

The check is enforced whatever `config.write.softValidation` is: a bad schema reference is not a reference a later
write could resolve.

Callers:

- `ConfigResourceController.checkRouter`, with `mergedConfigStore.get()`, or an empty `Config` when that is null.
- The `AdminRouterManifest` arm of `ConfigValidationService.validateOnly` and the `ROUTER` block of
  `ConfigApplyService.applyManagedEntity`, with the scratch config. The scratch holds the batch's earlier `Schema`
  entries keyed by `$id` (`mutateScratch`), and `Schema` sorts before `Router`.
- Both service constructors take `ApplicationSchemaService`, wired in `AiDial`. File migration goes through apply.

All of these run on the task executor inside the admin bucket locks. A download is blocking HTTP there, never on the
event loop.

**Not in the merged rebuild.** A schema download must not run while the store holds its rebuild lock, and stored
applications are not validated there either. A stored router whose schema changes later is caught per request
(D4, D5).

Rejected: validating in `validateRouterInvariants`. It is static, runs inside the rebuild, and has no HTTP client.

### D4. The header, built once in `decideIfRouter`, before any connection

`ApplicationSchemaService.consumeMetadataProperties` gets an overload taking `(URI schemaId, Map<String, Object>
properties, consumer)`; the `Application` overload delegates to it.

At its start, `decideIfRouter` builds the header for any typed router, in proxy and decision mode, and stores it on
`ProxyContext` (`routerPropertiesHeader`, null when the router has no schema or the schema turns the header off).
When the schema is gone or the properties fail, it returns a failed future with
`HttpException(502, "Router <name> properties do not match its schema")`. That is the path the existing "re-entered"
`502` takes, so the per-request key and span are closed as for any failed request, and nothing is sent.

The header is then put on:

- the decide request, in `RouterDecisionClient.send`;
- the forwarded request, in `sendProxyRequest`, when the context deployment is a `Router` and the interface's mode
  is not `TRANSLATOR`. A translator converts and calls Core back on the router's own interface, and that pass carries
  the header.

The chosen deployment's pass has a non-router deployment, so it never carries the header.

Both callers use one builder, `ApplicationSchemaService.routerPropertiesHeader(Router)`, which returns the header or
null and throws the `502` (named during apply).

`DeploymentFeatureController` (rate, tokenize, truncate prompt, configuration) does not run `decideIfRouter`. It
builds the header there with the same overload, and on failure answers the same `502` before connecting, inside its
existing try block, as for applications.

Rejected: building the header in `sendProxyRequest` as for applications. It runs inside `onSuccess`, so a failure
hangs the request (Context).

`ponytail:` per-request validation of a small map, as for applications. Cache the serialized header per `Router`
instance if a profile ever shows it.

### D5. The schema cache follows the merged config

`CachedSchema` keeps the source text it was built from (the config's schema text, before any download). Each
`resolveSchema` call compares the cached source with `configStore.get().getCustomApplicationSchema(id)`, a map
lookup. On a mismatch, or when the schema is gone, it rebuilds or drops the entry. A schema written, replaced or
deleted through the Configuration API is then seen on the next request on every pod, as each pod's merged config
updates. Downloaded schemas are not refetched while their source text is unchanged.

This is a deliberate difference for applications too. Today their requests validate against a stale schema until a
force reload; after this change they follow the config. It is the root fix for D4's "caught per request". Rejected:
a `getSchema(id, true)` after each router write. That fixes only the writing pod and only router writes.

### D6. Listings and the Configuration API read

`GET /v1/routers` is unchanged (`RouterData.toData` copies named fields only). `GET /v1/routers/platform/{name}`
and the system export return the stored blob, so both fields appear there in plain text. `docs/open_api_core.yaml`
is regenerated (the `Router` schema gains two properties).

## Effect per request

- **Router without `applicationTypeSchemaId`:** one null check in `decideIfRouter`, nothing else.
- **Router with one:** one map lookup to confirm the cached schema is current, one serialization and one parse of
  its properties inside the validation, and one serialization of the header. That is the work a schema-rich
  application does today plus the lookup.
- **Download schemas:** on a cache miss (first request after start, or after the schema text changes), the build
  downloads the schema with blocking HTTP on the event loop. That is today's behaviour for applications. Write-time
  validation runs off the event loop but does not warm the cache.
- **No Redis or blob round trip, no body copy or parse.** Measured by inspection. There is no perf-env run because
  the typed path is opt-in.

## Risks / Trade-offs

- [A slow schema endpoint during a write] → the download runs inside the cluster-wide admin bucket locks, so every
  admin write waits up to the connect and request timeouts (5 s each), once per typed router in a batch.
- [A router schema shows up as an application type] → `GET /v1/application_type_schemas/schemas` lists it for every
  user. Creating an application of that type fails ("At least MCP or chat completion endpoint must be provided").
  The docs say so.
- [Properties are not secret] → they are stored and exported in plain text, returned by the Configuration API read,
  sent to the router, and visible in a debug log line when a router loads. The docs say secrets do not belong there.
- [Rollback] → a Core without this change rejects a router that sets either field: a file router stops the file
  load, and a stored router is skipped. Untyped routers are unaffected (D1). The docs say to remove the fields from
  typed routers first.

## Migration Plan

None. Rollback note as above, in `docs/dynamic-settings/routers.md`.

## Open Questions

None.
