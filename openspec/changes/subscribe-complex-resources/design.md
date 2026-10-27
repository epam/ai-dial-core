## Context

`ResourceOperationController.subscribe` filters on `SUBSCRIPTION_ALLOWED_TYPES`. Events come from
`ResourceService.publishEvent`, which publishes only `ResourceTypes.EVENT_TYPES`. `ComplexResourceService` writes the
marker and version files through `ResourceService`, so any event from those writes would carry internal URLs.

## Decisions

- **Logical events from the engine.** `ComplexResourceService.publishEvents` publishes after each committed change
  (`put`, `copyResourceLocked`, `copyOnWrite`, `delete`) through the new `ResourceService.publishLogicalEvent`.
  `ResourceService.publishEvent` drops events of complex types. Rejected: filtering URLs in the topic, which would
  still publish and carry internal events across pods.
- **File events by etag diff.** The per-file etags of the marker before and after the commit give CREATE, UPDATE and
  DELETE per file; unchanged files emit nothing. Rejected: always emitting for every file of a whole-resource put.
- **Fan-out in `ResourceTopic`.** An event for `{group}/{bucket}/{folders}/{name}/files/{path}` also goes to
  subscribers of the resource URL. Only complex groups are matched, and `files` is a reserved name for resources and
  folders (`RESERVED_NAMES`), so the first `files` segment after the bucket ends the resource URL.
- **Best effort publish.** A publish failure after the commit is logged, never failed back to the caller.
- **Rolling deploy gap.** Single release; documented in the spec impact, not mitigated. Rejected: a two-step rollout.

## Risks / Trade-offs

Each file change publishes up to 2 events, and a whole-resource put up to one per changed file. Events are small
and only the complex types pay it.

## Migration Plan

none
