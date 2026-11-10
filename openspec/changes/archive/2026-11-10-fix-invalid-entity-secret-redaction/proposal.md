# Proposal

## Why

When a toolset or an application in the `platform` bucket is recorded as an invalid entity, `GET /v1/toolsets/platform/{name}`
and `GET /v1/applications/platform/{name}` answer with the stored blob. `auth_settings.client_secret` and
`code_verifier` of a toolset, and the same two fields in each `external_services.<name>.auth_settings` of an application,
come back as the ciphertext stored in the blob, without the redaction a valid entity gets. The invalid branch only drops
fields marked `@EncryptedField`, and these secrets are not marked: they are encrypted by a separate service and held
decrypted in the merged config.

An entity becomes invalid when its secret does not decrypt. A system import of an archive without `encryption-keys/`
does that: the key already in the target environment cannot decrypt the imported secrets.

## What Changes

- The single-entity GET of an invalid toolset or application leaves out `client_secret`, `code_verifier` and
  `client_secret_hint` from the toolset's `auth_settings` and from every `external_services.*.auth_settings` of the
  application.
- The response never carries a `client_secret_hint` for an invalid entity, also for an admin. A hint is the last
  characters of the plaintext secret; for a value that failed to decrypt it would be computed from ciphertext.
- Every other field of the invalid payload, `name`, `status` and `validationWarnings` stay as they are.

Out of scope:

- The valid-entity GET, which already redacts.
- Other readers of a raw secret: `ConfigFileMigrateController` has its own redaction and is not touched.
- Why the secret did not decrypt, and writing a new secret over an undecryptable one (a toolset `PUT` decrypts the
  stored secret first).
- Listings: they answer from blob metadata, not from the invalid record's payload.
- A blob written by hand with camelCase keys (`authSettings`, `clientSecret`). The deserializer accepts them as aliases,
  but every Core write path stores snake_case, so the redaction matches snake_case only.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `config-store`: adds the requirement "Invalid entity read omits auth-settings secrets" next to "Invalid entity on read"
  and "Secrets masked on read". It does not change "Invalid entity on read".

## Impact

- Issue: [#2253](https://github.com/epam/ai-dial-core/issues/2253) (Invalid toolset and application config GET returns
  stored client_secret and code_verifier ciphertext). This change covers all of it.
- Code: `ConfigResourceController` (`projectInvalidItem`, which now calls the redaction helpers the valid branch uses),
  `InvalidEntityApiTest`.
- API: no new field or status. An invalid toolset or application no longer shows its secret keys in this one response.
- Compatibility: a client that read `client_secret` or `code_verifier` out of an invalid entity's GET stops getting
  them. Those values were ciphertext that does not decrypt, so nothing usable is lost.
