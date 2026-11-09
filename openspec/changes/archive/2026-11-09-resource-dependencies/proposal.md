# Proposal

## Why

An application can read and write only its own `appdata` folder and what the request attaches or shares with it
for one call. A skill creator that writes skills into the user's `skills/` folder, or an assistant that reads the
user's reports folder on every call, has no way to get that access: the user would have to attach the folder each time,
and an application cannot ask for it up front. Nothing lets an administrator review such access before users run the
application.

## What Changes

- An application definition may carry `resource_dependencies` (`resourceDependencies` in a config file): a list of up
  to 100 entries `{kind: "dial.resourceLink", link_id, target: {url}, access, required}`. `target.url` is
  `<type>/{current-user}/<path>` (the bucket of whoever calls the application) or `<type>/<bucket>/<path>`, with
  `<type>` one of `applications`, `conversations`, `files`, `projects`, `prompts`, `skills`, `toolsets`; a URL ending
  with `/` is a folder. `access` is a non-empty list of `read` and `write` (any case). An entry is a request, not a grant.
- Write-time validation: a list that breaks these rules is answered `400` on `PUT /v1/applications/...` and on the
  platform config entity `PUT`, and is a `FAILED` entry in `POST /v1/admin/apply` and `POST /v1/admin/validate`. A
  caller without explicit admin access gets `403` for any non-empty list unless the new static setting
  `applications.allowUserResourceDependencies` (default `false`) is on. Applications from a config file are not
  checked on load; an entry with a wrong `kind`, empty or invalid `access` or an invalid `target.url` never resolves.
- Admin consent: `GET`, `POST` and `DELETE /v1/admin-consent/{deployment_id}`, for administrators only. `POST` takes
  the list the administrator reviewed and stores the approval only when it equals the application's current list (else
  `409`). `GET` answers `consented`, `stale` and who approved what and when. The approval is one record per application
  in the public bucket, bound to each entry's `target.url` and `access` in order: an added, removed or reordered entry,
  or changed rights, need a new approval. Deleting an application deletes its approval; moving or publishing it takes
  the approval along; a copy starts unapproved, also over an approved application.
- Resolution: on every route of the chat completions controllers (chat completions, completions, embeddings,
  `/openai/v1/chat/completions`), Responses, Anthropic Messages (`count_tokens` included) and application MCP calls, Core
  checks each entry of the called application against the admin approval and against the initiator's own access
  (own bucket, resources shared with the user, public resources the folder rules let the user read, read-only). Each entry for which
  the user holds every declared right is added to the per-request key the application receives. A `required` entry that does not pass
  fails the call with `403`. On a chained call, the called application's own list is checked for the same initiator,
  and grants the calling application holds do not count.
- The user consent document (`GET /v1/consent/{id}`) of an application that declares resource dependencies lists them
  in `resources`, and such an application is not auto-accepted. The user consent check at request time is unchanged.
- Audit: grant, denial, runtime failure and admin consent decisions are logged to `DIAL_RESOURCE_DEPS_AUDIT`.

Changes to existing behaviour for requests that use no `resource_dependencies`:

- A per-request key's rights on a resource are the union of every entry that covers it (the exact URL and each folder
  above it). Before, the exact entry alone counted, else the first covering folder in map order.
- The MCP proxy (applications and toolsets, the toolset tools listing included) mints one per-request key per request
  and reuses it on a redirect (before, a key per re-entry; MCP deployments have one upstream, so there are no retries).
  For an application with key forwarding the key is stored before the upstream connect. The proxy reads the body and
  runs its enhancement chain on the task executor, not the event loop, so an exception there now gets an error answer.
- A platform application `PUT` decodes its body on the event loop, before the bucket locks (before, on the task
  executor inside them).
- Deleting an application, and copying one through `/v1/ops/resource/copy`, also deletes the admin-consent record of
  the deleted application or of the copy's destination: one more storage delete.
- Moving an application or approving its publication reads the admin-consent record of the source and writes it to the
  destination, or deletes the destination's record when the source has none: one more read and one more write or delete.
  An absent record is cached as such in Redis for the type's TTL.
- `/v1/admin-consent/...` is a built-in route: a configured global route matching that prefix no longer receives it.
- `POST /v1/consent/{id}` accepts a `resources` section in the consent document (before, an unknown property was `400`);
  request-time user consent checks ignore it.
- A platform application `PUT` decodes and checks the body before it takes the bucket locks, so a body that is both
  malformed and a duplicate gets `400` before `409`.

**BREAKING**: none. The section is new and optional; applications without it resolve nothing.

Out of scope:

- User consent to resource dependencies: the `resources` the user consent document lists are informational; nothing
  enforces them yet.
- Admin approval of an unpublished application in a user's bucket: administrators have no access to it (`403`).
- Write-time validation of applications from a config file, and checks of copy and move against the setting.
- Invocable targets (calling another application or toolset by declaration), offline access, re-delegation.
- Org-unit roles and admin, global-reader or review standing do not count as the initiator's reach.
- Application routes, feature endpoints, `/v1/deployments/{id}/mcp/resources`, response item endpoints and the toolset
  proxy do not resolve dependencies: their keys carry no grant, and a `required` entry does not fail them.
- A withdrawal does not reach per-request keys already issued.

## Capabilities

### New Capabilities

- `resource-dependencies`: the declaration and its write-time rules, the admin consent API and its record, resolution
  into the per-request key at every hop, and the user consent document of a declaring application.

### Modified Capabilities

- `request-routing`: "Bucket, user and schema routes" gains `/v1/admin-consent/{id}`; "Path variable decoding" and
  "Multi-segment ids" cover the admin-consent id.
- `app-to-model-accounting`: adds "Dependency grants in the key", the resolved dependency grants as a source of
  `perRequestSharedResources`.
- `resource-access-control`: "Grants carried by a per-request key" states the union of covering entries.

## Impact

- Motivation: https://github.com/epam/ai-dial-core/issues/1930 (App resource dependencies) and its stories #1931 to
  #1939. This change covers all nine: the definition model, write-time validation, admin consent and its status, the
  target grammar, resolution at request start, the Messages, Responses and MCP mint sites, and chained hops. Consent
  per reference (user consent that gates), offline access and invocable targets stay open.
- Code: `ResourceDependency`, `Application`, `ResourceDependencyValidator`, `ResolveResourceDependenciesFn`,
  `ConsentService`, `ConsentController`, `AccessService`, `ApplicationService`, `ResourceOperationService`,
  `PublicationService`, `McpProxyController`, `ApplicationMcpProxyController`, `McpUpstreamAuthInjector`, the chat,
  Responses and Messages controllers, `ConfigResourceController`, `ConfigApplyService`, `ConfigValidationService`,
  `ResourceDependencyAuditLog`, `AuditLogSanitizer`, `ResourceTypes.ADMIN_CONSENT`, `RouteTemplate.ADMIN_CONSENT`.
- API: three new admin endpoints; a new optional field on applications; new `400`, `403` and `409` causes.
- Settings: `applications.allowUserResourceDependencies` (default `false`).
- Docs: `docs/dynamic-settings/applications.md`, `docs/dynamic-settings/admin_consent_api.md`, `docs/error_codes.md`,
  `README.md`, `docs/open_api_core.yaml`.
