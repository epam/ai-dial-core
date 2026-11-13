# Design

## Context

Two secret surfaces, two encryption services (see proposal.md - Why for the failure):

- `ResourceAuthSettings.clientSecret` and `codeVerifier`, encrypted by `ResourceAuthSettingsEncryptionService`
  (credentials module) for toolsets, application external services and user-authored external services. The
  ciphertext is bare Base64 with no marker, and the AAD is the resource URL (toolsets), or the URL plus
  `/external_services/<id>` (applications, `ExternalServiceService.secretAad`), or the absolute file path (user-authored,
  `UserExternalServiceService.put`).
- The `@EncryptedField` fields (`Key.key`, `Upstream.key`/`secretExtraData`, `UpstreamInterface.key`/`secretExtraData`),
  encrypted by `SecretFieldProcessor` as `ENC[...]`, AAD the absolute file path.

What decides every outcome below is **who decrypts the old value, and when**:

| Path | Decrypts the stored value | Today |
|---|---|---|
| `ToolSetService.writeToolSet`:192 | strict, before the request is read | `EncryptionException`, bare `500` (`ResourceController.handleError`) |
| `ExternalServiceService.processOnWrite`:65 | strict, every stored service | same |
| `ExternalServiceService.putExternalService`:139 | strict, every service of the app | same, and blocks a healthy service |
| `UserExternalServiceService.put`:86 | strict, one blob | same |
| `ToolSetRepairService.repair`:62,84 | strict, twice | same |
| `ConfigResourceController.handlePut`:1921 | none (JSON-level merge), but strict `decryptFields`:1965 **after** `putResource` | `500` after the blob is written; `applyEntityWrite` skipped |

`CredentialEncryptionService.encrypt` never needs the old key: `ContentEncryptionKeyManagerImpl.getOrCreateKey`
replaces a CEK that KMS cannot unwrap, so a new secret always encrypts. The old ciphertext is what is lost.

`ResourceAuthSettingsService.processResourceAuthSettings` is called from `ToolSetService.writeToolSet` only (and tests).
Its preserve step (`:88-90`) copies the stored secret into a body that omits it. Error mapping already turns an
`IllegalArgumentException` or `HttpException` into `400` on every path touched: `ResourceController.handleError`,
`ConfigResourceController.handleWriteError`, `ExternalServiceErrorHandler.respond`, and per entry in
`ConfigApplyService` (`catch (Exception ex)` at `:123`, status `FAILED`, `ex.getMessage()`).

## Goals / Non-Goals

**Goals:**
- One rule, the same on every path: `400` exactly where the write would have kept the unreadable value.
- The rule is decided before anything is written, so a refused write leaves the blob and the in-memory config alone.
- A fresh secret never depends on the old one.

**Non-Goals:**
- Reading, listing, copying, signing in or publishing with an unreadable secret (proposal.md - Out of scope).
- Changing what counts as unreadable, or healing it silently: the failure stays visible to the client.
- Application route secrets and server-initiated application rewrites.

## Decisions

### 1. Lenient decrypt of the stored side that reports, instead of throwing

`ResourceAuthSettingsEncryptionService` gets `decryptLenient(resourceId, bucketInfo, settings)`, returning `true` when a
stored `clientSecret` was present and could not be decrypted. In that case it sets both `clientSecret` and `codeVerifier`
to `null` and returns, and the caller keeps the flag. The strict `decrypt` stays for every other use.

- A stored plaintext `clientSecret` (written before encryption existed) fails the same decrypt, and is treated the same.
- `codeVerifier` is not tested on its own. It lives in the same bucket under the same AAD, so it fails together with the
  secret. The existing plaintext fallback (`decryptValueWithLegacyFallback`) is unchanged for the readable-secret case.

*Rejected: catch `EncryptionException` at each call site.* Four call sites would each need the same catch, null-out and
flag, and one would drift. *Rejected: leave the ciphertext in place and skip re-encryption.* `encryptValue` has no
"already encrypted" test, because toolset ciphertext carries no marker, so an unreadable value would be encrypted a
second time and look valid. `SecretFieldProcessor` can do that skip only because `ENC[` marks its values.

### 2. The `400` is thrown where the stored value would be copied

- **Toolset:** `processResourceAuthSettings(updated, existing, existingSecretUnreadable)`. In the OAuth-to-OAuth branch,
  where `:88-90` copies the stored secret, the flag with a null body secret throws `IllegalArgumentException`:
  `Stored client_secret of toolset <url> can't be decrypted; send client_secret`. When the stored
  `dynamicallyRegistered` is true, the message adds that the toolset can be repaired. `ToolSetService.writeToolSet` is
  the only production caller, so the parameter replaces the two-argument form and the tests' calls are updated.
- **Inline external services:** `ExternalServiceService.decryptSecretsLenient(resource, application)` returns the ids of
  the unreadable services. `preserveOmittedSecrets` (`:284`) throws
  `HttpException(BAD_REQUEST, "External service '<id>': stored client_secret can't be decrypted; send client_secret")`,
  the wording of `ExternalServiceValidation`, for an id in that set whose body omits the secret and whose type did not
  change. `putExternalService`'s own preserve step (`:147-151`) gets the same check.
- **User-authored:** `UserExternalServiceService.decryptSecretLenient` returns the flag and `preserveOmittedSecret` (`:180`)
  throws the same `400`. That method keeps the stored secret whatever the type, unlike the inline path, so the `400`
  applies on a type change too. This change does not add a type-change exemption there.

The check sits in the preserve step, not in a pre-check, so "would have kept it" is the code that keeps it and the rule
cannot drift from the behaviour.

### 3. A fresh secret over an unreadable one regenerates the PKCE pair

`resolveCodeChallengeSettings` (`ResourceAuthSettingsService:118`) copies the stored verifier. With the flag set, the
stored verifier is `null` (decision 1), so it calls `setCodeChallengeProperties` with the new method or, when the body has
none, the stored one, and issues a new challenge and verifier together. Nothing happens when there is no method.

*Rejected: keep what `decryptValueWithLegacyFallback` returns.* It returns the undecryptable string as-is, `writeToolSet`
re-encrypts it, and `TokenService:43` sends the ciphertext-of-ciphertext as `code_verifier`, so sign-in fails with a
secret that was just fixed. A new pair interrupts a sign-in in flight at that moment, as a method change already does
in the same method.

### 4. The single-service write touches one service

`ExternalServiceService.putExternalService` decrypts and encrypts only the target service. The other services keep the
ciphertext they were loaded with: `serializeEncrypted` runs `secretFieldProcessor.encryptFields` over the application,
which leaves a valid `ENC[` envelope alone, and the external-service secrets are untouched by it.

*Rejected: decrypt every service leniently and null the bad ones.* It would write `null` over an unrelated service's
stored secret, drop the evidence of the failure, and re-encrypt every secret of the application on every write for no
reason. *Rejected: keep strict decrypt of the whole application.* It is the blast radius of the report: one bad secret
blocks writing a healthy service.

`processOnWrite` (application `PUT` carrying `external_services`) cannot use this, because the body is the desired state
for the whole map. It decrypts the stored side leniently as in decision 2 and only `400`s a service that would be kept.

### 5. Repair does not read the old secret

`ToolSetRepairService.repair` drops the first decrypt (`:62`). Dynamic registration reads only `getRedirectUri()` from the
settings it is given (`DynamicResourceRegistrationStrategy:129`). The decrypt inside `computeResource` (`:84`) becomes
`decryptLenient`, and stays only so that fields `applyRegistration` does not replace are not encrypted twice. After
`applyRegistration`, if the stored verifier was nulled and the registration carries no PKCE method, the stored
`codeChallenge` and `codeChallengeMethod` are cleared too, so a challenge is never left without its verifier. Eligibility
(`dynamicallyRegistered`, OAuth) is unchanged, and the test `testRepairRejectedForStaticOauthToolset` keeps its meaning.

### 6. Models, keys and routes: trial-decrypt what is kept, before writing

In `ConfigResourceController.handlePut`, once the merged entity exists (`:1929`) and before `encryptFields` (`:1951`),
`SecretFieldProcessor` checks the entity for values that no longer decrypt. A value with a valid `ENC[` envelope that
fails is reported with its path (`upstreams[0].key`), and the controller throws `HttpException(BAD_REQUEST)` naming each.
The check only reads, so it runs on the entity itself and needs no copy (a simplification found while implementing).
Plaintext values are never reported, so a supplied secret passes, and a secret supplied *instead of* an unreadable one
passes because the merge never copied the old one.

The path needs a small extension: `walk` today knows the field name and class only. `SecretFieldProcessor` now has one
traversal (`traverse`) that visits every encrypted field and tracks where it sits (`Path`, rendered only on demand);
`walk` supplies the encrypt/decrypt action and the new `findUnreadableFields(entity, descriptor)` supplies a read-only
check that collects paths, so the two cannot disagree on which fields exist. `decryptFields` and `decryptFieldsLenient`
are unchanged in behaviour.

With this check the later `decryptFields` (`:1965`) cannot meet a preserved unreadable value, which closes the
`500`-after-write without touching it. The `Key` branch keeps its non-fatal prior-secret read (`:1912-1919`) for
rotation, since a new secret is authoritative, and gains the same check for an omitted `key`.

*Rejected: make `:1965` lenient.* The blob would be written with a value that can never decrypt, the entity would turn
invalid on the next reload, and the client would see `200`. *Rejected: also `400` application route secrets.* The tests
in the proposal's out-of-scope list assert the opposite on purpose, because an undeploy rewrites the application.

`/v1/admin/apply` does not merge with the stored blob for models, keys and routes (the manifest is authoritative), so
it has no preserve step and needs no change. For toolsets and applications it goes through the services above and
reports the `400` text as the entry's `FAILED` error.

## Risks / Trade-offs

- **[A `PUT` that worked now answers `400`]** No: the cases that reach the new `400` answered `500` before
  (toolset, services) or stored an unusable secret (models, keys, routes, which failed on reload). → Release note.
- **[Models: one bad upstream key blocks every edit of the model until re-supplied]** Admin `GET` never returns keys, so
  a client cannot round-trip them. This is what the rule is for, but it is a visible behaviour. → The message lists
  every unreadable field at once, so one retry fixes it.
- **[The verifier of a readable secret that is itself unreadable]** Not detected: only the secret triggers regeneration.
  A bit-rotted verifier next to a readable secret keeps today's behaviour. → Accepted; both keys share a CEK and AAD, so
  they fail together in every observed cause.
- **[Server-initiated rewrites]** `ExternalServiceService.processOnWrite` has one caller, `ApplicationService.writeApplication`.
  Its callers are client writes (`ResourceController`, `ConfigResourceController`, `ConfigApplyService`) and
  `PublicationService:367`, which hands back a decrypted application, so no omitted secret reaches the new `400`.
  Deploy and undeploy do not call it. → A test keeps undeploy passing with an unreadable service secret.
- **[Error text]** The messages name entity and field, never the ciphertext or the crypto error. → Tests assert the
  stored ciphertext is absent from the body, as `InvalidEntityApiTest.assertSecretsOmitted` does.

### Effect per request

None on the proxy path. The writes touched are admin or owner `PUT`s. Each does the same decrypts as before, in a
different order; the model, key and route check adds one CEK-cached decrypt per kept secret before the write. No extra
Redis or blob round trip, body copy or parse of a forwarded body; the entity copy is of a config object, not a request
body. Not measured separately: the lock-held section gets no new I/O.

## Migration Plan

None. No stored data changes. An entity that is unreadable today stays unreadable until its secret is written again,
which is what this change makes possible. Rollback is a revert.

## Open Questions

- Whether `decryptFieldsLenient`'s log line should carry the path as well. Cosmetic, answerable during implementation.
