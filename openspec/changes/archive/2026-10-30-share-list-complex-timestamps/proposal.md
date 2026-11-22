## Why

`POST /v1/ops/resource/share/list` returns `createdAt` and `updatedAt` since #2210, but not for `PROJECT` and `SKILL`
items. These are complex resources: nothing is stored at their URL, and their timestamps live in the `.dial-resource`
marker. `ShareService.populateTimestamps` reads the resource URL, finds nothing and leaves both fields unset. The
shared projects view in DIAL Chat, the client #2210 was made for, still gets no dates.

## What Changes

- `PROJECT` and `SKILL` items in the share list, for `"with": "me"` and `"with": "others"`, get `createdAt` and
  `updatedAt` from the marker, the same values `GET /v2/metadata/...` returns for the resource.
- Other resource types and folders are unchanged. No request or response field changes.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `resource-access-control`: the "List shared resources" requirement gets a scenario for complex resources.

## Impact

- Issue [#2214](https://github.com/epam/ai-dial-core/issues/2214), a follow-up to #2210. Covers both points of the
  issue: the fix for complex types and the `PROJECT` / `SKILL` tests.
- `docs/open_api_core.yaml` needs no change: the `ResourceItemMetadata` schema already declares both fields.
- Out of scope: timestamps on folders, and the other marker fields (`etag`, `author`, `attributes`).
