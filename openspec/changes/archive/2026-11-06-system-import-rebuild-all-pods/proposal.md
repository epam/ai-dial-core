## Why

A system import copies blobs directly, so no per-entity event reaches the other pods. Each of them keeps serving its old
in-memory merged config until the periodic rebuild (`config.reload`, 60 s by default) fires. For up to a minute a model
or role that the import replaced looks stale depending on which pod answers.

## What Changes

- After its own rebuild, the importing pod publishes a rebuild request on the Redis topic that pods already share for
  resource events. Each other pod schedules a rebuild through the debounced `MergedConfigStore.requestRebuild()`, which
  takes the bucket locks, so it runs as soon as the import releases them.
- `ResourceService.publishUpdate(String url)` publishes an update event for any URL; the descriptor overload uses it.
  `MergedConfigStore.requestRebuildOnAllPods()` publishes the event with the URL `config/rebuild`, which matches no
  resource type.
- If the request cannot be published, the blobs stay replaced and the last line of the response carries a warning.
- A pod that misses the message still rebuilds within `config.reload`.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `system-import`: the requirement "Merged config rebuilt on the importing pod" no longer leaves the other pods to
  their own schedule; new requirement "Other pods rebuild their merged config".

## Impact

- Issue [#2204](https://github.com/epam/ai-dial-core/issues/2204), pull request #2213.
- Code: `ResourceService`, `MergedConfigStore`, `AdminSystemImportService`. Docs: `docs/admin-system-import.md`.
- Out of scope: the same request after `POST /v1/admin/apply`, which already publishes per-entity events.
