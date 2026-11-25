# Proposal

## Why

The router resource design (phase 1, requirement 8) lets one generic router service sit behind several router
entries, each configured with its own typed properties that an app-type schema validates. Applications already
work this way: `applicationTypeSchemaId` names a schema, `applicationProperties` holds the values, and Core
sends the server-side properties to the service in `X-DIAL-APPLICATION-PROPERTIES`. A router has neither field
today, so per-entry configuration is limited to untyped `defaultHeaders`.

## What Changes

- A router accepts `applicationTypeSchemaId` and `applicationProperties`, with the names and aliases an
  application uses. Both are left out of the stored JSON when unset, so a router without them is stored exactly as
  before. The same rule applies on load and on every Configuration API write: the two fields are set together or
  not at all.
- The properties are validated against the schema wherever an application's are and on every Configuration API
  write: on the file load against the file's schemas, as for applications, and on a `PUT`, validate, apply or file
  migration against the merged config plus the batch. A failure answers `422` (or a `FAILED` entry), whatever the
  soft-validation mode.
- Core sends `X-DIAL-APPLICATION-PROPERTIES` to the router with the properties the schema declares. It goes on
  requests forwarded to the router on its own interfaces (chat completions, completions, embeddings, Responses
  create, Messages), on its feature endpoints (rate, tokenize, truncate prompt, configuration), and on decide
  requests. It does not go to a translator in front of a router interface, to Responses item calls, or to the
  deployment a decision chose. The schema's `dial:appendApplicationPropertiesHeader: false` suppresses the header,
  as for an application. A client-sent header of that name is already dropped.
- Core builds the header once per request, before it connects anywhere. When the stored properties no longer match
  the schema, the request answers `502` and nothing is sent to the router.
- The schema contributes nothing else to a router: no endpoints, routes, MCP settings, files, prompts or skills.
  A router keeps `baseUrl` and `interfaces`.
- `GET /v1/routers` and `GET /v1/routers/{id}` are unchanged: they do not show properties.

No breaking change: a router without the two fields is stored, served and charged exactly as before.

Out of scope:

- Client-visible properties in the router listing (the design leaves router UI out of phase 1).
- Taking a router's endpoints from its schema, as schema-rich applications do.
- Validating stored routers against the schema in the merged rebuild (applications are not either; see design.md D3).

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `router-deployment`: added requirements for the two fields and their pairing rule, the schema validation, the
  properties header and its failure. "Router config entry" keeps its field list; the new requirement names the two
  fields.
- `config-store`: "Write rejected with 422" names a router's write rules in general, so the schema check is one of
  them.
- `deployment-origin`: "Identity headers for applications and translated interfaces" names the router's properties
  header next to the application's.

## Impact

Motivated by the router resource design (`design/router_resource.md`, documentation MR 211): this change covers
phase-1 requirement 8 (the app-type schema and custom properties) and answers its open question 3 (how Core passes
the properties: the application's header, on both flows). It leaves out client-visible properties and all of phase
2. It ships in PR #2232 after `router-config-api`, which it depends on for the write paths.

Code: `config` (`Router` fields, `CustomApplicationsConformToTypeSchemasValidator` for file routers),
`ConfigPostProcessor.validateRouterInvariants` (pairing rule), `ApplicationSchemaService` (entry points that take a
schema id and a properties map), `ConfigResourceController.checkRouter`, `ConfigValidationService` and
`ConfigApplyService` router arms (both constructors gain `ApplicationSchemaService`, wired in `AiDial`),
`BaseDeploymentPostController.decideIfRouter` (builds the header), `sendProxyRequest`, `DeploymentFeatureController`
and `RouterDecisionClient` (send it), `ProxyContext` (holds it). Docs:
`docs/dynamic-settings/routers.md`, regenerated `docs/open_api_core.yaml`. No new dependency, no migration.

A note for review: the Go router today serves several entries from one global policy through each entry's
`defaultHeaders` (for example `X-DIAL-ROUTER-TIER`), and it reads no properties header. This change is the typed,
schema-validated form of that, for a router service that wants structured per-entry configuration.
