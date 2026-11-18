# Tasks

Spec scenario to test. `FieldSelectionTest` (new, unit): "Same name twice", "Selection on a scalar", "Unbalanced
parenthesis", "Empty name", "Too long", "Too deep", "Empty value", plus the union rules of design D2 (`*,features(rate)`
keeps all of `features`; `features,features(rate)` likewise) and `*` equals no selection. `DeploymentApiTest`
(integration, existing fixtures, no new class): "Top-level fields only", "Several names", "Selection without id and
object", "Nested object", "Misspelled name", "Field of another kind", "Unbalanced parenthesis" (the `400` end to end),
"Empty value", "With interface type", "Other endpoints" on `dial-config/deployment-interfaces-listing.json`;
"Wildcard over a map" on `dial-config/deployment-interface-configs.json` (`gpt-5.4-mini`, three `interface_configs`
entries). "No parameter": the existing `testListDeployments`, which must pass unchanged.

## 1. FieldSelection helper

- [x] 1.1 Add `server/.../util/FieldSelection` (design D2, D3, D5): `parse(String)` returning no selection for null,
  empty or blank, throwing `IllegalArgumentException` (message with the position) for unbalanced parentheses, an empty
  name, a parenthesis with no name before it, text after `)` other than `,` or `)`, a value over `MAX_LENGTH = 1024` bytes
  and nesting over `MAX_DEPTH = 5`; a merged member tree with the union rule; `serialize(Object)` writing the value with a
  property filter that keeps the selected members and the caller's always-kept names (`id` and `object`) at the top
  level, applies nested selections to objects, maps and the object elements of arrays, leaves a value with no members
  unchanged, and keeps member order. Tests first in `FieldSelectionTest` (the unit scenarios
  above, one assertion group each, and `*` over the serialized fixture-like `DeploymentData` rows equals the
  unselected tree). Verify `./gradlew :server:test --tests "com.epam.aidial.core.server.util.FieldSelectionTest"`
  passes.
- [x] 1.2 `./gradlew checkstyleMain checkstyleTest` and the test class of 1.1 pass.

## 2. The parameter on the listing

- [x] 2.1 `DeploymentController.listDeployments` (design D4): parse `fields` after `build` and before `execute`, answer
  `400` with the exception message on `IllegalArgumentException`; with a selection, respond with `selection.serialize`
  of the listing and the JSON content type; without one, leave the current `respond` call untouched.
  Tests first in `DeploymentApiTest`: one method on `deployment-interfaces-listing.json` for the scenarios of the
  header ("Top-level fields only", "Several names", "Selection without id and object", "Nested object", "Misspelled
  name", "Field of another kind", "Unbalanced parenthesis", "Empty value", "With interface type", "Other
  endpoints"), one method on `deployment-interface-configs.json` for "Wildcard over a map". Verify
  `./gradlew :server:test --tests "com.epam.aidial.core.server.DeploymentApiTest"` passes, including the unchanged
  `testListDeployments`.
- [x] 2.2 Document the parameter: a `DEPLOYMENT_FIELDS` constant in `OpenApiDescriptions` (syntax in one example,
  snake_case wire names, unknown names ignored, `id` and `object` always returned, limits) and an
  `@ApiParameter(name = "fields", in = QUERY, schema = String.class)` on `listDeployments`. Verify `./gradlew
  replaceSpec` runs and the regenerated `docs/open_api_core.yaml` shows the parameter on `listDeployments` (CI commits
  that file; the merge keeps the committed description of an existing parameter, so delete it from the file before
  `replaceSpec` when the text changes later). No `docs/*.md` describes `GET /v1/deployments`, so no other doc changes.
- [x] 2.3 `./gradlew checkstyleMain checkstyleTest` and the test classes of 1.1 and 2.1 pass.

## 3. Final checks

- [x] 3.1 Measure the cost claim of design "Per-request effect" and record the numbers in `review.md`: response bytes
  of `GET /v1/deployments` on `deployment-interfaces-listing.json` with and without `fields=id,display_name`; the time
  of the property filter against `writeValueAsString` of the full list, and against converting to a tree and pruning,
  for 5,000 synthetic `ModelData` rows, in a throwaway test that is not committed. If the filter costs more than the
  alternatives, record that in `design.md` before changing it.
- [x] 3.2 `npx -y @fission-ai/openspec@1.14.3 validate deployment-fields-selection --strict` passes, and
  `validate --all --strict` adds no failure (the known placeholder Purpose of `spec/resource-subscription` is the
  only one it reports).

## Workflow follow-up

- Write `review.md` in the change folder, then archive with `npx -y @fission-ai/openspec@1.14.3 archive
  deployment-fields-selection -y` and run `validate --specs --strict`.
- Commit once, staging explicit paths.
