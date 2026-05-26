# 04 — Security

Authorization, secrets at rest, and audit — at the level a reviewer needs to assess the model. No CEK provisioning details, no `@JsonView` mechanics.

## Authorization

Effective permissions:

| Bucket | Read | Write |
|---|---|---|
| `public/` | Any authenticated user (existing Resource API rule) | Admin role |
| `platform/` | Admin role | Admin role |
| `{user-bucket}` | Bucket owner only (unchanged) | Bucket owner only (unchanged) |

**Admin has no access to user buckets** — locked by design, out of scope for this proposal. Admin management of shared `files` / `prompts` / `conversations` targets `public/` instances only; user-owned instances remain owner-managed.

**`forwardAuthToken` admin-only write (slice U.3).** `Application.forwardAuthToken` / `ToolSet.forwardAuthToken` is stripped to `false` by default on every write — pre-existing security policy. Slice U.3 allows admin writes to `public/` (per-entity `PUT` + bulk `apply`) to preserve `forwardAuthToken: true` when supplied. User-bucket writes, publication-approval re-writes, and non-admin writes continue to strip.

A static `(entityType, bucket)` allowlist (`models → public`, `keys → platform`, etc.) rejects structurally-permitted-but-semantically-invalid combinations (e.g. `GET /v1/keys/public/foo`) with `404 Not Found` — same response shape as a genuinely-missing entity so unauthenticated probes can't distinguish the two.

### File-config inspection surface

The per-entity Configuration API (`/v1/{type}/{bucket}/{name}`) is blob-only — file-sourced entries from `aidial.config.json` are not addressable there. Operators who need to inspect file-sourced configuration use a separate read-only surface: `GET /v1/admin/config/file/{type}` and `GET /v1/admin/config/file/{type}/{name}`. Authz:

- **All types except `keys`** — admin role, same gate as the rest of `/v1/admin/*`.
- **`keys`** — **security-admin role required**. Non-security-admin callers receive `403`.

The `keys` carve-out is locked because file-sourced `Config.keys` uses the legacy format where the map key *equals the secret value* (OQ-12 — kept permanently dual-format so existing customer config files are not broken). Listing or addressing those entries via URL therefore exposes secrets to anyone who can read the response — admin role alone is not enough. Security-admin is the same operator-vetted tier already gating `?reveal_secrets=true` for plaintext secret reads (see *Secrets at rest* below); reusing it keeps the secret-exposure surface coherent. No `PUT` / `DELETE` on file entries — `aidial.config.json` remains the operator-managed source of truth.

### Public vs Owner views

Per-entity `GET` shares the same URL between authenticated readers, bucket owners, and admins. The Public view exposes a **per-type allowlist** of properties — not "everything that isn't a secret". The allowlist for each `public/`-bucket type matches today's hand-curated public projection (`ModelData` for models at `server/.../data/ModelData.java`, `ApplicationData` for applications at `server/.../data/ApplicationData.java`): identity, display metadata, capabilities, limits, pricing, attachment types — but **not** `upstreams`, `endpoint`, `extraData`, interceptor references, or other infrastructure fields. Those are Owner-only, alongside operational metadata such as `validationWarnings`. The `status` flag (`valid` | `invalid`) is Public — anyone discovering the entity sees whether it's functional. There is no `source` field on any response: the per-entity Configuration API is blob-only, the file-config surface is file-only, and the URL itself tells the caller which they're looking at.

The Public allowlist is the *contract*; implementations may realize it via dedicated DTOs (continuing today's `ModelData` / `ApplicationData` pattern) or via Jackson `@JsonView(Public)` annotations on a curated subset of entity fields. A fail-closed serializer (`DEFAULT_VIEW_INCLUSION = false`) makes a forgotten annotation invisible everywhere rather than silently Public.

`platform/`-bucket types have no Public view at all — non-admin callers never reach the projection step.

### CLI credentials

`dial-cli` authenticates with the same API keys or JWTs as any other client. The CLI **never accepts an API key as a command-line flag** — that would leak the secret into `ps`, shell history, CI logs under `set -x`, and `kubectl describe pod` output. Supported inputs, in priority order: environment variable named by the profile, `--api-key-file <path>`, OS keystore (macOS Keychain / libsecret / Windows Credential Manager), interactive no-echo prompt.

## Secrets at rest

API-managed entities store secret fields encrypted via the existing `CredentialEncryptionService`:

- **AES-256-GCM, envelope encryption.** A platform-scoped Content Encryption Key wraps each ciphertext; the CEK is itself wrapped by a KMS provider (AWS KMS / Azure Key Vault / GCP KMS in production; `SimpleKeyManagementService` no-op pass-through in dev with a startup warning).
- **No new infrastructure.** This reuses crypto DIAL Core already runs for `clientSecret`, `codeVerifier`, and other operationally-sensitive fields today.
- **Field-level, not document-level.** Fields are tagged with `@EncryptedField` (`Key.key`, `Upstream.key`, `Upstream.extraData`). The annotation is the gate; a field without it is never encrypted. Toolset OAuth credentials (`ResourceAuthSettings.clientSecret` and `codeVerifier`) are encrypted through the existing bespoke `ResourceAuthSettingsEncryptionService` path and follow the same masking and preserve-on-omit contract.

**API write-only by default:**

| Operation | Behavior |
|---|---|
| `GET` *(and future `export` — deferred per Defer.1)* | Secret value masked as `"***"` |
| `PUT … If-None-Match: *` (create — field absent / `null`) | Stored as `null` |
| `PUT … If-None-Match: *` (create — field with `"***"`) | Rejected `400` — the mask sentinel is not a valid create-time secret |
| `PUT … If-None-Match: *` (create — real value) | Encrypted and stored |
| `PUT` (update — field absent / `null` / `"***"`) | **Preserve-on-omit** — existing ciphertext kept |
| `PUT` (update — real value) | Encrypted and stored |
| `promote` | Secrets skipped — set per environment |
| `validate` | Secret fields ignored |

Preserve-on-omit is server-side behavior, not a CLI ergonomic — every client (CLI, Admin Backend, MCP, direct curl) gets it for free.

**Optional reveal** — operators with a separate `security-admin` role can request plaintext via `?reveal_secrets=true`. If the role isn't configured on the environment, the feature is simply unavailable. The same `security-admin` tier gates the file-config `/keys` endpoints (`/v1/admin/config/file/keys[/...]`) — see *File-config inspection surface* above.

**Backward compatibility with config files** — file-sourced entities continue to carry plaintext secrets. `MergedConfigStore` transparently handles four formats: plaintext (file/dev), `ENC[...]` (this proposal), `${SECRET:...}` (future vault references — Phase 5+), and the existing bare-Base64 `ResourceAuthSettings` payloads on toolsets.

## Audit *(deferred — Phase 7)*

The audit subsystem is fully designed but deferred to Phase 7, after entity-management API, CLI, and Admin MCP land. Phases 1–6 ship without an audit trail; structured DIAL Core application logs cover all Configuration API writes in the interim.

The Phase 7 design, preserved for delivery:

- **Vault-style intent log.** Each mutation writes `PENDING` to Redis Streams *before* it executes, then `APPLIED` or `FAILED` afterwards. No silent drops — if the stream is at `MAXLEN`, the write critical path returns `503` rather than skip the event.
- **Two-tier storage.** Redis Streams for the hot, queryable tier (`XREAD` / `XRANGE`); blob archival for cold and durable.
- **Single interception point.** All Configuration API mutations across both buckets, captured at the controller layer with the admin JWT identity. User publication workflow (`PublicationService`) is a separate code path, audited separately later.
- **Query API and CLI surface.** `GET /v1/admin/audit` with time/actor/entity/operation filters, and `dial-cli audit history | snapshot | rollback`.
- **Validity transitions are not audited.** Those are derived runtime state — they live on listing API `status`, Prometheus metrics, and `/v1/admin/health/config`. Audit captures *actor mutations* only.

> See the full version: [`../dial-unified-config/04-security-and-audit.md`](../dial-unified-config/04-security-and-audit.md)
