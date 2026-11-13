# Design

## Context

See proposal.md for motivation. The baseline is Core development at `34f667173f1b81cf9dcde17e3968b3f983e9c2b8`.
`PolicyRecordCodec.java:parsePolicy/readPolicy/write` maps the same policy for API writes and stored records.
`Config.java` exposes file policies, and `CostObjectIndex.java:build` keeps the policy on each budget instance.
`BudgetEnforcer.java:collectStats` already has that instance when it builds `BudgetStats` for both user endpoints.
`BudgetCounterStore.java:describe` keys spend by assignment or self-applying policy path, user and model.

## Goals / Non-Goals

**Goals:** supply one readable label without another lookup; retain existing records and counters.

**Non-Goals:** change the source or identity of budgets, introduce a package catalog, or change request enforcement.

## Decisions

1. Add nullable `String displayName` to `Policy`, rather than an assignment override. The policy names the reusable
   budget offer. Assignment naming needs a separate precedence contract and is outside this issue. Use camelCase,
   as policy fields and `OrgUnitService.java:create` already do. A localized map would need locale selection in
   user stats and is not needed for this change.
2. Add `String displayName` to `BudgetStats` and populate it in `BudgetEnforcer.java:collectStats`. Use the non-blank
   policy value unchanged, otherwise `instance.policyPath()`. This matches the blank-name fallback pattern in
   `OrgUnitService.java:create` while keeping the full policy path to avoid ambiguous leaf names. Returning null
   instead would push the same fallback into every client.
3. Keep the stored value separate from the fallback. The existing Jackson mapping retains configured strings;
   absent/null names remain absent on policy reads. Policy identity, matching, priorities and counter paths do not
   read the display name. Add it to `PolicyAssignmentValidator`'s policy-only fields for the existing clear error
   when clients mistakenly send policy properties on an assignment.
4. Document the setting in `docs/dynamic-settings/policies.md` and link it from README. The response schema follows
   `BudgetStats` through the existing OpenAPI generator; leave `docs/open_api_core.yaml` to CI's `replaceSpec`.
   No architectural or command change requires editing CLAUDE.md.

## Risks / Trade-offs

- Multiple assignments of a policy share a label → retain `policy` and `costObject`; names are presentation only.
- Older Core rejects the new policy field → deploy supporting Core instances before configuring names; remove
  the field before rolling back to a version with the strict old policy parser.
- A label change must not reset spend → integration coverage charges through `BudgetCounterStore`, renames via
  the policy API and compares the returned window statistics on both endpoints.

## Request cost

One in-memory field read and blank check per reported budget. No added Core pass, forwarded-body parse/copy, Redis
or blob round trip: the existing instance already contains its policy. Verify this by reviewing the projection
diff and the call sites; no latency or throughput improvement is claimed and no benchmark is needed.

## Migration Plan

No data migration. Existing policies use the full path fallback. Roll out Core before writing `displayName`;
remove configured display names before rolling back. Client changes are tracked separately in ai-dial-chat#9855.

## Baseline check before implementation

The inspected code matches these assumptions: no existing policy/display-name field, no extra policy fetch in
the stats projection, and no display metadata in counter keys.

During test review, `FileConfigController.java:entitySource` proved that file-policy inspection is not exposed
on `/v1/admin/config/file`. The file-policy scenario therefore checks the existing admin reload response and
both user endpoints. This avoids adding an unrelated inspection endpoint.
