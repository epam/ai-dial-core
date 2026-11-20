# Design

## Context

See proposal.md, Why. What the code does today, on `development` (3eccd2603):

- `StoredAliases.java:fromRequest` keeps the body fields named in `StoredAliases.FIELDS` (`presentAs`, `target`,
  `userRoles` and the display fields, in the spellings of `SPELLINGS`), ignores `name` and `status`, and throws
  `IllegalArgumentException` for any other field; `ConfigResourceController.java:handleWriteError` answers it `400`.
  `defaultHeaders` is not in `FIELDS`, so a `PUT` with it answers
  `400 Fields not supported for a stored alias: defaultHeaders`.
- `StoredAliases.java:toJson` writes the blob body and the `GET` answer field by field and has no `defaultHeaders`.
- `ConfigResourceController.java:handleAliasPut` parses the body with `fromRequest`, runs `checkAliasTarget`, writes
  `toJson(alias)` and, for a platform alias, puts the parsed `alias` itself into the merged config
  (`MergedConfigStore.java:applyEntityWrite`). `handleAliasGet` answers `toJson` of the parsed blob.
- A public stored alias is read on each call: `AliasService.java:parseStored` -> `StoredAliases.java:parse`, which
  reads the whole blob into `Alias` with `BLOB_MAPPER`, `defaultHeaders` included. A blob with
  `"defaultHeaders": null` reads as a null map, and nothing normalises it: `AliasPasses.java:build` copies it to the
  pass, `Deployment.java:resolveDefaultHeaders` returns it (the pass's interfaces have `Map.of()` headers), and every
  call of the alias fails at `ApplyDefaultDeploymentSettingsFn.java:75` (`defaultHeaders.isEmpty()`).
  `handleAliasGet` (`ConfigResourceController.java:1878-1881`) and the platform reload below hand that null map to
  `toJson`.
- A platform alias joins the config at reload and on replica events: `MergedConfigStore.java:addBlobEntity`
  (`case ALIAS`) reads the blob and keeps `BLOB_MAPPER.treeToValue(StoredAliases.toJson(dirty), Alias.class)`, so
  only what `toJson` writes survives.
- `AliasPasses.java:build` copies `alias.getDefaultHeaders()` to the pass of an alias of type `model`, `application`
  or `router`, and `ApplyDefaultDeploymentSettingsFn.java:applyDefaultHeaders` sets them where the client sent none.
  A toolset pass (`AliasToolSetPass`) gets none. This is the same for every alias, config or stored.
- Listings: `AliasPasses.java:view` copies neither the alias's `defaultHeaders` nor the target's (top level or per
  interface), so `InterfaceConfigData.java:createInterfaceConfigs` gives empty `default_headers` for an alias entry
  (`AliasPassesTest` checks the target's side; task 1.2 adds the alias's).
- The OpenAPI descriptions of the alias `GET` (`200`) and `PUT` (`400`) in `ConfigResourceController.java:1156` and
  `:1187` list the kept fields and the `400` reasons; CI regenerates `docs/open_api_core.yaml` from them.

So the call path already honours the field for any alias; only the write path (`FIELDS`) and the blob body
(`toJson`) drop it.

## Goals / Non-Goals

Goals: a stored alias keeps `defaultHeaders` end to end (PUT, blob, GET, calls, platform reload); a toolset alias
refuses it on PUT.

Non-goals: any change to `AliasPasses`, `AliasService`, `MergedConfigStore` or the header semantics; a behaviour
change in `ConfigResourceController` (only its OpenAPI annotation text changes, task 1.5); a header name check.

## Decisions

1. **`defaultHeaders` joins the kept fields.** Add it to `StoredAliases.FIELDS` after `userRoles`, add
   `"default_headers" -> "defaultHeaders"` to `SPELLINGS` (the spelling `Deployment.defaultHeaders` accepts through
   `@JsonAlias`), and name it in the refusal message and the class Javadoc. The shape check is the one Jackson
   already makes for `Map<String, String>` in `ConfigEntityCodec.java:treeToEntity`, the same mapper settings as
   the blob read: a list or an object value answers `400`; a number or boolean value becomes its string, as in the
   config file. Rejected: a dedicated validator for names and values; Core checks neither for a deployment's
   `defaultHeaders` (out of scope in the proposal).

2. **`toJson` writes `defaultHeaders` when it is set, not empty, and the alias is not of type `toolset`**, after
   `userRoles`, as it writes `descriptionKeywords`: `!getDefaultHeaders().isEmpty()` and
   `getEffectivePresentation() != TOOLSET`. `Deployment.defaultHeaders` defaults to `Map.of()`, so a plain
   `putIfSet` would write `{}` into every blob and every `GET`; a JSON `null` never reaches it (decision 4). The toolset condition keeps the `GET` answer of a toolset blob written around the API
   a body its `PUT` accepts (a `GET` body can be sent back as is: `StoredAliases.IGNORED`, `aliases.md` Body), and
   the blob rewrite and the platform reload drop a field the toolset pass never sends. This one condition also fixes
   the platform reload, which keeps only what `toJson` writes (`MergedConfigStore.java:addBlobEntity`). Rejected:
   - reading the platform blob without the `toJson` round trip; that round trip is what strips the fields the API
     refuses, and it is not this change's to remove;
   - writing the field for any type: a toolset blob written around the API would then answer a `GET` that its own
     `PUT` refuses (decision 3).

3. **A toolset alias with non-empty `defaultHeaders` is refused in `fromRequest`.** After the body parses, if
   `alias.getEffectivePresentation()` is `TOOLSET` and the map is not empty, throw `IllegalArgumentException`
   (`400`), naming the field and the type. The type needs no lookup: it is `presentAs` or the first segment of the
   target path. An empty map is accepted and stored as nothing (decision 2). Rejected:
   - accept and ignore, as the config file does: the stored API already refuses the fields it does not use
     (`endpoint`, `features`), and a header that is silently never sent is the mistake an admin would not notice;
   - the check in `StoredAliases.java:validateFields`: that method also gates the read path (`parse`, the platform
     reload), so a blob written around the API would stop being served for a field the config file only ignores.
     On the read path the field stays ignored, as for a config alias (`AliasToolSetPass` takes no headers), and
     `toJson` drops it (decision 2), so `GET`, the blob and the platform reload agree.

4. **A null `defaultHeaders` reads as empty.** `Deployment.defaultHeaders` carries `@JsonSetter(nulls = Nulls.SKIP)`
   (the pattern of `Publication.resources`), so a JSON `null` keeps the `Map.of()` default for every reader:
   `fromRequest`, `parse`, the platform reload and the config file. Without it,
   `"defaultHeaders": null` in a `PUT` sets the map to `null`, `handleAliasPut` puts that `Alias` into the merged
   config for a platform alias, and every call of it on that pod fails at `ApplyDefaultDeploymentSettingsFn.java:75`
   until the next reload; a public blob written around the API with `"defaultHeaders": null` fails every call
   already today (Context). The `fromRequest` loop is unchanged, so a field a stored alias does not keep still answers
   `400` when its value is `null` (`{"endpoint": null}`). Rejected:
   - skipping every JSON `null` field in the `fromRequest` loop (the first draft): it covers the `PUT` only, not a
     blob read by `parse`, and placed before the `FIELDS` check it would stop refusing `{"endpoint": null}`;
   - refusing `null` with `400`, unlike the other fields, which accept it as unset today;
   - setting a null map to `Map.of()` in `fromRequest` and `parse` (the first implementation): it misses the
     platform reload and the config file, and needs a null check in `toJson` as well.

Deliberate differences from today's behaviour:

- A `PUT` with `defaultHeaders` answers `200` instead of `400`; a toolset alias with them still answers `400`, with
  another message.
- A blob written around the API (for example by a system import) that has `defaultHeaders`, for an alias of type
  `model`, `application` or `router`: a public one was already honoured on calls, and `GET` now shows the field; a
  platform one lost the field at reload and now keeps it, so its calls start sending the headers.
- Such a blob for an alias of type `toolset`: unchanged on calls (never sent), and `GET` and the platform reload still
  drop the field.
- A deployment with `"defaultHeaders": null`, whether a model, application or router of the config file, a
  custom application blob or a stored alias: its calls failed on the null map (at
  `ApplyDefaultDeploymentSettingsFn.java:75`, or `RouterDecisionClient.java:198` for a router), and now run with no
  default headers. `DeploymentInterface` headers are unchanged.

## Risks / Trade-offs

- [A header set by a stored alias travels on to the deployment that serves the call, as any client header does] ->
  as for a config alias (alias-router-target, Risks); `aliases.md` already says not to put a secret there.
- [A client may send its own value of a defaulted header] -> unchanged rule for every deployment; a client value
  wins.

## Cost per request

No new Core pass, body copy, body parse, Redis or blob round trip. A public stored alias is read on each call
already (through the resource cache); its blob grows by the header bytes. A platform alias is in the config. No
cost claim, so no measurement task.

## Migration Plan

None for data. Rolling upgrade: a pod on the old version refuses a `PUT` with `defaultHeaders`, and drops the field
of a platform alias when it reloads the blob, so its calls of that alias do not send the headers until it runs the
new version. A public stored alias's headers are sent by old pods too (the read path is unchanged since #2286).
Rollback: old pods ignore the field of platform blobs as above; nothing to clean up.

## Open Questions

- Platform aliases (`/v1/aliases/platform/...`, since #2258) are not described in `aliases.md` nor fully in the
  `deployment-aliases` spec. This change corrects only the one stale scenario that said their `PUT` answers `404`.
  A separate docs and spec change can describe them.

## Spec review

| # | Lens | Severity | Finding | Verdict | What changed |
|---|---|---|---|---|---|
| 1 | fidelity | low | Decision 4 cites the wrong place for the null-map failure and covers only the `PUT`; a null map from a blob reaches calls and `toJson` | accepted | Checked: `resolveDefaultHeaders` returns the null top-level map (the pass's interfaces have `Map.of()`), the NPE is at `ApplyDefaultDeploymentSettingsFn.java:75`, and `parse` keeps `null`. Context now says so; decision 4 normalises a null map in `fromRequest` and `parse` and `toJson` handles null (decision 2); the call-time fix for a hand-written public blob is listed in Deliberate differences; task 1.2 adds `parse` of a `"defaultHeaders": null` blob and `toJson` of a null map; 1.4 updated |
| 2 | fidelity | low | `toJson` writes the field for any type, so a toolset blob written around the API answers a `GET` its `PUT` refuses | accepted | Decision 2: `toJson` omits the field for an alias of type `toolset`; decision 3 and Deliberate differences say `GET`, the blob and the platform reload agree; spec delta "Alias in public storage" keeps `defaultHeaders` except on a toolset alias; task 1.2 adds the `toJson` assertion, 1.4 the condition |
| 3 | fidelity | low | The Listings paragraph of `aliases.md` still hides only the target's default headers | accepted | Task 1.5 rewrites the Listings sentence: no path or endpoint of the target, no default headers of the alias or the target |
| 4 | fidelity | low | The proposal names "Content of an alias entry" but the delta added a separate requirement | accepted | The delta now MODIFIES "Content of an alias entry" (all four scenarios copied, the new one added) and drops the ADDED requirement, so the proposal line is right; it now says "the alias's or the target's" |
| 5 | fidelity | low | Context says `AliasPassesTest` checks both sides of the view; it checks only the target's | accepted | Context narrowed to the target's side; task 1.2 gives the alias in the view unit test `defaultHeaders: {"X-Team": "b"}` (passes already, pins the alias side) |
| 6 | simplicity | medium | No task updates the OpenAPI descriptions of the alias `GET` and `PUT`; the non-goals block it; `aliases.md:161` stale | accepted | Checked `ConfigResourceController.java:1156` and `:1187`. Task 1.5 updates the annotation text (`defaultHeaders` among the kept fields, the toolset `400` reason), CI regenerates `docs/open_api_core.yaml`; non-goal narrowed to no behaviour change in `ConfigResourceController`; Context and proposal Impact name it; `aliases.md:161` as finding 3 |
| 7 | simplicity | low | A null skip before the `FIELDS` check would let `{"endpoint": null}` through | accepted | Decision 4 no longer skips nulls in the loop (it normalises the parsed map, finding 1), so the loop and the refusal of `{"endpoint": null}` are unchanged; the old draft is listed as rejected; task 1.2 asserts `{"target": ..., "endpoint": null}` still throws |
| 8 | simplicity | low | The rule lives in a separate ADDED requirement next to "Content of an alias entry", which can drift | accepted | Same edit as finding 4: one MODIFIED requirement, "SHALL NOT carry the target's path or endpoint, nor any default headers, the alias's or the target's", with the "Stored alias with default headers listed" scenario under it |
| 9 | simplicity | low | `GET`-then-`PUT` of a toolset blob with `defaultHeaders` answers `400`; the platform reload keeps a field calls ignore | accepted | Same edit as finding 2: one condition in `toJson` |

## Implementation notes

- Drift: none. design.md was written on `3eccd2603`, which is still the base of this branch; the cited lines
  (`ConfigResourceController.java:1156`, `:1187`, `:1878-1881`, `ApplyDefaultDeploymentSettingsFn.java:75`,
  `MergedConfigStore.java:addBlobEntity`, `AliasPasses.java:build`) match the code. The spec deltas start from the
  current text of the five requirements in `openspec/specs/deployment-aliases/spec.md`; no change was archived since.
- Decision 4, implemented at the source instead: `Deployment.defaultHeaders` carries `@JsonSetter(nulls = Nulls.SKIP)`
  (the pattern of `Publication.resources`), so a JSON `null` keeps the `Map.of()` default for every reader:
  `fromRequest`, `parse`, the platform reload (`MergedConfigStore.addBlobEntity`, `BLOB_MAPPER.treeToValue`) and the
  config file. `StoredAliases` has no null check, and `toJson` (decision 2) tests only for an empty map. A first cut
  normalised the map in `fromRequest` and `parse` and kept a null check in `toJson`; review replaced it, since it left
  the config file out. One more deliberate difference: a model, application or router of the config file, or a custom
  application blob, with `"defaultHeaders": null` failed every call at `ApplyDefaultDeploymentSettingsFn.java:75` (or
  `RouterDecisionClient.java:198` for a router) and now calls with no default headers. `interfaces.<type>.defaultHeaders`
  (`DeploymentInterface`) is unchanged.
- Decision 3: the refusal message is `defaultHeaders are not supported for an alias of type toolset`.
