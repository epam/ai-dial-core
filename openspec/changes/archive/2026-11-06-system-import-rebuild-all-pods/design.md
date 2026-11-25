## Context

`MergedConfigStore.onResourceEvent` already rebuilds a pod's config from events of other pods for the managed types. An
import writes blobs without events, so those pods only catch up on the periodic rebuild.

## Decisions

- **Reuse the resource topic.** A rebuild request is a `ResourceEvent` with a URL that is no resource (`config/rebuild`).
  Pods drop events of their own `senderPodId` as they do for every event, so the importing pod does not rebuild twice.
  The alternative, a second topic, needs a new subscription and nothing else gains from it.
- **Go through `requestRebuild()`.** It debounces by 500 ms and then waits for the bucket locks, which the import still
  holds, so the other pods rebuild after the replace and never read a half-replaced bucket.
- **Publish after the local rebuild, failure is a warning.** The import already succeeded when the publish fails, and a
  missed pod rebuilds on its own schedule. The warning tells the operator which of the two happened.

## Risks

- A pod that is not subscribed at that moment misses the event (Redis pub/sub does not replay) and rebuilds within
  `config.reload`, as before.
