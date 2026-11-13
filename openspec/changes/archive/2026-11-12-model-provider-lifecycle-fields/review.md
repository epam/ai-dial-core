# Review: model-provider-lifecycle-fields

A record of what was done and checked. It is not an independent review. Issue #2275.

## Shipped

- `Model` has `provider`, `deprecationDate` and `endOfService` (snake_case aliases for the dates); `ModelData` lists them
  as `provider`, `deprecation_date` and `end_of_service`; `ModelController` and `AliasPasses.modelView` fill them.
- Dates are `String`, because Core has no Jackson JSR-310 module. Nothing reads or validates the values.
- Docs: `models.md`, `open_api_core.yaml`, `sample/aidial.config.json`.
- Tests: `ListingTest` (3), `ModelWriteApiTest` (1), `AliasApiTest` (3 added assertions).

## Checked

| Check | Result |
|---|---|
| `openspec validate model-provider-lifecycle-fields --strict` | valid |
| `checkstyleMain`, `checkstyleTest`, the three test classes | not run: no JDK 21 or GitHub Packages credentials on the machine that wrote the change. CI runs them. |

## Not covered

- The tests were never seen failing before the change and passing after it.
- The exact JSON text asserted in `ModelWriteApiTest` (`"provider":"AWS"`, no space after the colon) assumes the
  Configuration API's compact serialization, as the existing `displayName` assertion in the same class does.
- Application, router and toolset schemas are unchanged by design.
