# Review

Notes from applying `deployment-fields-selection`. This is not the three-lens review of `sdd-apply`; it records the
measurements of tasks.md 3.1 and what differed from the plan.

## Measurement (tasks.md 3.1)

Taken on a developer machine (JDK 25, one JVM, 15 to 25 warm-up runs, median of 21 to 31) with throwaway tests that
were deleted afterwards. Indicative, not a benchmark.

### First version: the whole list converted to a tree and pruned on the executor

| What | Without `fields` | `fields=id,display_name` |
|---|---|---|
| Body of `GET /v1/deployments` on `deployment-interfaces-listing.json` (5 deployments) | 8,246 bytes | 308 bytes |
| Body for 5,000 synthetic `ModelData` rows (description, keywords, defaults, catalog properties) | 6,139,451 bytes | 322,781 bytes |
| `writeValueAsString` of the rows, on the event loop | 57.4 ms | 4.0 ms (serializing the pruned tree) |
| `valueToTree` plus prune, on the task executor | not run | 74.2 ms |

### Final version: per-row conversion and prune, 5,000 `ModelData` rows

The corpus has `interface_configs` (three entries per row) or not. Allocation is per call and exact. CPU time is not
given, because the thread CPU clock of the machine used ticks every 15.6 ms.

| `fields` | Executor: convert and prune row by row | Event loop: serialize the pruned tree | Full listing serialized today |
|---|---|---|---|
| `id,display_name`, no configs (10.5 MB listing, 358 KB body) | 51 ms, 39 MB | 2.6 ms, 1.4 MB | 44 ms, 43 MB |
| `display_name,features(rate,tokenize)`, no configs (563 KB body) | 54 ms, 48 MB | 4.7 ms, 2.2 MB | 44 ms, 43 MB |
| `id,display_name`, with configs (20.7 MB listing) | 112 ms, 87 MB | 2.2 ms, 1.4 MB | 73 ms, 83 MB |
| `display_name,features(rate,tokenize)`, with configs | 106 ms, 93 MB | 2.6 ms, 2.2 MB | 73 ms, 83 MB |
| `interface_configs(*(features(reasoning_efforts)))`, with configs (1.5 MB body) | 116 ms, 111 MB | 6.9 ms, 6.0 MB | 73 ms, 83 MB |

Retained heap above the DTO list: the pruned result kept until the answer is written holds 1.7 MB; a tree of the whole
listing, which the first version held, 26 MB without configs and 57 MB with them.

Reading: the event loop serializes about 9 to 33 times less than the full list (2 to 7 ms against 44 to 73 ms) and the
body is up to 58 times smaller (358 KB against 20.7 MB). The total CPU is higher than the full serialization, 1.2 to 1.7
times, because the tree is built as well. That work runs on a virtual thread of the task executor. A very large listing
keeps a carrier thread busy for the length of the loop (about 100 ms for 5,000 rows with configs). The executor hop
costs one task submission and saves the loop 39 to 71 ms at this size, so design D4 stands. Without `fields` the only
added work is one `getParam` call.

### Tried and dropped: a Jackson property filter while serializing

A path-aware `PropertyFilter` on a copy of the mapper (two runs, ranges) took 5 to 61 ms and 1 to 6 MB for the same
selections, against 39 to 130 ms and 40 to 98 MB for the tree, with byte-identical output, and no tree was held. It was
dropped because it narrows only what goes through the serializer provider: a serializer that writes straight to the
generator or through the mapper (`LocalizedValueSerializer`, `PricingRateSerializer`) and a `JsonNode` in a free-form
map were returned whole, and keeping new serializers cooperative would rest on a rule in CLAUDE.md. The tree holds
exactly what the mapper wrote, so every type is narrowed the same way. The cost is the higher CPU and the time on the
executor shown above.

Not measured: a listing that reads storage (custom applications, toolsets, stored aliases), where the storage reads
still cost what they cost today because `fields` does not skip building a field.

## Differences from the plan

- A bug found by `FieldSelectionTest`: whitespace after a closing parenthesis (`features(rate) , x`) was rejected,
  against design D2 ("surrounding whitespace is trimmed"). `FieldSelection` now skips it. No change to the spec or
  design.
- After the PR review: `FieldSelection` takes the always-kept names from the caller (`parse(text, alwaysKept)`); the
  controller converts and prunes row by row, so the full tree of the listing is never held; `MAX_LENGTH` is 1,024 UTF-8
  bytes, so that a selection whose bytes are all percent-encoded still fits the 4,096-byte request line; the proposal
  says that a request already sending an unrelated `fields` parameter is read as a selection.
- The pruner carries the matching selection nodes of a name and `*` down the recursion and does not merge them into the
  parsed tree, so the parsed selection is immutable. Merging at parse time copies the `*` subtree into every named
  sibling at every level: a simulation turned a 1,004-byte selection of 152 nodes into 125,051 nodes with three `*`
  levels and 2,086,619 with four. `FieldSelectionTest.wildcardsAtSeveralLevelsAreNotMergedIntoTheParsedSelection` guards this.
- A failure of the listing, of the conversion and pruning, or of writing the answer, with or without `fields`, is
  logged and answered `500` with the text `Internal error`, and only logged when the response head is already
  written. `DeploymentControllerTest` covers it through the package-private `respondListing`.
- From a later review: a bare `*` at the top (`*`, `*,x`) keeps everything, so `parse` returns no selection and the
  request takes the unselected path; arrays are selected into recursively, nested arrays included; the matching
  selection lists of a single node are cached on the node, so the common case allocates nothing; the size guard of the
  parsed selection counts members, not seconds; the spec and the OpenAPI text say that an object left with no members
  stays `{}`. The cost of the conversion on the virtual-thread executor was accepted and is recorded in design.md.
- A filter-while-serializing version replaced the tree for a while and was dropped again (see above). Along the way a
  model with a decision-tree pricing rate had made every `fields` request fail, which the tree version cannot do;
  `FieldSelectionTest` keeps the cases for localized values, decision-tree rates and a `JsonNode` in a free-form map.
- design.md named the measurement task as `4.2`; it is `3.1`. Corrected.
- `docs/open_api_core.yaml` was regenerated with `./gradlew replaceSpec`. `SpecMerger` keeps the committed
  `description` of a parameter that already exists, so for each later change of the `fields` description the committed
  description was deleted from the file before `replaceSpec`, and the generated text replaced it.
- `docs/dynamic-settings/routers.md` lists `GET /v1/deployments`, so it got a short paragraph on `fields`; the proposal
  had said that no `docs/*.md` did.
- `validate --all --strict` reports one failure, `spec/resource-subscription` (a placeholder Purpose from an earlier
  archive, a known issue this change does not touch). The empty change folder `soft-validation-request-param` that
  failed it before was removed by the owner.
