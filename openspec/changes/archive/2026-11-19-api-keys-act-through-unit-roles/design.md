# Design

## Context

What the code does today, on `development` (71fd56259):

- `OrgUnitSubjects.java:isPerson` is `context.getExtractedClaims() != null && apiKeyData.getPerRequestKey() == null`.
  A config API key has no claims (`ProxyContext.java:initExtractedClaims` keeps the key's roles in `userRoles`), so
  it is not a person. A per-request key carries its user's claims but has a per-request key, so it is not one either.
- `OrgUnitSubjects.java:heldRoles` returns the matched roles for a person and nothing for anyone else;
  `rolesToReadWith` returns the matched roles for every caller. `matches` accepts only `claimsPredicate` subjects
  and evaluates them through `RuleMatcher.java:match`, which reads `roles` from `ProxyContext.getUserRoles()`, so an
  API key matches `roles` subjects through its configured roles and nothing else.
- Callers of the person line:
  - `OrgUnitGrants.java:access`: `readOnly = !isPerson` cuts the access to `READ_ONLY`.
  - `OrgUnitApprovals.java:mayDecide`, `mayReview`, `approvedTargetFolders`, `approvesAnyUnit`, `mayReadReviewCopy`
    refuse a non-person; `grants` uses `heldRoles`.
  - `OrgUnitController.java:requireGrant` and `mayChange` use `heldRoles` for managing role assignments.
  - `OrgUnitService.java:unitsOf` (`GET /v1/user/role-assignments`) answers no units to a non-person;
    `personsRoles` and `loadHeldRolesByUnit` use `heldRoles`.
  - `OrgUnitService.java:unitsAboveHeldRoles` answers none to a non-person.
- Admin checks: `AccessService.java:hasAdminAccess` and `hasGlobalReaderAccess` exclude only per-request keys.
- `OrgUnitApprovals.java:isRequester` compares the request url with `BucketBuilder.buildInitiatorBucket(context)`,
  which gives an API key its project bucket; it needs no claims. `OrgUnitController.createOrgUnit` takes
  `context.getUserDisplayName()` as the author, but unit creation is DIAL-admin only and already open to an admin
  API key.

## Goals / Non-Goals

Goals: an API key acts through its unit roles exactly as a signed-in user does. Non-goals: new subject types,
changes to matching, any change for applications.

## Decisions

1. **The line is "not an application"**: `isPerson` becomes `isRoleHolder` =
   `apiKeyData.getPerRequestKey() == null`, the same condition `AccessService.java:hasAdminAccess` uses.
   `heldRoles` returns the matched roles for such a caller. Every caller site keeps its code and only follows the
   renamed predicate.
   Rejected: a separate API-key branch at each call site (five classes) — more code for the same line, and easy to
   miss a site.
2. **No `apiKey` subject type.** `Subject.ApiKey` stays refused by `OrgUnitService.java:requireSubject`. A
   `roles` subject already names a set of keys, and DIAL admins control a key's roles in the config.
   Rejected: matching `{"type":"apiKey","id":<key name>}` — another subject for unit admins to manage, and key names
   are config ids unit admins don't otherwise see.
3. **`TRUE` on `roles` is left as is**: it matches every caller, every API key included, and now gives keys the
   role's full rights. Documented rather than special-cased, since it already gives every signed-in user those rights.
   Rejected: refusing `TRUE` on `roles` for roles beyond `viewer` — a new validation rule no consumer asked for.

Deliberate differences from today: API keys write, approve, review, manage role assignments, list their units and see
the units above, where today they only read.

## Per-request cost

Unchanged for people and applications. For an API key, the decisions that read assignments now do the same work they
do for a person: `unitsAboveHeldRoles` reads every unit's assignments in one batch at most once per request (cached in
`ProxyContext.unitsAboveHeldRoles`), where today it returns at once; the unit list and approvals read the same policies
they read for a person. No body copies or parses change. Measured by
`OrgUnitServiceTest.testApiKeyReadsEveryPolicyAtMostOncePerRequest`: two decisions for one API-key request make one
batch read of the assignments.

## Risks

- A unit admin who gave a role to `TRUE` on `roles` now gives every API key write or approve rights there. Mitigation:
  documented in `docs/org-units.md`; DIAL admins see assignments.

## Migration Plan

None. Stored assignments are unchanged; their effect on API keys widens at deploy.

## Drift

None found at task 1.1: the call sites listed in Context match the branch. During apply the private
`OrgUnitService.personsRoles` was renamed `loadAllHeldRoles`, and the Javadoc of `OrgUnitGrants`, `OrgUnitRoles` and
`CallerUnitRoles` that said only people hold roles now says applications don't.
