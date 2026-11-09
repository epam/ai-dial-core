# Design

## Context

See proposal.md for why. Terms: an approval is an application's admin consent (the `ConsentGrant` record); the
initiator is the user who started the request. Today's behaviour, on `development` (line numbers at `3157af667`):

- A per-request key is prepared before the enhancement chain runs and stored after it. The chat path calls
  `setupProxyApiKeyData` (`DeploymentPostController.java:248`, `ApiKeyData.initFromContext`, `ApiKeyData.java:84`),
  runs `buildEnhancementFunctions` (`BaseChatCompletionController.java:92`, last function `CollectDeploymentsFn`) and
  then stores the key with `assignPerRequestApiKey` (`BaseChatCompletionController.java:107`). The same chain serves
  every route of `DeploymentPostController` (chat completions, completions, embeddings) and `ChatCompletionsController`
  (`/openai/v1/chat/completions`). Responses (`ResponsesController.java:118`, stored at `:330-332`) and Messages
  (`MessagesBaseController.java:76`, stored at `:169`) do the same, both already on the task executor. Whatever a chain
  function writes into `context.getProxyApiKeyData()` is stored with the key.
- The MCP application proxy has its own chain (`ApplicationMcpProxyController.java:40`,
  `InjectApplicationPropsToMcpRequest` only). The key is minted in `McpUpstreamAuthInjector.assignPerRequestKey`
  (`McpUpstreamAuthInjector.java:76`) while the upstream request is built, after the chain, again on every re-entry (a
  redirect); the toolset proxy and the toolset tools listing use the same injector. The body is read and the chain runs
  on the event loop (`McpProxyController.java:451`, `handleRequestBody` at `:167`).
- A per-request key's own grants are read by `AccessService.getPerRequestPermissions` (`AccessService.java:360`): the
  exact entry of `perRequestSharedResources`, else the first folder entry that covers the resource
  (`findFolderPermissions`, `:380`), in `HashMap` order.
- `AccessService.permissionRulesBesidesUnitRoles` (`AccessService.java:66-77`) is the chain for any caller, in order: own
  bucket, admin, global reader, the request's attachments (`getAutoSharedAccess`), the calling key's grants
  (`getPerRequestPermissions`), the application's own `appdata` (`getAppResourceAccess`), review, public folder rules
  (`getPublicAccess`, read-only), shared (`getSharedAccess`), the application reading its own definition
  (`getAppSelfAccess`), the schema-rich chained rule; org-unit roles come last. `hasExplicitAdminAccess` (`:532`) is the
  admin test that a per-request key never passes.
- User consent: `ConsentService.buildConsent` (`ConsentService.java:39`) builds the document `GET /v1/consent/{id}`
  answers and auto-accepts when no deployment in the tree sets `consentRequired`; `verifyUserConsent` (`:94`) enforces
  it per call. The route is `RouteTemplate.USER_CONSENT` (`RouteTemplate.java:349`, `^/v1/consent/(?<id>.+?)$`),
  registered in `ControllerSelector.java:272` and `:575`. Records live under `ResourceTypes.USER_CONSENT` in the user's
  bucket.
- Application writes: `ResourceController.validateCustomApplication` (`ResourceController.java:660`) for
  `PUT /v1/applications/...`; the platform config entity `PUT` decodes the body inside the bucket locks
  (`ConfigResourceController.java:1507`, `:1580`); `ConfigApplyService.applyApplication` (`ConfigApplyService.java:327`)
  and `ConfigValidationService.validateOnly` (`ConfigValidationService.java:69`) for the admin manifest API.
- Application lifecycle: `ApplicationService.deleteApplication` (`ApplicationService.java:354`);
  `ResourceOperationService.moveResourceLocked` (`ResourceOperationService.java:65`, deletes the source at `:135`) and
  `copyResource`; publication approval copies the review copy and deletes it
  (`PublicationService.copyReviewToTargetResources`, `PublicationService.java:826`, `copyApplication` at `:852`).
- `ExternalServiceAuditLog` (`ExternalServiceAuditLog.java:19`) has the outcome and actor-evidence helpers an audit
  logger needs.

## Goals / Non-Goals

**Goals:**
- An application can name the user resources it needs, an administrator approves the list once, and every call gets
  exactly the approved entries the calling user can reach, at every hop of a call chain.
- Nothing changes for an application without the section: no reads, no writes, no new failures.

**Non-Goals:**
- User consent that gates resource dependencies, offline access, invocable targets, re-delegation (proposal.md).
- Approving an unpublished application in a user's bucket.

## Decisions

### 1. A declaration is a request, resolved per call against the initiator

The section grants nothing by itself. At request start Core intersects each entry with two facts: an administrator
approved the list, and the initiator holds every declared right on the target on their own standing. The entries that
pass are written into the key the application receives, so the existing `AccessService` enforces them like any other
per-request grant.

Rejected: a grant at write time or access owned by the application. Either outlives the user's own access (a share
revoked tomorrow would still be readable by the app) and needs a second enforcement path.

### 2. One target grammar, parsed by `ResourceDescriptorFactory`

`target.url` is a DIAL resource URL: `<type>/<bucket>/<path>`, or `<type>/{current-user}/<path>` where the braced
placeholder stands for the initiator's bucket and is valid only as the bucket segment. Braces cannot occur in a real
bucket or a stored name, so the token never collides. Write time and request time share one parser
(`ResourceDependencyValidator.checkTarget` / `resolveTarget`): the type must be declarable, the target must be inside a
bucket (not the type root), no empty, `.` or `..` element, no element containing `*`, and the URL must decode as the
factory decodes it. A URL whose first segment is `users` is rejected with a hint to use the placeholder. The field is
named `url`, as in the consent entries that carry it.

Declarable types: `applications`, `conversations`, `files`, `projects`, `prompts`, `skills`, `toolsets`. Internal types
(credentials, keys, consent records, models) never are.

Rejected: a separate `current-user/...` root and a hand-written per-segment parser. Two parsers drifted apart and let
`public/...` pass write time while it could never resolve.

### 3. Write-time checks: author gate first, then shape

`PUT /v1/applications/...` by a caller without explicit admin access fails with `403` for a non-empty section while
`applications.allowUserResourceDependencies` is off, before the shape rules run, so the user learns nothing about them;
a body that does not parse (an unknown `access` value, say) is still `400` from the JSON reader. The gate is the author
(`hasExplicitAdminAccess`), not the destination bucket. Shape violations are `400` for every author; a section over 100
entries is reported alone. Messages use the config-file spelling (`resourceDependencies[i]: linkId ...`). The platform
config entity `PUT` checks the shape before it takes the bucket locks; `POST /v1/admin/apply` and `/v1/admin/validate`
report a `FAILED` entry with the same message (`ResourceDependencyValidator.findShapeError`). Config-file applications
are not checked on load; a malformed entry never resolves, and a `required` one fails the call naming its URL or
`<missing target.url>`.

Rejected: gating by destination bucket. It would block an administrator prototyping in their own bucket.

### 4. Admin consent: one content-bound record per application

`POST /v1/admin-consent/{deployment_id}` stores a `ConsentGrant` (`consent.resources`, `grantedBy`, `grantedAt` in epoch
milliseconds) in the public bucket under `ResourceTypes.ADMIN_CONSENT`, keyed by the application's resolved name (the
config name of a platform application, used as it is, the URL of any other). A name whose record would exceed the
storage path limit gets no record: it cannot be approved, the resolver treats it as unapproved, and deleting, moving or
copying the application skips the record. The type is not mapped in `ResourceTypes.of()`, so no
Resource API path can address the record. The approval binds each entry's `target.url` and `access`, in declaration
order: an added, removed or reordered entry, or changed rights, need a new approval, and `GET` reports such a record as
`stale`; a changed `required`, `link_id` or `kind` does not. A record that cannot be read, or has no list, counts as not
consented and can still be withdrawn.

The request body carries the list the administrator reviewed; Core stores the approval only when it equals the current
list, else `409` and nothing is stored, so an approval always covers what the administrator saw.

Rejected: an approval flag on the application. Any writer of the application could change the list under it. Rejected:
`If-Match` with the application's ETag. The ETag changes with any field, so an unrelated edit would fail the approval,
and a config-file application has no ETag.

### 5. Route `/v1/admin-consent/{id}`, admin only

The three operations live on `/v1/admin-consent/{id}`, registered next to the user consent routes. The id is decoded and
may span `/` (`applications/{bucket}/{name}`); a platform application is addressed by its config name (its URL form is
refused as a deployment the caller cannot use). The admin check runs first, so a refusal leaks nothing; a per-request
key is refused even when the initiator is an administrator. `POST` reads its body before it resolves the id. An id that
is not an application is `404`. An application the administrator cannot read (another user's bucket) is `403`; the
application's `userRoles` do not matter. A user's application is approved on its publication's review copy or after
publication.

Rejected: `/v1/consent/{id}/admin-consent`. The user consent pattern takes a multi-segment id, so the suffix would be
read as part of a deployment id. Rejected: `/v1/applications/{id}/resource-dependencies/consent`. It splits one consent
concept across two route families and does not fit config-file applications.

### 6. The initiator's reach is a purpose-built rule list

`AccessService.lookupInitiatorPermissions` runs only: the initiator's own bucket (`BucketBuilder.buildInitiatorBucket`,
at any chain depth), resources shared with the initiator, and public resources under the folder rules, which give read
only. It leaves out the calling key's attachments and grants (an app could launder what it was given into a called app's
reach), the application's `appdata` and its read of its own definition, admin, global-reader and review standing, and
org-unit roles. A `write` entry on a public target therefore never grants.

A grant on a folder reaches everything under it, so a public folder that spans org units counts only when the
initiator's unit roles read every unit under it (`OrgUnitGrants.spansUnit` / `readsEveryUnitUnder`, judged on the
initiator's claims at any hop), as for a folder attachment or a folder passed on through the per-request permission
API. The existing `hasReadAccessToEverythingUnder` / `mayPassOnFolder` are not reused: they first read the calling
key's own grants. A subfolder with stricter publication rules under a granted public folder is still covered, as it
is for a folder attachment today.

Rejected: the full `permissionRules`. It reads the calling key's grants and would let an administrator's call widen any
declaration to the public bucket.

### 7. Resolution at every mint site and every hop

`ResolveResourceDependenciesFn` is the last function of the chat chain (chat completions, completions, embeddings,
`/openai/v1/chat/completions`), the Responses chain (`/openai/[v1/]responses`) and the Messages chain (`count_tokens`
inherits it), and runs in the application MCP chain. It returns at once for a deployment that is not an application or
has no section. On a chained call the called application's own section is resolved for the same initiator.

Grants are written into the key's own `perRequestSharedResources`, merged copy-on-write: the entry may be shared with the
calling key's receivers, and the calling key's record is never written. The grants do not go into `perRequestReceivers` through
`PerRequestPermissionService.addPermissions`, which would propagate them down the chain: each hop resolves its own list
instead, and a grant reaches a child only when the application shares it.

For the MCP proxy, an application with `mcp.forwardPerRequestKey` (the default) creates its key before the chain and
stores it after, as the chat path does; `McpUpstreamAuthInjector` reuses an assigned key instead of minting another, so a
redirect sends the same key with the same grants. The reuse also applies to the toolset proxy and the toolset tools
listing. MCP paths do not retry: one upstream per deployment. The body and chain run on the task executor, for every MCP
request, because the resolver reads storage. With `forwardPerRequestKey: false` no key is sent: entries are not granted,
and a `required` one fails the call with `403` that says the grants cannot be delivered. A request whose Content-Type is
not JSON, or whose JSON is not an object (a batch), skips the chain, so no `required` entry fails it; with key forwarding
it still gets a key, without grants.

A `required` entry that does not pass fails the call with `403 Required resource dependencies are not accessible:
<urls>`; an optional one is left out silently. An unexpected failure of the resolver is `500`, not the MCP proxy's
default `400`.

Application routes, feature endpoints, `/v1/deployments/{id}/mcp/resources`, response item endpoints and the toolset
proxy do not resolve: their keys carry no grant and a `required` entry does not fail them.

Rejected: resolving once at the root and passing grants down. A called application would inherit the caller's grants
instead of getting its own approved list.

### 8. Union of covering entries in a per-request key

A key's rights on a resource are the union of the exact entry and every folder entry above it. A key now has two writers
(receivers shared by the caller and the resolver), so a narrow exact grant must not hide a wider folder grant, and the
answer must not depend on map order. The change applies to every per-request key, not only to keys with dependency
grants.

Rejected: keeping exact-then-first-folder. The result would depend on which entry the `HashMap` yields first.

### 9. The approval follows the application

Deleting an application deletes its record. A move, and publication approval (a copy of the review copy and its
deletion), copy the source's record to the destination before the source is deleted; when the source has none, the
destination's record is deleted, so an application moved over an approved one does not inherit that approval. A copy
through `/v1/ops/resource/copy` deletes the destination's record: it starts unapproved, also over an approved
application. A record of an application removed from a config file stays; while the application is absent it cannot be
withdrawn (the id no longer resolves), and the application added back with the same list is approved again.

Rejected: dropping the approval on move. Publication would always lose it and force a second review of the same list.

### 10. The user consent document shows the declaration

For a declaring root application, `GET /v1/consent/{id}` lists the declaration in `resources` and is never auto-accepted,
whatever the author set in `features.consentRequired`: consent is not an author-controlled flag. Because `resources` is
part of the comparison with the stored user consent, a change of the declaration asks the user again. The request-time
user consent check is unchanged; the admin record is what gates grants, so the section is informational for now.

Rejected: auto-accepting a declaring application that does not set `consentRequired`. The author's flag would decide
whether the user is shown what the application reaches.

### 11. Audit on a dedicated logger

`ResourceDependencyAuditLog` writes `resource_dependency_consent`, `_grant`, `_denial` and `_runtime_fail` lines to
`DIAL_RESOURCE_DEPS_AUDIT`, with identities, targets and outcomes only: `source_deployment` is `root` for a call made
directly by the user (the interceptor's name when one hands the call back), `access_types` is the union over the line's
targets, and `_runtime_fail` carries a `reason`. The outcome and actor helpers move from `ExternalServiceAuditLog` to a
shared `AuditLogSanitizer`; the external-service lines are unchanged. Audit lines are outside the boundaries a spec may
describe, so the deltas leave them out.

## Risks / Trade-offs

- **Public targets are read-only, and admin, global-reader and review standing do not reach.** An administrator calling
  an app that declares `files/public/policies/` with `write` gets no grant. → Deliberate (decision 6); raised with the
  team.
- **A granted public folder covers its subfolders' stricter publication rules**, as a folder attachment does.
  → Raised with the team (it is Core-wide, not specific to dependencies).
- **Org-unit roles do not reach.** A user who reads a unit folder through a unit role cannot have it granted. → Out of
  scope; raised with the team.
- **The approval is bound to the list, not to the endpoint.** A changed endpoint keeps the approval. → Raised with the
  team; the list is what the administrator reviews.
- **A withdrawal does not reach keys already issued.** A call in progress keeps its grants until its key expires; a
  background Responses job keeps them up to `backgroundJob.jobTtlMs`. → Documented.
- **A user's unpublished application cannot be approved.** → Documented; user consent will cover it.
- **Records that outlive their application.** A config-file application's record stays after it leaves the file; a review
  copy dropped from a publication is deleted as a plain resource and keeps its record. → Documented for config files. A
  declaring application copied to a review url deletes any record left there, so a swap under an approved review copy
  starts unapproved; a left-behind record has no effect on an application without a list.
- **Per-request grants passed down a chain** (`ApiKeyData.sharePerRequestPermissions`, development code): the child key
  shares mutable permission sets with the caller's, and on an interceptor hop an entry from the initial deployment
  replaces the receiver's instead of adding to it. The resolver copies before it writes, but the issue stays. → Raised
  with the team.
- **Mint sites without resolution** (decision 7, last paragraph). → Documented in the operator docs.
- **Union in `findPermissions` scans every entry.** → Keys hold few entries; the scan replaces a scan that stopped at the
  first folder.
- **One MCP key per request.** A redirect, and the several calls of a toolset tools listing, send the same key; before,
  each minted a key that stayed live until its TTL. → Fewer live keys.

Effect per request:
- A deployment without the section: one type check and one list check; no Redis or blob round trip, no body copy.
- A declaring application: one read of its admin-consent record (Redis-cached resource), the initiator-reach lookups for
  the resolved targets (shared-resource reads and public folder rules, as `lookupPermissions` reads them today), no extra
  key write: the grants go into the key write the request already makes.
- MCP proxy: one hop to a virtual thread per request; for an application with key forwarding, one key per request instead
  of one per re-entry.
- Application delete and application copy: one more storage delete. Move and publication approval: one more read, and a
  write only when a record exists.
No measurement was made; no cost claim goes beyond these counts.

## Migration Plan

None. The section and the setting are new, and no stored data changes shape. Rollback is a revert; admin-consent records
left behind are never read.
