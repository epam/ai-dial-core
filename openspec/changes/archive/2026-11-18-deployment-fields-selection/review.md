# Review

Notes from applying `deployment-fields-selection`. This is not the three-lens review of `sdd-apply`; it records the
measurements of tasks.md 3.1 and what differed from the plan.

## Measurement (tasks.md 3.1)

Taken on a developer machine (JDK 25, one JVM, 15 to 25 warm-up runs, median of 21 to 31) with throwaway tests that
were deleted afterwards. Indicative, not a benchmark.

### First version: the list converted to a tree and pruned on the executor

| What | Without `fields` | `fields=id,display_name` |
|---|---|---|
| Body of `GET /v1/deployments` on `deployment-interfaces-listing.json` (5 deployments) | 8,246 bytes | 308 bytes |
| Body for 5,000 synthetic `ModelData` rows (description, keywords, defaults, catalog properties) | 6,139,451 bytes | 322,781 bytes |
| `writeValueAsString` of the rows, on the event loop | 57.4 ms | 4.0 ms (serializing the pruned tree) |
| `valueToTree` plus prune, on the task executor | not run | 74.2 ms |

With `fields` the event loop serialized about 14 times less and the body was about 19 times smaller, but the tree
work added about 74 ms of CPU on a virtual thread and a full tree of the listing in memory. Memory was not measured.

### Property filter against the tree, 5,000 `ModelData` rows

The same filter code as in `FieldSelection` (a copy in a throwaway test), two runs, so the figures are ranges. The
corpus had `interface_configs` (three entries per row) or not. "Tree" is the per-row convert and prune followed by
serializing the pruned tree. Allocation is per call and exact; CPU time is not given, because the clock of thread CPU
time ticks every 15.6 ms on the machine used.

| `fields` | Full listing, serialized today | Tree: executor + event loop | Property filter, one pass |
|---|---|---|---|
| `id,display_name`, no configs (10.5 MB listing, 358 KB body) | 34 to 47 ms, 42 MB | 42 to 45 ms + 3 ms, 40 MB | 5 to 6 ms, 1.4 MB |
| `id,display_name`, with configs (20.7 MB listing) | 66 to 79 ms, 83 MB | 78 to 97 ms + 2 ms, 85 MB | 5 to 7 ms, 1.4 MB |
| `display_name,features(rate,tokenize)`, with configs (563 KB body) | 66 to 79 ms | 96 to 114 ms + 3 ms, 87 MB | 10 to 13 ms, 2.2 MB |
| `interface_configs(*(features(reasoning_efforts)))`, with configs (1.5 MB body) | 66 to 79 ms | 92 to 121 ms + 8 to 9 ms, 98 MB | 41 to 61 ms, 6.3 MB |

Retained heap above the DTO list: a tree of the whole listing held 24 MB (no configs) and 55 MB (with configs); the
per-row result kept until the answer is written held under 1 MB. The output of the filter was byte-identical to the
output of the tree version for the three selections.

Reading: the filter costs between 2 and 15 times less time and 10 to 40 times less memory, and even in its slowest
case, the wildcard over the maps of `interface_configs`, it costs less than serializing the full listing does today.
It therefore runs on the event loop with no executor hop, and design D3 and D4 were rewritten to the filter.

Not measured: a listing that reads storage (custom applications, toolsets, stored aliases), where the storage reads
still cost what they cost today because `fields` does not skip building a field.

## Differences from the plan

- A bug found by `FieldSelectionTest`: whitespace after a closing parenthesis (`features(rate) , x`) was rejected,
  against design D2 ("surrounding whitespace is trimmed"). `FieldSelection` now skips it. No change to the spec or
  design.
- After the PR review the tree version was replaced by the Jackson property filter (design D3, D4, D5): the listing is
  serialized once with the filter on the event loop, `DeploymentController` no longer uses the task executor for it, and
  `FieldSelection` exposes `serialize(Object)` instead of a tree projection. A custom serializer has to write its nested
  members through the serializer provider for the filter to reach them (noted in CLAUDE.md).
- After the PR review: `FieldSelection` takes the always-kept names from the caller (`parse(text, alwaysKept)`); the
  union of a name and `*` is cached per node in a plain map (a selection is parsed and used on the event loop, so no thread shares it), because merging it at parse time would copy the
  wildcard subtree into every named sibling at every level; `MAX_LENGTH` is 1,024 UTF-8 bytes, so that a selection
  whose bytes are all percent-encoded still fits the 4,096-byte request line. The proposal says that a request
  already sending an unrelated `fields` parameter is read as a selection.
- A later review found that a model with a decision-tree pricing rate made every `fields` request fail: `PricingRate`
  writes its `test` condition through the mapper, which had no filter provider for the mixin. The mapper copy now has a
  tolerant default provider, a failure while serializing the filtered body answers `500`, and `FieldSelectionTest`
  covers a decision-tree rate. `LocalizedValueSerializer` and `PricingRateSerializer` (module `config`) now write
  through the serializer provider, so a nested selection reaches inside a localized value and a decision-tree rate
  instead of returning them whole; their output without a selection is unchanged (`LocalizedValueTest`,
  `PricingRateTest`).
- design.md named the measurement task as `4.2`; it is `3.1`. Corrected.
- `docs/open_api_core.yaml` was regenerated with `./gradlew replaceSpec`; the diff was the one new parameter
  (7 lines). `SpecMerger` keeps the committed `description` of a parameter that already exists, so regenerating does not
  update it: for the later change of the `fields` description the committed description was deleted from the file
  before `replaceSpec`, so that the generated text replaced it.
- `validate --all --strict` reports one failure, `spec/resource-subscription` (a placeholder Purpose from an earlier
  archive, a known issue this change does not touch). The empty change folder `soft-validation-request-param` that
  failed it before was removed by the owner.
