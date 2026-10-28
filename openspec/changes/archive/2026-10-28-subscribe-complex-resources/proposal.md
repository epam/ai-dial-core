## Why

`POST /v1/ops/resource/subscribe` rejects SKILL and PROJECT resources, and Core publishes no events for them: the
complex resource engine commits a write through its `.dial-resource` marker, and `ResourceTypes.EVENT_TYPES` does not
list the two types. Clients cannot learn that a skill or project, or a file inside one, changed.

## What Changes

- The subscribe API accepts SKILL and PROJECT resource URLs. Folder URLs stay rejected.
- Core publishes events for the logical resource URL and for the file URLs inside it, on create, update and delete,
  mirroring the complex resource API. The marker and the `v/{versionId}` files are never exposed.
- A subscription to a resource URL also delivers the events of the files inside that resource.

## Capabilities

### New Capabilities
- `resource-subscription`: the resource subscribe event stream. No capability in `openspec/specs` covers it today.

### Modified Capabilities
None.

## Impact

- Issue [#2178](https://github.com/epam/ai-dial-core/issues/2178); this change covers all of it.
- Out of scope: subscription to folders or prefixes, new request fields, event filtering.
- Rolling deploy: pods of the previous release publish no events for these types, so subscribers can miss events until
  every pod runs this release. Documented, not mitigated.
