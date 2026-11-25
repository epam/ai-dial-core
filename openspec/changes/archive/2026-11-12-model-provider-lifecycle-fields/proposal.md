# Proposal

## Why

Operators have no structured place to say who hosts a model, when it is deprecated, or when the provider stops serving
it. They put it in `description` or `descriptionKeywords`, which clients cannot read as data. Providers retire models
regularly, so enterprises need this in the model schema.

## What Changes

- A model takes three optional, informational fields: `provider` (free-form text, such as `AWS`, `Google`, `Azure`),
  `deprecationDate` and `endOfService` (dates written `YYYY-MM-DD`).
- The config file and the Configuration API (`/v1/models/platform/{path}`) use `provider`, `deprecationDate` and
  `endOfService`; `deprecation_date` and `end_of_service` are accepted as aliases.
- `GET /openai/models` returns `provider`, `deprecation_date` and `end_of_service` next to `display_version`, and leaves
  out a field that is not set.
- A model entry of an alias shows the target model's three values.

Out of scope:

- Applications, routers and toolsets: the fields are on models only.
- Any behaviour that reads the fields: Core does not validate the dates, compare them, warn, or refuse calls after
  them.
- `lifecycle_status` of the model listing, which stays as it is.
- The Terraform provider, which follows as its own change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-interfaces-config`: adds "Model provider and lifecycle dates" and "Model listing shows provider and lifecycle dates".
- `deployment-aliases`: modifies "Content of an alias entry" so the target's provider and lifecycle dates show.

## Impact

- Issue: [#2275](https://github.com/epam/ai-dial-core/issues/2275). This change covers all of it.
- Code: `Model`, `ModelData`, `ModelController`, `AliasPasses`.
- Docs: `docs/dynamic-settings/models.md`, `docs/open_api_core.yaml`, `sample/aidial.config.json`.
- Tests: `ListingTest`, `ModelWriteApiTest`, `AliasApiTest`.
