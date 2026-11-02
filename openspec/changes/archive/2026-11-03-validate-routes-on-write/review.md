# Review: validate-routes-on-write

A record of what was done and checked. It is not an independent or three-lens review.

## Shipped

- `ConfigPostProcessor.validateRouteInvariants(Route, warnings)` is new and calls `validateRouteUpstreams`, which only
  loses its `entityLabel` and `entityName` parameters (the label is always "Route", the name is `route.getName()`).
  `processRoutes` and `validateSingleRoute` call the new method, so
  the rebuild, the partial update and the
  gates share one entry point.
- `PUT` (`ConfigResourceController.checkRoute`, on the merged entity, before the blob write), `/v1/admin/validate` (the
  `Route` arm) and `/v1/admin/apply` (a `ROUTE` branch in `applyManagedEntity`, before the write) refuse a route upstream
  with an `authType` other than `API_KEY` and no `key`: `422` with `validationWarnings` on `PUT`, `FAILED` with `error`
  on validate and apply.
- `docs/dynamic-settings/routes.md` states the new answer next to the `authType` description.
- `docs/open_api_core.yaml` is unchanged: `saveRoute` already documents `422`.

## Checked

Run with JDK 21 from the Gradle cache and `--offline` (no GitHub Packages credentials on this machine).

| Check | Result |
|---|---|
| `ConfigPostProcessorTest`, `MergedConfigStorePartialUpdateTest` | pass, unchanged |
| `ConfigEntityWriteApiTest` (2 new tests) | pass |
| `AdminValidateApiTest` (1 new test) | 34/34 |
| `AdminApplyApiTest` (1 new test) | 39/39 |
| `ConfigRouteTest` | 4/4 |
| `checkstyleMain`, `checkstyleTest` | pass |
| `openspec validate validate-routes-on-write --strict` | valid |

Red check: with the `checkRoute` call disabled, `testRoutePutUpstreamWithoutKeyRejected` fails with `500` and the body
`Route 'routes/platform/test-route-no-key': authType 'BEARER' requires a key`, the message in issue #2123. The call was
restored afterwards.

Each spec scenario has a test: Bearer without a key and Custom header without a key (`testRoutePutUpstreamWithoutKeyRejected`),
Update without the key (`testRoutePutOmittedUpstreamKeyKeptOnUpdate`), Validate (`testValidateRouteUpstreamWithoutKey`),
Apply (`testApplyRouteUpstreamWithoutKeyFails`).

## Not covered

- The gate does not read `config.write.softValidation` or `config.onInvalidEntity`, so it does not depend on them. No
  test runs the new check under `skip`, and the change from `200` or `APPLIED` to a refusal under `skip` is stated in
  the proposal only.
- Task 3.1 was reworded: `validate --all --strict` still fails on the `resource-subscription` and `user-limits` specs
  (placeholder `Purpose`), which predate this change.
- The gate's message has no route name (the route has no name yet), while the rebuild's keeps it. The spec requires only
  `requires a key`.
- Leftover blobs written by earlier releases are not healed. That a route `DELETE` removes one is read from
  `handleDelete`, not tested.
- The full test suite was not run; CI runs it.
