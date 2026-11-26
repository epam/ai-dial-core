# Design

## Context

A user or `public` application or toolset is stored as a JSON body plus blob metadata. The metadata holds the audit
author: the first writer, kept on every later write. The body has an `author` field, the developer's name, but a read
replaces it with the audit author, so a value the client sends is saved and never shown. Models, and applications and
toolsets in the `platform` bucket, have no such replacement: the config rebuild loads their body alone. Right after a
write, though, the in-memory copy of a platform entity is built through the same read that replaces the author, so it
can differ from the rebuilt one. Copies (move, publication review, publication approval) read the source the same way
and write the replaced value into the copy.

## Decisions

### D1. `author` in the body is the author; the audit author stays in the metadata
The body field already exists, is already documented as the developer, is already one of the fields an approval ignores
(`APPROVAL_UNBOUND_FIELDS`), and is what platform entities and models show. Reads stop replacing it. No field is added,
so the dynamic-config surface, the approval check and the OpenAPI schemas keep their shape.

**Rejected: a new `originalAuthor` body field** - it would sit beside `author` with the same meaning for every entity in
the config, and clients would have to learn which one to send.
**Rejected: letting the body `author` set the audit author** - the audit value is what `GET /v1/metadata/` and
`Publication.author` report as who stored the resource; a client could forge it.

### D2. The writer fills a missing `author` on create, for every path
`writeApplication` and `writeToolSet` already receive the caller's name as `author` and every create path goes through
them: the Resource API, the Configuration API, apply, file migration, unit-role writes. Doing it there leaves one place to
change and no path where a new entity has no `author`, so the in-memory copy after a write and the rebuilt copy agree.

**Rejected: filling it only in `ResourceController`** - an admin `PUT` or an apply would leave the body empty, the
in-memory copy of a platform entity would show the audit author fallback until the next rebuild and nothing afterwards.

### D3. An update that omits `author` keeps the stored one
The write callback already holds the stored body, so keeping it is one assignment. Filling the caller's name on every
write would rename the author each time a different admin, or a CI key, applies the same manifest.

**Rejected: fill on every write** - an apply is repeated by design; the author would be whoever ran it last.

### D4. A read falls back to the audit author only when the body has no `author`
Entities saved before this change have no `author` in the body, or one the old code ignored; without the fallback every
one of them loses its `owner` in the listings. The test is for `null`, not for blank, so `""` is a value ("anonymous").

**Rejected: no fallback** - every existing user application and toolset loses its owner at once.
**Rejected: a backfill of every body** - it rewrites blobs of every bucket to save one `if` on a read.

### D5. Publication approval sets the copy's `author` to `displayAuthor` when there is one
Today the published copy shows `displayAuthor` because the audit author of the copy is `displayAuthor`. With reads
returning the body, the copy must carry it in the body too, in `copyApplication` and `copyToolSet`, taken before the
audit author is defaulted to the source's. Without a `displayAuthor` the copy keeps the `author` its source stores in the
body; a source with none keeps none, and the copy's audit author is the source's too, so a read shows the same name as
for the source. A move or a copy by a user passes no author, so it behaves the same way.

**Rejected: leaving the copy's body as it is** - the public listing would switch from `displayAuthor` to the source's
author on the day this ships.

### D6. Apply and migration take the caller's name as a parameter
`ConfigApplyService.applyEntries` gets the name from `AdminApplyController` and `ConfigFileMigrateController`, which
hold the request context, and passes it to every write it makes, as `ConfigResourceController` does. It is the audit
author of new entities and, through D2, the default `author` of applications and toolsets.

**Rejected: also letting `putResourceTraced` adopt the supplied author when the stored one is null** - it would repair
entities applied before this change, but it changes the storage layer for every resource type; a separate decision.

## Differences from today

| What | Today | After | Requirement |
|---|---|---|---|
| `author` sent in a user or `public` write | saved, never returned | returned as sent | Author on create / on update |
| `author` of a `GET` and `owner` in listings | audit author | stored `author`, audit author when none | Author on read, Owner in deployment listings |
| `author: ""` | audit author is shown | `""` is shown | Author on read |
| Platform application or toolset saved without `author` | no `author` | the caller's name | Author on create |
| Apply or file migration, audit author | none | the admin's display name | Apply and migration record the caller |
| Published application or toolset | `displayAuthor` through the audit author | `displayAuthor` through the body | Approving a publication |
| Published copy without `displayAuthor` | audit author of the source | `author` of the source's body, audit author of the source when it has none | Approving a publication |

## Effect per request

None: the read already loads the body and the metadata, the write callback already holds the stored body, and the
copies already read the source. No Redis or blob call is added or removed.

## Risks / Trade-offs

- **`owner` is client-supplied.** A user can name any author on their own application or toolset, and the marketplace
  shows it. Nothing in Core reads `author` or `owner` to authorize (checked in `ApplicationData`, `ToolSetData`,
  `ApplicationController`, `ModelController`, `PublicationService`). The audit author stays in `GET /v1/metadata/` and in
  `Publication.author`.
- **A legacy platform entity with an audit author and no body `author`** shows the fallback right after a write and no
  owner after a rebuild, as it does today. It settles when it is saved with an `author`.
- **Entities applied before this change** have no audit author and keep none (`putResourceTraced` keeps the stored value,
  null included). With no body `author` either, they show no owner, as today.
- **A client that sends back what a `GET` returned** stores the fallback audit author of a legacy entity as its `author`
  on the first update; that is the value it showed, so nothing visible changes.
- **An apply without `spec.author`** stores the admin's name as `author`. A manifest that should show no author sets
  `author: ""`.

## Migration Plan

None. Old entities are read through the D4 fallback and nothing is rewritten. A rollback is safe: the previous code
ignores the body `author`, so values stored in the meantime do no harm.

## Code map

- Overlay on read: `ApplicationService.getApplication`, `ApplicationService.extractFrom`,
  `ToolSetService.getToolSet(ResourceDescriptor, EtagHeader)`, `ToolSetService.getToolSetWithDecryptedAuthSettings`,
  `ToolSetService.extractFrom`, `ToolSetRepairService.repair` (its overlay also feeds the audit author it passes to
  `computeResource`). `ApplicationService.getApplicationWithDecryptedSecrets` and
  `ToolSetService.getToolSetWithDecryptedAuthSettings` feed the in-memory copy of a platform entity after a write.
  The private `ToolSetService.getToolSet(ResourceDescriptor, boolean, EtagHeader)` sets no author, so
  `ToolSetService.copyToolSet` copies the stored body `author` as it is; the copy's audit author is the source's, so the
  read fallback shows a source without a body `author` the same name. `ApplicationService.copyApplication` copies what
  `getApplication` returns.
- Write callbacks: `ApplicationService.writeApplication` (`computeResource` callback; `prepareAdminManagedFields` runs
  first), `ToolSetService.writeToolSet`. Callers pass `context.getUserDisplayName()`:
  `ResourceController.putResource`, `ConfigResourceController.handleAppOrToolSetPut`. Reuse the stored body those
  callbacks already parse.
- Copies: `ApplicationService.copyApplication`, `ToolSetService.copyToolSet`, called from
  `PublicationService.copyReviewToTargetResources` (review copy: no author; approval: `displayAuthor`) and
  `ResourceOperationService` (move and copy: no author). `PublicationService.updatePublication` re-puts a review
  application with `putApplication` and no author.
- Apply: `ConfigApplyService.applyEntries`, `applySingle` and each `apply*` method;
  `AdminApplyController.process`; `ConfigFileMigrateController.runMigration`. `PolicyService.putPolicy` and `putAssignment`
  take the author already.
- Unchanged: `ResourceService.putResourceTraced` (keeps the stored audit author), `ApplicationData.mapApplication` and
  `ToolSetData.toData` (`owner` from `getAuthor()`), `ApplicationService.APPROVAL_UNBOUND_FIELDS` (`author` is in it),
  `MergedConfigStore` (the rebuild and the replica events read the body with `ResourceService.getResource`).
- Listings that call `extractFrom`: `DeploymentController.getToolSets`, `ToolSetController.getResourceToolSets`,
  `ApplicationDeploymentExtractor` (`controller/extraction`).
- Tests to extend: `CustomApplicationApiTest`, `ToolSetApiTest`, `ApplicationDeploymentApiTest`, `AdminApplyApiTest`,
  `ConfigEntityWriteApiTest`, `PublicationApiTest`, `ToolSetRepairServiceTest`.
