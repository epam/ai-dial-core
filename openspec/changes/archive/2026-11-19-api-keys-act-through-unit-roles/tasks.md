# Tasks

## 1. API keys act through unit roles

- [x] 1.1 Compare `design.md` (Context, decisions 1-3) with `OrgUnitSubjects`, `OrgUnitGrants`, `OrgUnitApprovals`,
      `OrgUnitService`, `OrgUnitController` on the branch; record any drift in `design.md`
- [x] 1.2 Integration tests in `OrgUnitKeysApiTest`, failing before 1.4: "API key writes through its role"
      (`testApiKeysWriteThroughTheirConfiguredRoles`, replacing `testApiKeysReadThroughTheirConfiguredRoles`),
      "API key approves into its unit" (`testApiKeysApproveIntoTheirUnit`), "API key manages role assignments"
      (`testApiKeysManageRoleAssignments`), "API key lists its units and the units above"
      (`testApiKeysListTheirUnitsAndTheUnitsAbove`); "Application stays read-only" keeps
      `testApplicationsReadThroughTheirUsersRoles` and `OrgUnitUserOrgUnitsApiTest.testApplicationsHaveNoUnits`;
      `OrgUnitApprovalsTest.testApplicationsAndSwitchedOffNeverMatch` and `OrgUnitServiceTest.testApplicationsHaveNoUnits`
      drop the API-key cases and the "not a person" wording;
      "API key subject refused" (`testApiKeySubjectIsRefused`)
- [x] 1.3 Unit test in `OrgUnitSubjectsTest`: an API key (no claims, no per-request key) holds the roles a `roles`
      subject gives it; a per-request key holds none and reads with its user's
- [x] 1.4 `OrgUnitSubjects`: `isPerson` becomes `isRoleHolder` (no per-request key); `heldRoles` uses it; rename
      the callers in `OrgUnitGrants`, `OrgUnitApprovals`, `OrgUnitService`, and fix the Javadoc that says only
      people hold roles
- [x] 1.5 `docs/org-units.md`: API keys act through roles like people (subjects, ancestors, write/approve note,
      `GET /v1/user/role-assignments`); a `TRUE` subject on `roles` gives every API key the role's full rights
- [x] 1.6 Measurement: `OrgUnitServiceTest.testApiKeyReadsEveryPolicyAtMostOncePerRequest` (two decisions for one
      API-key request, one batch read of the assignments)
- [x] 1.7 `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests` `OrgUnitKeysApiTest`,
      `OrgUnitSubjectsTest`, `OrgUnitApprovalsTest`, `OrgUnitServiceTest`, `OrgUnitApprovalApiTest`,
      `OrgUnitUserOrgUnitsApiTest`, `OrgUnitAncestorsApiTest`, `OrgUnitListingApiTest`, `ResourceDependencyOrgUnitApiTest`
