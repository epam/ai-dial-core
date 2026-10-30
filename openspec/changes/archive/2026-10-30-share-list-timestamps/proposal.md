## Why

`POST /v1/ops/resource/share/list` builds each item from its URL alone, so items carry no `createdAt` or `updatedAt`.
The folder listing and single-item metadata endpoints return both. A client that lists shared resources, such as
the shared projects view, cannot show or sort by modification time.

## What Changes

- Each ITEM in the response, for `"with": "me"` and `"with": "others"`, gains `createdAt` and `updatedAt`, read
  from the stored resource as the metadata endpoints read them.
- Folders are unchanged. No request field and no other response field changes.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `resource-access-control`: the "List shared resources" requirement.

## Impact

- Issue [#2210](https://github.com/epam/ai-dial-core/issues/2210).
- `docs/open_api_core.yaml` needs no change: the `ResourceItemMetadata` schema already declares both fields, and
  regenerating it (`:openapi-generator:replaceSpec`) gives no diff.
- Out of scope: timestamps on folders, and a `lastAccessed`-style field.
