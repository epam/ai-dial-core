# Review

Base 71fd56259 (merge-base with `development`), branch `feat/issue-2316`, reviewed uncommitted before the commit.
Contract: the ten rules of the proposal and spec (API keys act through unit roles like people; applications stay
read-only; `apiKey` subjects refused; matching unchanged; signed-in users and DIAL-admin keys unchanged).

## Rounds

One round by a fresh gated reviewer: no blocking findings, so no second round. The reviewer compiled, ran the
org-unit, publication and resource-dependency test classes and checkstyle, and probed with a temporary test class
(deleted): per-request keys launched by an API key and by a user, each holding `admin` at a unit, read with `200`
but get `403` on write, role-assignment listing and publication list/get/approve, and list no units; an API key with
only `editor` cannot approve or manage assignments and cannot create units; an API key never matches `sub` or
`email` subjects; an `apiKey` subject answers `400` and stores nothing. No remaining org-unit decision gates on token
claims, and no path in approve, reject, review or role management dereferences claims for an API key.

A second round, asked for by the user, reviewed code style and structure against `docs/CODE_STYLE.md`,
`openspec/config.yaml` and the neighbouring org-unit code. It found no behaviour problems.

## Findings fixed

Round 2 (style and structure), each checked against the cited rule before fixing:

- `isRoleHolder` replaces `actsThroughRoles`: boolean methods start with `is` or `has`. Its Javadoc keeps only the why.
- `OrgUnitService.loadAllHeldRoles` replaces `allHeldRoles`: methods are verb phrases.
- `OrgUnitApprovalsTest.testApplicationsApiKeysAndSwitchedOffNeverMatch` still asserted that API keys never approve,
  passing only because its key matched nothing; it is now `testApplicationsAndSwitchedOffNeverMatch`, without the
  API-key lines. `OrgUnitServiceTest.testCallersWhoAreNotPersonsHaveNoUnits` is now `testApplicationsHaveNoUnits`.
- The per-request cost is measured: `OrgUnitServiceTest.testApiKeyReadsEveryPolicyAtMostOncePerRequest`.
- `OrgUnitKeysApiTest.testApplicationsListNoUnits` duplicated `OrgUnitUserOrgUnitsApiTest.testApplicationsHaveNoUnits`
  and is removed. `DEFAULT_ROLE` is now `DEFAULT_ROLE_SUBJECT`.
- The spec's first requirement is named for the behaviour area ("Callers other than applications act through unit
  roles"); tasks list every touched test class; two edited lines in `docs/org-units.md` are wrapped like their
  paragraphs.

## Accepted, not changed

- An API key holding `editor` on a unit toolset can set its organisation (GLOBAL) credentials, as a person editor can.
  This follows from "act as people do".
- API keys with the same `project` share one bucket, so one key's publication request counts as the other's own:
  it adds the self-approval credential check and skips the rejection notification. This is the existing bucket model.

A final gated review of the committed PR found no blocking findings. Its two notes are fixed: the `ProxyContext`
field comment said "person" where it means any role holder, and the application clauses (no approve, review, role
management or units above) had no test in the branch; `OrgUnitKeysApiTest.testApplicationsNeitherApproveNorManageNorSeeUnitsAbove`
now pins them, with a matching scenario in the spec. It passes on the base too, as a guard; it fails (`403` expected,
`200` received) when applications are made role holders.

## Test evidence

Fail-before/pass-after on a throwaway checkout of the base: `OrgUnitKeysApiTest.testApiKeysWriteThroughTheirConfiguredRoles`,
`testApiKeysApproveIntoTheirUnit`, `testApiKeysManageRoleAssignments`, `testApiKeysListTheirUnitsAndTheUnitsAbove` and
`OrgUnitSubjectsTest.testApiKeysHoldTheRolesTheirRolesMatchAndApplicationsOnlyReadWithThem` fail on the base (403 or no
units/roles) and pass on the branch. `testApiKeySubjectIsRefused` passes on both: it pins behaviour that must not
change. After round 2, on the branch, all 17 `OrgUnit*`, `Publication*`, `ResourceDependencyOrgUnitApiTest`
and `AccessServiceTest` classes pass, and checkstyle is clean.

## Deferred

None. The full `:server:test` suite runs in CI.
