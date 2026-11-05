# Design

## Context

See proposal.md for why. Today's behaviour, on `development`:

- `AdminSystemExportController.handle` (`server/.../controller/AdminSystemExportController.java:77`) checks admin, sets the
  headers hook, then submits `AdminSystemExportService.export(out, start)` to the task executor. A `409` or `500` is
  answered from the failure handler only while the head is not written.
- `AdminSystemExportService.export` (`server/.../service/AdminSystemExportService.java:79`) takes
  `lockService.tryLockBuckets(MergedConfigStore.ADMIN_BUCKET_LOCATIONS)` and `writeArchive` walks the same list.
  `ADMIN_BUCKET_LOCATIONS` is `[public/, platform/]` (`MergedConfigStore.java:142`), and is also the lock set of every admin
  writer. `LockService.tryLockBuckets` already sorts and de-duplicates its keys (`LockService.java:299`), so the order in
  which locks are taken does not depend on the order of the list passed in.
- `shouldExport` (`AdminSystemExportService.java:132`) drops a blob whose first segment under the bucket is in the static
  `EXCLUDED_GROUPS` (`encryption_keys/`, `credentials/`).
- A CEK lives at `<bucket>/encryption_keys/cek`
  (`ContentEncryptionKeyService.getContentEncryptionKeyDescriptor`, `credentials/.../ContentEncryptionKeyService.java:36`).
  Platform secrets use the `platform` CEK (`AiDial.java:267`, `SecretFieldProcessor` with `platformBucketInfo`); secrets of
  `public`-scoped resources use the `public` one. The blob holds `keyManagementService.encrypt(cek)`
  (`ContentEncryptionKeyManagerImpl.createKey`).
- `KeyManagementServiceFactory.create` (`KeyManagementServiceFactory.java:22`) returns `SimpleKeyManagementService`, an
  identity function, when `kmsSettings` is null, `provider` is null or `provider` equals `unencrypted`. So with those
  settings the stored CEK is plaintext. `KmsSettings` is parsed from `toolsets.security.kms` in
  `AiDial.getCredentialEncryptionService` (`AiDial.java:500`), which runs before the export service is created
  (`AiDial.java:425`).
- Query parameters on admin and metadata endpoints are read with `request.getParam(name)`. An early error status is
  answered with `context.respond(status, message)` and `return Future.succeededFuture()`, as the admin check in
  `AdminSystemExportController.handle` does. `ProxyContext.respond(int, Buffer)` is the one funnel for short and error
  responses: it records the failure status on the trace span and logs a warning. An `HttpException` thrown out of
  `handle` is answered by `Proxy.handleError` without either.
  `ProxyContext.getBooleanRequestQueryParam` is lenient (`Boolean.parseBoolean`), which is not wanted here.

## Goals / Non-Goals

**Goals:**
- A `scope` list that selects buckets and that needs no change when a bucket is added to or renamed in
  `ADMIN_BUCKET_LOCATIONS`.
- One rule for "KMS is on", shared by the factory that builds the KMS service and by the export.

**Non-Goals:**
- Anything about import, user buckets, `credentials`, or checking a CEK blob before exporting it (see proposal.md).
- A new setting.

## Decisions

### 1. Scope names are derived from `ADMIN_BUCKET_LOCATIONS`

A scope name is a bucket location without its trailing `/` (`public/` is `public`). Valid names are exactly those
derived from `ADMIN_BUCKET_LOCATIONS`; the selected buckets are the entries of that list whose name was requested, in
list order. A bucket added to the list becomes selectable, and a rename changes the accepted name with the constant.

Rejected: a separate enum or map of scope names to locations. It is a second place to update, and nothing derives the
right value from it. Rejected: a boolean such as `includePublished`. It answers only "with or without public"; the
list answers "which two of three" without a new parameter when a bucket is added.

### 2. Parse and validate in the controller, before anything starts

`handle` reads `scope` right after the admin check, so a non-admin gets `403` first. It calls a static
`AdminSystemExportService.resolveBucketLocations(String scope)`, which returns the selected locations and throws
`IllegalArgumentException` for an empty value, an empty element or an unknown name; the controller answers that with
`context.respond(HttpStatus.BAD_REQUEST, message)` and returns, like the admin check, so the `400` goes through the same
funnel as the `403`, `409` and `500`. The message lists the valid names. This happens before the chunked
response, the pipe, the task and the locks exist, so a `400` has no `Content-Disposition` (the headers hook only
fires for `200`) and takes no lock.

Parsing: split on `,` keeping trailing empties (`split(",", -1)`), trim, lower-case with `Locale.ROOT` (a Turkish default
locale would turn `PUBLIC` into a name with a dotless `i`), collect into a set. No `scope` (null) selects every entry.
`scope=` (empty) is a `400`, not "all": the caller named a scope and named nothing.

Rejected: lenient parsing that ignores unknown or empty names. A typo such as `scope=publc` would produce a platform-only
archive of a backup that the operator believes complete. Rejected: an `all` value. Omitting `scope` already means that,
and `all` would need a rule for mixing it with names.

Assumption: a repeated `scope=a&scope=b` is read as the first value, which is what `getParam` returns. No spec states
it.

### 3. The service takes the selected locations

`export(OutputStream out, Instant start, List<String> bucketLocations)` locks and walks that list instead of
`ADMIN_BUCKET_LOCATIONS`. The old two-argument signature goes away, and `AdminSystemExportServiceTest` and the
controller are updated. The lock is `tryLockBuckets(bucketLocations)`, so an export of `public` does not hold the
`platform` lock and the other way round. Writers that lock both buckets still wait for an export that holds either one;
writers that need only the unselected bucket (publication approval for a platform-only export) do not wait.

### 4. "KMS is on" is a method of `KmsSettings`, used by the factory and the export

`KmsSettings` gets two `@JsonIgnore` methods. `isUnencrypted()` is the factory's existing early-return test, unchanged:
`provider == null || "unencrypted".equals(provider)`. `isEnabled()` is true only for `aws`, `azure` or `gcp`
(case-insensitive, as the factory's own provider switch). `KeyManagementServiceFactory.create` uses `isUnencrypted()` in
place of its inline test, so its behaviour does not change: an unsupported provider still reaches the `else` branch and
throws "Unknown toolsets.security.kms.provider" at startup. Using `!isEnabled()` there would instead turn a typo in the
provider into a silent plaintext setup. An unsupported provider is neither unencrypted nor enabled, so the export would
not include keys for it either, although Core never gets that far. `AiDial` moves the `toolsets.security.kms` parsing into a private `readKmsSettings()` used by
`getCredentialEncryptionService` and by the construction of `AdminSystemExportService`, which receives the boolean.

The comparison stays case-sensitive for `unencrypted`, as in the factory. A provider spelled `Unencrypted` makes the
factory throw "Unknown toolsets.security.kms.provider" at startup, so Core never runs with it.

Rejected: a new `adminSystemExport` setting that says whether to include keys. It is a second source of truth that can
say "include" while KMS is off, which would put plaintext CEKs in the archive. Rejected: inspecting the built
`KeyManagementService` (`instanceof SimpleKeyManagementService`). The real service is wrapped by the tracing decorator for
every provider, and it ties the export to a class.

### 5. The exclusion list is per service instance

`EXCLUDED_GROUPS` becomes an instance field: `credentials/` always, `encryption_keys/` when KMS is off. `shouldExport`
stays a prefix test on `bucketLocation + group`, so a deeper `encryption_keys` segment is still exported, as before. The
service constructor gets a `boolean kmsEnabled`. With KMS on, a CEK blob is exported like any other blob: metadata entry
first, then bytes as stored.

### 6. Documentation

`docs/admin-system-export.md`: describe `scope`; replace "`encryption_keys/` and `credentials/` are never exported" and
the last operational note with the KMS rule; state that an archive with CEKs restores only where the same KMS key is
usable, and why this matters (Risks). The parameter is added to the `@ApiOperation` of the controller with
`@ApiParameter(in = ParameterIn.QUERY)` and `@ApiResponse(code = 400)`, as in `AnthropicModelController`;
`docs/open_api_core.yaml` is regenerated, not edited.

## Risks / Trade-offs

- **A plaintext CEK in the archive while KMS is on.** The export trusts the setting. A blob written while KMS was off, or
  a new wrapped CEK still in the write queue (`ResourceTypes.ENCRYPTION_KEYS` is cached and synced like other resources),
  can be a plaintext CEK in blob storage. → Accepted and stated in the proposal: switching providers already invalidates
  existing encrypted values, so it is an operator event. The docs state that the export trusts the setting.
- **Restoring a wrapped CEK without its KMS key destroys the secrets.** `ContentEncryptionKeyManagerImpl.getOrCreateKey`
  generates and stores a new CEK when `decrypt` fails, so a restored CEK that the target's KMS cannot decrypt is
  replaced, and every restored `ENC[...]` value becomes unreadable. → Documented. The import design has to check this.
- **The archive now holds the wrapped CEKs next to the ciphertext.** Anyone who also may decrypt with the KMS key can read
  the secrets. → This is the same exposure as the live bucket; docs keep "treat the archive as confidential".
- **Behaviour change for current callers.** With KMS on, archives now hold `encryption_keys` blobs. → Preview endpoint;
  callers that copy the CEKs out of band can stop.
- **Scope names equal bucket names.** A future rename of a bucket renames the accepted value with it unless the name
  is derived differently. → Accepted: the derivation is in one place (decision 1).

Effect per request: one string split per call, no extra blob or Redis round trips. A scoped export lists and reads fewer
blobs and takes fewer locks. With KMS on, up to two more blobs are read. No measurement task: no cost claim is made
beyond "fewer blobs for a smaller scope".

## Migration Plan

None. No stored data changes and no setting is added. Archives made before this change contain no CEKs and no manifest
field is added, so they stay readable as before. Rollback is a revert.
