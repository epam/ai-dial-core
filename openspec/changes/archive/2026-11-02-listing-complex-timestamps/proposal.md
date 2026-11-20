## Why

`GET /v2/metadata/{projects|skills}/{bucket}/{folder}/` reports stale `createdAt` / `updatedAt` for `PROJECT` and
`SKILL` items for up to about 3 minutes after a write. The listing takes them from the blob metadata of the
`.dial-resource` marker file. A write updates the marker document in Redis at once, but the blob catches up only on the
next sync (`syncDelay` 120 s, `syncPeriod` 60 s). `GET /v2/metadata/` of the item itself reads the marker document and
is correct. DIAL Chat sorts and labels projects by this field, so a project with a fresh chat shows an old date.

## What Changes

- `PROJECT` and `SKILL` items in a metadata listing, recursive or not, carry the `createdAt` and `updatedAt` of the
  marker document, the same values `GET /v2/metadata/` of the item returns. When the marker document has no value, the
  listing keeps the marker blob's value.
- Folders, `author` and `attributes` are unchanged. No request or response field changes.

## Capabilities

### New Capabilities
- `resource-listing`: metadata listings of resources. Starts with the timestamps of complex items.

### Modified Capabilities
None.

## Impact

- Issue [#2223](https://github.com/epam/ai-dial-core/issues/2223). Covers the fix and the `PROJECT` / `SKILL` tests.
- `docs/open_api_core.yaml` needs no change: the listing item schema already declares both fields.
- Out of scope: `author` in the listing. The blob keeps the first writer, the marker document the last one, so moving it
  changes what the field means; that is a separate decision.
