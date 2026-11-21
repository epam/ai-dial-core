## Context

`AdminSystemExportService` now exports the `encryption_keys` blobs of the selected buckets when a KMS provider is
configured (#2237): the KMS key wraps them, so the archive does not expose them. Without a provider the keys stay out. The
export can also hold a single bucket (`scope`). The manifest does not record which buckets, and an archive is not signed.

`ContentEncryptionKeyManagerImpl.getOrCreateKey` unwraps the stored key with the KMS; when that throws
`CekEncryptionException`, it creates a new key and overwrites the blob. `CachedContentEncryptionKeyManager` keeps the
unwrapped key per pod, with an expiry counted from the last access, so a pod that uses a key often never refreshes it.

## Decisions

- **Keys are accepted only with a KMS provider.** The validation service decides this from `KmsSettings.isEnabled()`, as
  export does. Without a provider the entry is rejected like a credential, since its bytes would be used as a plaintext key.
- **Each key is unwrapped in the stage phase.** The import reads the (small) blob that it is about to stage and calls
  `KeyManagementService.decrypt`. Only `CekEncryptionException` means a bad key and is answered `400`: it is the condition
  under which Core would replace the key. Any other KMS error propagates and is answered `500`, because the key could not be
  verified. An empty key, or one over 64 KiB, is `400` without a KMS call. The check runs before any lock is taken and before
  anything is changed, and costs one KMS call per key, at most two.
- **Buckets come from the entries.** The stage phase collects the bucket location of every blob entry; the replace phase
  wipes, copies and invalidates only those, in the order of `MergedConfigStore.ADMIN_BUCKET_LOCATIONS`. A bucket that was
  exported empty has no entries and is not cleared, which errs towards keeping data. Without a manifest field there is no
  other way to tell it from a bucket that was not exported.
- **Both bucket locks are still taken.** The config rebuild after the replace reads both buckets, so it needs both locks,
  and an import is rare.
- **The wipe still keeps `encryption_keys/`.** The copy overwrites it with the key in the archive; a bucket without a key
  in the archive (export skips an empty or plaintext-sized key, or the bucket had none) keeps the key of the environment.
- **The importing pod forgets its cached keys.** After the copy and the Redis invalidation, and before the rebuild,
  `ContentEncryptionKeyService.invalidateKey` is called for each replaced bucket, so the rebuild decrypts secrets with the
  imported key. `ContentEncryptionKeyManager.invalidate` is a default no-op, which only the caching manager overrides.
- **One KMS client and one key service.** `AiDial` builds them once and passes them to both credential encryption and the
  import, so the import checks keys with the client that Core uses.
- Rejected: trusting the keys in the archive without unwrapping them. A wrong key is only noticed when the rebuild
  replaces it, and then nothing says why every secret is unreadable.
- Rejected: locking only the buckets of the archive. It needs a rebuild that reads one bucket.

## Risks

- Other pods keep the cached key until they restart or it expires, and a pod that uses it often does not expire it: the
  periodic config rebuild reads it every minute. Until then such a pod cannot decrypt the imported secrets, so its rebuild
  skips every entity with one, and a secret written there is encrypted with the old key and is lost. The docs ask for a
  rolling restart without admin writes meanwhile. A cluster-wide invalidation would need `ResourceTopic`, which does not
  publish for `encryption_keys` and which the import's direct blob copy bypasses.
- A KMS outage makes an import of an archive with keys fail with `500` in the stage phase, with nothing changed.
- An archive with keys restores only where the same KMS key is usable. The unwrap check turns a wrong environment into
  `400`.

## Migration Plan

None. An archive without `encryption_keys/` entries imports as before, except that a bucket it has no entries for is no
longer wiped.
