# Budget policy display names

A budget policy can have an optional string `displayName`. Use it for a readable label such as
"Engineering Pro"; the policy path remains its identifier. The field uses camelCase, as the other policy fields do.

Set the name through the policy API or an admin Policy manifest. Policies are API-managed records; they are
not loaded from the dynamic configuration JSON files. For example, an admin can PUT this body to
`/v1/policies/platform/engineering-pro`:

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

## Default budget

A user can pick one budget they hold as their **default budget**. Later versions use it to pay the user's calls that
name no budget; in this version Core only stores it. Only the user picks it: an admin cannot set it, and neither an
API key nor an application calling with a per-request key can read or set it (403).

- `GET /v1/user/budgets/default` answers `{"budget": "<costObject>/<id>"}`, or `{"budget": null}` when no default is
  set or the user no longer holds that budget (its assignment was withdrawn or has expired, or its subject no longer
  matches them).
- `PUT /v1/user/budgets/default` with `{"budget": "projA/engineering-alice"}` sets it, and answers with the same body.
  The budget must be one the user holds now, one of those `GET /v1/user/limits` lists for them, in any cost object.
  Otherwise the answer is 400, with the same message whether or not the budget exists.
- `PUT /v1/user/budgets/default` with `{"budget": null}` clears it.

The id is the path of the assignment that grants the budget, `<costObject>/<id>`, or the policy path of a
self-applying policy. The default works in every `spendingLimits.mode`. Core keeps it as a private record in the
user's bucket, which the files and resource APIs cannot reach. A default whose budget is withdrawn stays stored and
reads as unset. It counts again if the same assignment is granted back.

## Rollout

Existing policies require no migration and use the policy path as their label. Deploy supporting Core versions
on all instances before configuring names: older versions reject unknown policy fields. Before rolling back,
remove `displayName` from stored policy records while the supporting version is still running.

# Spending limits in the analytics log

The analytics log row of a JWT caller's model call whose limits Core checks carries a `spending_limits` object,
right after `project`:

```json
"project": { "id": "project-a" },
"spending_limits": { "mode": "enforce", "cost_object": "project-a", "outcome": "paid",
                     "policy": "engineering-pro", "assignment": "project-a/42" },
```

| Key           | Value                                                                                                  |
|---------------|--------------------------------------------------------------------------------------------------------|
| `mode`        | `spendingLimits.mode`: `off`, `shadow` or `enforce`. Always present; with `off` it is the only key       |
| `cost_object` | The cost object the call is charged to: the token's project claim, or `spendingLimits.defaultCostObject` when the token has none |
| `outcome`     | `paid`: a budget pays. `no_budget`: no budget covers the caller and the model. `exhausted`: every such budget is used up. Absent when a budget counter could not be read |
| `policy`      | With `paid`: the path of the budget's policy, as `budgets[].policy` of `GET /v1/user/limits` reports it |
| `assignment`  | With `paid`: the path of the policy assignment that grants the budget, `<costObject>/<id>`; absent for a policy that applies by itself |

The paying budget is the one the check before the call picked: the first budget of the caller's cost object, by
priority, that covers the model and has room. The call's cost is charged to it after the call, in `shadow` mode as in
`enforce` mode. `user.id` is the user's hash; `spending_limits` does not carry the user.

In `enforce` mode a call with `exhausted` is refused with 429, `retry-after` and error code `budget_exhausted`, and a
call with `no_budget` with 403 and error code `no_budget`, except on the Messages and Responses routes:

- On the Anthropic Messages route a client's own request gets a 402 with the Anthropic `billing_error` body, and on
  the Responses routes a 400 whose body is the message as `text/plain`, also through an alias. A background Responses
  request through an alias, and a call made with a per-request key, get a 429 with error code `no_budget`. Inside Core
  the refusal stays a 429 on these routes, so an alias candidate and a router still fall back on it.
- With an interceptor in front of the model, global interceptors included, the refusal is made on the interceptor's
  call, so the client gets what the interceptor answers (see [Codex spend limits](../codex-spend-limits.md)).

Core writes no analytics row for a refused call, so `no_budget` and `exhausted` appear in `shadow` mode only, where
the call goes through. The metric `dial_budget_rejections_total` counts refusals in both modes, with `reason` set to
the error code (`no_budget` or `budget_exhausted`) and `mode` to `spendingLimits.mode`.

A row has no `spending_limits` when:

- the caller is an API key, or an application whose call chain started with an API key;
- Core does not check the limits of the pass, as for the outer request of a translated interface;
- the row is not a model's: an application, interceptor or route row, or the router row of a decided request,
  whose `price` rolls up the model's;
- the row is the one the poller writes from the job record when a background Responses job ends. The poller
  charges no budget yet. A background job that its own stream ends is charged like any call, and its row has the
  object.

The spend charged to a budget is the sum of `token_usage.deployment_price` over the rows that name it. A call that
failed after the check, such as on an upstream error, has `outcome` `paid` with no or zero `deployment_price`.

`project.id` is the token's project claim, so it is empty when the token has none, while
`spending_limits.cost_object` then holds the default cost object.

# Cost object in the analytics log

Every row also carries a `cost_object` object: the cost object of the caller that started the call chain, and where it
came from. It follows `spending_limits`, or `project` when the row has none:

```json
"project": { "id": "project-a" },
"spending_limits": { "mode": "enforce", "cost_object": "project-a", "outcome": "paid", "policy": "engineering-pro" },
"cost_object": { "id": "project-a", "source": "claim" },
```

`source` is `claim` (the token's claim at `identityProviders.*.costObjectPath`), `key` (the API key's project) or
`default` (`spendingLimits.defaultCostObject`). `project` stays in the row while consumers move to `cost_object`. The
row a background Responses job writes when it ends carries the cost object of the request that started the job; a job
started before an upgrade to this version has no `cost_object`.
