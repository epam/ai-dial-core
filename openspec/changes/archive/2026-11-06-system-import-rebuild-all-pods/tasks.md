## 1. Publish and receive

- [x] 1.1 `ResourceService.publishUpdate(String url)`; the descriptor overload delegates to it
- [x] 1.2 `MergedConfigStore.requestRebuildOnAllPods()` and the `config/rebuild` event in `onResourceEvent`; tests in
  `MergedConfigStoreReplicaUpdateTest`
- [x] 1.3 `AdminSystemImportService.rebuildMergedConfig` calls it after the local rebuild; a failure adds a warning; tests in
  `AdminSystemImportServiceTest`

## 2. Docs and checks

- [x] 2.1 `docs/admin-system-import.md`: other pods are told to rebuild
- [x] 2.2 `./gradlew checkstyleMain checkstyleTest`; storage and the import and config store test classes
- [x] 2.3 `npx -y @fission-ai/openspec@1.14.3 validate system-import-rebuild-all-pods --strict`
- [ ] 2.4 (deferred: needs two pods) Import on one pod and check that a second pod lists the imported model within a few
  seconds
