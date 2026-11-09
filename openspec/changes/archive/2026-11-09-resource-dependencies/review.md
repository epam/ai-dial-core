# Review: resource-dependencies

## What shipped

The change records the EPIC #1930 series (#1941 to #1948), merged into #1940. The code was written before the spec-driven
process (#2168, 2026-10-23); this folder states its behaviour against `development`.

- `resource_dependencies` on applications: `ResourceDependency`, `Application.resourceDependencies`, both spellings.
- Write-time checks in `ResourceDependencyValidator`: author gate (`applications.allowUserResourceDependencies`,
  explicit admin access), then shape and one target grammar shared with resolution. Called from
  `ResourceController.validateCustomApplication`, the platform config entity `PUT` (before the bucket locks),
  `ConfigApplyService` and `ConfigValidationService`.
- Admin consent: `GET`, `POST`, `DELETE /v1/admin-consent/{id}` in `ConsentController`, records in
  `ResourceTypes.ADMIN_CONSENT` (public bucket, not reachable through the Resource API), bound to the exact list; the
  approval is deleted with the application and follows it on move and publication approval.
- Resolution: `ResolveResourceDependenciesFn` on chat, Responses, Messages (`count_tokens` included) and application MCP;
  `AccessService.lookupInitiatorPermissions`; grants merged into the per-request key copy-on-write; union of covering
  entries in `findPermissions`.
- MCP: key created before the chain when forwarded, reused on a redirect (also by the toolset proxy and tools listing),
  body and chain on the task executor.
- User consent document of a declaring application lists the declaration and is not auto-accepted.
- Audit on `DIAL_RESOURCE_DEPS_AUDIT`; shared `AuditLogSanitizer`.
- Docs: `applications.md`, `admin_consent_api.md`, `error_codes.md`, README, OpenAPI descriptions.

## Drift from design.md

design.md was written from the code as merged. Decisions that changed during the series (the route, the target
grammar, the field name `url`, the approval moving with the application) are recorded there in their final form, and
the departures from the series' design documents (grants in the key's own `perRequestSharedResources`, the union of
covering entries) are named in decisions 7 and 8.

Checking the change against those documents found one defect in the code: a copy with `overwrite` onto an approved
application kept the approval for the copied content. `ResourceOperationService.copyResource` now deletes the
destination's record, as decision 9 and the docs say.

## Verification

All run locally on JDK 21:

- `./gradlew build --continue` on `adedcea8b`: 3286 tests, 3 failed (`ConfigModelListTest`,
  `ConfigResourceConditionalHeaderTest`, `OrgUnitApiTest`), all three outside the feature; each class passed when rerun
  alone. Checkstyle and `:openapi-generator:checkOpenApiDiff` passed.
- Two scenarios had no test over HTTP and got one, in `ResourceDependencyApiTest` (11 tests, passed):
  - `testCountTokensResolvesLikeMessages`: `count_tokens` fails on an unapproved `required` entry and hands the app a
    key with the grant once approved. Mutation: the resolver filtered out of the `count_tokens` chain only; this test
    fails (`200` instead of `403`), the Messages and Responses tests still pass.
  - `testAdminStandingDoesNotWidenTheReach`: an admin who may write `prompts/public/dep-smoke/` calls an app declaring it
    with `write`; the app's key writes the admin's own folder and gets `403` in the public one. Mutation:
    `lookupInitiatorPermissions` on the full rule list; this test fails (`200` instead of `403`), and so does
    `testInterceptorProtectedAppStillResolvesOnTheRootCall`.
- `testApplicationCopiedOverAnApprovedOneStartsUnapproved` (`ResourceDependencyConsentApiTest`, 18 tests with the two nested classes, passed):
  copying an application with the same list and another endpoint over an approved one leaves it unapproved. Mutation:
  without the delete in `copyResource` only this test fails (`consented: true`).
- `ResourceDependencyOrgUnitApiTest` (2 tests, passed): a dependency on `files/public/` is not granted to a user without
  a role in a unit under it, directly or on a chained hop, and is granted once the user reads every unit (audit lines).
  Found by the final security review; mutation: without the check the app reads `files/public/Acme/Risk/r.txt` (200).
- `npx -y @fission-ai/openspec@1.14.3 validate resource-dependencies --strict`: valid.

## Not done

- Covered only below the HTTP boundary: covering entries add up, shares counting as reach, the cap of 100 entries,
  withdrawing when there is no approval, malformed entries of a config-file application (`AccessServiceTest`,
  `ResourceDependencyValidatorTest`, `ConsentServiceTest`, `ResolveResourceDependenciesFnTest`).
- No test: resolution on `/completions`, `/embeddings`, `/openai/v1/chat/completions` and `/openai/responses` (same
  chains as the tested routes); the sites that do not resolve (application routes, feature endpoints, MCP resources,
  toolsets, response items); a user consent asked again after the declaration changes.
- Where a denial and "the resolver never ran" give the same status (a public target the user cannot write, the
  caller's grants on a chained hop), the tests tell them apart by the audit lines.

Open with the team (not part of this change): admin, global-reader and review standing not counting as reach; org-unit
roles; the approval not bound to the endpoint; whether `count_tokens` should resolve at all.
