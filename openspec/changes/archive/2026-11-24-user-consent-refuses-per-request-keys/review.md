## What shipped

`POST /v1/consent/{id}` with a per-request key answers `403` `User consent is not allowed for per-request keys` before
reading the body, and stores nothing. A user's JWT and API keys are served as before. No docs change: the endpoint's
OpenAPI annotation already lists `403`.

## Verification

- `ConsentApiTest.testAcceptConsent_RefusedToPerRequestKey` failed before the fix (`200` instead of `403`) and passes
  after it; it also checks, with `GET /v1/consent/secured-app` as the user, that nothing was stored.
- `./gradlew checkstyleMain checkstyleTest :server:test --tests "*ConsentApiTest"`: 6 tests, all pass.
- Other tests that accept consent use an admin JWT (`ToolSetApiTest`) or a project API key (`AliasApiTest`); the check
  only fires for per-request keys.

## Drift from design.md

None.

## Not done

None.
