# Review

Notes from applying `deployment-fields-selection`. This is not the three-lens review of `sdd-apply`; it records the
measurement of tasks.md 3.1 and what differed from the plan.

## Measurement (tasks.md 3.1)

Taken once on a developer machine (JDK 25, one JVM, 15 warm-up runs, median of 21) with a throwaway test that was
deleted afterwards. Indicative, not a benchmark.

| What | Without `fields` | `fields=id,display_name` |
|---|---|---|
| Body of `GET /v1/deployments` on `deployment-interfaces-listing.json` (5 deployments) | 8,246 bytes | 308 bytes |
| Body for 5,000 synthetic `ModelData` rows (description, keywords, defaults, catalog properties) | 6,139,451 bytes | 322,781 bytes |
| `writeValueAsString` of the rows, on the event loop | 57.4 ms | 4.0 ms (serializing the pruned tree) |
| `valueToTree` plus prune, on the task executor | not run | 74.2 ms |

Reading: with `fields` the event loop serializes about 14 times less (4 ms against 57 ms for 5,000 rows) and the
body is about 19 times smaller. The total CPU is higher, 78 ms against 57 ms, because the tree is built in addition
to the serialization; that work runs on a virtual thread, not on the loop. The executor hop therefore costs one task
submission and saves the loop about 53 ms at this size, so design D4 stands and nothing in design.md changes.
Without `fields` the only added work is one `getParam` call.

Not measured: memory (the rows exist as DTOs and as a tree until the answer is written) and a listing that reads
storage (custom applications, toolsets, stored aliases), where the storage reads still cost what they cost today
because `fields` does not skip building a field.

## Differences from the plan

- A bug found by `FieldSelectionTest`: whitespace after a closing parenthesis (`features(rate) , x`) was rejected,
  against design D2 ("surrounding whitespace is trimmed"). `FieldSelection` now skips it. No change to the spec or
  design.
- design.md named the measurement task as `4.2`; it is `3.1`. Corrected.
- `DeploymentController` gained a private `respondListingFailure` shared by the two failure handlers (the listing
  and the projection); the design did not name it.
- `docs/open_api_core.yaml` was regenerated with `./gradlew replaceSpec`; the diff is the one new parameter
  (7 lines). CI commits that file.
- `validate --all --strict` reports one failure, `spec/resource-subscription` (a placeholder Purpose from an earlier
  archive, a known issue this change does not touch). The empty change folder `soft-validation-request-param` that
  failed it before was removed by the owner.
