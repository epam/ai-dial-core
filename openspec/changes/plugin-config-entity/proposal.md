# Proposal: plugin-config-entity

## Why

Core is going to run some deployments in process instead of behind HTTP. A Java plugin loaded at start will take
the place of the HTTP call to an origin, or transform a request or a response inside the pass that serves it.
Every such plugin needs a declaration with its settings. That declaration has to live where the rest of the
config lives: in the config files and in the `platform` bucket written through the Configuration API,
hot-reloaded, validated on write and visible to the admin tooling. This change adds that declaration as a config
entity of its own, `plugins`, and the host side that loads the plugin code the declarations refer to. It ships
first because the two changes that give plugins a place in the request path depend on it.

## What Changes

- A new config entity type `plugins`, keyed by short name like `translators`: `{"type": <provider id>,
  "settings": <object>}`. Declared in the config files under `plugins` and in the `platform` bucket at
  `plugins/platform/<name>`; a blob replaces the file entry of the same name, as for every short-name type.
- The Configuration API serves it: `GET`, `PUT`, `DELETE /v1/plugins/platform/{name}`,
  `GET /v1/metadata/plugins/platform/`, manifest kind `Plugin` in `/v1/admin/validate` and `/v1/admin/apply`,
  `GET /v1/admin/config/file/plugins[/{name}]`, and `plugins` in `/v1/admin/config/file/migrate`.
- A new Gradle module `plugin-api`, the artifact a plugin jar compiles against. In this change it holds only
  the provider contract: a provider names the `type` it serves and validates the `settings` of a declaration.
  The request-path contracts (the plugin kinds) come with the two follow-up changes.
- Core loads provider jars at start from a directory named by a new static setting, through `ServiceLoader`,
  and refuses to start when a jar cannot be loaded or two providers claim one `type`.
- A declaration is validated against its provider: an unknown `type` is a dangling reference (rejected on
  write, tolerated on a rebuild under `abort`, dropped under `skip`), invalid `settings` are an invariant
  violation (rejected on write in every mode, a rebuild treats the entity as invalid).
- No request handled by Core changes. Nothing references a plugin yet, so no deployment behaviour changes.

## Capabilities

### New Capabilities

- `plugin-registry`: the `plugins` config entity, how Core loads the plugin code it refers to, and how a
  declaration is validated against that code.

### Modified Capabilities

- `config-store`: the entity lists in "Canonical-shaped file keys rejected", "Merged sources", "Blob wins on
  a name clash", "Manifest names", "Batch dependency order" and "Write rejected with 422" gain `plugins`.
- `request-routing`: "Config entity routes" and "Admin and config routes" gain the `plugins` type.

## Out of scope

- The plugin kinds and their place in a pass: an embedded origin that replaces the HTTP call and can start
  a child pass, and the request and response filters. Changes `embedded-origin` and `embedded-filter`; both
  depend on this change and add the `plugin` reference fields to `interfaces`, `interceptors` and
  `translators`, with the dangling-reference and delete-cascade rules those references need.
- Secrets inside `settings`. A declaration carries no secret in this version; a plugin gets credentials from
  the upstream it is handed or from the environment. The known upgrade path is an encrypted string map on
  the entity, as `keys` and `upstreams` do today.
- Publishing `plugin-api` to a Maven repository for other repositories to build against. A test provider in
  the server module is enough to prove the contract here; publishing is its own small change.
- Hot reload of plugin code. Code arrives by rollout; only declarations and settings reload.

## Impact

Motivated by the plugin-system exploration of 2026-10-28 to 2026-11-01 in the perf improvement initiative. There
is no GitHub issue; the design note is internal and not linked. Its decisions:

- Four first components: a routing decision plugin, a guardrail interceptor, a Messages to Responses translator,
  a cloud-signing passthrough.
- First-party jars from other repositories; Java only, with the boundary kept portable on paper.
- Settings hot-reloaded through the config; code rolled out with the image.
- Declarations as an entity type of their own in the bucket.
- Two kinds, origin and filter, with Vert.x types in the API; the kind derived from the provider; named references
  only; no secrets in settings.

This change covers the entity type, the loading and the validation rule. It leaves the two kinds, the `call` host
callback, the component plugins and the artifact publishing to the follow-up changes named above.

Code: the `config` module (`Plugin`, `Config.plugins`), the `storage` module (`ResourceTypes.PLUGIN`), the
`server` module (merged store, config entity controllers, manifest support, apply and validate services,
migration, startup wiring, a `PluginRegistry`), the new `plugin-api` module, `settings.gradle`, the built-in
`aidial.settings.json` and the test-resources copy, `docs/dynamic-settings/plugins.md`, `README.md`,
`docs/open_api_core.yaml` and `CLAUDE.md` (module table).
