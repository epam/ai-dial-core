# Design

## Context

- `RateLimiter.java:collectUserStats` builds the response of both endpoints and sets `budgets` from
  `BudgetEnforcer.java:collectStats`. That method returns an empty list when the mode is `off` or the context has
  no extracted claims (an API key caller), and otherwise one `BudgetStats` per instance from
  `EffectiveBudgets.java:resolveAll`. It lists budgets in `shadow` as well as `enforce`.
- `BudgetEnforcer.java:resolveCostObject` is how a model call picks its cost object: `ProxyContext.getUserProject()`
  (the token's project claim, `IdentityProvider` `projectPath`), then the API key's project, then
  `SpendingLimits.defaultCostObject` (default `"ORG"`, `SpendingLimits.java`). A JWT caller has no key
  (`Proxy.java` builds an `ApiKeyData` with a null `originalKey`), so with claims present the result is the token's
  project or the default.
- A per-request key carries the root caller's claims and key (`ProxyContext.java` constructor), so an application
  calling these endpoints sees its user's budgets and cost object, and none when the chain started with an API key
  (null claims).
- `PolicyObjectSet` (config module) serializes through `@JsonValue toJson` to `"*"` or a sorted list of names, which is
  what the policy API returns.
- `UserBudgetsApiTest` covers `budgets[]` with `dial-config/user-budgets.json` (mode `shadow`);
  `BudgetSpendCapsApiTest` runs `dial-config/budget-spend-caps.json` (mode `enforce`) and has a `userUsage` helper.
  `ResourceBaseTest` maps the `user@<project>` authorization to claims with that project. `ResourceBaseTest.createAppKey`
  builds a per-request key from a user's claims; `FileApiTest.testPerRequestBucket` builds one rooted at the API key
  `proxyKey1` (`setOriginalKey`, no claims).
- The OpenAPI generator copies no javadoc into property descriptions; `docs/open_api_core.yaml` lists the new fields
  without text. The meaning of the fields is documented in `docs/anthropic-spend-limits.md` and
  `docs/codex-spend-limits.md`, whose "Reported only with one budget" bullets already point readers to `budgets[]`;
  their "`shadow` mode" bullets already say budgets do not reject there.

## Goals / Non-Goals

**Goals:**
- The behaviour of the spec deltas, with no new reads.

**Non-Goals:**
- A per-model "who pays next" view.
- Any change to the order, filtering or totals of `budgets[]`.
- A JWT with a project but no user id. Its budgets are already loaded with a null user id
  (`BudgetEnforcer.java:collectStats`); this change gives it a `costObject` like any other JWT and leaves the rest as
  it is.

## Decisions

1. **Fill `costObject` in `BudgetEnforcer.collectStats`, not in `RateLimiter`.** `collectStats` already holds the
   `off` and API key checks that decide whether budgets apply, so `costObject` appears exactly when `budgets` can be
   non-empty. It returns the list and the cost object together (a small record). Rejected: computing it in
   `RateLimiter.collectUserStats`, which would repeat the mode and claims check there.
2. **Reuse `resolveCostObject` for the value.** The field then names the cost object a model call is charged to, by
   construction, including for a per-request key. Rejected: reading `getUserProject()` directly, which would skip the
   default and drift if resolution changes.
3. **Absent rather than null or the API key's project for an API key caller.** Budgets never reach an API key, so
   naming its key's project would point the client at budgets that are never charged. `@JsonInclude(NON_NULL)` on the
   field. Rejected: always present, set to the key's project.
4. **Present in `shadow`.** `budgets[]` is already listed in `shadow`, and the cost object is the same one the shadow
   check charges. Rejected: only in `enforce`, which would hide which budgets the shadow check uses. The docs already
   say that budgets do not reject in `shadow`.
5. **`object` is the policy's `PolicyObjectSet`, typed as such on `BudgetStats`.** Its `@JsonValue` gives the same
   form as the policy API, sorted, with no copy logic. Rejected: a `List<String>` with `"*"` mapped by hand, and a
   clearer name such as `deployments`, kept out so the admin and usage responses use one name for one thing.
6. **No query parameter.** A client filters by `costObject`; a flag would add a second response shape and needs a
   definition of "effective" that depends on the model.
7. **`object` lists the policy's deployments unfiltered.** A deliberate difference from `deployments`, which lists
   only the deployments the caller can access (`LimitController.java:listAccessibleDeployments`): a caller can see the
   name of a model their budget covers but their roles do not grant. The list is what an admin wrote into a policy that
   targets this caller, and filtering it would make `object` differ from the policy API and could turn a list into an
   empty one. Rejected: filtering by `hasAccess` (an in-memory config check per name, cheap), because it needs a rule for an
   emptied list and a scenario for it, for names an admin already chose to put in front of this caller.

Per request: no effect on proxied calls. On the two endpoints, one in-memory `resolveCostObject` call and no extra
Redis or blob round trip; the policy object is already in the `BudgetInstance` from the config index. Measured by
reading the diff (task 1.3): no new `store.*` or `resourceService` call.

## Risks / Trade-offs

- [The generated OpenAPI schema of `PolicyObjectSet` is `type: object`, not "string or array", and this change puts it
  in a client-facing response] → the existing policy schema already has this; `BudgetStats.object` reuses the same
  `$ref`, so the two stay consistent. Fixing the schema of `PolicyObjectSet` is a follow-up change.
- [A client reads `costObject` as "the budget that will pay"] → the two spend-limit docs say it is the cost object,
  and that within it the budget is picked per model by priority.

## Migration Plan

None. Both fields are additive.
