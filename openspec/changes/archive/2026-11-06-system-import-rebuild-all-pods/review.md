# Review: system-import-rebuild-all-pods

## What shipped

- `ResourceService.publishUpdate(String url)` publishes an update event for any URL on the shared resource topic; the
  descriptor overload delegates to it. `ResourceTypes.EVENT_TYPES` is unchanged.
- `MergedConfigStore.requestRebuildOnAllPods()` publishes the URL `config/rebuild`. `onResourceEvent` treats an event with
  that URL from another pod as `requestRebuild()` (debounced, waits for the bucket locks); the existing `senderPodId`
  filter drops the one from its own pod.
- `AdminSystemImportService.rebuildMergedConfig` rebuilds the local config, then publishes the request; a failure of either
  adds its own warning and the blobs stay replaced.
- Docs: `docs/admin-system-import.md` says the other pods are told to rebuild and when a pod still waits for `config.reload`.

## Checks

- `./gradlew checkstyleMain checkstyleTest`: passes.
- `:storage:test`: `ResourceTopicServiceWiringTest` (2).
- `:server:test`: `MergedConfigStoreReplicaUpdateTest` (23), `MergedConfigStoreTest` (16), `AdminSystemImportServiceTest` (40),
  `AdminSystemImportValidationServiceTest` (24), `AdminSystemExportApiTest` (11), `ApplicationRouteApiTest` (12).
  `AdminSystemImportApiTest` (4) fails only in `testSuccessRoundTripViaExportThenImport`, with the known Windows error
  (`:` in the staging path).
- `npx -y @fission-ai/openspec@1.14.3 validate system-import-rebuild-all-pods --strict`: valid.

## Drift from design.md

None.

## Not done

- Task 2.4: a check with two pods that the second lists an imported model within seconds.
