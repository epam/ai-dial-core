# Review

## What shipped
`GET /v1/user/info` answers `project` for a JWT caller when the identity provider's `projectPath` resolved one; the
API-key answer is unchanged. Docs: the `projectPath` row in `README.md`, `routers.md`, `plugins.md` and the
`UserInfoResponse.project` description. `SpecMerger` keeps descriptions from the committed `docs/open_api_core.yaml`,
so the text is edited there, as #1738 did, and in the generator's `schemas/UserInfoResponse.yaml` so both agree.

## Verification
Checkstyle, `UserInfoApiTest`, `RouterApiTest` and `EmbeddedCallApiTest` pass; the two new user-info tests failed
before the code change. `validate user-info-jwt-project --strict` passes. `:openapi-generator:mergeOpenApiSpec` gives
a file identical to the committed `docs/open_api_core.yaml`, so CI's `replaceSpec` keeps the new description.

## Drift from design.md
None.

## Not done
None.
