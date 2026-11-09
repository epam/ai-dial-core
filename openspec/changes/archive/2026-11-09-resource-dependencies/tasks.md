# Tasks

The feature shipped as the PR series #1941 to #1948, merged into #1940; this change records it. Every task is done
in the code on the branch; each names the tests that verify it.

## 1. Definition model (#1931)

- [x] 1.1 `ResourceDependency` (`kind`, `linkId`, `target.url`, `access`, `required`) and `Application.resourceDependencies`
  with both JSON spellings, kept by the copy constructor. Verify with `ResourceDependencyTest` (config module): parsing
  of both spellings, defaults, round trip, copy. (Scenarios: Valid section stored and returned, Config-file spelling.)
- [x] 1.2 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :config:test --tests "*ResourceDependencyTest"`;
  both pass.

## 2. Write-time validation (#1932, #1937)

- [x] 2.1 `ResourceDependencyValidator`: `validateShape` / `findShapeError` (cap of 100, `kind`, `link_id`, `target.url`,
  `access`) and one target parser shared with resolution (`checkTarget`, `resolveTarget`): declarable types, the
  placeholder in the bucket segment only, no `users/...`, no type root or bare bucket, no empty, `*`, `.` or `..`
  segment, strict decoding by `ResourceDescriptorFactory`. Verify with `ResourceDependencyValidatorTest` (accepted and
  rejected targets, cap, null entry, `share`, encoded tokens). (Scenarios: Accepted targets, Rejected targets,
  Placeholder alone is not a target.)
- [x] 2.2 Call the checks from `ResourceController.validateCustomApplication` (author gate first, for callers without
  explicit admin access, then shape), from the platform config entity `PUT` before the bucket locks, and from
  `ConfigApplyService.applyApplication` and `ConfigValidationService.validateOnly`. Verify with
  `CustomApplicationApiTest.testResourceDependenciesSectionWriteTimeValidation`,
  `CustomApplicationApiTest.AllowUserResourceDependenciesOn.testUserAuthoredDependenciesWhileFlagOn`,
  `CustomApplicationApiTest.AdminRulesUnconfigured.testUserAuthoredDependenciesRejectedWithoutAdminRules`,
  `PlatformAppToolsetApiTest.testMalformedResourceDependenciesSectionRejected`,
  `AdminApplyApiTest.testApplyPrecheckRejectsInvalidResourceDependencies`,
  `AdminApplyApiTest.testApplyPrecheckFalseFailsInvalidResourceDependencies`,
  `AdminValidateApiTest.testValidateRejectsInvalidResourceDependencies`. (Scenarios: Malformed entries, Platform config
  entity, Apply with precheck, Apply without precheck, Validate, User with the setting off, Admin in their own bucket, User with the setting on, No admin
  rules.)
- [x] 2.3 Add `applications.allowUserResourceDependencies` (default `false`) to the built-in and sample settings, read in
  `AiDial`.
- [x] 2.4 Run checkstyle and `./gradlew :server:test --tests "*ResourceDependencyValidatorTest" --tests
  "*CustomApplicationApiTest" --tests "*PlatformAppToolsetApiTest" --tests "*AdminApplyApiTest" --tests
  "*AdminValidateApiTest"`; all pass.

## 3. Admin consent and its status (#1933, #1936)

- [x] 3.1 `ResourceTypes.ADMIN_CONSENT` (not mapped in `of()`), `ConsentGrant`, `AdminConsentStatus`,
  `GrantAdminConsentRequest`; `ConsentService.grantAdminConsent` (reviewed list must equal the current one, else `409`),
  `withdrawAdminConsent`, `describeAdminConsent`, `isAdminConsented` (exact snapshot equality). Verify with
  `ConsentServiceTest` (`testGrantAdminConsent_*`, `testIsAdminConsented_*`, `testDescribeAdminConsent_*`,
  `testWithdrawAdminConsent_ReturnsTheWithdrawnGrantForTheAudit`, `testRecordWithoutSnapshotFailsClosedAndCanBeWithdrawn`,
  `testUnreadableAdminConsentRecordFailsClosedAndCanBeWithdrawn`,
  `testAdminConsentRecordIsKeyedByTheResolvedApplicationsCanonicalName`).
- [x] 3.2 `RouteTemplate.ADMIN_CONSENT` and the `GET`, `POST`, `DELETE` routes; `ConsentController` admin operations with
  the admin check first and their own error handler. Verify with `ResourceDependencyConsentApiTest`:
  `testAdminCanGrantAndWithdrawConsent`, `testGrantApprovesOnlyTheReviewedDeclaration`,
  `testOnlyAdministratorsMayConsent`, `testAdminConsentIsRefusedToPerRequestKeys`,
  `AdminRulesUnconfigured.testAdminConsentIsRefusedWithoutAdminRules`, `testUnknownDeploymentIsNotFound`,
  `testAdminManagesConsentOfConfigAppOutsideItsUserRoles`, `testAdminConsentStatusReadsTheTriState`,
  `testAdminConsentStatusForNonDeclarersAndNonAdmins`, `testApiKeyAdminGrantIsStampedAndAuditedWithTheKeysProject`,
  `testAdminConsentRecordIsUnreachableViaTheResourceApi`, `testAdminConsentSuffixOnTheUserConsentPathIsUserConsent`.
  (Scenarios: Non-administrator, Per-request key of an administrator's call, No admin rules, Unknown id, Config
  application outside the admin's roles, Reviewed list matches, List changed since the review, Application without a section (both), API key administrator, Record out of
  the Resource API's reach, Tri-state, Custom application in an admin-consent path, Admin consent, Admin-consent suffix
  on a user consent path.)
- [x] 3.3 The approval follows the application: `ApplicationService.deleteApplication` deletes the record,
  `ApplicationService.moveAdminConsent` is called by `ResourceOperationService` on move and by `PublicationService` on
  approval, and `ResourceOperationService.copyResource` deletes the destination's record (found when the change was
  checked against the design documents; mutation: without the delete only the copy test fails). Verify with
  `testDeletingTheApplicationDeletesItsAdminConsent`,
  `testDeletingPlatformApplicationDeletesItsAdminConsent`, `testMovedApplicationKeepsItsApprovalAndCopyStartsUnapproved`,
  `testApplicationCopiedOverAnApprovedOneStartsUnapproved`,
  `UserAuthoredApplications.testPublishedUserApplicationIsApprovedAndResolves`. (Scenarios: Delete and create again,
  Move and copy, Moved over an approved application, Copied over an approved application, Publication approved,
  Application in another user's bucket.)
- [x] 3.4 The user consent document of a declaring root application lists the declaration and is not auto-accepted.
  Verify with `ConsentServiceTest.testBuildConsent_IncludesDeclaredResourcesRegardlessOfConsentRequiredFlag` and
  `ResourceDependencyConsentApiTest.testAdminCanGrantAndWithdrawConsent`. (Scenario: Declaration in the consent document.)
- [x] 3.5 Run checkstyle and `./gradlew :server:test --tests "*ConsentServiceTest" --tests
  "*ResourceDependencyConsentApiTest"`; both pass.

## 4. Resolution at request start and at every hop (#1934, #1938)

- [x] 4.1 `AccessService.lookupInitiatorPermissions` with its own rule list (initiator's bucket, shared, public); union of
  covering entries in `findPermissions`. Verify with `AccessServiceTest` (`testLookupInitiatorPermissions_*`,
  `testLookupPermissions_PerRequestGrantsUnionAcrossCoveringEntries`, `testLookupPermissions_UnaffectedByTheNewRuleList`), and
  over HTTP `ResourceDependencyApiTest.testAdminStandingDoesNotWidenTheReach` (mutation: with the full rule list the
  public write is granted and the test fails). A folder spanning org units needs the initiator's read in each:
  `ResourceDependencyOrgUnitApiTest` (`testFolderSpanningAnUnreadableUnitIsNotGranted`,
  `testChainedHopJudgesTheUnitsByTheInitiator`; mutation: without the check the app reads the unit file, 200).
  (Scenarios: Administrator standing does not reach, Folder spanning an unreadable unit, Covering entries add up.)
- [x] 4.2 `ResolveResourceDependenciesFn` (no-op without a section, approval check, reach check, copy-on-write merge,
  `required` → `403`, `500` for an unexpected failure), appended to the chat chain. Verify with
  `ResolveResourceDependenciesFnTest` and `ResourceDependencyResolutionApiTest`:
  `testChatPathBakesConsentedDependencyGrantIntoThePerRequestKey`, `testAppWritesSkillIntoUsersSkillsThroughItsKey`,
  `testAppWritesProjectIntoUsersProjectsThroughItsKey`, `testChainedHopResolvesItsOwnDeclarationAgainstTheInitiator`,
  `testChainedHopFailsWhenRequiredDependencyIsUnreachable`, `testChainedHopIsSandboxedFromTheCallersOwnGrants`; and
  `ResourceDependencyApiTest`: `testRequiredDependencyWithoutConsentFailsTheCall`,
  `testOptionalDependencyWithoutConsentDegradesSilently`, `testConsentedRequiredDependencyGrantsAndFailsAfterWithdrawal`,
  `testDeclarationChangeInvalidatesTheGrant`, `testPublicTargetGrantFollowsTheUsersOwnReach`,
  `testInterceptorProtectedAppStillResolvesOnTheRootCall`. (Scenarios: Chat completion receives the grant, Granted
  dependency in the key, Skill and project written through the key, Public target as far as the user reaches,
  Interceptor in front of the application, Required entry without approval, Optional entry without approval, List
  changed after approval, Withdraw, then call, Chained call resolves the callee's list, Required entry of the callee
  unreachable, Caller's grants do not widen the callee.)
- [x] 4.3 Run checkstyle and `./gradlew :server:test --tests "*AccessServiceTest" --tests
  "*ResolveResourceDependenciesFnTest" --tests "*ResourceDependencyResolutionApiTest" --tests
  "*ResourceDependencyApiTest"`; all pass.

## 5. Messages, Responses and MCP mint sites (#1935, #1939)

- [x] 5.1 Append the resolver to the Responses and Messages chains (`count_tokens` inherits the Messages chain); keep
  `BuildUpstreamCacheFn` before `CollectDeploymentsFn` in `MessagesController`. Verify with
  `ResourceDependencyApiTest.testConsentedFlowOnMessagesAndResponses`,
  `ResourceDependencyApiTest.testRequiredDependencyWithoutConsentFailsMessagesAndResponses`,
  `ResourceDependencyApiTest.testCountTokensResolvesLikeMessages` (mutation: without the resolver in the `count_tokens`
  chain only this test fails) and `MessagesCacheWiringTest.testMessagesController_keepsTheChatAndResponsesOrder`.
  (Scenarios: Messages and Responses, Token counting, Required entry without approval.)
- [x] 5.2 MCP: the key is created before the chain for an application with `forwardPerRequestKey` and stored after it;
  `McpUpstreamAuthInjector` reuses an assigned key; body and chain on the task executor; the resolver in the application
  MCP chain. Verify with `McpUpstreamAuthInjectorTest` (`*_reusesAssignedKey`, `*_mintsOnceWhenNoKeyAssigned`) and
  `ResourceDependencyResolutionApiTest`: `testMcpMintSiteBakesConsentedDependencyGrantIntoThePerRequestKey`,
  `testMcpMintSiteSurvivesAnUpstreamTrailingSlashRedirect`, `testMcpMintSiteFailsTheCallWhenRequiredDependencyIsUnresolvable`,
  `testMcpMintSiteGrantsNothingWhenTheAppDoesNotForwardThePerRequestKey`,
  `testMcpMintSiteNamesTheCauseWhenRequiredDependencyCannotBeDelivered`,
  `testMcpMintSiteNonJsonPostGetsUsableKeyWithoutGrants`, `testMcpChainedHopResolvesItsOwnDeclaration`,
  `testMcpChainedHopIsSandboxedFromTheCallersOwnGrants`. (Scenarios: Grant on the MCP key, Redirect keeps the key, Key
  forwarding off, Non-JSON request.)
- [x] 5.3 The chat chain keeps its error mapping: `DeploymentPostControllerTest` (`testHandleRequestBody_*`).
- [x] 5.4 Run checkstyle and `./gradlew :server:test --tests "*McpUpstreamAuthInjectorTest" --tests
  "*MessagesCacheWiringTest" --tests "*DeploymentPostControllerTest" --tests "*ResourceDependencyApiTest" --tests
  "*ResourceDependencyResolutionApiTest"`; all pass.

## 6. Audit

- [x] 6.1 `ResourceDependencyAuditLog` on `DIAL_RESOURCE_DEPS_AUDIT`; outcome and actor helpers moved to
  `AuditLogSanitizer`, used by `ExternalServiceAuditLog` too. Verify with `AuditLogSanitizerTest`,
  `ResourceDependencyApiTest.testAuditEventsCarryTheDesignFields` and
  `ResourceDependencyConsentApiTest.testConsentDecisionsAreAudited`.

## 7. Documentation

- [x] 7.1 `docs/dynamic-settings/applications.md` (the section), `docs/dynamic-settings/admin_consent_api.md` (new),
  `docs/error_codes.md`, the README setting row, and the controller annotations with hand-written descriptions in
  `docs/open_api_core.yaml`. Verify with `./gradlew :openapi-generator:checkOpenApiDiff` and by reading the docs against
  the specs.

## Workflow follow-up

- Record `review.md`, archive with `npx -y @fission-ai/openspec@1.14.3 archive resource-dependencies -y`, write the
  Purpose of the new `resource-dependencies` spec, then run `validate --specs --strict`.
