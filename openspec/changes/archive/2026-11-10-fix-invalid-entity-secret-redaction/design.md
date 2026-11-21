# Design

## Context

See proposal.md for the problem. How an invalid toolset or application reaches the response today, all on `development`:

- `MergedConfigStore.java:rebuildMergedConfig` parses each blob into a `JsonNode`, builds the typed entity with
  `addBlobEntity`, then runs `decryptManagedEntity`. A failure there goes to `recordInvalid` with reason
  `REASON_DECRYPTION` and the raw `JsonNode` as the record's `payload` (`InvalidEntityRecord.java`). For a toolset,
  `decryptManagedEntity` calls `ResourceAuthSettingsEncryptionService.decrypt`; for an application it calls
  `ExternalServiceService.decryptSecrets`.
- `ControllerSelector.java` routes `/v1/(applications|toolsets)/platform/{path}` (`RouteTemplate.PLATFORM_APP_TOOLSET_RESOURCE`)
  to `ConfigResourceController`. Its `handleSingleGetFromBlob` answers an entity that has an invalid record with
  `projectInvalidItem`.
- `ConfigResourceController.java:projectInvalidItem` copies the payload through
  `SecretFieldProcessor.java:stripEncryptedFields`, which removes `@EncryptedField` fields only. `ResourceAuthSettings`
  `clientSecret` and `codeVerifier` carry no such annotation (toolset and external-service secrets are encrypted by
  `ResourceAuthSettingsEncryptionService`, bare Base64 without `ENC[`), so they stay in the response.
- The valid branch projects the typed entity and calls `redactAuthSettingsSecrets` or `redactExternalServiceSecrets`,
  both built on `redactSecretFields` (`ConfigResourceController.java`).
- The payload is shared state: `MergedConfigStore.java:resurrectInvalidModels` reads `record.getPayload()` to retry an
  invalid model on an interceptor write.
- Every Core write path serializes the typed entity (`ConfigEntityCodec.serializeForBlob`,
  `ProxyUtil.convertToString`), and `ResourceAuthSettings` is `SnakeCaseStrategy`, so a blob Core wrote has snake_case
  keys. The deserializer also accepts camelCase through `@JsonAlias`; only a blob written outside Core would use it.

## Goals / Non-Goals

**Goals:**
- An invalid toolset or application GET carries no auth-settings secret and no hint.
- One redaction path for the valid and the invalid branch.

**Non-Goals:**
- Changing what is stored, what the valid branch returns, or what makes an entity invalid.
- Redacting free-form content such as an application's `application_properties`.

## Decisions

**Reuse the JSON-level redaction helpers for the invalid branch.** `projectInvalidItem` calls
`redactAuthSettingsSecrets` for `TOOL_SET` and `redactExternalServiceSecrets` for `APPLICATION` on its payload copy,
after `stripEncryptedFields`, with `revealHint` always `false`. The helpers themselves do not change: the payload of a
blob Core wrote has the same snake_case keys as the valid branch's projection.

- Rejected: read the payload into the typed entity and call `redactSecrets()`. A payload that failed to parse cannot be
  read into the entity, and a typed round trip drops unknown fields and adds defaults, so the response would no longer
  be "the stored payload's fields".
- Rejected: annotate `clientSecret` and `codeVerifier` with `@EncryptedField` so `stripEncryptedFields` removes them.
  The annotation also makes `SecretFieldProcessor` encrypt them in an `ENC[` envelope on top of
  `ResourceAuthSettingsEncryptionService`, which changes the stored format.
- Rejected: a second redaction method for raw payloads. `docs/CODE_STYLE.md` says to reuse `withoutSecrets()` and the
  existing redaction, not hand-roll another path; two paths would also drift on the next secret field.

**No hint for an invalid entity, also for an admin.** `redactSecretFields` computes the hint from the value it removes.
An entity is invalid here because that value did not decrypt, so the hint would be the tail of the ciphertext: wrong
and a fragment of it. The `revealHint` parameter already exists; the invalid branch passes `false`.

**Snake_case keys only.** The redaction matches `auth_settings`, `external_services`, `client_secret`, `code_verifier`
and `client_secret_hint`. Rejected: also match the camelCase aliases (`authSettings`, `externalServices`,
`clientSecret`, `codeVerifier`, `clientSecretHint`). It needs new constants and a second lookup in each helper, to guard
against a blob that no Core write path produces. A hand-written camelCase blob whose secret does not decrypt still
returns that ciphertext in the invalid GET; the exposure is ciphertext on an admin endpoint, and this is accepted.

**Only `TOOL_SET` and `APPLICATION`.** `entityClassFor` already names the type. Other types either have only
`@EncryptedField` secrets, which `stripEncryptedFields` removes, or none. A new secret-bearing type must add a case.

**Redact the copy.** `stripEncryptedFields` and the `entityClass == null` branch both return a deep copy, so the
redaction runs on that copy and never on `record.getPayload()`, which `resurrectInvalidModels` still reads.

Per request: no change on any proxied or hot path. An invalid-entity GET (admin only, rare) walks one small
`ObjectNode`; no Redis or blob round trip is added, and no body is copied or parsed beyond what the branch does today.
No measurement is needed, because the change claims no cost effect.

## Risks / Trade-offs

- A hand-written blob with camelCase keys keeps its ciphertext in the invalid GET → accepted, see "Snake_case keys
  only".
- Secrets in free-form fields, such as `application_properties`, are not covered → they are not auth-settings secrets,
  and the issue does not ask for them.
- An operator loses the ciphertext view of an invalid entity → it does not decrypt, and the blob stays in storage.

## Migration Plan

None. No stored data, setting or API field changes.
