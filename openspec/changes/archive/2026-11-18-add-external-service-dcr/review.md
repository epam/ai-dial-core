# Review

## What was reviewed

The whole change against `development` at `ccc370e47` (after the rebase onto #2281): code, tests, OpenAPI, docs and
this change folder. Two rounds by an independent reviewer that had only the diff, the code style guide and a written
contract of the intended behaviour (16 rules, the accepted trade-offs and what is out of scope); every finding needed a
reproduction run against the checkout.

## Fail-before / pass-after

- `ResourceAuthSettingsServiceTest` and `DynamicResourceRegistrationStrategyTest`: do not compile on the base (they use
  `RegistrationPolicy` and the new `processResourceAuthSettings` overload); pass on the branch.
- `ExternalServiceDcrApiTest`: 25 methods were run on the base with `discovery_url` removed from the test config,
  since the base rejects the unknown field at startup. 23 fail on the base and pass on the branch. Seven of those first
  passed on the base only because it rejects any `discovery_url`; each now asserts its own Core message and fails there.
  The other two are pure guards, passing on both: a static client without `discovery_url` makes no outbound request,
  and config-file services are never registered. Two methods added during the review
  (`testBlankRedirectUriAndDiscoveryUrlCountAsAbsent`, `testSwitchToOauthNeedsClientSecret`) were not run on the base.
- `PublicationApiTest.testUpdatePublicationWithPkceExternalServiceKeepsVerifier`: fails before the round-1 fix (400),
  passes after.
- `ResourceDependencyOrgUnitApiTest.testUnitEditorDescriptionEditKeepsApprovalOfAppWithExternalServicePkce`: pure guard
  (a description edit keeps the resource-dependency approval).

## Findings fixed

- Round 1: updating a publication failed (`400 Field 'CODE_VERIFIER' is forbidden`) for an application whose external
  service has a PKCE pair. The publication link rewrite reads the application with its secrets decrypted, which now
  includes the verifier, and writes it back. A body verifier equal to the stored one now counts as unchanged; a
  different one is still rejected. Copy, Config API and admin apply were checked and do not take this path.
- While strengthening the tests: the no-client, no-URL error said "requires an endpoint"; it now names `discovery_url`.
- Notes from both rounds: a blank `discovery_url` is stored as absent on a create and on a switch away from OAUTH; a
  blank `redirect_uri` in a registration body counts as absent; a comment no longer claims every service is validated
  before any registration request.

## Findings accepted (contract amended)

- Switching an existing service to OAUTH with a client but no `client_secret` is now rejected, as a create is and as for
  toolsets; `development` accepted it on a switch only.
- In a registration body a blank `code_challenge_method` counts as absent, so PKCE follows the provider; "blank means no
  PKCE" applies to clients registered by hand.
- Toolset side effects of the shared code, both bug fixes: a blank `code_challenge_method` saves without PKCE instead of
  failing, and a blank `redirect_uri` is no longer added to the registration request.

## Test evidence

Checkstyle; the credentials module suite; `ExternalServiceDcrApiTest`, `PublicationApiTest`,
`ExternalServiceCredentialsApiTest`, `ExternalServiceOboCredentialsApiTest`, `ToolSetApiTest`, `ToolSetRepairApiTest`,
`ResourceDependencyOrgUnitApiTest`, `InvalidEntityApiTest`, `AdminApplyApiTest`: 717 tests, 0 failures. One
`ToolSetApiTest` method failed once in a combined run and passed alone and in its own class run (known cross-test
flakiness of `:server:test`). `./gradlew :openapi-generator:validateOpenApiSpec` passes.

## Deferred

- The full `:server:test` suite runs in CI.
- The 0.50 patch is a separate backport: `release-0.50` does not have #2281 and differs in most touched files.
- Out of scope, kept for later: per-sign-in `state` and PKCE (one verifier per resource today), the redirect-URI
  allow-list for toolsets and at sign-in (separate change), and the admin panel support (requirements handed to the UI
  team).
