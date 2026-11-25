# Review: stored-alias-default-headers

## Implementation

- Tasks done: 7. Deferred: none.
- Files changed: `server/.../alias/StoredAliases.java`, `server/.../controller/ConfigResourceController.java` (OpenAPI annotation text only), `docs/dynamic-settings/aliases.md`, `StoredAliasesTest`, `AliasPassesTest`, `AliasPublicApiTest`, `AliasPlatformApiTest`, `design.md`, `tasks.md`; in fix round 1 also `config/.../Deployment.java` and `proposal.md`.
- Tests added or changed:
  - `StoredAliasesTest`: `keepsDefaultHeadersInEitherSpelling`, `nullDefaultHeadersReadAsNone`, `defaultHeadersMustBeHeaderNamesToValues`, `toolsetAliasRefusesDefaultHeaders`, `blobHasDefaultHeadersAfterUserRolesUnlessEmptyOrToolset`.
  - `AliasPassesTest.modelViewTakesDisplayFromTheAliasAndPricingFromTheTarget`: the alias now has `defaultHeaders {X-Team: b}`; the view still has none.
  - `AliasPublicApiTest`: `storedAliasDefaultHeadersReachTheTargetNotTheListing`, `toolsetAliasRefusesDefaultHeaders`; `invalidBodiesAreRefusedAndNothingIsWritten` adds `defaultHeaders: ["X-Team"]`.
  - `AliasPlatformApiTest.platformAliasDefaultHeaderReachesTheTargetAfterReload`.
- Design drift: none against base 3eccd2603; the cited lines (ConfigResourceController.java:1156, :1187, :1878-1881, ApplyDefaultDeploymentSettingsFn.java:75, MergedConfigStore.addBlobEntity, AliasPasses.build) match the code. Adjustments recorded in design.md "Implementation notes": the toolset refusal message is "defaultHeaders are not supported for an alias of type toolset"; the null handling of decision 4 was first a helper `StoredAliases.readNullHeadersAsNone`, then moved in fix round 1 to `@JsonSetter(nulls = Nulls.SKIP)` on `Deployment.defaultHeaders`.
- `docs/open_api_core.yaml` not regenerated locally; the pr.yml workflow regenerates it (task 1.5).

## Verification

### Run 1 (after implementation): not passed

- checkstyleMain checkstyleTest (JDK 25.0.3): passed, 0 errors.
- `:server:test` with StoredAliasesTest (13), AliasPassesTest (11), AliasPublicApiTest (22), AliasPlatformApiTest (13), AliasApiTest (25): 84 passed, 0 failed. Before the code change 5 of 24 unit tests failed, as intended.
- `openspec validate stored-alias-default-headers --strict`: valid.
- git status: failed. 13 empty untracked files at the repo root (names are shell-command fragments, dated 2026-11-10 to 2026-11-18 01:14, before the change folder existed) belonged to no task.
- Cost claim: design.md claims no new Core pass, body copy, body parse, Redis or blob round trip. Confirmed: `fromRequest` (PUT only) adds checks on the already-parsed Alias; `parse` (per call) gained one O(1) null check; `toJson` (PUT, GET, reload) adds one `valueToTree` when non-empty and not a toolset. AliasPasses, AliasService, MergedConfigStore unchanged.

### Run 2 (after fix round 1): passed

- checkstyleMain checkstyleTest (`--rerun`, JDK 25, Gradle 9.9.1): passed.
- `:config:test`: all 26 classes passed (DeploymentTest 24/24); up-to-date reuse of the same-day run on the same inputs.
- `:server:test`: StoredAliasesTest 13, AliasPassesTest 11, AliasPublicApiTest 22, AliasPlatformApiTest 13, AliasApiTest 25; 0 failures.
- `openspec validate --strict`: valid.
- git status: only the change folder is untracked.
- Cost claim: confirmed. The per-call path (AliasService.java:111 -> `StoredAliases.parse`) is unchanged by the diff; new code sits in `fromRequest` (admin PUT, ConfigResourceController.java:1790) and `toJson` (ConfigResourceController.java:1797, 1881; MergedConfigStore.java:1753). The `Deployment.java` annotation affects deserialization only.

The final verification equals run 2.

## Review findings

| Lens | Severity | File:line | Finding |
| --- | --- | --- | --- |
| simplicity | medium | server/src/main/java/com/epam/aidial/core/server/alias/StoredAliases.java:185 | `readNullHeadersAsNone` patched a JSON null `defaultHeaders` in two readers plus a `!= null` check in `toJson`; the platform reload and config-file deployments were not covered (NPE at ApplyDefaultDeploymentSettingsFn.java:75, RouterDecisionClient.java:198). Fix at the source with `@JsonSetter(nulls = Nulls.SKIP)` on `Deployment.defaultHeaders`, as in Publication.java:31. |
| simplicity | low | server/src/test/java/com/epam/aidial/core/server/alias/StoredAliasesTest.java:100 | `defaultHeadersMustBeHeaderNamesToValues` asserts the codec's "Failed to parse Alias" prefix, an implementation detail; AliasPublicApiTest:113 already covers the 400. |

## Fix round 1

- Fixed: simplicity/medium. `@JsonSetter(nulls = Nulls.SKIP)` added to `Deployment.defaultHeaders`; `readNullHeadersAsNone` deleted; readers back to plain `treeToEntity`/`treeToValue`; `!= null` dropped from `toJson`; the `put.setDefaultHeaders(null)` test case dropped. tasks.md 1.2/1.4/1.6, design.md Implementation notes and the proposal's Touches line updated. Side effect: a config-file model, application or router, or a custom application blob, with `"defaultHeaders": null` used to fail every call and now calls with no default headers.
- Fixed: verification check 4. The 13 empty stray files at the repo root were deleted.
- Rejected: none.
- Not addressed in the workflow: simplicity/low (StoredAliasesTest.java:100); fixed after it, below.
- Still open after recheck (low): proposal.md:32. The wider null behaviour is in the proposal's Touches line and design.md Implementation notes only; the proposal's What Changes and design.md Deliberate differences describe the stored-alias scope only, decision 2 still quotes the removed `getDefaultHeaders() != null` condition, and no test covers the config-file reader with `"defaultHeaders": null`.

## After the workflow

- Fixed: simplicity/low. `defaultHeadersMustBeHeaderNamesToValues` asserts the `IllegalArgumentException` only.
- Fixed: recheck/low. The proposal's null bullet and design.md decision 4 and Deliberate differences describe
  the annotation's effect on every deployment; decision 2 quotes the current `toJson` condition. No config-file
  test added: `@JsonSetter(nulls = Nulls.SKIP)` is plain Jackson, which the config file's mapper honours, and the
  `parse` assertion in `nullDefaultHeadersReadAsNone` pins it for the blob mapper.

## Archive

- Archived 2026-11-18; `deployment-aliases`: 5 requirements modified. `validate --specs --strict` flagged
  "Content of an alias entry" over 500 characters after the merge (the review had folded the no-default-headers
  rule into it). Shortened in the main spec and in this delta: "nor any default headers", and the fallback sentence
  without repeating "the target's"; the scenario "Stored alias with default headers listed" keeps the detail.
  `validate --specs --strict` then passes except `resource-subscription`, whose placeholder Purpose predates this change.

## Outcome

- High/medium findings open: none.
- Low findings left: none (both fixed after the workflow).
- CI must still confirm: the full test suite (only checkstyle and the alias classes plus `:config:test` ran locally) and the regenerated `docs/open_api_core.yaml`.
