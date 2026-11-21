# Proposal

## Why

An API key from the config stands for a real user or service. Everywhere else in Core it is a peer of a signed-in
user: `AccessService.java:hasAdminAccess` and `hasGlobalReaderAccess` exclude only per-request keys (applications),
so an API key whose roles match the admin rules is a DIAL admin.

Org units draw the line elsewhere. `OrgUnitSubjects.java:isPerson` counts a caller as a role holder only when it has
token claims and no per-request key. An API key has no claims, so a role assignment that matches it only lets it
read: it cannot write unit content, approve or reject publication requests, read their review copies, manage role
assignments, see its units in `GET /v1/user/role-assignments`, or see the units above those where it holds a role.
That blocks automation the same roles allow a person, and it differs from how the rest of Core treats API keys.

## What Changes

- Every caller that is not an application (no per-request key) acts through its unit roles in full: a signed-in user
  as today, and now an API key too. The line is the one `AccessService.java:hasAdminAccess` draws.
- An API key matches role assignments as today: a `claimsPredicate` subject on `roles`, through the key's configured
  roles. Its other claims are absent, so subjects on any other source never match it.
- So an API key holding a role at a unit writes content there as the role allows, approves and rejects requests into
  the unit and reads their review copies, manages the unit's role assignments, gets its units in
  `GET /v1/user/role-assignments`, and sees the units above them.
- Applications (per-request keys) do not change: they read through their user's matched roles and never write,
  approve or manage through them.
- `docs/org-units.md`: API keys act through roles like people; a `TRUE` subject on `roles` matches every API key, so a
  role given to it gives every API key that role's full rights.
- Tests: `OrgUnitKeysApiTest` asserts the full rights of an API key (write, approve, manage, unit list, units above)
  and keeps the read-only assertions for applications; `OrgUnitSubjectsTest` covers the caller line.

Out of scope:

- The `apiKey` subject type (`Subject.ApiKey`): it stays refused with `400`. `roles` subjects cover API keys, and DIAL
  admins control a key's roles.
- Any change to how subjects match, to the subject validation, or to which functions are accepted.
- Applications (per-request keys): unchanged.
- Unit creation, renaming and deletion: DIAL admins only, as today; an API key that is a DIAL admin already does them.

Capabilities: adds `org-units`, a new capability (there is no baseline spec of org units yet); this change adds only
the requirement on which callers act through unit roles. No existing capability changes.

## Impact

- Issue: #2316. Covers every point of the issue: write, approve and review, manage role assignments, unit list,
  units above, docs on `TRUE` over `roles`, the `apiKey` subject type left out.
- Code: `OrgUnitSubjects` (the caller line), its callers in `OrgUnitGrants`, `OrgUnitApprovals`, `OrgUnitService`,
  `OrgUnitController` pick it up unchanged.
- Docs: `docs/org-units.md`.
- No API shape, config or settings change; no OpenAPI change.
