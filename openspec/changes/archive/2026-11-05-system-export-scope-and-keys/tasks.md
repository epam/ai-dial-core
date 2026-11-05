# Tasks

## 1. One rule for "KMS is on"

- [x] 1.1 Add `@JsonIgnore` `isUnencrypted()` (the factory's existing test) and `isEnabled()` (true only for `aws`, `azure`, `gcp`, case-insensitive) to `KmsSettings`, and use `kmsSettings == null || kmsSettings.isUnencrypted()` in `KeyManagementServiceFactory.create` so an unsupported provider still throws at startup. Verify with a new `KmsSettingsTest` in `credentials`: no provider, `unencrypted`, an unsupported name -> `isEnabled()` false; `aws`, `AWS`, `azure`, `gcp` -> true; `isUnencrypted()` true only for no provider and `unencrypted`; a new `KeyManagementServiceFactoryTest` (`unencrypted` gives the plain service, an unsupported name throws); and an `ObjectMapper` round trip of `{"provider":"aws","keyId":"k"}` still deserializes and does not write an `enabled` property.
- [x] 1.2 In `AiDial`, move the parsing of `toolsets.security.kms` into a private `readKmsSettings()` used by `getCredentialEncryptionService`; behaviour of the credential path is unchanged. Verify with `./gradlew :server:test --tests "com.epam.aidial.core.server.AdminSystemExportApiTest"` (the app still starts with the test settings).
- [x] 1.3 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :credentials:test --tests "com.epam.aidial.core.credentials.keymanagement.*" --tests "com.epam.aidial.core.credentials.data.configuration.KmsSettingsTest"`; both pass.

## 2. Service: selected buckets and encryption keys

- [x] 2.1 In `AdminSystemExportService`, add the static `resolveBucketLocations(String scope)` (names derived from `MergedConfigStore.ADMIN_BUCKET_LOCATIONS`, `split(",", -1)`, trim, `Locale.ROOT` lower case, null selects all, result in list order, `IllegalArgumentException` for empty value, empty element or unknown name). Verify in `AdminSystemExportServiceTest`: null -> both; `platform` -> `[platform/]`; ` PUBLIC , public` -> `[public/]`; `platform,public` -> `[public/, platform/]`; `org`, `all`, empty, `public,`, `,platform` each throw. (Scenarios: No scope, One bucket, Case/spaces/repeats, Order follows the buckets, Unknown/empty/blank values.)
- [x] 2.2 Change `export` to `export(OutputStream, Instant, List<String> bucketLocations)`: lock and walk only that list. Update the callers in the controller and in `AdminSystemExportServiceTest`. Verify in `AdminSystemExportServiceTest`: `public`-only export holds no entry under `platform/`, and the `platform/` lock can be taken while it runs; with the `platform/` lock held a `public`-only export succeeds and a both-bucket export still gets `AdminSystemExportBusyException` and leaves `public/` free. (Scenarios: One bucket, A bucket outside the scope is not locked, One lock held elsewhere.)
- [x] 2.3 Add a `boolean kmsEnabled` constructor argument and make the exclusion list per instance (`credentials/` always, `encryption_keys/` only when KMS is off). Verify in `AdminSystemExportServiceTest`, with the flag on: both `cek` blobs are exported with their metadata entries, `credentials/` is not, and a `platform`-only export holds only `platform/encryption_keys/cek`; with the flag off the existing `testEncryptionKeysAndCredentialsAreExcluded` still passes. (Scenarios: KMS on, KMS on with one bucket, KMS off, Keys and credentials, Credentials with KMS.)
- [x] 2.4 In `AiDial`, pass `readKmsSettings().isEnabled()` to the service. Verify by `./gradlew :server:compileJava` and the existing API test starting with `kmsEnabled = false`.
- [x] 2.5 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "com.epam.aidial.core.server.service.AdminSystemExportServiceTest"`; both pass.

## 3. Controller: the `scope` parameter

- [x] 3.1 In `AdminSystemExportController.handle`, read `scope` after the admin check, call `resolveBucketLocations`, answer `IllegalArgumentException` with `context.respond(HttpStatus.BAD_REQUEST, message)` and return, before the response, pipe and task are created, and pass the locations to the service. Add `@ApiParameter(name = "scope", in = ParameterIn.QUERY, required = false, ...)` and `@ApiResponse(code = 400)` to `@ApiOperation`. Verify by the API tests in 3.2.
- [x] 3.2 Extend `AdminSystemExportApiTest` (the class runs with a KMS provider set, see 3.3): `scope=platform` gives manifest plus `platform/` entries and nothing under `public/`; `scope=PUBLIC` gives `public/` only; `scope=platform,public` keeps `public/` entries first; `scope=org`, `scope=all`, `scope=`, `scope=public,` answer `400` with no `Content-Disposition` and a following plain export answers `200` (locks free); `scope=org` with the `user` token answers `403`; `scope=public` succeeds while the `platform/` lock is held. (Scenarios: No scope, One bucket, Case/spaces/repeats, Order follows the buckets, Unknown/empty/blank values, Invalid scope from a non-admin, A bucket outside the scope is not locked.) The KMS-off scenarios stay covered in `AdminSystemExportServiceTest` (2.3): settings apply to the whole test class, so one class cannot cover both modes over HTTP.
- [x] 3.3 Cover "KMS on" at the HTTP boundary inside `AdminSystemExportApiTest`: set `toolsets.security.kms` (`provider: aws`, `keyId`, `region`) in its `additionalSettingsOverrides` (Core starts offline, the client is lazy), keep `testExportReturnsArchiveOfBothBuckets` asserting that `encryption_keys` is exported and `credentials` is not, and add `testEncryptionKeysOfTheSelectedBucketsAreExported` (both `cek` blobs, then `scope=platform`). Nothing is deferred.
- [x] 3.4 Run `./gradlew replaceSpec` and check that the generator accepts the new parameter and `400` (the file `docs/open_api_core.yaml` is generated, never hand-edited). Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "com.epam.aidial.core.server.AdminSystemExportApiTest"`; all pass.

## 4. Documentation

- [x] 4.1 Update `docs/admin-system-export.md`: the `scope` parameter with examples and the `400` row of the responses table; replace "`encryption_keys/` and `credentials/` are never exported" and the final operational note with the KMS rule (keys exported when a KMS provider is set, `credentials/` never); say that a restored archive needs the same KMS key and that Core replaces a key it cannot decrypt, so secrets restored without that key become unreadable; say that the export trusts the setting. Verify that the text matches the specs. Running the `curl` examples against a local Core is not part of this change.
- [x] 4.2 In `README.md` line 361, change "streams the `public` and `platform` buckets" to mention the optional `scope`. Verify by reading the rendered section.

## 5. Review follow-up (PR #2237)

- [x] 5.1 Read every `scope` value and combine them; an empty value in any repetition is a `400`. Verified by `testResolveBucketLocations`, `testInvalidScopeIsRejected` (service) and `testScopeSelectsTheBuckets`, `testInvalidScopeIsRejected` (API).
- [x] 5.2 Skip an `encryption_keys` blob with no content encoding that is empty or not longer than `keySize / 8`, with a warning, and pass the key length from `AiDial`. Verified by `testEncryptionKeyThatCannotBeWrappedIsNotExported` (service and API).
- [x] 5.3 Correct the "not yet synced" requirement and docs: a new resource is an empty entry. Verified by `testEmptyBlobIsExportedAsEmptyEntry`.

## Workflow follow-up

- Archive the change with `npx -y @fission-ai/openspec@1.14.3 archive system-export-scope-and-keys -y` after review, then run `validate --specs --strict`.
- Record `review.md` in the change folder before archiving, as the project process requires.
