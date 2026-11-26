# Proposal

## Why

An application or toolset saved by a user always reports the saving user as its `author`: on every read Core replaces the
`author` in the body with the audit author stored in the blob metadata. A client that copies a resource it received from
someone else, to another environment or into its own bucket, cannot keep the original developer's name; the copier
becomes the author. Applications and toolsets in the `platform` bucket, and models, already keep the `author` they were
saved with; user and `public` ones are the exception.

## What Changes

- `author` of an application or toolset is a business value, saved in the body and returned as saved, not the audit
  value. The audit author stays in the blob metadata (`GET /v1/metadata/` `author`) and is unchanged.
- A write that creates an application or toolset without `author` stores the caller's name as `author`, on every write
  path: a Resource API `PUT`, a Configuration API `PUT`, `/v1/admin/apply` and file migration. A write that sends `author`,
  including `""`, stores it as sent. An update that omits `author` keeps the stored one.
- A read returns the stored `author`. Only when the body has none (entities saved before this change) it returns the
  audit author, as today. `""` is returned as `""` and means "anonymous".
- `GET /openai/applications`, `GET /openai/toolsets`, their `/{id}` reads and `GET /v1/deployments` report the same
  value as `owner`.
- Apply and file migration record the calling admin as the audit author of what they write; today they record none.
- Publication approval stores the publication's `displayAuthor` as the `author` of the published application or toolset,
  so a published copy keeps showing what it shows today.
- Toolset repair keeps passing the audit author, not the body one, when it rewrites a toolset.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `deployment-interfaces-config`: adds the requirement for the `author` of an application or toolset (create, update,
  read, listings, empty string, entities saved before the change).
- `config-store`: adds the requirement that apply and file migration record the caller as the audit author.
- `resource-access-control`: "Approving a publication" also sets `author` of a published application or toolset.

## Impact

- Issue [#1521](https://github.com/epam/ai-dial-core/issues/1521) ("Support `originalAuthor` field"). This change covers
  keeping a client-supplied author on an application or toolset, through the entity's own `author`; it needs no new field
  and no client change, because clients already send back what a `GET` returned.
- Out of scope, a separate change that depends on this one: listing the business author in `GET /v1/metadata/`
  (it needs a copy of the value in the blob and Redis metadata), and any author for other resource types
  (conversations, prompts, files).
- Out of scope: `ResourceService.putResourceTraced` keeps the stored audit author even when it is null, so entities
  applied before this change keep no audit author.
- `owner` in listings is now supplied by the client: any user can name any author on their own application or toolset.
  Nothing in Core reads `author` or `owner` to authorize; the audit author remains in `/v1/metadata/` and in
  `Publication.author`.
- Docs: `docs/dynamic-settings/applications.md` and `toolsets.md`. `docs/open_api_core.yaml` needs no change: its
  `author` entries are examples of the metadata response, which keeps the audit author.
