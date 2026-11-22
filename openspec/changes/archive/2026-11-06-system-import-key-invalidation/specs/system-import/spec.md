## MODIFIED Requirements

### Requirement: Imported content is what Core serves
After a successful system import, Core SHALL serve the imported content for every resource under each replaced bucket, and no
write that was queued before the system import SHALL later replace an imported blob. Core SHALL drop the queued and the
cached Redis entries of each replaced bucket, including the cached copy of `encryption_keys/cek`, while it still holds the
locks, and SHALL tell every pod to drop its in-memory copy of the unwrapped key of each replaced bucket.

#### Scenario: Cached copy does not mask the system import
- **WHEN** `platform/roles/r1` was read before the system import so that Redis holds it, and the archive holds a different
  `platform/roles/r1`
- **THEN** `GET` of that role after the system import returns the imported content

#### Scenario: Queued write does not overwrite the system import
- **WHEN** a write to `platform/roles/r1` is queued and not yet synced when the system import runs, and the sync period passes
  after it
- **THEN** the blob `platform/roles/r1` still has the imported content

#### Scenario: Keys not in the archive
- **WHEN** a model existed before the system import in Redis and blob storage and the archive has no such model
- **THEN** it is not served after the system import

#### Scenario: Cached key of a replaced bucket is forgotten
- **WHEN** the system import replaces `platform/` and a pod has the key of `platform/` cached, the importing pod or another
- **THEN** that pod reads the key again from blob storage on its next use, and the importing pod does so before it
  rebuilds the merged config; a pod keeps its cached key of `public/`
