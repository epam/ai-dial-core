# Review: exempt-system-import-body-limit

## What shipped

- `Proxy.handleRequest` skips the `resources.maxSize` check when `isSystemImport(request)`: `POST` and the raw path
  matching `RouteTemplate.ADMIN_SYSTEM_IMPORT`, with a guard for a null path. The `multipart/form-data` branch and every
  other route are unchanged.
- Test: `ProxyTest.testHandle_SystemImportSkipsBodySizeLimit` sends a 1 GiB `application/zip` body to the import route
  and checks that it is not answered `413` and reaches `request.pause()`, the step before authorization. The existing
  `testHandle_ContentBodyIsTooLarge` and `testHandle_ContentBodyIsTooLarge_Multipart` still pass.
- `docs/error_codes.md`: the `413` section says `maxSize` does not apply to the import archive.

## Checks

- `./gradlew checkstyleMain checkstyleTest`: passes.
- `./gradlew :server:test --tests "*ProxyTest"`: passes.
- `npx -y @fission-ai/openspec@1.14.3 validate exempt-system-import-body-limit --strict`: valid.

## Drift from design.md

None.

## Known limits

- The check runs before authentication, so a caller without credentials can send a body of any size to this route until
  authentication answers `401`. No worse than today: a request without `Content-Length` is already checked as 1024 bytes.
- A `Content-Length` above 2 GiB does not parse as an `int` and is checked as 1024 bytes on every route. Unchanged.

## Review

Part of pull request #2213, not yet reviewed there. Verification on a real archive is the review environment import.
