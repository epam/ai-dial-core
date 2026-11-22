## 1. Key cache

- [x] 1.1 `ContentEncryptionKeyManager.invalidate` (default no-op), `CachedContentEncryptionKeyManager.invalidate`,
  `ContentEncryptionKeyService.invalidateKey`; tests `CachedContentEncryptionKeyManagerTest`,
  `ContentEncryptionKeyServiceTest.invalidateKey_forRootBucket`
- [x] 1.2 `AiDial`: build the KMS client and the key service once and share them with credential encryption and the import

## 2. Validation

- [x] 2.1 `AdminSystemImportValidationService`: `encryption_keys/` accepted with a KMS provider, rejected without one;
  `credentials/` always rejected; `isEncryptionKey`; `validateEncryptionKey` decodes a gzip key and unwraps it with the KMS (400 only for
  `CekEncryptionException`, empty, over 64 KiB, bad encoding or bad gzip), tests in `AdminSystemImportValidationServiceTest`
- [x] 2.2 Stage phase: each key is checked as it is staged

## 3. Replace

- [x] 3.1 `StagedImport.bucketLocations`: the buckets with blob entries; wipe, copy and invalidation use only those
- [x] 3.2 After the copy, `invalidateEncryptionKeys` for the replaced buckets
- [x] 3.3 Tests in `AdminSystemImportServiceTest`: `testBucketWithoutEntriesIsLeftUntouched`,
  `testEncryptionKeyFromTheArchiveReplacesTheTargetKey`, `testEncryptionKeyThatCannotBeUnwrappedIsRejected`, and
  `testCredentialsAreWiped` and `testEncryptionKeysSurvive` with entries in both buckets

## 4. Docs and checks

- [x] 4.1 `docs/admin-system-import.md`: keys with KMS, the unwrap check, the rolling restart, archives of one bucket
- [x] 4.2 `./gradlew checkstyleMain checkstyleTest`; `:credentials:test` and the import and export test classes
- [x] 4.3 `npx -y @fission-ai/openspec@1.14.3 validate system-import-scope-and-keys --strict`
- [ ] 4.4 (deferred: needs a KMS provider and a second environment) Import an archive with keys between two environments
  that use the same KMS key, then roll-restart the pods
