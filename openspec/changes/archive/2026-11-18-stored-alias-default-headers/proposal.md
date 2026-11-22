# Proposal

## Why

Since `alias-router-target` (archived 2026-11-17) an alias of the config file sends its `defaultHeaders`, so several
aliases over one router can each tell the router which tier they stand for. An alias stored through the admin API
(`PUT /v1/aliases/public/<name>` or `/v1/aliases/platform/<name>`) cannot do this: the `PUT` answers
`400 Fields not supported for a stored alias: defaultHeaders`. So tier aliases can only live in the config file, and
an admin cannot add or change one at run time.

## What Changes

- A stored alias keeps `defaultHeaders`: the `PUT` accepts it, with the same shape and rules as on an alias of the
  config file (an object of header name to string value), writes it in the blob, and `GET` answers it.
- A call of a public stored alias (by its url) and of a platform alias (by its name, once it joins the config) sets
  each of these headers that the client did not send, as for an alias of the config file. The call path already
  does this for any alias; today only the write path drops the field.
- A `PUT` of an alias of type `toolset` with non-empty `defaultHeaders` answers `400` and writes nothing. The config
  file ignores the field on a toolset alias; the stored API refuses fields it does not use. `GET` and the platform
  reload drop the field of a toolset blob written around the API, so a `GET` body can still be sent back as is.
- A JSON `null` `defaultHeaders` reads as none for every deployment, whether it comes from the config file, an API
  blob or a stored alias (`@JsonSetter(nulls = Nulls.SKIP)` on `Deployment.defaultHeaders`): calls that failed on
  a null map now run with no default headers.
- Listings still show no default headers of an alias: neither the alias's own nor the target's.
- Spec correction, no behaviour change: "Managing a stored alias" says another bucket answers `404` and its
  scenario has `PUT /v1/aliases/platform/<name>` answer `404`, stale since platform aliases were added (#2258). The
  delta says "a user bucket" and drops the platform `PUT` from the scenario, as `AliasPublicApiTest` already
  asserts.
- Tests in `StoredAliasesTest`, `AliasPassesTest`, `AliasPublicApiTest` and `AliasPlatformApiTest`; updates of
  `docs/dynamic-settings/aliases.md` and of the OpenAPI descriptions of the alias `GET` and `PUT`.

Out of scope:

- Per-interface default headers on an alias (`interfaces.<type>.defaultHeaders`): an alias has no `interfaces`.
- Checks of header names. Core checks none for a deployment's `defaultHeaders`, and adds none here.
- `/v1/admin/validate` and `/v1/admin/apply`: there is no `Alias` manifest kind.
- Documenting platform aliases (`/v1/aliases/platform/...`) in `aliases.md`: the page does not describe them at all
  since #2258. A separate docs change.
- Any change to how an alias call uses the headers, or to toolset aliases of the config file.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-aliases`: a stored alias keeps `defaultHeaders` ("Alias in public storage"), refuses them on a toolset
  alias ("Stored alias validated on write"), and its calls send them ("Default headers of an alias"); an alias entry
  carries no default headers, the alias's or the target's ("Content of an alias entry"); the stale platform `PUT` in
  "Managing a stored alias" is corrected.

## Impact

No GitHub issue yet; one is to be opened before the PR leaves draft. Motivation: tier aliases over one router,
managed at run time through the admin API instead of the config file. `alias-router-target` (design.md, decision 1)
left them out: "A stored alias takes no `defaultHeaders` ... so tier aliases that differ by a header are config-file
aliases". This change covers that point. A public stored alias still cannot target a router
("Target of a stored alias"), so a stored tier alias over a router is a platform alias.

Touches `StoredAliases` (the kept fields, the blob body, the toolset check) and its tests, `Deployment.defaultHeaders`
(a JSON `null` read as empty, for every reader),
`AliasPassesTest`, the stored-alias API tests, `docs/dynamic-settings/aliases.md`, and the OpenAPI annotation text
of the alias `GET` and `PUT` in `ConfigResourceController` (no behaviour change there). No new endpoint, setting or dependency. Depends on
`alias-router-target` (merged as #2286); no other change in flight.
