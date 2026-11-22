## 1. Publish and receive

- [x] 1.1 `ResourceService.publishUpdate`; test `ResourceTopicServiceWiringTest.publishUpdateReachesSubscribersWhateverTheResourceType`
- [x] 1.2 `CachedContentEncryptionKeyManager` takes the `ResourceService` (`ContentEncryptionKeyManagerFactory` passes it), publishes
  in `invalidate` and drops the entry of a key URL on an event; tests in `CachedContentEncryptionKeyManagerTest`

## 2. Docs and checks

- [x] 2.1 `docs/admin-system-import.md`: no restart after an import, a restart for a key placed by hand or a pod that missed the
  message, and what "same KMS key" means for the CEKs
- [x] 2.2 `./gradlew checkstyleMain checkstyleTest`; credentials, storage and the import and export test classes
- [x] 2.3 `npx -y @fission-ai/openspec@1.14.3 validate system-import-key-invalidation --strict`
- [ ] 2.4 (deferred: needs two pods on a KMS provider) Import an archive with keys and check that a second pod decrypts the
  imported secrets without a restart
