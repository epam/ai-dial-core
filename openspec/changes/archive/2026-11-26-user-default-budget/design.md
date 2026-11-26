# Design

## Context

A user's budgets come from the policies and assignments of the dynamic config. Core keeps them in memory as a cost
object index, and `GET /v1/user/limits` lists the ones that match the caller: cost budgets whose subject matches the
caller's claims and whose assignment has not expired, in every cost object. Each such budget has an id,
`<costObject>/<assignment id>` (or the policy path of a self-applying policy). Today nothing records which of them
the user prefers, and the priority walk picks the payer. Per-user choices with the same privacy needs already exist:
user consent is a blob record in the user's bucket under a resource type that no resource API route can address.

## Decisions

### D1. The default is a private blob record in the user's bucket, like user consent

A new resource type `USER_BUDGET_DEFAULT` (group `user_budget_default`, compressed, 5-minute Redis TTL) holds one
record, `default`, in `Users/<sub>/`, with `{"budget": "<id>"}`. The type is left out of `ResourceTypes.of`, so no
files, resource, listing or metadata route resolves it, and out of `EVENT_TYPES`, so nothing is published. Clearing
deletes the record.

**Rejected: a Redis key without a blob** - lost on a Redis flush, and every other per-user record is a resource.
**Rejected: a field in a user-visible resource** - the resource API would let an application with the user's bucket
write it.

### D2. "Held" is the set `GET /v1/user/limits` lists, in every cost object

PUT accepts a budget when `EffectiveBudgets.resolveAll` returns it for the caller now, and the read helper uses the
same check, on the wall clock as the budget checks are. A user can thus pick only what they can already see, and the 400 message is the same for an unknown id
and another person's id. The ladder (#2365) adds its own check that the default lies on the call's cost object.

**Rejected: only budgets of the caller's effective cost object** - the claim carrying the cost object can change
between sessions, and the ladder already skips a default on another cost object.

### D3. A stale default reads as unset and stays stored

`DefaultBudgetService.findDefault` reads the record and returns the budget only while the caller still holds it.
Nothing deletes the record when an assignment is withdrawn; a later GET answers `{"budget": null}`, and the record
counts again if the budget is granted back under the same id.

**Rejected: delete the pointer when the assignment goes** - it needs a sweep over every user bucket on each config
change.
**Rejected: delete it on read** - a read that writes, for a record that costs nothing to keep.

### D4. Only a JWT user calling directly; everything else is 403

The endpoints refuse an API key (no budget reaches it) and a per-request key (an application must not choose who
pays for the user), as `POST /v1/consent` does. A JWT without a user id also gets 403, as it has no user bucket.

**Rejected: let an application set the default for its user** - the issue reserves the choice for the user.

### D5. PUT with `{"budget": null}` clears; no DELETE

The issue's API is GET and PUT. GET and PUT share the body shape `{"budget": <id or null>}`, so a client writes back
what it read.

**Rejected: DELETE to clear** - a third method for the same result.

## Differences from today

None: two new endpoints and a new record; no existing behaviour changes.

## Effect per request

None: model calls do not read the record in this change. Each GET is one resource read (Redis, then blob on a miss);
each PUT is one write or delete, after an in-memory check.

## Risks / Trade-offs

- A user cannot find a budget's id through the API yet: `budgets[]` of `GET /v1/user/limits` has the cost object and
  the policy, not the assignment id. Budget discovery (#2370) adds it. Until then a client takes the id from the
  admin.
- A default for a budget withdrawn and later granted again under the same id counts again (D3). That is the same
  budget, so it is the expected result.

## Migration Plan

None: a new record type; nothing exists to migrate.

## Code map

- `ResourceTypes.java`: the enum, `of` and `EVENT_TYPES` (D1); `USER_CONSENT` and `ADMIN_CONSENT` as the models.
- `ConsentService.java:getResourceDescription`, `ResourceDescriptorFactory.java:fromEntityPath`,
  `BucketBuilder.java:buildInitiatorBucket`: the record's descriptor in the user's bucket.
- `ResourceService.java:getResource`, `putResource`, `deleteResource`: blocking, so on `AsyncTaskExecutor.submit`.
- `EffectiveBudgets.java:resolveAll`: the held budgets (D2); `BudgetEnforcer.java:collectStats` reads the same set.
- `ConsentController.java:acceptConsent`: body parsing, the per-request-key refusal and the task-executor pattern.
- `RouteTemplate.java` (`USER_LIMITS`), `ControllerSelector.java` (`get`, `put`): the new route.
- `AnnotationEndpointCollector.java`: the controller list the OpenAPI generator reads; a route whose controller is not
  listed fails the generation.
- `AiDial.java` and `Proxy.java`: where `ConsentService` is built and exposed; the new service goes next to it.
- Tests: `ResourceBaseTest` (`user`, `user@<project>` claims with title `Manager`), `BudgetSpendCapsApiTest.putBudget`
  (policies and assignments through the admin API), `ResourceDependencyConsentApiTest.testAdminConsentRecordIsUnreachableViaTheResourceApi`.
