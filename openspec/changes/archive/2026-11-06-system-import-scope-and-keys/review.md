# Review: system-import-scope-and-keys

## What shipped

- `AdminSystemImportValidationService` takes `kmsEnabled` and the `KeyManagementService`. `credentials/` is always rejected;
  `encryption_keys/` only without a KMS provider. `isEncryptionKey` and `validateEncryptionKey` unwrap a key with the KMS:
  `CekEncryptionException`, an empty key and a key over 64 KiB are `400`; any other KMS error propagates and is `500`.
- Stage: each `encryption_keys/` entry is checked from the bytes already read for staging, before any lock or change.
  `StagedImport` carries `bucketLocations`, the buckets with blob entries, in the order of `ADMIN_BUCKET_LOCATIONS`.
- Replace: wipe, copy and Redis invalidation use only those buckets; both bucket locks are still taken. After the copy,
  `invalidateEncryptionKeys` calls `ContentEncryptionKeyService.invalidateKey` for each replaced bucket, before the rebuild.
  The `System import replace finished` log line lists the buckets.
- Credentials module: `ContentEncryptionKeyManager.invalidate` (default no-op), overridden by
  `CachedContentEncryptionKeyManager`, and `ContentEncryptionKeyService.invalidateKey`.
- `AiDial` builds the KMS client and the key service once and shares them with credential encryption and the import; the
  export wiring reuses the same `kmsSettings`.
- The merge with #2237 left two compile errors, now fixed: the import no longer uses `AdminSystemExportService.EXCLUDED_GROUPS`
  (it has its own rule), and the new export tests use `SystemArchiveBlobMetadata.ENTRY_PREFIX`.
- Docs: `docs/admin-system-import.md` describes keys with KMS, the unwrap check, the rolling restart, and archives of one bucket.

## Checks

- `./gradlew checkstyleMain checkstyleTest`: passes.
- `:credentials:test`: `CachedContentEncryptionKeyManagerTest` (1), `ContentEncryptionKeyServiceTest` (3).
- `:server:test`: `AdminSystemImportValidationServiceTest` (22), `AdminSystemImportMetadataReaderTest` (8),
  `AdminSystemImportServiceTest` (38), `AdminSystemExportServiceTest` (29), `AdminSystemExportApiTest` (11).
  `AdminSystemImportApiTest` (4) fails only in `testSuccessRoundTripViaExportThenImport`, with the known Windows error
  (`:` in the staging path); it runs the full stack, so it also covers the new wiring.
- `npx -y @fission-ai/openspec@1.14.3 validate system-import-scope-and-keys --strict`: valid.

## Drift from design.md

None.

## Not done

- 4.4: an import of an archive with keys between two environments that use the same KMS key. It needs a KMS provider; the
  unwrap check is covered only with a fake `KeyManagementService`.

## Known limits

- Only the importing pod forgets its cached key. Other pods keep it until they restart, cannot decrypt the imported secrets
  meanwhile (their rebuild skips those entities), and lose a secret written on them; the docs ask for a rolling restart with
  no admin writes meanwhile.
- A key stored with a content encoding is exported as stored; the unwrap check decodes gzip first, and rejects any other
  encoding, invalid gzip, or a decoded key over 64 KiB.
- A bucket exported empty has no entries and is not cleared.
- Both bucket locks are held for a single-bucket import.

## Review

Part of pull request #2213, not yet reviewed there.
