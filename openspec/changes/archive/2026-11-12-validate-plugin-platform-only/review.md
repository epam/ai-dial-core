# Review: validate-plugin-platform-only

## What was built

- `ApplicationService.pluginOutsidePlatformError` holds the rule "Plugins are for platform deployments" for a write;
  `requireValidInterfaces` (`PUT`, `/v1/admin/apply`) and `ConfigValidationService` (`/v1/admin/validate`) both use it
  (design D1).

## Tests

- Checkstyle passes.
- `EmbeddedOriginApiTest` (45 tests, the new `testCustomApplicationRefusedByValidate` included) and
  `AdminValidateApiTest` pass.

## Not done

- Nothing.
