# Proposal

## Why

A stored secret can stop decrypting while the entity keeps working as data: the key-management service (KMS) can no
longer unwrap a bucket's content encryption key (CEK) and Core replaces it with a new one, a blob is copied or
imported to a path other than the one it was encrypted for (the path is part of the encryption), or a value was
seeded by hand. Writing a new secret still works, because encrypting only needs the current CEK. But several write
paths decrypt the old secret first, and that decrypt throws. A toolset with a stale `client_secret` cannot be updated
at all: `PUT` answers a bare `500`, the repair endpoint fails the same way, and the only way out is to delete the
toolset and its credentials. The same decrypt blocks application and external-service writes, and a model, key or
route edit that omits a secret answers `500` after the blob is already written.

The client has no way to tell that the stored secret is the problem, and no way to replace it.

## What Changes

One rule on every affected write: **a write answers `400` only when it would have had to keep an unreadable stored
secret.** A request that supplies a new secret, drops the field, or changes the authentication type never needs the
old value, so it succeeds. The `400` names the entity and the field and says the stored value cannot be used.

- **Toolset `PUT`** (Resource API, admin config API, `/v1/admin/apply`): the stored `client_secret` is read leniently.
  A request that supplies `client_secret` is accepted. A request that omits it answers `400`. When the stored secret
  was unreadable, the PKCE `code_verifier` is regenerated together with its challenge, because a verifier kept from
  the same lost key would fail the token exchange at sign-in.
- **Application `PUT` carrying `external_services`**: the same rule per service. One unreadable service no longer
  blocks the write for the others, for unrelated application fields, or for removing that service.
- **Single external-service `PUT`**: only the service being written is decrypted and encrypted. The other services of
  the application keep their stored ciphertext untouched, so a bad secret in one no longer blocks writing another.
- **User-authored external-service `PUT`**: the same rule as an inline service.
- **Toolset repair** (`/v1/ops/toolset/{bucket}/{path}/repair`): no longer needs the old secret. It replaces the
  secret and verifier, so an unreadable stored value does not stop it. This is the way to recover a dynamically
  registered toolset, whose secret the client never knew.
- **Model, key and route `PUT`** (config API): preserved secrets are trial-decrypted before anything is written. An
  unreadable one answers `400` naming the field and the upstream, and writes nothing. This replaces today's `500`
  after a successful write, and keeps the in-memory config in step with the blob.

**BREAKING (narrow):** a `PUT` that omits a secret whose stored value is unreadable answers `400` instead of `500`
(toolsets, services) or instead of `200`/`500` with a stored-but-unusable value (models, keys, routes). No request that
works today starts failing, because each of these either failed already or stored a value that could not be used.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `config-store`: "Omitted secret preserved" gains the unreadable-secret case for models, keys and routes; "Route
  upstream key on write" gains the same case for routes; "External services on write" gains the per-service rule and
  the single-service write; a new requirement covers toolset and repair behaviour with an unreadable stored secret.

## Impact

- **Code:** `ToolSetService`, `ToolSetRepairService`, `ExternalServiceService`, `UserExternalServiceService`,
  `ResourceAuthSettingsService` and `ResourceAuthSettingsEncryptionService` (credentials module),
  `ConfigResourceController`, `SecretFieldProcessor`.
- **API:** no new endpoints or fields. A new `400` on the writes above. `docs/open_api_core.yaml` is generated from
  controller annotations; the affected `@ApiResponse` lists are checked, not hand-edited.
- **Docs:** `docs/dynamic-settings/toolset_credentials_api.md` (the `400` and the repair route for a dynamically
  registered toolset).
- **Motivation:** the report that a toolset whose `client_secret` can't be decrypted can't be updated, widened to every
  other write path with the same decrypt-first shape. Issue: https://github.com/epam/ai-dial-core/issues/2280.

### Out of scope

- **Strict reads with a large blast radius:** external-service sign-in decrypting every service of an application,
  toolset and application copy, publication, and the platform reload marking a whole application or model invalid
  over one bad secret. These stay as they are and are a separate change.
- **Application route secrets** (`routes[].upstreams[].key`/`secretExtraData`): they stay tolerant on write.
  `ApplicationRouteApiTest`, `ExternalServiceCredentialsApiTest` and `ApplicationDeploymentApiTest` assert that an
  application write and an undeploy succeed with a corrupted route secret.
- **CEK replacement on a failed unwrap** (`ContentEncryptionKeyManagerImpl.getOrCreateKey`, which a plain read can
  trigger) and any bulk re-encryption or migration tool.
- **Server-initiated rewrites** of an application (publication, deploy and undeploy). The design lists them; none is
  changed here.
