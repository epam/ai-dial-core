# Budget policy display names

A budget policy can have an optional string `displayName`. Use it for a readable label such as
"Engineering Pro"; the policy path remains its identifier. The field uses camelCase, as the other policy fields do.

Set the name in a policy under the dynamic configuration's `policies` map, in an admin Policy manifest, or through
the policy API. For example, an admin can PUT this body to `/v1/policies/platform/engineering-pro`:

```json
{
  "displayName": "Engineering Pro",
  "priority": 10,
  "kind": "budget",
  "quantity": "cost",
  "object": "*",
  "per": ["subject"],
  "cost": { "day": 10, "month": 200 }
}
```

The policy still needs an assignment matching the user, unless it pins both `subject` and `costObject` and
applies by itself. The name belongs to the policy; assignments do not accept a `displayName` override.
GET of a stored policy retains its configured name. An omitted or null name is omitted from policy reads;
an empty or whitespace-only string is accepted and retained.

## User budget responses

`GET /v1/user/limits` and `GET /v1/user/usage` include a `displayName` in each entry of `budgets[]`.
The value is the policy's non-blank name, unchanged, or its **full policy path** when the name is absent, null,
empty or whitespace-only. Assigned and self-applying policies follow the same rule.

An illustrative budget entry:

```json
{
  "costObject": "project-a",
  "policy": "engineering-pro",
  "displayName": "Engineering Pro",
  "priority": 10,
  "day": { "total": 10, "used": 4.25, "resetsAt": "2026-11-14T00:00:00Z" },
  "month": { "total": 200, "used": 4.25, "resetsAt": "2026-12-01T00:00:00Z" }
}
```

Budget visibility is unchanged: the user's claims must match, the assignment must not have expired, and
`spendingLimits.mode` must be `shadow` or `enforce`. API-key callers receive no budget entries.

The label is not unique and must not be used as a budget identifier. Multiple assignments of the same policy
share its name. Updating only the display name at the same policy path changes subsequent responses without
resetting spend or changing limits, priority order or enforcement. No locale selection is performed.

## Rollout

Existing policies require no migration and use the policy path as their label. Deploy supporting Core versions
on all instances before configuring names: older versions reject unknown policy fields. Before rolling back,
remove `displayName` from policy files and stored policy records while the supporting version is still running.
