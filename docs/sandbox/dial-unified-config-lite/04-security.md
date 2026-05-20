# 04 — Security

Authorization, secrets at rest, and audit — at the level a reviewer needs to assess the model. No CEK provisioning details, no `@JsonView` mechanics.

## Authorization

Authorization for the Configuration API runs through a `ConfigAuthorizationService` — a pluggable interface, not inline `isAdmin()` checks scattered across controllers. Phase 1–3 ships an `AdminRoleAuthorizationService` that dispatches on `(role, verb, entityType, bucket)`.

Effective permissions:

| Bucket | Read | Write |
|---|---|---|
| `public/` | Any authenticated user (existing Resource API rule) | Admin role |
| `platform/` | Admin role | Admin role |
| `{user-bucket}` | Bucket owner only (unchanged) | Bucket owner only (unchanged) |

**Admin has no access to user buckets** — locked by design, out of scope for this proposal. Admin management of shared `files` / `prompts` / `conversations` targets `public/` instances only; user-owned instances remain owner-managed.

A static `(entityType, bucket)` allowlist (`models → public`, `keys → platform`, etc.) rejects structurally-permitted-but-semantically-invalid combinations (e.g. `GET /v1/keys/public/foo`) with `404 Not Found` — same response shape as a genuinely-missing entity so unauthenticated probes can't distinguish the two.

When multi-tenancy lands, only the `ConfigAuthorizationService` implementation is swapped — endpoint code does not change.

### Public vs Owner views

Per-entity `GET` and listing share the same URL between authenticated readers, bucket owners, and admins. Operational metadata that admins/owners need (`source`, `validationWarnings`) must not leak to public callers; entity-intrinsic fields and the `status` flag are public-safe.

Two Jackson views (`Public`, `Owner extends Public`) handle this declaratively. The controller picks `Owner` when the caller is admin or bucket-owner, `Public` otherwise. The serializer is configured fail-closed (`DEFAULT_VIEW_INCLUSION = false`) so a forgotten annotation makes a field invisible everywhere, not silently public.

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

**Optional reveal** — operators with a separate `security-admin` role can request plaintext via `?reveal_secrets=true`. If the role isn't configured on the environment, the feature is simply unavailable.

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
