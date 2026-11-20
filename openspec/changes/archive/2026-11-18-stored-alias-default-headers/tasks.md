# Tasks

The build on this branch needs a JDK 25 as `JAVA_HOME` (development is on Java 25 and Gradle 9.9.1).

## 1. Stored alias keeps defaultHeaders

- [x] 1.1 Compare `design.md` (Context, decisions 1-4) with `StoredAliases`, `ConfigResourceController.handleAliasPut`
      / `handleAliasGet` and `MergedConfigStore.addBlobEntity` on `development`; record any drift in `design.md`
- [x] 1.2 Unit tests in `StoredAliasesTest`, failing before 1.4: `fromRequest` keeps `defaultHeaders` in both
      spellings (`defaultHeaders`, `default_headers`); `"defaultHeaders": null` gives an empty map and `toJson` writes
      no field; `parse` of a blob with `"defaultHeaders": null` gives an alias with an empty map, and `toJson` of it
      writes no field;
      `"defaultHeaders": ["X-Team"]` throws `IllegalArgumentException`; a non-empty map throws for target
      `toolsets/search` and for `presentAs: toolset` over `applications/mcp-app`, and is kept for an alias of type
      `model` and `application`; `{}` on a toolset alias is accepted; `toJson` writes a non-empty map after
      `userRoles`, no field for an empty one, and no field for an alias of type `toolset` with a non-empty map (as
      `parse` reads a blob written around the API). Two assertions that pass already and pin behaviour:
      `{"target": ..., "endpoint": null}` still throws (decision 4); in `AliasPassesTest`, the view unit test gives
      the alias `defaultHeaders: {"X-Team": "b"}` and the view still has none
- [x] 1.3 Integration tests, failing before 1.4. In `AliasPublicApiTest`: "Header of a public stored alias" and
      "Stored alias with default headers listed" (one new test, `storedAliasDefaultHeadersReachTheTargetNotTheListing`:
      `GET` answers the map, the upstream of `gpt-4-internal` receives `X-Team: b`, `/openai/models/aliases/public/team-v1`
      has empty `default_headers` in every `interface_configs` entry and no `X-Team`); "Invalid bodies" (add
      `defaultHeaders: ["X-Team"]` to `invalidBodiesAreRefusedAndNothingIsWritten`); "Default headers on a toolset
      alias" (new test `toolsetAliasRefusesDefaultHeaders`). In `AliasPlatformApiTest`: "Header of a platform alias"
      (new test `platformAliasDefaultHeaderReachesTheTargetAfterReload`). "Not directly in the public bucket on
      write" is already asserted by `AliasPublicApiTest.aliasesLiveDirectlyInThePublicOrPlatformBucket`: confirm it
      still asserts `404`, `400`, `400` and no platform `404`
- [x] 1.4 `StoredAliases`: `defaultHeaders` in `FIELDS` after `userRoles`, `default_headers` in `SPELLINGS`, the
      toolset refusal after the body parses (decision 3), the field in `toJson` when not empty and the alias not of
      type `toolset` (decision 2), and the refusal message and class Javadoc naming it; a JSON `null` `defaultHeaders`
      keeps the `Map.of()` default through `@JsonSetter(nulls = Nulls.SKIP)` on `Deployment.defaultHeaders`
      (decision 4, see Implementation notes); verify 1.2 and 1.3 pass
- [x] 1.5 Docs. `docs/dynamic-settings/aliases.md`: the `defaultHeaders` bullet of `aliases.<alias_name>` ends "Not
      for an alias of type `toolset`: the config file ignores them there, and a stored alias with them answers `400`"
      (drop "nor for a stored alias"); the Listings paragraph says an entry never shows the target's path or
      endpoint, nor any default headers (the alias's or the target's), nor the target's version as the alias's; the
      **Body** paragraph of "Aliases in public storage" lists `defaultHeaders` among the kept fields, refused on an
      alias of type `toolset`. `ConfigResourceController`, annotation text only: the alias `GET` `200` description
      names `defaultHeaders` among the fields, and the `PUT` `400` description names it among the kept fields and
      adds "non-empty `defaultHeaders` on an alias of type `toolset`"; CI regenerates `docs/open_api_core.yaml`.
      Verify by reading all of them against the spec delta
- [x] 1.6 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "*StoredAliasesTest" --tests "*AliasPublicApiTest" --tests "*AliasPlatformApiTest" --tests "*AliasApiTest"`
      and `./gradlew :config:test` pass

## 2. Change checks

- [x] 2.1 `npx -y @fission-ai/openspec@1.14.3 validate stored-alias-default-headers --strict` passes

## Workflow follow-up

- Write `review.md` in the change folder, archive with `npx -y @fission-ai/openspec@1.14.3 archive stored-alias-default-headers -y`,
  then `npx -y @fission-ai/openspec@1.14.3 validate --specs --strict`.
- One commit, staging explicit paths; the full suite runs in CI after the push.
