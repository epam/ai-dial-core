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

A user can pick one budget they hold as their **default budget**. It pays the user's calls that indicate no budget,
when it is on the call's cost object (see [Which budget pays a call](#which-budget-pays-a-call)). Only the user picks it: an admin cannot set it, and neither an
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

## Which budget pays a call

The budget that pays a JWT caller's call to a model is **declared**, not chosen by Core:

1. **The indication.** `X-DIAL-BUDGET: <costObject>/<id>` names the budget instance that pays: an assignment, or a
   self-applying policy. It is the only payer: when the caller does not hold it (no such budget, another subject's,
   expired) the call gets 403 `no_budget`, the same message in each case; when it does not cover the call, 403
   `budget_not_applicable`, naming the deployment the caller asked for; when it is used up, 429 `budget_exhausted`.
   Core never lets another budget pay instead.
2. **The default budget**, when the call indicates none and the default is on the call's cost object. When it does not
   cover the call, 403 `default_budget_not_applicable`, even when another budget would cover it; used up, 429. A default
   on another cost object is skipped.
3. **The walk**, when `spendingLimits.autoBudgetSelection` is `true` (the default): the first budget of the cost
   object, by priority, that covers the call and has room, as before. With `false` the call gets 403 `no_budget` asking
   to indicate a budget.

With `spendingLimits.budgetMandatory: true` (default `false`) a call that indicates no budget gets 400
`budget_indication_required`.

**The cost object of a call** is the token's claim at `identityProviders.*.costObjectPath`, else
`spendingLimits.defaultCostObject`: a token without the claim is charged to the default. An indicated budget on
another cost object gets 400 `cost_object_mismatch`, whose message names the call's cost object and where it came
from. Where the identity provider sets no `costObjectPath`, the request can name the cost object instead: the
indicated budget's own, or `X-DIAL-COST-OBJECT: <name>` when nothing is indicated. Where `costObjectPath` is set,
`X-DIAL-COST-OBJECT` is ignored.

**A cost object named by a header attests nothing.** A budget whose subject reaches everybody (`"function": "TRUE"`,
"everyone on this cost object") pays only when the cost object came from the claim or the default. Through a header,
a budget pays only through a subject rule that names the caller, such as `sub` or another claim.

**Calls an application makes** with its per-request key use the indication and the header cost object of the user's
request: their own `X-DIAL-BUDGET` and `X-DIAL-COST-OBJECT` are ignored. The indication is looked up again on each
call, so a budget withdrawn mid-session refuses the next call with 403. API key callers are not paid by budgets yet,
and Core ignores both headers on their calls.

The 400s (`invalid_budget_indication` for an `X-DIAL-BUDGET` that is not `<costObject>/<id>`, `invalid_cost_object`
for an `X-DIAL-COST-OBJECT` that is not one name, `cost_object_mismatch`, `budget_indication_required`) are checked on
the user's request to any deployment, in every `spendingLimits.mode`, `off` included. In `shadow` mode the 403s and
429s are logged and counted but do not refuse the call.

## Rollout

Existing policies require no migration and use the policy path as their label. Deploy supporting Core versions
on all instances before configuring names: older versions reject unknown policy fields. Before rolling back,
remove `displayName` from stored policy records while the supporting version is still running.

# Packages in a policy's object

A policy's `object` says what its budgets cover: `"*"` for every deployment, or a list of deployment names. It can
also name a [package](packages.md) with `@`: a single package as a string, or packages mixed with deployment names in
a list.

```json
{ "priority": 50, "kind": "budget", "quantity": "cost", "costObject": "IMF",
  "object": "@engineering", "per": ["subject"], "cost": { "month": 1000 } }
```

```json
"object": ["@engineering", "gpt-6-luna"]
```

Reads of the policy return `object` as written: a single package stays a string, a list is sorted. So do the
`budgets[].object` entries of `GET /v1/user/limits` and `GET /v1/user/usage`: they never list the deployments inside a
package. The policy field `packages` is reserved and still rejected; a package is named in `object` only.

# Which budgets cover a call

Budgets check and charge a call on the model, under the model's name, but a budget covers the call by any name it was
reached through. The **coverage chain** of a model call is the model and, read back from the call's execution path,
the aliases and routers before it, up to the first application, interceptor or toolset, which starts a call of its
own. A budget covers the call when its policy's `object` is `"*"` or names, directly or through a package, any name on
the chain.

| Call | Coverage chain | `@engineering` = `["weak", "medium", "strong"]` | `"*"` |
|---|---|---|---|
| `strong` → router `r1` decides `claude-opus-vertex` → model `m` | `strong`, `r1`, `claude-opus-vertex`, `m` | covered | covered |
| `m` called directly | `m` | not covered | covered |
| `hi-end` → model `m2` | `hi-end`, `m2` | not covered | covered |
| application `codemie` calls `strong` with its per-request key | `strong`, `r1`, `claude-opus-vertex`, `m` | covered | covered |

So a budget naming the tier aliases pays for routed calls, and direct model access needs a budget that names the
model or `"*"`. A model release retargets the routing-target alias; no package or policy changes.

When budgets of the caller's cost object cover a call at different positions of the chain, only those covering the
earliest position, nearest the head of the chain, are candidates. A budget naming `strong` takes a call made through
`strong` before a budget naming `m`, whatever their priorities, and when it has no money left the call is refused
with `budget_exhausted`; it does not fall through to the model's budget. Budgets at the same position are walked by
priority, as before. A budget with the object `"*"` counts as covering the call at the model, the last name of
the chain: it ties by priority with budgets that name the model, and yields to a budget that names an earlier name.

Two consequences to keep in mind:

* The chain follows the routing topology. Retargeting a router or an alias can change which budget pays.
* An interceptor in front of a model starts the chain again at the model, so budgets that name only the tier aliases
  do not cover that model. Add the model to the package when its calls should be covered.

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
| `cost_object` | The cost object the call is charged to: the token's project claim, the cost object the request named where the identity provider sets no `costObjectPath`, or `spendingLimits.defaultCostObject` |
| `outcome`     | `paid`: a budget pays. `no_budget`: a 403 refusal: no budget covers the caller and the model, the indicated or default budget is not held or does not cover the call, or the walk is off. `exhausted`: the budget that would pay is used up. Absent when a budget counter could not be read |
| `policy`      | With `paid`: the path of the budget's policy, as `budgets[].policy` of `GET /v1/user/limits` reports it |
| `assignment`  | With `paid`: the path of the policy assignment that grants the budget, `<costObject>/<id>`; absent for a policy that applies by itself |

The paying budget is the one the check before the call resolved: the indicated budget, the default budget, or the
first budget of the caller's cost object, by priority, that covers the model and has room. The call's cost is charged to it after the call, in `shadow` mode as in
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
the error code (`no_budget`, `budget_not_applicable`, `default_budget_not_applicable`, `budget_exhausted`, or one of the
400 codes) and `mode` to `spendingLimits.mode`.

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

`source` is `claim` (the token's claim at `identityProviders.*.costObjectPath`), `key` (the API key's project),
`header` (the cost object the user's request named with `X-DIAL-COST-OBJECT` or `X-DIAL-BUDGET`, where the identity
provider sets no `costObjectPath`) or `default` (`spendingLimits.defaultCostObject`). `project` stays in the row while consumers move to `cost_object`. The
row a background Responses job writes when it ends carries the cost object of the request that started the job; a job
started before an upgrade to this version has no `cost_object`.
