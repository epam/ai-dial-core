# Deployment listing

`GET /v1/deployments` lists the deployments the caller may use: models, applications, toolsets and routers, with the
[aliases](dynamic-settings/aliases.md) presented as one of them. `interface_type` (`chat`, `embedding`, `mcp`,
`custom_ui` or `all`, comma separated) picks the deployments, and `fields` picks what each entry holds.

## Selecting fields

The optional `fields` parameter cuts each entry down to the members it names, plus `id` and `object`, which are always
returned.

| `fields` | Each entry holds |
|---|---|
| `display_name,display_version` | `id`, `object`, `display_name`, `display_version` |
| `features(rate,tokenize)` | `id`, `object`, and `features` with only `rate` and `tokenize` |
| `interface_configs(*(features(reasoning_efforts)))` | `id`, `object`, and `interface_configs` with, for every interface, only `features.reasoning_efforts` |

* A name followed by parentheses selects inside that member. `*` stands for every member at its level, so
  `routes(*(rewritePath))` selects `rewritePath` of every route. A `*` that keeps the whole value keeps everything at
  its level, so `display_name,*` is the whole entry.
* Names are the names of the response: snake_case at the top level, and inside a nested object the names that object
  has, such as `rewritePath` inside `routes` and `openaiChatCompletions` among the `interface_configs`.
* A selection narrows objects, also inside arrays. A string, a number or `null` stays as it is, and an object left with
  none of the named members is `{}`.
* Unknown names are ignored: `fields=display_name,descriptio` returns `display_name` only.
* A selection that is malformed, longer than 1024 bytes (UTF-8) or nested deeper than 5 levels answers `400`. A blank
  value and a selection that keeps everything, such as `*`, return the full listing.
* A request that already sends an unrelated `fields` parameter to this endpoint is now read as a selection and gets
  filtered entries or a `400`.
* `GET /v1/deployments/{id}` and the other listings do not read `fields`.
