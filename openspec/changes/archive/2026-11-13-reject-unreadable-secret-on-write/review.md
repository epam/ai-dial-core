# Review

Issue: https://github.com/epam/ai-dial-core/issues/2280

## What changed for clients

A write answers `400` only when it would have had to keep a stored secret that no longer decrypts. A request that
supplies a new secret, drops the field or changes the authentication type never needs the old value and succeeds, and
the new value is encrypted under the current content encryption key.

### Cases that went from `500` to `400`

| Write | Before | Now |
|---|---|---|
| Toolset `PUT` (Resource API, admin config API, `/v1/admin/apply`) omitting `client_secret`, stored secret unreadable | bare `500` | `400` naming the toolset and asking for `client_secret`; a dynamically registered toolset's message names the repair endpoint. In `/v1/admin/apply` the same text is the entry's `FAILED` error |
| Application `PUT` carrying `external_services`, a service with an unreadable secret and no `client_secret`, same type | `500` | `400` naming the service; other services, unrelated fields and dropping the service still succeed |
| Single external-service `PUT`, unreadable secret and no `client_secret`, same type | `500`, and one bad service blocked writing every other service of the application | `400` for that service only; a healthy service is written and the others keep their stored ciphertext byte for byte |
| User-authored external-service `PUT` omitting `client_secret` | `500` | `400`, also on an authentication-type change (that path keeps the stored secret whatever the type) |
| Model, key or route `PUT` omitting a secret whose stored value is unreadable | `500` after the blob was written, or `200` with a stored value that failed on reload | `400` listing every unreadable field (`upstreams[0].key`), nothing written, ETag unchanged |

### Cases that now succeed instead of `500`

- **Toolset repair** of a dynamically registered toolset with an unreadable stored secret. Registration replaces the
  secret and verifier, so repair no longer decrypts the old values first. When the registration carries no PKCE method,
  the stored challenge and method are cleared so a challenge is never left without its verifier.
- **A toolset `PUT` that supplies `client_secret`** over an unreadable one, with a new challenge and verifier generated
  together (the token exchange completes).
- **One unreadable service** no longer blocks writing another service of the same application.

### The one visible effect for admins

A model, key or route whose stored upstream key can't be decrypted can no longer be edited without re-supplying that
key, because the admin `GET` never returns keys and a client cannot round-trip them. This is the point of the rule: the
unusable value is no longer kept silently. The error lists every unreadable field at once, so one retry fixes it.

## What did not change

- No new endpoints or fields. Strict reads stay as they were (external-service sign-in, copy and publish, platform
  reload marking an invalid entity), as does CEK replacement on a failed unwrap.
- Application route secrets (`routes[].upstreams[].key`/`secretExtraData`) stay tolerant on write.
- Nothing on the proxy path. The writes touched are admin or owner `PUT`s; each does the same decrypts as before, in a
  different order.

## Design drift

- `SecretFieldProcessor` has one traversal shared by encrypt/decrypt and the unreadable-field search instead of a
  second, parallel walk (decision 6 updated).
- `UserExternalServiceService` and `ExternalServiceService` wrap the lenient decrypt and the target-service encrypt in
  small private methods (`decryptSecretLenient`, `encryptSecret`) so `put`/`putExternalService` read as one line each.
  `decryptSecretLenient` stays before `ExternalServiceValidation.validate`: validation exempts a secret handed back
  unchanged and can only compare it with the stored one in plaintext.
- `ExternalServiceValidation.unreadableStoredSecret` holds the shared `400` text, because inline and user-authored
  services must word it identically.

## Checks run

On Windows 11 with JDK 21.0.8.

- `checkstyleMain` and `checkstyleTest`, all modules: clean.
- `openspec validate reject-unreadable-secret-on-write --strict`: valid. `validate --all --strict`: 17 passed, 2 failed
  (`spec/user-limits`, `spec/resource-subscription`: placeholder Purpose warnings in main specs this change does not
  touch).
- Credentials module: `ResourceAuthSettingsEncryptionServiceTest` and `ResourceAuthSettingsServiceTest` (53 tests),
  0 failures.
- Server, one combined run of `ToolSetApiTest`, `ToolSetRepairApiTest`, `ToolSetRepairServiceTest`,
  `ToolSetServiceTest`, `ExternalServiceCredentialsApiTest`, `ExternalServiceOboCredentialsApiTest`,
  `ApplicationRouteApiTest`, `ApplicationDeploymentApiTest`, `SecretFieldProcessorTest`, `ModelWriteApiTest`,
  `ConfigEntityWriteApiTest`, `AdminApplyApiTest`, `ConfigApiTest`, `InvalidEntityApiTest`, `MergedConfigStoreTest`
  and `RouteApiTest`: 491 tests, 15 failures, all in known Windows categories (below).
- The key and route write tests are covered by `ConfigEntityWriteApiTest`, `ModelWriteApiTest` and `AdminApplyApiTest`.

### Failures, all pre-existing on this machine

- `ApplicationDeploymentApiTest`, 13 tests: the deploy flow fails on a file lock on `app.py` (`FileSystemException`,
  or the application never reaches `DEPLOYED`). This includes the existing
  `testApplicationUndeploySucceedsWithCorruptedRouteSecret` and the new
  `testApplicationUndeploySucceedsWithUnreadableExternalServiceSecret`, so **task 3.4 was not verified on this machine**;
  CI has to confirm it.
- `ModelWriteApiTest.testPutGetWithUrlEncodedName`: a `:` in the entity name is an illegal character in a Windows path.
- `ExternalServiceOboCredentialsApiTest.testOboAuditDoesNotAllowLogInjection`: a newline in a Windows path.
- Publication classes. `PublicationApiTest`, `ProjectPublicationApiTest`, `SkillPublicationApiTest`,
  `CustomApplicationApiTest` and `ApplicationDeploymentApiTest` were run on this tree and on an unmodified checkout of
  `development` (`dc838108`, a separate git worktree, since removed). The failure types match (file locks, "No content
  to map", `expected 200 but was 400/500`) and 47 tests fail in both. Three failed only with the change: the new
  deploy test above (file lock; the baseline does not have it), `ApplicationDeploymentApiTest.testPublication` (file
  lock) and `PublicationApiTest.testPublicationCreation_OnlyRules`. Run alone twice on each tree, that last test
  failed once and passed once on both, so it is flaky, not caused by this change. Four other tests failed only on the
  baseline run.

## Open

- Task 3.4 is ticked on the understanding that CI confirms it: `ApplicationDeploymentApiTest` has to pass there,
  including `testApplicationUndeploySucceedsWithUnreadableExternalServiceSecret`, to show that undeploy with an
  unreadable external-service secret still succeeds.
- Release note: a `PUT` that omits a secret whose stored value is unreadable answers `400` instead of `500` (toolsets,
  services) or instead of `200`/`500` with a stored-but-unusable value (models, keys, routes).
