# Review: system-import-key-invalidation

## What shipped

- `ResourceService.publishUpdate(descriptor)` publishes an update event for a resource of any type on the shared resource
  topic. `ResourceTypes.EVENT_TYPES` is unchanged.
- `CachedContentEncryptionKeyManager` takes the `ResourceService` (passed by `ContentEncryptionKeyManagerFactory`).
  `invalidate` drops the local entry, calls the wrapped manager and publishes an update for the key. On start it subscribes
  to all resource events; an event whose URL starts with the encryption keys segment removes the cached entries with that
  URL. The import is unchanged: its `invalidateKey` call now reaches every pod.
- Docs: `docs/admin-system-import.md` no longer asks for a restart after an import, says a pod that missed the message and a
  key placed by hand still need one, and explains that two environments on one KMS key have different content encryption
  keys until an import makes the target use the archive's.

## Checks

- `./gradlew checkstyleMain checkstyleTest`: passes.
- `:credentials:test`: `CachedContentEncryptionKeyManagerTest` (3), `ContentEncryptionKeyServiceTest` (3).
- `:storage:test`: `ResourceTopicServiceWiringTest` (2, with Redis), `ResourceTopicSubscribeAllTest` (5).
- `:server:test`: `AdminSystemExportApiTest` (11), `AdminSystemImportServiceTest` (38),
  `AdminSystemImportValidationServiceTest` (24), `ApplicationRouteApiTest` (12), `MergedConfigStoreReplicaUpdateTest` (20).
  `AdminSystemImportApiTest` (4) fails only in `testSuccessRoundTripViaExportThenImport`, with the known Windows error
  (`:` in the staging path); its other tests start the full stack, so the new subscription at startup is exercised.
- `npx -y @fission-ai/openspec@1.14.3 validate system-import-key-invalidation --strict`: valid.

## Drift from design.md

None.

## Not done

- 2.4: an import between two pods on a KMS provider, checking that the second pod decrypts the imported secrets without a
  restart. The event path is covered by unit tests and a Redis test only.

## Known limits

- Pub/sub does not replay: a pod disconnected from Redis when the import ends keeps its old key until it restarts.
- A key placed in blob storage by hand is not announced.
- The publishing pod receives its own event and drops the entry again, costing one extra read.

## Review

Part of pull request #2213, not yet reviewed there.
