# resource-dependencies Specification

## Purpose
How an application declares the DIAL resources outside its own `appdata` that it needs on the calling user's behalf
(`resource_dependencies`), the write-time rules for that declaration, the admin consent API that approves it and the
record it keeps, and how Core resolves an approved declaration into the per-request key at every hop of a call: what
the initiator's own reach counts, which calls resolve, what fails a call, and what the user consent document shows.
How a per-request key carries and enforces grants is the app-to-model-accounting and resource-access-control
capabilities; audit lines are described in the change's design.

## Requirements

### Requirement: Resource dependencies in an application
An application MAY carry `resource_dependencies` (`resourceDependencies` in a config file; both spellings are read): a
list of entries `{"kind": "dial.resourceLink", "link_id", "target": {"url"}, "access", "required"}`. `access` is a
non-empty list of `read` and `write`, in any case, returned as `READ` and `WRITE`; `required` defaults to `false`. An
entry gives the application no access of its own. An application without the section SHALL behave exactly as before.

#### Scenario: Valid section stored and returned
- **WHEN** an admin sends `PUT /v1/applications/public/resource-dependency-app` with an entry targeting
  `skills/{current-user}/` (`write`, `required: true`) and an entry targeting `files/public/policies/` (`read`)
- **THEN** Core answers `200` and `GET` of the application returns both entries under `resource_dependencies`

#### Scenario: Config-file spelling
- **WHEN** a config-file application declares `"resourceDependencies": [{"linkId": "lnk_1", "target": {"url":
  "files/public/folder/"}}]`
- **THEN** it is read as the same entry as `"resource_dependencies": [{"link_id": "lnk_1", "target": {"url":
  "files/public/folder/"}}]`

### Requirement: Target of a resource dependency
`target.url` SHALL be `<type>/{current-user}/<path>`, standing for the bucket of whoever calls the application, or
`<type>/<bucket>/<path>`, a fixed bucket. `<type>` is one of `applications`, `conversations`, `files`, `projects`,
`prompts`, `skills`, `toolsets`. A URL ending with `/` is a folder that covers everything under it.

#### Scenario: Accepted targets
- **WHEN** a section declares `files/public/policies/`, `skills/{current-user}/`, `projects/{current-user}/` or
  `prompts/public/my-prompt`
- **THEN** the shape check accepts each of them

### Requirement: Write-time shape of resource dependencies
On `PUT /v1/applications/...` and on the platform config entity `PUT /v1/applications/platform/...`, Core SHALL answer
`400` `Invalid resource dependencies: ` with every violation when an entry is null, has a `kind` other than
`dial.resourceLink`, a missing or duplicate `link_id`, a missing `target.url`, an empty `access`, `share` in `access`, or
a target that is not declarable. More than 100 entries SHALL be reported alone. Messages use the config-file spelling.

#### Scenario: Malformed entries
- **WHEN** an admin writes an application whose entry has `kind: "dial.resource"`, or has an empty `access`
- **THEN** each write is answered `400`

#### Scenario: Platform config entity
- **WHEN** an admin sends `PUT /v1/applications/platform/<name>` with an entry targeting `users/bob/files/f/`
- **THEN** Core answers `400`

### Requirement: Declarable targets
A target SHALL NOT be declarable when it has an unknown type, starts with `users/` (the old personal spelling), has the
`{current-user}` placeholder outside the bucket segment, has no folder or resource inside the bucket (a type root, or a
bucket without `/`), has an empty, `.` or `..` element or one containing `*`, or has an element that is not a valid
encoded resource name.

#### Scenario: Rejected targets
- **WHEN** an admin writes an application whose entry targets `users/bob/files/f/`, `files/public/*/`,
  `files/public/%7Bcurrent-user%7D/` or `buckets/public/f/`
- **THEN** each write is answered `400`

#### Scenario: Placeholder alone is not a target
- **WHEN** an application declares `{current-user}/` or `current-user/rootstuff/`
- **THEN** the write is answered `400`

### Requirement: Shape checks in the admin manifest API
`POST /v1/admin/apply` and `POST /v1/admin/validate` SHALL report an application whose section breaks the shape rules as
`FAILED`, with the same message. With `precheck: true` apply SHALL answer `422` and write nothing; with
`precheck: false` it SHALL apply the other entries. Core SHALL NOT check applications from a config file on load.

#### Scenario: Apply with precheck
- **WHEN** an admin applies a batch of a valid model and an application with an entry targeting `users/bob/files/f/`,
  with `precheck: true`
- **THEN** Core answers `422`, the application is `FAILED` with a message starting `Invalid resource dependencies`, and
  neither entity is written

#### Scenario: Apply without precheck
- **WHEN** the same batch is applied with `precheck: false`
- **THEN** Core answers `200` with `applied: 1` and `failed: 1`; the model is written and the application is not

#### Scenario: Validate
- **WHEN** an admin validates a manifest whose application has an entry targeting `users/bob/files/f/`
- **THEN** `POST /v1/admin/validate` reports it `FAILED`

### Requirement: Who may declare resource dependencies
While the static setting `applications.allowUserResourceDependencies` is `false` (the default), a write of an
application with a non-empty section by a caller without explicit admin access SHALL be answered `403` before the shape
rules are checked (a body that does not parse is still `400`). An admin's write to their own bucket is not gated. With
the setting `true`, a user's section SHALL pass the same shape rules as an admin's.

#### Scenario: User with the setting off
- **WHEN** a user sends `PUT /v1/applications/<own bucket>/app` with a valid section, or with a malformed one
- **THEN** both writes are answered `403`

#### Scenario: Admin in their own bucket
- **WHEN** an admin writes an application with a valid section to the admin's own bucket, the setting off
- **THEN** Core answers `200`

#### Scenario: User with the setting on
- **WHEN** `applications.allowUserResourceDependencies` is `true` and a user writes a valid section to the own bucket
- **THEN** Core answers `200`, and a section declaring `{current-user}/` is answered `400`

#### Scenario: No admin rules
- **WHEN** no admin rules are configured and a user writes a valid section
- **THEN** Core answers `403`

### Requirement: Admin consent is for administrators
An approval is the admin consent of an application's list. `GET`, `POST` and `DELETE /v1/admin-consent/{deployment_id}`
SHALL be answered `403` for a caller without explicit admin access, a per-request key included, before the id is
resolved.

#### Scenario: Non-administrator
- **WHEN** a user sends `GET`, `POST` or `DELETE /v1/admin-consent/applications/public/dependency-consent-app`
- **THEN** Core answers `403`

#### Scenario: Per-request key of an administrator's call
- **WHEN** an application called by an admin sends `GET`, `POST` or `DELETE /v1/admin-consent/<id>` with the key it
  received
- **THEN** Core answers `403`

#### Scenario: No admin rules
- **WHEN** no admin rules are configured and a caller sends `POST /v1/admin-consent/<id>`
- **THEN** Core answers `403`

### Requirement: Admin consent id
`{deployment_id}` SHALL be a config-file application's name (a platform application's included) or
`applications/{bucket}/{path}`. An id that is not an application SHALL get `404`; an application the admin cannot read
SHALL get `403`. The application's `userRoles` SHALL NOT matter.

#### Scenario: Unknown id
- **WHEN** an admin sends `GET`, or `POST` with a well-formed body, to `/v1/admin-consent/unknown-app`, or `GET
  /v1/admin-consent/files/public/x`
- **THEN** Core answers `404`

#### Scenario: Application in another user's bucket
- **WHEN** an admin sends `GET` or `POST /v1/admin-consent/applications/<user bucket>/app`
- **THEN** Core answers `403`

#### Scenario: Config application outside the admin's roles
- **WHEN** a config application is limited by `userRoles` to a role the admin does not have, and the admin reads its
  status
- **THEN** Core answers `200`

### Requirement: Grant admin consent
`POST /v1/admin-consent/{deployment_id}` with `{"resources": [{"url", "access"}, ...]}` SHALL store the approval and
answer `200` when the list equals each declared entry's `target.url` and `access`, in declaration order; otherwise `409`,
storing nothing. The body is checked first: a missing or malformed body, or an application that declares nothing, SHALL
get `400`. A new approval replaces the previous one.

#### Scenario: Reviewed list matches
- **WHEN** an admin posts an empty body, and then the application's current list
- **THEN** the first is answered `400`, the second `200`

#### Scenario: List changed since the review
- **WHEN** the application's list changed after the admin read it, and the admin posts the old list
- **THEN** Core answers `409` and the application stays unapproved

#### Scenario: Application without a section
- **WHEN** an admin posts `{"resources":[]}` for an application without `resource_dependencies`
- **THEN** Core answers `400`

### Requirement: Admin consent record
Core SHALL store an approval as one record per application in the public bucket, holding the approved `url` and
`access` of each entry, `grantedBy` (the admin's user id, or the project of the API key used) and `grantedAt` (epoch
milliseconds). No Resource API path SHALL reach the record.

#### Scenario: API key administrator
- **WHEN** an admin project key grants the approval
- **THEN** `GET /v1/admin-consent/<id>` reports `grantedBy` as that key's project

#### Scenario: Record out of the Resource API's reach
- **WHEN** an admin sends `GET /v1/admin_consent/public/<id>` after granting the approval
- **THEN** Core answers `404`

#### Scenario: Config name with a percent sign
- **WHEN** an admin approves, then deletes, the platform application `a%2Fb`
- **THEN** the record is keyed `a%2Fb`, not `a/b`, and the delete answers `204`

#### Scenario: Id too long for a record
- **WHEN** an application's admin-consent record would exceed the storage path limit
- **THEN** `POST /v1/admin-consent/<id>` answers `400`, the application never resolves its dependencies, and deleting it
  answers `200`

### Requirement: Admin consent status
`GET /v1/admin-consent/{deployment_id}` SHALL answer `{"consented": false}` when there is no record. With a record it
SHALL add `stale`, `grantedBy`, `grantedAt` and `grantedResources`, and answer `consented: true, stale: false` when the
record matches the current list, else `consented: false, stale: true`, an application that no longer declares
anything included. `consented` SHALL be exactly the condition the request-time check uses.

#### Scenario: Tri-state
- **WHEN** an admin reads the status before approving, after approving, after the list changed, and after withdrawing
- **THEN** the answers are `{"consented": false}`; `consented: true` with `stale: false`; `consented: false` with
  `stale: true` and the earlier `grantedBy`, `grantedAt` and `grantedResources`; and `{"consented": false}`

#### Scenario: Application without a section
- **WHEN** an admin reads the status of an application that never had `resource_dependencies`
- **THEN** Core answers `200` `{"consented": false}`

### Requirement: Withdraw admin consent
`DELETE /v1/admin-consent/{deployment_id}` SHALL delete the approval and answer `200`, also when there is none. Calls
started afterwards SHALL receive none of the application's entries.

#### Scenario: Withdraw, then call
- **WHEN** an admin withdraws the approval of an application with a `required` entry, and a user calls it
- **THEN** the call is answered `403`

### Requirement: The approval follows the application
Deleting an application SHALL delete its approval, a platform application's included. Moving an application, and
approving the publication of one, SHALL take the source's approval to the destination; when the source has none, the
destination's approval SHALL be deleted. A copy SHALL start unapproved, also over an approved application. An
application created again at a deleted one's id SHALL start unapproved.

#### Scenario: Delete and create again
- **WHEN** an approved application is deleted and created again with the same list
- **THEN** `GET /v1/admin-consent/<id>` answers `{"consented": false}`


#### Scenario: Move and copy
- **WHEN** an approved application is moved, and another is copied from it
- **THEN** the moved one is approved at its new id and nothing remains at the old id; the copy is not approved

#### Scenario: Moved over an approved application
- **WHEN** an unapproved application is moved with `overwrite` onto an approved one
- **THEN** the destination is not approved

#### Scenario: Copied over an approved application
- **WHEN** an application with the same list and another endpoint is copied with `overwrite` onto an approved one
- **THEN** the destination has the copied endpoint and is not approved

#### Scenario: Copy failing after it wrote the application
- **WHEN** a copy with `overwrite` onto an approved application writes the application and then fails on its files
- **THEN** the destination is not approved

#### Scenario: Publication approved
- **WHEN** an admin approves the review copy's list and then the publication of a user's application
- **THEN** the published application is approved and a call to it receives its entries

#### Scenario: Application swapped under an approved review copy
- **WHEN** an admin approves a review copy and then updates the publication to another source application with the same
  list and the same target
- **THEN** the new review copy and, after approval, the published application are not approved

### Requirement: The approval lives only while the list does
An update that removes the list SHALL delete the approval. A write by a caller without explicit admin access SHALL
delete the approval of an application that declares a list or declared one before; an administrator's edit SHALL keep
it.

#### Scenario: List removed and restored
- **WHEN** an update removes an approved application's list, and a later update restores it
- **THEN** `GET /v1/admin-consent/<id>` answers `{"consented": false}` after each

#### Scenario: Edited by a non-administrator
- **WHEN** an administrator changes an approved application's endpoint, and then an org-unit editor does, keeping the
  list
- **THEN** the application is approved after the first edit and not after the second

### Requirement: Dependencies resolved into the per-request key
On every route of the chat completions, Responses and Messages controllers (`/openai/deployments/{id}/chat/completions`,
`/completions`, `/embeddings`, `/openai/v1/chat/completions`, `/openai/[v1/]responses`, `/anthropic/v1/messages`,
`/anthropic/v1/messages/count_tokens`) and on application MCP calls, Core SHALL add to the key the approved application
receives each entry the initiator can reach with every declared right. Entries for one URL add up.

#### Scenario: Chat completion receives the grant
- **WHEN** a user calls an approved application declaring `prompts/{current-user}/dep-smoke/` with `write`
- **THEN** the application can write under `prompts/<user bucket>/dep-smoke/` with the key it received, and gets `403`
  outside that folder

#### Scenario: Skill and project written through the key
- **WHEN** an approved application declaring `skills/{current-user}/` (or `projects/{current-user}/`) with `read` and
  `write` is called by a user
- **THEN** the application writes a skill (a project) into the user's bucket and reads it back with its key

#### Scenario: Messages and Responses
- **WHEN** a user calls the approved application through `/anthropic/v1/messages` and `/openai/v1/responses`
- **THEN** the key the application receives on each carries the grant

#### Scenario: Token counting
- **WHEN** a user calls the application through `/anthropic/v1/messages/count_tokens`, before and after its `required`
  entry is approved
- **THEN** the first call is answered `403` without reaching the upstream, and on the second the application's key
  carries the grant

#### Scenario: Interceptor in front of the application
- **WHEN** the approved application is protected by an interceptor and a user calls it
- **THEN** the application's key carries the grant

### Requirement: The initiator's reach
The initiator (the user who started the request) SHALL reach their own bucket, resources shared with them, and public
resources the folder rules let them read, read-only. Nothing else SHALL count: not the calling key's attachments or
grants, the calling application's `appdata` or own definition, admin, global-reader or review standing, or org-unit
roles, which only restrict: a folder target that spans org units SHALL count only when the initiator's unit roles
read every one of them.

#### Scenario: Public target as far as the user reaches
- **WHEN** an approved application declares `files/public/dep-open/` and `files/public/dep-restricted/` (`read`) and the
  user can read only the first under the folder rules
- **THEN** the application reads the first with its key and gets `403` on the second

#### Scenario: Folder spanning an unreadable unit
- **WHEN** org units are on, an approved application declares `files/public/` (`read`), and a user without a role in
  unit `public/Acme/Risk` calls it, directly or through another application's key
- **THEN** the folder is not granted and the application gets `403` on `files/public/Acme/Risk/r.txt`; once the user
  holds a role in every unit the folder spans, it is granted

#### Scenario: Administrator standing does not reach
- **WHEN** an admin, who may write `prompts/public/dep-smoke/`, calls an approved application declaring that folder and
  `prompts/{current-user}/dep-smoke/`, both with `write`
- **THEN** the application writes into the admin's own folder with its key, and gets `403` in the public folder

### Requirement: Entries that are not granted
An entry of an unapproved list, or whose target the initiator cannot reach with every declared right, SHALL NOT be
added. When any such entry is `required`, Core SHALL answer the call `403` `Required resource dependencies are not
accessible: <target urls>`; otherwise the call SHALL go on without the entry.

#### Scenario: Required entry without approval
- **WHEN** a user calls an application whose `required` entry has no approval, through chat completions, Messages or
  Responses
- **THEN** Core answers `403` and the upstream is not called

#### Scenario: Optional entry without approval
- **WHEN** a user calls an application whose optional entry has no approval
- **THEN** the call succeeds and the application gets `403` on the target with its key

#### Scenario: List changed after approval
- **WHEN** an approved application with a `required` entry changes its list and a user calls it
- **THEN** Core answers `403` until the new list is approved

### Requirement: Dependencies at every hop
When an application calls another application with its per-request key, Core SHALL resolve the called application's own
list for the same initiator, on chat completions and MCP alike. A grant held by the calling key SHALL NOT count as the
initiator's reach.

#### Scenario: Chained call resolves the callee's list
- **WHEN** a user calls application A, A calls approved application B, and B declares
  `prompts/{current-user}/...` with `write`
- **THEN** B's key carries the grant on the user's bucket

#### Scenario: Required entry of the callee unreachable
- **WHEN** B's `required` entry targets a resource the user cannot reach
- **THEN** A's call to B is answered `403` `Required resource dependencies are not accessible: ...` and B is not called

#### Scenario: Caller's grants do not widen the callee
- **WHEN** A's key holds `files/<admin bucket>/secret.txt` in its own `perRequestSharedResources`, B declares the same
  target with `write`, and the user cannot reach it on their own standing
- **THEN** B's key carries no grant for it

### Requirement: MCP delivery of dependency grants
For an MCP application with `mcp.forwardPerRequestKey` (the default), Core SHALL send one per-request key per request,
the same on a redirect, carrying the resolved grants. With `forwardPerRequestKey: false` no key is sent and nothing is
granted, and a `required` entry SHALL fail the call `403` ending `— dependency grants cannot be delivered to this
application`. A request whose Content-Type is not JSON, or whose JSON is not an object, SHALL skip resolution.

#### Scenario: Grant on the MCP key
- **WHEN** a user calls an approved MCP application declaring `prompts/{current-user}/mcp-demo/`
- **THEN** the key sent as `API-KEY` can write under that folder

#### Scenario: Redirect keeps the key
- **WHEN** the MCP upstream answers the first attempt with a trailing-slash redirect
- **THEN** the second attempt carries the same key with the grant

#### Scenario: Key forwarding off
- **WHEN** an approved MCP application with `forwardPerRequestKey: false` declares an optional entry, and another a
  `required` one
- **THEN** the first call succeeds and its upstream receives no `API-KEY`; the second is answered `403` saying that
  dependency grants cannot be delivered to this application

#### Scenario: Non-JSON request
- **WHEN** a user sends a non-JSON `POST` to an MCP application with key forwarding whose `required` entry is not
  approved
- **THEN** the call succeeds, the upstream receives a key it can use, and the key carries no grant for the target

### Requirement: User consent document of a declaring application
For a root application that declares resource dependencies, or an alias whose target declares them,
`GET /v1/consent/{id}` SHALL list the declaration in the document's `resources` (`url` as declared, `access`) and SHALL
NOT auto-accept, whatever `features.consentRequired` says.
The request-time user consent check SHALL stay as it is.

#### Scenario: Declaration in the consent document
- **WHEN** a user reads `GET /v1/consent/<id>` of an application that declares `skills/{current-user}/` and sets no
  `consentRequired`
- **THEN** the answer is not accepted and its document lists `skills/{current-user}/`

#### Scenario: Alias of a declaring application
- **WHEN** a user reads `GET /v1/consent/<alias>` of an alias whose target declares `files/{current-user}/reports/`
  and sets no `consentRequired`
- **THEN** the answer is not accepted and its document lists `files/{current-user}/reports/`
