# Tasks

The group order follows the dependency: the credentials module first (the rule and the flag), then each path that uses
it. Every group ends with `./gradlew checkstyleMain checkstyleTest` and its test classes with `--tests` (Gradle 8.10
needs a JDK 21 as `JAVA_HOME`). Tests plant an unreadable secret the way `ApplicationRouteApiTest` and
`InvalidEntityApiTest` do: a structurally valid envelope or Base64 that no key decrypts, written with
`ResourceService.putResource`.

## 1. Credentials module: lenient decrypt, the flag, the PKCE pair

- [x] 1.1 Add `ResourceAuthSettingsEncryptionService.decryptLenient`, returning whether a stored `clientSecret` could not
  be decrypted and nulling `clientSecret` and `codeVerifier` in that case. Verify in `ResourceAuthSettingsEncryptionServiceTest`:
  readable secret returns `false` and decrypts both fields; undecryptable Base64 secret returns `true` and nulls both;
  absent secret returns `false`; plaintext legacy secret returns `true`.
- [x] 1.2 Add the `existingSecretUnreadable` parameter to `ResourceAuthSettingsService.processResourceAuthSettings`
  (design decision 2) and update the existing calls in `ResourceAuthSettingsServiceTest` and `ToolSetService`. Verify
  in `ResourceAuthSettingsServiceTest`: flag with a null body secret throws `IllegalArgumentException` and the message
  names the toolset; dynamically registered adds the repair hint; flag with a body secret does not throw; flag with a
  changed authentication type does not throw.
- [x] 1.3 In `resolveCodeChallengeSettings`, regenerate the challenge and verifier when the flag is set and a method
  exists (design decision 3). Verify in `ResourceAuthSettingsServiceTest`: the new pair differs from the stored one and
  `challenge` matches `verifier`; no method leaves both `null`.
- [x] 1.4 Run `./gradlew checkstyleMain checkstyleTest :credentials:test --tests "*ResourceAuthSettingsEncryptionServiceTest" --tests "*ResourceAuthSettingsServiceTest"`.

## 2. Toolset write and repair

- [x] 2.1 In `ToolSetService.writeToolSet`, call `decryptLenient` on `existing` and pass the flag to
  `processResourceAuthSettings`. Verify in `ToolSetApiTest` (extend the preserve-on-omit tests; scenarios "New secret
  replaces an unreadable one" and "Omitted secret"): `PUT` with a new `client_secret` answers `200` and `GET` carries
  `client_secret_hint`; the same `PUT` without it answers `400`, the ETag is unchanged, and the body holds neither the
  stored ciphertext nor a crypto message.
- [x] 2.2 Verify the PKCE scenario in `ToolSetApiTest`: after the `200`, a sign-in completes its token exchange with a
  `code_verifier` that matches the `code_challenge` of its authorization request (the `MockWebServer` token endpoint
  asserts it).
- [x] 2.3 Verify the remaining toolset scenarios in `ToolSetApiTest`: a dynamically registered toolset's `400` names the
  repair endpoint; `OAUTH` to `API_KEY` with the key answers `200`; and in `ConfigApplyApiTest` (or the class that holds
  the toolset apply tests) a manifest without `client_secret` yields a `FAILED` entry with the same message.
- [x] 2.4 In `ToolSetRepairService.repair`, drop the decrypt at `:62`, use `decryptLenient` at `:84`, and clear
  `codeChallenge`/`codeChallengeMethod` when the verifier was nulled and the registration has no PKCE method (design
  decision 5). Update the call-order assertion in `ToolSetRepairServiceTest` (it expects `decrypt, decrypt,
  applyRegistration, encrypt`) and add a case for the unreadable secret. Verify in `ToolSetRepairApiTest`: repair of a
  dynamically registered toolset with an unreadable secret answers `200`, `GET` shows the new `client_id` and a
  `client_secret_hint`, and credentials are cleared; `testRepairRejectedForStaticOauthToolset` still passes.
- [x] 2.5 Document the `400` and the repair route for a dynamically registered toolset in
  `docs/dynamic-settings/toolset_credentials_api.md`; verify the section reads correctly against the `ToolSetApiTest`
  assertions. Check that the `@ApiResponse` lists of the toolset `PUT` in `ResourceController`, the repair controller and
  the config API name `400`; fix the annotations if not, and never hand-edit `docs/open_api_core.yaml`.
- [x] 2.6 Run `./gradlew checkstyleMain checkstyleTest` and `:server:test --tests "*ToolSetApiTest" --tests "*ToolSetRepairApiTest" --tests "*ToolSetRepairServiceTest" --tests "*ToolSetServiceTest"`.

## 3. External services (inline and user-authored)

- [x] 3.1 Add `ExternalServiceService.decryptSecretsLenient` returning the unreadable service ids, use it in
  `processOnWrite`, and make `preserveOmittedSecrets` throw the `400` for an unreadable, omitted, same-type service
  (design decision 2). Verify in `ExternalServiceCredentialsApiTest` (extend the secret-preserved-when-omitted tests):
  application `PUT` with `myapi` and no `client_secret` answers `400` naming `myapi` and leaves the application
  unchanged; with a new secret it answers `200`; with `external_services` dropping `bad` it answers `200` and `bad` is
  gone; with a changed `authentication_type` it answers `200`.
- [x] 3.2 Rework `putExternalService` to decrypt and encrypt only the target service and to apply the same `400`
  (design decision 4). Verify in `ExternalServiceCredentialsApiTest`: with `bad` unreadable and `good` healthy, the
  service `PUT` for `good` answers `200`, `good` carries a `client_secret_hint` of its new secret, and `bad`'s stored
  value is byte-identical to before; the service `PUT` for `bad` answers `400` without `client_secret` and `200` with it.
- [x] 3.3 Make `UserExternalServiceService.put` use the lenient decrypt and throw the same `400` from
  `preserveOmittedSecret`. Verify in `ExternalServiceCredentialsApiTest`: omitted secret answers `400`, a new one `200`.
- [x] 3.4 Verify that undeploy of an application with an unreadable service secret still succeeds (the risk in design.md
  for server-initiated rewrites), next to `ApplicationDeploymentApiTest.testApplicationUndeploySucceedsWithCorruptedRouteSecret`.
  The test `testApplicationUndeploySucceedsWithUnreadableExternalServiceSecret` is written and compiles. It did not run
  on the Windows machine this was built on: every deploying test in `ApplicationDeploymentApiTest`, the existing
  route-secret one included, fails there on a file lock on `app.py`. CI is the check.
- [x] 3.5 Run `./gradlew checkstyleMain checkstyleTest` and `:server:test --tests "*ExternalServiceCredentialsApiTest" --tests "*ExternalServiceOboCredentialsApiTest" --tests "*ApplicationRouteApiTest"` (the last keeps the tolerant route-secret write passing). `ApplicationDeploymentApiTest` is covered by 3.4. The one failure in the OBO class, `testOboAuditDoesNotAllowLogInjection`, is a Windows path error that does not touch this change.

## 4. Models, keys and routes (config API)

- [x] 4.1 Add `SecretFieldProcessor.findUnreadableFields(entity, descriptor)`, walking with an index/key prefix and
  returning paths such as `upstreams[0].key` (design decision 6). Verify in `SecretFieldProcessorTest`: a mixed list of
  readable and unreadable upstreams returns exactly the unreadable paths, including `interfaces` entries; plaintext and
  `${SECRET:` values are never reported; `decryptFields` and `decryptFieldsLenient` behave as before.
- [x] 4.2 Call it in `ConfigResourceController.handlePut` after the merge and before `encryptFields`, throwing
  `HttpException(BAD_REQUEST)` that lists every path; apply it to the `Key` branch too, leaving the non-fatal prior-secret
  read as is. Verify in `ModelWriteApiTest` (scenarios "Model update that would keep an unreadable key" and "New key
  replaces an unreadable one"): the `400` names `upstreams[0].key`, the ETag and the `display_name` are unchanged; a `PUT`
  that supplies the key for `upstreams[0]` answers `200` and the stored key of `upstreams[1]` still works.
- [x] 4.3 Verify "Key update that would keep an unreadable secret" and the route scenario "Update without the key when
  the stored key is unreadable" in the key and route write tests (the classes that hold `testPutPreservesOmittedSecret`'s
  siblings): `400`, then `200` with a new secret, and `Api-key: <new>` authenticates.
- [x] 4.4 Run `./gradlew checkstyleMain checkstyleTest` and `:server:test --tests "*SecretFieldProcessorTest" --tests "*ModelWriteApiTest"` plus the key and route write test classes. The one failure in `ModelWriteApiTest`, `testPutGetWithUrlEncodedName`, is a `:` in a Windows path and does not touch this change.

## 5. Integration

- [x] 5.1 Run `npx -y @fission-ai/openspec@1.14.3 validate reject-unreadable-secret-on-write --strict` and
  `validate --all --strict`; fix any finding in the change, not in the main specs. (the change is valid; the two
  `--all` failures are placeholder-Purpose warnings in `user-limits` and `resource-subscription`, main specs this change
  does not touch)
- [x] 5.2 Run every test class named above together, plus `ConfigApiTest`, `InvalidEntityApiTest` and
  `MergedConfigStoreTest`, to confirm reads, the invalid-entity surface and the reload are unchanged. (491 server tests,
  15 failures, all pre-existing Windows failures; publication classes compared with an unmodified `development`, see
  `review.md`)
- [x] 5.3 Write `review.md` in the change folder: the cases that went from `500` to `400`, the one visible effect for
  admins (a model with an unreadable key needs the key re-supplied), and the checks run.

## Workflow follow-up

- Archive with `npx -y @fission-ai/openspec@1.14.3 archive reject-unreadable-secret-on-write -y` once the tasks above are
  checked and `review.md` exists, then `validate --specs --strict`.
- One commit, staging explicit paths; the orchestrator commits.
