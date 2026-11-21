# Proposal

## Why

QA engineers and plugin developers do not read a plugin entry's `type` as "which plugin runs this entry", and the
admin console cannot help them: Core lists no plugins, so the console's create form takes the type as free text and
its settings editor knows nothing about the settings. No release tag contains the plugin system yet, so the field
can be renamed now with a deprecated alias instead of a breaking cycle later.

## What Changes

- A `plugins` entry names its plugin with `pluginId`. `type` is still accepted on input as a deprecated alias (PUT,
  validate and apply manifests, config files, blobs already stored); a body with both is refused with `400`. Each
  config file is read on its own before the files merge, so an overlay's `pluginId` replaces a base file's `type`.
  Core stores and returns `pluginId` only, invalid entries included, so the next write of an entry rewrites its blob
  with the new name. Warnings name `pluginId` whichever name the body used.
- A provider may declare a settings schema (JSON Schema draft-07, the draft Core uses for application type and
  catalog schemas). Core checks `settings` against it on every write and rebuild before the provider's own check,
  with the same outcomes as a rejection by the provider: the schema is a validation rule, not only a hint. A schema
  that is not valid draft-07, names another draft or has a `$ref` outside itself stops the start: Core loads no
  schema from a url.
- A provider may declare a description and its targets, the places a plugin url may name its entries: `model`,
  `application`, `router`, `translator`, all four by default. Targets are a hint for the admin console: Core does
  not check a plugin url against them.
- `GET /v1/admin/plugin-providers` (admin role, `x-preview`) lists the providers loaded at start: `id`, `targets`,
  and when known `directory`, `version` (the jar's `Implementation-Version`), `description` and `settingsSchema`. The
  path says "providers" so that it is not taken for the API of the `plugins` entries.
- In `aidial-core-plugin-api`, `PluginProvider` gets `id()`, `description()`, `settingsSchema()` and `targets()` as
  default methods, and a `PluginTarget` enum; `type()` stays as a deprecated method that `id()` falls back to, so a
  jar built against today's API loads unchanged. `validate()` gets a default that accepts everything.

Out of scope: checking a plugin url against the plugin's targets; targets per interface type; a form generated
from the schema (admin console); a format rule for ids; removing the `type` alias; a limit of one entry per plugin.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `plugin-registry`: the entry field `pluginId` and its deprecated alias `type`; the settings schema check; the new
  load errors; the plugin listing. Requirements whose scenarios write `type` are restated with `pluginId`.

## Impact

- Motivation: feedback from QA and plugin developers on the `type` field, and the admin console's Plugins page
  (ai-dial-admin-frontend, `openspec/specs/platform-plugins`: "the form SHALL NOT offer a list of types, because
  Core lists none"). This change covers the four points raised: the rename, the settings schema, the targets and the
  listing. The console's use of them (a selector of plugins, the schema handed to its JSON editor, base url
  selectors filtered by target) ships as a separate PR in ai-dial-admin-frontend and depends on this one.
- Code: `aidial-core-plugin-api` (`PluginProvider`, new `PluginTarget`), `config` (`Plugin`), `server`
  (`PluginRegistry`, `ConfigPostProcessor` plugin validation, a new admin controller), `docs/dynamic-settings/plugins.md`,
  the OpenAPI specification. No new dependency: `networknt` json-schema-validator is already used by `config` and
  `server`.
- Plugin jars: `ai-dial-model-provider-plugin` and the `forward` sample keep working and can move to `id()` when
  convenient.
- Mixed releases: none to protect, since no released Core reads `plugins` entries.
- Renames a Jackson-bound field of `config`, which docs/CODE_STYLE.md forbids; design D2 records why.
- Scenarios in `deployment-interfaces-config` and `deployment-origin` say an entry is "of type `test-origin`"; that
  wording stays.
- Supersedes: plugin-config-entity D2 and D3 for the name `type`; D3 also for `validate()`, which gets a default
  and runs after the schema check; D5 for the warning field `type` (now `pluginId`) and the check order.
