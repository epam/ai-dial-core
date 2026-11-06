## Why

Pull request #2237 changed what `POST /v1/admin/system-export` writes, and the system import was written against the old
archive:
- With a KMS provider (`aws`, `azure`, `gcp`), the archive carries `public/encryption_keys/cek` and
  `platform/encryption_keys/cek`, wrapped by the KMS key. The import answers `400` for any such entry, so it rejects
  an archive that export now produces.
- With `scope`, an archive can hold one bucket. The replace phase deletes every blob under both buckets, so importing a
  `scope=platform` archive wipes all of `public/`.

## What Changes

- **Encryption keys.** With a KMS provider, an `encryption_keys/` entry is accepted. Before anything is changed, the
  import unwraps each key with the KMS of this environment and answers `400` if that is not possible. Core would replace
  a key it cannot decrypt with a new one, and every restored secret would become unreadable. Without a KMS provider, such
  an entry is still rejected, because the bytes would be used as a plaintext key. `credentials/` entries are rejected in
  both cases.
- **Replace only the buckets in the archive.** The replace phase wipes, copies and invalidates only the buckets that have
  at least one blob entry. The other bucket is not touched. Both bucket locks are still taken.
- **Keys.** The key of an imported bucket is overwritten by the key in the archive. A bucket with no key in the archive keeps
  the key of the environment. After the copy, the importing pod forgets its cached copy of each imported bucket's key.
- New `ContentEncryptionKeyManager.invalidate` and `ContentEncryptionKeyService.invalidateKey`, to forget a cached key.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `system-import`: "Encryption keys and credentials are not importable", "The buckets are replaced" and "Imported
  content is what Core serves"; a new requirement "Encryption keys from the archive".

## Impact

- Issue [#2204](https://github.com/epam/ai-dial-core/issues/2204), pull request #2213, after #2237.
- Code: `AdminSystemImportService`, `AdminSystemImportValidationService`, `AiDial` (the KMS client and the key service are
  now built once and shared with the import), `ContentEncryptionKeyManager`, `CachedContentEncryptionKeyManager`,
  `ContentEncryptionKeyService`. Docs: `docs/admin-system-import.md`.
- Operators: other pods keep their cached copy of an imported key until they restart, so after an import that brought keys,
  restart them in a rolling way and do not write admin config until they have.
- Out of scope: a manifest field that records the exported buckets (declined in #2237), clearing a bucket that was exported
  empty, and invalidating the key cache on the other pods.
