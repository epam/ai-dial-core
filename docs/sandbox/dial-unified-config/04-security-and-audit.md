# 04 — Security and Audit

> **Audience:** Security team, compliance reviewers, architects responsible for authorization and audit.
> **Reading time:** ~20 minutes.
> **Prerequisites:** [`02-architecture.md`](02-architecture.md) §Bucket Strategy.

This document consolidates every security and audit decision in the proposal in one place: who can call the Configuration API, how secret fields are stored at rest, what gets logged, and how the audit trail is queried. Each section is written to stand on its own — you do not need to read the architecture document to understand the authorization model, though it helps.

---

## 1. Authorization

### 1.1 Requirements

| ID | Requirement |
|----|---|
| R-AuthZ-1 | All Configuration API mutations are authorized. Read endpoints are authorized for `public/` via the existing Resource API rule and for `platform/` via the admin role. |
| R-AuthZ-2 | Authorization is pluggable. The Phase 1–3 admin-role check is swappable for a hierarchical (multi-tenant) model without changing endpoint code. |

### 1.2 `ConfigAuthorizationService` interface

Authorization for the Configuration API is implemented through a **`ConfigAuthorizationService`** abstraction, not inline `isAdmin()` checks. Because per-entity CRUD shares the URL pattern with the existing user Resource API (`/v1/{type}/{bucket}/{name}` — see [`03-api-reference.md`](03-api-reference.md) §1), authorization dispatches from `(role, verb, entityType, bucket)` rather than from URL prefix:

```java
public interface ConfigAuthorizationService {
    /**
     * Check if the actor can perform the operation on the given entity.
     *
     * <p>{@code entityType} and {@code entityName} are reserved for future hierarchical
     * (multi-tenancy) implementations and per-entity ACLs (e.g., role-scoped allowlists,
     * tenant-bound resources). The Phase 1–3 implementation dispatches on
     * {@code bucket} and {@code operation} only — the additional parameters are
     * available so future implementations can tighten authorization without
     * reshaping every controller call site.
     */
    boolean isAuthorized(ProxyContext context, String entityType, String entityName,
                          String bucket, Operation operation);

    /**
     * Check whether the caller holds the admin role for cross-entity operations
     * and projection dispatch (Owner-vs-Public view selection per §1.5).
     *
     * <p>Used at two call sites: (a) the {@code /v1/admin/*} cross-entity endpoints
     * (apply, validate, export, audit, health/config, schema) which do not have a
     * per-entity {@code (type, bucket)} dimension and gate on the admin role only;
     * (b) {@code projectionFor()} in per-entity GET / listing controllers, where
     * "admin OR bucket-owner" yields the Owner view and everyone else gets Public.
     * Phase 1–3 delegates to {@code accessService.hasAdminAccess(context)};
     * future Auth-MT implementations may translate hierarchical roles into a
     * single yes/no for these admin-scoped surfaces.
     */
    boolean isAdmin(ProxyContext context);
}

// Phase 1–3 implementation:
public class AdminRoleAuthorizationService implements ConfigAuthorizationService {
    public boolean isAuthorized(ProxyContext context, String entityType,
                                 String entityName, String bucket, Operation operation) {
        // Bucket-aware dispatch:
        // - public/  : reads = anyone authenticated; writes = admin role
        //              (covers admin-managed models, applications, toolsets, schemas
        //               and admin-managed shared files/prompts/conversations — see OQ-21)
        // - platform/: reads = admin role; writes = admin role
        // - {user-bucket}: reads/writes = bucket owner only (existing Resource API rule).
        //                  Admin has NO access to user buckets — locked by design,
        //                  out of scope for this proposal (see OQ-33).
        if (PLATFORM_BUCKET.equals(bucket)) {
            return accessService.hasAdminAccess(context);
        }
        if (PUBLIC_BUCKET.equals(bucket)) {
            return operation.isRead()
                ? accessService.isAuthenticated(context)
                : accessService.hasAdminAccess(context);
        }
        // user buckets: existing Resource API owner check, unchanged
        return accessService.isOwnerOf(context, bucket);
    }
}

// Cross-entity ops endpoints (/v1/admin/apply, /v1/admin/validate,
// /v1/admin/audit, /v1/admin/health/config, /v1/admin/schema) call a separate path —
// always admin-role, no bucket dimension, no per-entity cross-ref check.
// (/v1/admin/export is deferred — see IMPLEMENTATION.md §5.5 Defer.1.)

// Future Auth-MT implementation (not in scope):
// HierarchicalAuthorizationService evaluates: platform admin > tenant admin > team owner
```

This indirection costs one interface + one implementation class. The per-entity CRUD controllers call `configAuthorizationService.isAuthorized(...)` once per request; the cross-entity ops controllers call a simpler admin-role check. When Auth-MT introduces hierarchical roles, only the implementation is swapped — no endpoint code changes.

The `accessService.isAuthenticated(context)` and `accessService.isOwnerOf(context, bucket)` calls in the snippet above are real public methods on `AccessService` — added in the bootstrap slice (1S.0) alongside the pre-existing `hasAdminAccess(context)`. `isAuthenticated` returns true when `context.getUserRoles() != null` (a properly authorized JWT or API-key request); `isOwnerOf` compares the requested bucket to the encrypted form of the caller's initiator bucket via `BucketBuilder.buildInitiatorBucket(context)`. Both delegate to existing primitives in `AccessService`, so no new state is added — the methods exist only to give `AdminRoleAuthorizationService` (and any future `ConfigAuthorizationService` implementation) a stable seam to call.

**`(entityType, bucket)` validation step (defense in depth) — enforced from Phase 1.** The CONFIG_RESOURCE regex permits any `(type, bucket)` combination structurally — nothing in the regex prevents a request to `GET /v1/keys/public/foo` from reaching the controller. `AdminRoleAuthorizationService` would then gate that read on `isAuthenticated` (since `public/` reads for non-admins are allowed by §1.4), which would expose infrastructure entities if a `keys` blob ever landed in `public/` through a bug or misconfiguration. Because Phase 1 ships `GET /v1/{type}/{bucket}/{name}` for all seven admin-config types, this allowlist is required from Phase 1 forward — without it, any authenticated user could probe `GET /v1/keys/public/foo` and rely on the dispatch falling through to the `public/`-read branch. The allowlist is a static map with no runtime cost, so there is no reason to defer it; Phase 1 ships it together with the read endpoints. Tracked as a Phase 1 prerequisite item in [`07-migration-and-rollout.md`](07-migration-and-rollout.md). The mechanics:

- A static map on a dedicated **`EntityBucketBinding`** class declares the valid `(entityType, bucket)` pairs: `models → public`, `applications → public`, `toolsets → public`, `schemas → public`, `files → public + user-buckets`, `prompts → public + user-buckets`, `conversations → public + user-buckets`, `interceptors → platform`, `roles → platform`, `keys → platform`, `routes → platform`, `settings → platform`. (The broader `EntityLocationStrategy` covers `(entityType, scope)` translation — see [`02-architecture.md`](02-architecture.md) §4 — and is a distinct concept from the `(entityType, bucket)` allowlist.)
- Either the per-entity CRUD controller or `ConfigAuthorizationService` rejects requests where `entityType` does not belong to the requested `bucket` with `404 Not Found` (chosen over `400` to avoid leaking which type/bucket pairs exist to unauthenticated probes). **Response body indistinguishability:** the `EntityBucketBinding` 404 response body is byte-for-byte identical to the standard "entity not found" 404 body — same `error` payload shape, same message family — so an unauthenticated probe cannot tell from the response whether the type/bucket pair is invalid (binding rejection) or merely empty (no entity at that name). Indistinguishability is the whole point of choosing 404 over 400; a separate response shape would re-introduce the leak that motivated the binding allowlist.
- A startup-time assertion verifies every entry in the new `ResourceTypes` enum has a binding declared, so a future enum addition without a binding fails fast in tests rather than silently falling open at runtime.

### 1.3 Admin role configuration (Phase 1–3)

`AdminRoleAuthorizationService` reads `access.admin.rules` from static settings:

```json
"access": {
    "admin": {
        "rules": [
            { "function": "CONTAIN", "source": "roles", "targets": ["admin"] }
        ]
    }
}
```

Both JWT-authenticated users and API keys with matching roles can access admin-gated paths — per-entity writes to `public/` and reads/writes to `platform/` (via `/v1/{type}/{bucket}/{name}`), plus all cross-entity ops endpoints (`/v1/admin/*`).

### 1.4 Effective permissions per bucket

| Bucket | Read | Write |
|--------|------|-------|
| `public/` | All authenticated users (existing Rule 7 in AccessService) | Authorized via `ConfigAuthorizationService` (admin in Phase 1–3) |
| `platform/` | Authorized via `ConfigAuthorizationService` (admin in Phase 1–3) | Same |
| `{user-bucket}` (`Uxxx...`) | Bucket owner only — existing Resource API rule, unchanged | Bucket owner only — existing Resource API rule, unchanged |

Admin has **no** read or write access to user buckets — this is locked by `ConfigAuthorizationService` and out of scope for this proposal (see [OQ-33](08-open-questions-and-references.md)). User-owned files / prompts / conversations in user buckets remain managed exclusively by their bucket owner via the existing Resource API rule. Admin management of `files` / `prompts` / `conversations` (per [OQ-21](08-open-questions-and-references.md)) targets **shared** instances in `public/` only.

### 1.5 Response projection: Public / Owner views

**Scope — per-entity GET only.** The Public / Owner projection described in this section applies to per-entity `GET /v1/{type}/{bucket}/{name}` responses. The folder metadata listing surface (`GET /v1/metadata/{type}/{bucket}/{path}`) returns the existing `ResourceItemMetadata` shape (`url`, `name`, `etag`, `createdAt`, `updatedAt`, `author`) and carries no projection — see [`03-api-reference.md`](03-api-reference.md) §4. Operational metadata that admins/owners need on a per-entity GET (`validationWarnings`) must not leak to Public callers; entity-intrinsic fields and validity status (`status`) are public-safe. Two Jackson views handle this declaratively. *(Slice U.1, 2026-05-21: the previously Owner-only `source: "file" | "api"` field has been retired from the per-entity Configuration API — the URL itself discloses the source. File-sourced entries are no longer addressable on `/v1/{type}/{bucket}/{name}`; operators inspect them via `/v1/admin/config/file/{type}[/{name}]` — see "File-config inspection surface" below.)*

```java
public final class Views {
    public static class Public { }                  // everyone with read access to the bucket
    public static class Owner  extends Public { }   // bucket-owner OR admin-of-shared-bucket
}
```

**Dispatch** — one call per request, in the controller:

```java
private Class<?> projectionFor(ProxyContext ctx, ResourceDescriptor rd) {
    return (configAuthService.isAdmin(ctx) || rd.isOwnedBy(ctx))
        ? Views.Owner.class
        : Views.Public.class;
}

// Apply to serialization:
String body = objectMapper.writerWithView(view).writeValueAsString(response);
```

Admin and bucket-owner share the Owner view because they are the same kind of principal — full read/write authority over the bucket the resource lives in. Owner is therefore *access-shape-named*, not role-named.

**Field placement — flat shape, no `_meta` envelope:**

| Field | View | Notes |
|---|---|---|
| Entity-intrinsic fields (top level) | `Public` | Existing user Resource API shape preserved. Phase 2 mechanically adds `@JsonView(Public)` to every existing field on `Application`, `ToolSet`, `Key`, `Model`, `Role`, etc. |
| `status: "valid" \| "invalid"` (top level, on response wrapper) | `Public` | Validity is a public signal — anyone discovering the entity sees whether it's functional. |
| `validationWarnings: [...]` (top level, on response wrapper, only when `status: "invalid"`) | `Owner` | Warning text reveals admin-managed component names (interceptor names, schema URIs) Public callers shouldn't enumerate. Public sees the *fact* of invalidity; Owner sees the *reason*. |
| `etag` | n/a | Returned in HTTP `ETag` header, never in the body. |
| `lastModified` | n/a | Intentionally not exposed today (YAGNI — revisit if a use case shows up). |

`status` and `validationWarnings` live on the **response wrapper**, not on the entity data classes (`Model`, `Role`, `Application`, …). Those classes round-trip through `aidial.config.json` and are imported as a Gradle dependency by the CLI — adding runtime status fields on them would leak into the file format and the CLI types.

### 1.5.1 File-config inspection surface

Per slice U.1 (2026-05-21), file-sourced entries are inspected via a dedicated read-only surface — `GET /v1/admin/config/file/{type}` (list) and `GET /v1/admin/config/file/{type}/{name}` (single). The per-entity Configuration API (`/v1/{type}/{bucket}/{name}`) and the metadata listing (`/v1/metadata/{type}/{bucket}/{path}`) are blob-only — file entries do not surface there.

Authorization:

- **All types except `keys`** — admin role (same gate as the rest of `/v1/admin/*`). Security-admin is also accepted since it is strictly stronger.
- **`keys`** — security-admin role required. Non-security-admin callers receive `403`.

The `keys` carve-out is locked because file-sourced `Config.keys` uses the legacy format where the map key *equals the secret value* ([OQ-12](08-open-questions-and-references.md) — kept permanently dual-format so existing customer config files are not broken). Listing or addressing those entries via URL therefore exposes secrets to anyone who can read the response — admin role alone is not enough. Security-admin is the same operator-vetted tier that already gates `?reveal_secrets=true` for plaintext secret reads (see §2.6 below); reusing it keeps the secret-exposure surface coherent.

Singleton settings file-side view: `GET /v1/admin/config/file/settings/global` returns the file-defined and schema-default values for `globalInterceptors` and `retriableErrorCodes` regardless of whether an API blob exists on the per-entity endpoint. The merged `Config`'s values reflect the API blob when one is present (whole-object replacement per the prior OQ-10 contract); the file-config surface bypasses the merge and reads `MergedConfigStore.getFileSourcedConfig()` directly. This is the only way to see "what would the effective value be if no API blob existed?" after U.1 retired the singleton's three-state `source` projection on the per-entity GET.

No `PUT` / `DELETE` on file entries — `aidial.config.json` remains the operator-managed source of truth for file-sourced configuration; the file-config surface is strictly read-only.

**Defense in depth: `DEFAULT_VIEW_INCLUSION = false`.** The admin-CRUD `ObjectMapper` is configured with Jackson's `MapperFeature.DEFAULT_VIEW_INCLUSION = false` — every serialized field must carry an explicit `@JsonView` annotation. A new field added without an annotation is invisible everywhere (fail-closed at write time, caught by snapshot tests on existing endpoints), not silently public. The blob-I/O `ObjectMapper` keeps its current configuration unchanged (it always serializes everything, including encrypted secret blobs — that's its job).

**Future-field rules.** New Public field → top level on entity, `@JsonView(Public)`. New Owner-only operational metadata → top level on response wrapper, `@JsonView(Owner)`. New Owner-only **state** field that's part of the entity body (e.g., a future `regionOverride` flag the owner sets) → top level on entity, `@JsonView(Owner)`. Container-vs-flat is decided once: flat shape for everything; the view annotation is the gating mechanism. Secrets stay on their own track — `@EncryptedField` masking + `?reveal_secrets=true` for `security-admin` (§2.5–§2.6). The projection model and the secrets model compose orthogonally.

**Bucket-scope clarification — Public view shape only matters for `public/`-bucket types.** The "entity-intrinsic fields → Public" rule above includes `endpoint` and `upstreams[].endpoint` (cluster-internal URLs) without exception. This is intentional and consistent with today's `/openai/models` and `/openai/deployments` behaviour, where the same internal endpoints are already exposed to authenticated users — Phase 2–3 does not regress that posture, but neither does it tighten it. The Public view shape is observable **only** for `public/`-bucket types: `models`, `applications`, `toolsets`, `schemas`, `files`, `prompts`, `conversations`. `platform/`-bucket types (`interceptors`, `roles`, `keys`, `routes`, `settings`) are gated to admin-only at the `ConfigAuthorizationService` dispatch layer (§1.2) — non-admin callers never reach the projection step at all, so the Public-view representation of those types has no caller. `platform/`-bucket types (`interceptors`, `roles`, `keys`, `routes`, `settings`) have **no Public view**. All callers of their listing/get endpoints hold the admin role (enforced at `ConfigAuthorizationService` dispatch before the projection step runs), so the controller always emits the Owner view. Implementations MUST NOT provide a Public-view serialization code path for `platform/`-bucket types. Operators who need cluster-internal endpoints to be private to administrators must use a separate egress proxy or DNS rewriting; the Configuration API does not carve those fields out of the Public view.

**Failure modes guarded against:**

| Risk | Mitigation |
|---|---|
| New Owner-only field added without `@JsonView` | `DEFAULT_VIEW_INCLUSION = false` → field absent from every view → snapshot tests on existing endpoints surface the omission immediately. |
| `Owner` accidentally inheriting from a different view | Unit-test invariant on the view class hierarchy. |
| `projectionFor()` defaults to Owner on missing role | Default to `Public` — fail-closed. |
| Existing user Resource API client sees breaking shape change | Entity DTO top-level fields preserved; `_meta` envelope deliberately not introduced; `status` (Public) is a new top-level optional field that existing clients ignore (unknown-field tolerance). Verified by snapshot test on existing endpoints. |

### 1.6 CLI credential handling

`dial-cli` authenticates to the Configuration API with the same API keys or JWTs as any other client. The CLI **never accepts an API key as a command-line flag** — a `--api-key <key>` flag would leak the secret to process listings (`ps auxf`, `/proc/<pid>/cmdline`), shell history, CI logs under `set -x`, and `docker ps` / `kubectl describe pod` output. Supported inputs, in priority order:

| Source | Intended audience | How it's resolved |
|---|---|---|
| Env var named by the profile's `auth.key_env_var` (e.g. `DIAL_UAT_API_KEY`) | CI / pipelines — default | Profile config points at a name; the CLI reads the value at startup. Never logged, never echoed. |
| `--api-key-file <path>` | CI secret mounts, SOPS-decrypted files, K8s projected volumes | CLI reads the file contents; trailing newline stripped. |
| OS keystore entry | Interactive developer workstations | Populated by `dial-cli auth login --env <n> --store` via macOS Keychain, libsecret (Linux), or Windows Credential Manager. Not available in headless CI. |
| Interactive no-echo prompt | Ad-hoc developer use | Triggered when a TTY is attached and no credential was resolved from the sources above. Uses `java.io.Console.readPassword()` — no terminal echo, not in history. |

`auth.type: api_key` (current) and `auth.type: oidc` (future — see OQ-19 / D4) flow through this same precedence chain. A first-class `dial-cli auth login` command is the natural extension point once user-auth (OIDC device flow, JWT refresh) is in scope; see [`05-cli-design.md`](05-cli-design.md) §1.

**Destructive-operation risk profile.** The Phase 1–3 authorization model (§1.2) is intentionally binary: a caller either has the admin role or does not. One leaked admin credential can DELETE any admin-managed resource (`/v1/{type}/{bucket}/{name}` writes to `public/` and `platform/`, plus all `/v1/admin/*` ops). Compensating controls available **in Phase 1–3**:

- **Destructive-op confirmation** (D2 — elevated from "open" by the DevOps review Q5 feedback; proposed direction is: `delete` prompts interactively by default, `--force` / `--yes` skips the prompt for CI, `apply --prune` requires explicit `--force` and prints the pruned entity list before acting). Awaiting the reviewer's pick among (a) confirm-prompt only, (b) read-vs-write role split, (c) 4-eyes approval workflow, (d) per-entity-type key scoping — see Q5 reply in the review-round log.

Audit-based compensating controls (**Audit blocks the mutation**, **rollback from audit**, **daily config snapshots**) — designed in §3 below — are **deferred** along with the rest of the audit subsystem to Phase 7 (see [`07-migration-and-rollout.md`](07-migration-and-rollout.md)). Until Phase 7 lands, destroy-blast-radius mitigation rests on the destructive-op confirmation control above plus the operator-side change-management process.

Gaps explicitly out of scope for Phase 1–3 but noted for the Auth-MT hierarchical model (§1.2):

- **Read-admin vs. write-admin role split** — would allow "inspect production config" access without mutation rights.
- **Per-entity-type scoping** (`admin-models`, `admin-keys`, …) — would limit blast radius of a compromised key.
- **Approval / 4-eyes workflow** for production destroys — would extend the existing `PublicationService` pattern (`approvedBy`, already reserved in the audit schema §3.3) to admin config mutations.

---

## 2. Secrets at Rest

### 2.1 Requirements

| ID | Requirement |
|----|---|
| R-Sec-1 | **Secrets segregation.** Secret values protected via field-level encryption reusing the existing `CredentialEncryptionService` crypto primitives (envelope encryption: KMS provider → CEK per bucket → AES-256-GCM with resource-path AAD). **New code introduced by this proposal:** (a) `@EncryptedField` marker annotation in the `config/` module; (b) a new `SecretFieldProcessor` that walks the entity tree, encrypting `@EncryptedField`-annotated values on write and decrypting on rebuild; (c) dual Jackson `ObjectMapper` configurations — one for blob I/O (persists `ENC[...]`), one for API responses (masks as `"***"`). Scope of **newly-encrypted** fields: `Key.key` (API-managed keys only — see OQ-12), `Upstream.key`, `Upstream.extraData`, `ResourceAuthSettings.codeVerifier` — none of these are encrypted at rest today. `ResourceAuthSettings.clientSecret` is **already** encrypted today via `ResourceAuthSettingsEncryptionService` called from `ToolSetService.putToolSet()`; the existing bespoke path is kept for this field (a future unification under `@EncryptedField` is out of scope for Phase 2–3). `Application.env` out of scope. Export masks all secret fields. Dev mode (`SimpleKeyManagementService` — existing class) passes through unencrypted with startup warning. Optional `security-admin` role for plaintext secret access. **File-sourced key migration timing.** File-sourced `Config.keys` keep their current map-key-as-secret format **indefinitely** (per [OQ-12](08-open-questions-and-references.md)) — existing customer Helm / KeyVault / Admin-Backend-export pipelines are not touched by this proposal. File-sourced secrets implicitly migrate to encrypted blob storage only when an operator opts into config-file deprecation per [Phase 6](07-migration-and-rollout.md), at which point all entities including keys flow through the API path and inherit at-rest encryption. Phase 6 is optional and customer-driven, not a forced flag day. |
| R-Sec-2 | **Existing secrets workflow compatibility.** Current KeyVault-mounted config file approach continues to work during transition. |

### 2.2 Problem and scope

Several entity types contain secret fields (API keys, provider tokens, OAuth secrets). When entities are stored as JSON in blob storage, these secrets must be protected at rest.

**Secret fields in scope:**

| Entity | Field | Hot Path? | Encrypted at blob write today? | Notes |
|--------|-------|:-:|:-:|---|
| `Key` | `key` | ✅ | No (plaintext in config file / map key) | Platform API key secret. Highest risk. Depends on the OQ-12 key-model fix. API-managed keys encrypted via the new `SecretFieldProcessor`; file-sourced keys stay as-is by design. |
| `Upstream` | `key` | ✅ | No | Provider API tokens (OpenAI, Anthropic, etc.). |
| `Upstream` | `extraData` | ✅ | No | Entire JSON value encrypted as a single string. The in-memory `Java String` field value (e.g. `{"region":"us-east-1"}`) is what gets encrypted — `extraData` is already a JSON-as-string Java field before encryption (see `JsonToStringDeserializer` in [`02-architecture.md`](02-architecture.md) §8). On `?reveal_secrets=true`, the decrypted Java `String` is returned as-is in the JSON response body — it appears as a JSON string containing escaped JSON, not as an embedded object. No per-field carve-outs inside `extraData`. May contain AWS `secret_access_key` (Bedrock IAM credentials) but for region-only Bedrock upstreams it carries non-secret data like `{"region":"us-east-1"}`. **Hard invariant — no blob-write path bypasses `SecretFieldProcessor`.** On any write path that uses the blob-I/O `ObjectMapper` for entities containing `Upstream.extraData` (i.e. every `MergedConfigStore`-managed write — see §2.3 / §2.5), `SecretFieldProcessor` MUST run before serialization. There is no code path that writes `Upstream.extraData` to blob without encryption — the blob-I/O serialization step assumes the in-memory value is already `"ENC[..."` ciphertext (or an explicit `${SECRET:...}` reference). Phase 2 test requirement: a write attempt that hands a non-`ENC[`-prefixed `extraData` value to the blob mapper must demonstrably go through `SecretFieldProcessor` (which produces the `ENC[...]` string) before the mapper sees it; an integration test must assert the blob never contains a plaintext `extraData` payload from a `MergedConfigStore` write. Serialization-path details (the blob-I/O `BeanSerializerModifier`, `JsonToStringDeserializer` interaction, and the rationale against a class-level `@JsonSerialize`) are in [`02-architecture.md`](02-architecture.md) §8. **Operator visibility consequence.** Even when `extraData` carries no secret (region-only case), the persisted value is `ENC[...]` — operators inspecting via the Owner-view API see `"***"` and must use `?reveal_secrets=true` (security-admin role, §2.6) to read the region. This is a deliberate trade-off in favor of "always encrypted, no per-upstream-type carve-outs"; if review feedback indicates the region-only ergonomics are painful enough to address, future work could move `region` to a separate non-encrypted field on `Upstream`. |
| `ResourceAuthSettings` | `clientSecret` | ❌ | **Yes** — by `ResourceAuthSettingsEncryptionService.processFields()` invoked from `ToolSetService.putToolSet()` before Jackson serialization; uses `CredentialEncryptionService` under the hood. | OAuth client secret. Already encrypted at rest — no new code needed. The new `SecretFieldProcessor` does not touch this field (the existing bespoke path stays); if a future unification is desired, it becomes a refactor to add `@EncryptedField` here and retire `ResourceAuthSettingsEncryptionService`, but that is out of scope for Phase 2–3. |
| `ResourceAuthSettings` | `codeVerifier` | ❌ | **No** — plain `String` field, serialized verbatim by Jackson in `ToolSetService.putToolSet()`. | PKCE verifier. Plaintext in blob today. Encrypted in Phase 3 by extending `ToolSetService.putToolSet()` to invoke the existing `ResourceAuthSettingsEncryptionService` on this field — the same path that already encrypts `clientSecret`. The `@EncryptedField` / `SecretFieldProcessor` route does **not** fire for toolsets (toolsets are not routed through `MergedConfigStore` per §6 / §8 — the dual-mapper write path doesn't apply), so reusing the bespoke service is the only path that actually executes on the toolset write. **Lazy migration of legacy plaintext blobs.** Existing toolset blobs already in production carry `codeVerifier` as plaintext (no `Base64`-shaped ciphertext), so a naive `decryptValue()` invocation on read would throw `IllegalArgumentException` from `Base64.getDecoder().decode()`. Phase 3 must therefore extend `ResourceAuthSettingsEncryptionService.processFields()` (which today only handles `clientSecret` — see `ResourceAuthSettingsEncryptionService.processFields()` body) to also process `codeVerifier`, and the read path must guard the decode: attempt Base64 decode + decrypt, and if the value does not look like valid Base64 ciphertext (catch `IllegalArgumentException` from the decoder, or guard via an `isProbablyBase64(value)` precheck), treat the value as legacy plaintext, return it as-is, and re-encrypt on the next write. This mirrors the legacy-plaintext handling pattern used by `SecretFieldProcessor` for the `ENC[`/`${SECRET:`/plaintext branches. |

`Key.key`, `Upstream.key`, and `Upstream.extraData` are the fields newly encrypted at rest by `SecretFieldProcessor` via `@EncryptedField` (these flow through `MergedConfigStore`'s dual-mapper write path). `ResourceAuthSettings.codeVerifier` is also newly encrypted at rest, but via the existing `ResourceAuthSettingsEncryptionService` extended in Phase 3 — not via `@EncryptedField` — because toolsets do not flow through `MergedConfigStore`. `ResourceAuthSettings.clientSecret` is already encrypted today by the same bespoke service and is listed for completeness. `Application.env` is out of scope — deployed apps use a different storage path managed by `ApplicationOperatorService`.

### 2.3 Decision: field-level encryption reusing `CredentialEncryptionService` crypto

Encrypt individual secret fields within the JSON entity before writing to blob. Non-secret fields remain in plaintext (inspectable, debuggable). On `MergedConfigStore` rebuild, decrypt secret fields and populate the in-memory `Config` with plaintext values for hot-path reads.

**What is reused vs what is new:** the crypto primitives (`CredentialEncryptionService`, `ContentEncryptionKeyService`, `DataEncryptionService`, `KeyManagementService` providers incl. `SimpleKeyManagementService`) exist today and are used for credential storage. This proposal **adds new plumbing on top**: the `@EncryptedField` annotation, the `SecretFieldProcessor` that walks entity trees to find and process annotated fields, and the dual-mapper Jackson setup. No new KMS integration, no new key hierarchy — the encryption *layer* is genuinely new, the encryption *primitives* are not.

**Encryption hierarchy (existing primitives):**

```
KMS Provider (AWS KMS / Azure Key Vault / GCP KMS)
    ↓ wraps/unwraps
Content Encryption Key (CEK) — per-bucket, stored in blob
    ↓ encrypts/decrypts
Individual secret field values — AES-256-GCM with resource-path AAD
```

**Write path:**
```
PUT /v1/models/public/gpt-4
  → body.upstreams[0].key = "sk-abc123..."
  → SecretFieldProcessor detects @EncryptedField annotation
  → CredentialEncryptionService.encrypt(bucketInfo, keyBytes, resourcePath.getBytes())
  → Store JSON: { "upstreams": [{ "key": "ENC[AES256-GCM,data:base64...,iv:...,tag:...,aad:...]" }] }
```

**Read path (MergedConfigStore rebuild):**
```
  → Load blob JSON
  → Detect "ENC[" prefix on secret fields
  → CredentialEncryptionService.decrypt(...)
  → In-memory Config holds plaintext → zero hot-path impact
```

### 2.4 Secret field identification

`@EncryptedField` annotation on entity class fields in `config/` module (shared with CLI — CLI uses this to know which fields to mask in export):

```java
public class Upstream {
    private String endpoint;
    @EncryptedField
    private String key;
    @EncryptedField
    private String extraData;  // entire JSON value encrypted
}
```

**Blob format — encrypted field marker:** `"ENC[AES256-GCM,data:base64...,iv:base64...,tag:base64...,aad:...]"` prefix makes encrypted values self-identifying. Non-encrypted values (from config files or legacy) have no prefix and pass through as-is.

**Negative annotation rule for `ResourceAuthSettings.clientSecret` and `ResourceAuthSettings.codeVerifier` (Phase 2/3 implementation checklist item).** Neither field may carry `@EncryptedField` — both stay on the existing bespoke `ResourceAuthSettingsEncryptionService` path (§2.7). For `clientSecret`: the existing ciphertext format is bare Base64 with no `ENC[` prefix; if `clientSecret` were inadvertently `@EncryptedField`-annotated, `SecretFieldProcessor`'s prefix-check would treat that ciphertext as plaintext (no `ENC[` match, no `${SECRET:` match → "plaintext" branch) and silently send Base64 garbage to OAuth flows. For `codeVerifier`: toolsets are not routed through `MergedConfigStore` per §6 of `02-architecture.md`, so `SecretFieldProcessor` (which only runs inside `MergedConfigStore`'s dual-mapper write path) would never fire on the toolset write — annotating it would yield silent plaintext-at-rest. Phase 3 instead extends `ResourceAuthSettingsEncryptionService.processFields()` (already invoked by `ToolSetService.putToolSet()` for `clientSecret`) to encrypt `codeVerifier` on the same path, with the same bare-Base64 ciphertext format (no `ENC[` prefix). Enforced by a unit test that reflects over every `@EncryptedField`-annotated field across the `config/` module and asserts neither `ResourceAuthSettings.clientSecret` nor `ResourceAuthSettings.codeVerifier` is in the set.

### 2.5 API write-only policy

`PUT` is upsert (see [`03-api-reference.md`](03-api-reference.md) §3) — the controller distinguishes "create" from "update" by reading the pre-state inside the same `LockService` scope as the write (the same pre-read the preserve-on-omit logic needs). The secret-field policy splits along that pre-state, not along HTTP method:

| Operation | Pre-state | Secret field behavior |
|-----------|-----------|-----------------------|
| `GET` | (any) | Masked: `"key": "***"` |
| `PUT` (creating: pre-state absent) | absent | Field absent/null → store as null (no secret set on create). Field present, non-mask value → encrypt and store. Field = `"***"` → reject with `400 Bad Request` — message: *"Secret field 'X' contains the mask sentinel '***'. Provide a real secret value or omit the field."* The mask sentinel is not a valid create-time secret. |
| `PUT` (updating: pre-state present) | present | Field absent/null/`"***"` → preserve existing encrypted value (preserve-on-omit). Field present with non-mask value → encrypt and store. |
| `export` | (any) | Masked: `"key": "***"` |
| `promote` | (any) | Secrets skipped — set per-environment |
| `validate` | (any) | Secret fields ignored |

The mask-sentinel rejection on a creating PUT must be implemented at the same site as the preserve-on-omit logic (the per-entity `PUT` controller for every entity type whose data class carries `@EncryptedField` annotations) so the two behaviors compose without ambiguity. The rejection is a `400 Bad Request`, not a `412 Precondition Failed` — `412` is reserved for `If-Match` / `If-None-Match` conditional-header failures per [`03-api-reference.md`](03-api-reference.md) §3.

**Write path for entities with `@EncryptedField` fields — server-side preserve-on-omit (Phase 2 implementation requirement).** Preserve-on-omit is **server behavior**, not CLI ergonomic — every CLI / Admin Backend / MCP / direct-curl client gets the same behavior, no client-side logic required. On any `PUT /v1/{type}/{bucket}/{name}` whose entity class declares one or more `@EncryptedField` fields, the controller:

1. Reads the existing blob (if present) via `ResourceService.getResource(descriptor)` and deserializes it through the blob-I/O `ObjectMapper` so encrypted values are present as `ENC[...]` ciphertext (not yet decrypted).
2. For each `@EncryptedField` field on the entity, if the request body has the field **absent**, **`null`**, or equal to the literal mask string `"***"`, the controller substitutes the corresponding ciphertext value from the existing blob.
3. The merged body is then encrypted-on-write through `SecretFieldProcessor` for fields whose value was newly supplied (existing ciphertext from step 2 is already encrypted and passes through unchanged) and persisted.

Net effect: a client GET-merge-PUT round-trip is safe even when the GET response masks the secret — the masked `"***"` round-trips back as the preserved ciphertext rather than overwriting the stored secret with the literal string `"***"`. Phase 2 must implement this in the per-entity `PUT` controller for every entity type whose data class carries `@EncryptedField` annotations (`Model.upstreams[].key`, `Model.upstreams[].extraData`, `Key.key`); toolset writes preserve `clientSecret` and `codeVerifier` through the existing `ResourceAuthSettingsEncryptionService` path, which has equivalent preserve-on-omit semantics handled inside `ToolSetService.putToolSet()`.

**Atomicity note — pre-read must execute inside the same `LockService` scope as the write.** A naive implementation that calls `ResourceService.getResource(descriptor)` for the pre-read and then calls `ResourceService.put(descriptor, mergedBody)` for the write opens a TOCTOU window: `ResourceService.put()` acquires the distributed lock internally, so the pre-read runs **outside** that lock. Two concurrent PUTs can each read the same stale ciphertext from their respective pre-reads, each merge it into their request body, and each write — and last-write-wins silently.

Phase 2 implementation requirement (API surface change): extend `ResourceService` with a **public overload `put(descriptor, body, EtagHeader etag, boolean skipLock)`** that performs the storage write without re-acquiring `LockService.lock()` because the controller has already acquired it for the pre-read+merge bracket. (Earlier `package-visible` framing was incorrect — `ResourceService` (`storage` module) and the config controllers (`server` module) are in separate Gradle modules, so package visibility cannot bridge them.) Javadoc precondition on the overload: *"The caller MUST hold the distributed lock for `descriptor` via `LockService.lock()` before calling this overload."* The controller acquires the distributed lock once via `LockService.lock(descriptor, () -> ...)`, performs the pre-read inside the lambda, merges the ciphertext into the request body, then calls the `skipLock=true` overload to write under the same lock. This is the option chosen because the alternative — wrapping the entire pre-read + merge + put inside a single `LockService.lock(descriptor, () -> ...)` lambda and relying on the inner `ResourceService.put()`'s own lock acquisition being re-entrant — depends on `LockService` re-entrancy semantics not currently guaranteed by the interface, and the second alternative (controller bypasses `ResourceService.put()` entirely and writes through a lower-level storage method) duplicates the cache-invalidation / `ResourceTopic` publish work `ResourceService.put()` already performs. The `skipLock` overload is the minimal addition that preserves the rest of `ResourceService.put()`'s side-effect contract (Redis HASH update, blob fsync queue, `ResourceEvent` publish) while letting the controller co-locate the pre-read and the write under one lock acquisition. Co-locating the pre-read and the write under the same lock guarantees the second writer reads the first writer's ciphertext on its merge step rather than the stale pre-write value. Without this co-location, preserve-on-omit silently corrupts on concurrent writes. Tracked as a Phase 2 prerequisites compile-time blocker item in [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 2.

### 2.6 Optional secret read access

Operators with a special role (e.g., `security-admin`, configured via bootstrap settings) can retrieve plaintext secrets via a separate query parameter: `GET /v1/models/public/gpt-4?reveal_secrets=true`. If the `security-admin` role is not configured on the environment, this feature is simply unavailable — no risk surface. The `security-admin` tier is **separate from and stronger than** the Owner view in §1.5 — it grants plaintext-secret reveal in addition to the Owner-view fields. Useful for debugging and migration.

### 2.7 Config file backward compatibility

Config-file entities continue to store secrets in plaintext (as they do today). The encryption layer only applies to API-managed entities in blob storage. `MergedConfigStore` rebuild handles four formats transparently:
- **Plaintext** (config file / dev mode) — no prefix, passes through.
- **`ENC[...]`** (this proposal's `@EncryptedField` blob format) — handled by the new `SecretFieldProcessor` via `CredentialEncryptionService.decrypt()`.
- **`${SECRET:...}`** (Phase 5+ vault reference) — resolved against an external secret store.
- **Existing bare-Base64 ciphertext on `ResourceAuthSettings.clientSecret` and (Phase 3+) `ResourceAuthSettings.codeVerifier`** — produced by `ResourceAuthSettingsEncryptionService.encryptValue()` (no `ENC[` prefix, just `Base64.getEncoder().encodeToString(...)`), stored on toolset blobs. `clientSecret` has lived on this path since before this proposal; Phase 3 extends the same service to also encrypt `codeVerifier` since toolsets do not flow through `MergedConfigStore` and `SecretFieldProcessor` would not fire on the toolset write. Both fields are decrypted by the existing `ResourceAuthSettingsEncryptionService.decryptValue()`. Toolset reads must keep using that service for these two fields rather than routing through `SecretFieldProcessor`, otherwise the bare-Base64 payloads would be misinterpreted as plaintext (no `ENC[` prefix matches) and silently fail downstream OAuth / PKCE flows.

  **Legacy-plaintext handling for `codeVerifier` (Phase 3 read-path invariant).** Existing toolset blobs in production carry `codeVerifier` as plaintext today; the Phase 3 read path must therefore distinguish "legacy plaintext" from "new bare-Base64 ciphertext" before calling `decryptValue()`. Naive `Base64.getDecoder().decode()` on a non-Base64 plaintext throws `IllegalArgumentException`. The decryption invariant for `codeVerifier` is: attempt the Base64 decode + AES decrypt; on `IllegalArgumentException` from the decoder (or via an `isProbablyBase64(value)` precheck), treat the value as legacy plaintext, return it verbatim, and let the next toolset write re-encrypt it through the encrypted path. This mirrors `SecretFieldProcessor`'s prefix-based fallthrough to a "plaintext" branch and keeps the migration lazy — no separate one-shot blob-rewrite job. `clientSecret` does not need this guard because it has been encrypted by the bespoke service since before this proposal; only `codeVerifier` carries pre-Phase-3 plaintext payloads.

  **Why bare-Base64 is safe under the dual-path invariant.** `SecretFieldProcessor` distinguishes its three formats (plaintext / `ENC[...]` / `${SECRET:...}`) by **prefix only** — it has no way to tell, from the value content alone, that a bare-Base64 string is ciphertext rather than a literal plaintext key. The "stay on bespoke path" invariant for `clientSecret` is therefore not a runtime check on value content; it is enforced by the **absence of `@EncryptedField` on the field** (§2.4 negative annotation rule + reflective unit test). Field identity, not value shape, is what keeps the two encryption paths from colliding.

### 2.8 CEK provisioning

The `platform/` bucket needs a `KeyManagementService` provider configured via `admin.security.kms` in bootstrap settings. A provider is **always** configured — the only question is which one. Production environments configure a real KMS (AWS KMS / Azure Key Vault / GCP KMS). Dev environments fall back to `SimpleKeyManagementService` (existing class — no-op pass-through), which logs a startup warning: `"WARN: KMS provider is 'unencrypted' — secrets will be stored in plaintext. Not suitable for production."`. If no provider at all is resolvable at startup (misconfiguration — neither a real KMS nor `SimpleKeyManagementService`), DIAL Core fails to start rather than silently downgrading; the Configuration API therefore never has to return a runtime `400` for missing KMS. This keeps the "encryption is always applied" invariant honest and removes the earlier contradiction between a runtime `400` and the dev-mode pass-through.

### 2.9 Performance

Encrypt on write: ~1ms per field (dozens/day — negligible). Decrypt on rebuild: ~1ms × number of secret fields across all entities. 50 entities × 2 fields = ~100ms. Hot-path reads: zero impact — in-memory Config holds plaintext.

### 2.10 Phase 5+ extension (vault references)

A field value starting with `${SECRET:vault-path}` is resolved from an external secret store instead of decrypting from blob. Both config files and API-managed entities can use this syntax. The `resolveSecret()` function in `MergedConfigStore` handles the three formats reachable from `@EncryptedField` fields. The fourth format — bare-Base64 ciphertext on `ResourceAuthSettings.clientSecret` — never reaches `resolveSecret()` because `clientSecret` is not annotated with `@EncryptedField`; it stays on the existing `ResourceAuthSettingsEncryptionService` path (§2.7).

```java
String resolveSecret(String fieldValue, ResourceDescriptor resource) {
    if (fieldValue.startsWith("${SECRET:")) {
        return secretStoreService.resolve(fieldValue);  // Phase 5+
    } else if (fieldValue.startsWith("ENC[")) {
        return credentialEncryptionService.decrypt(bucketInfo, fieldValue, resource);
    } else {
        return fieldValue;  // Plaintext (config file / dev mode)
    }
}
```

See `dial_secrets_storage_analysis.md` for the full evaluation of alternative approaches (document-level encryption, secret references/indirection) and their trade-offs.

---

## 3. Audit

> **STATUS: WIP / DEFERRED.** The audit subsystem is **deferred to Phase 7** (Audit & Compliance) — after full entity-management API support, CLI surface, and Admin MCP land. §3 below remains as the working design draft for that future phase. **Phase 1–6 make no commitment to R-Audit-1 or R-Audit-2** and ship without an audit trail. Cross-references from other documents to specific §3 subsections (storage layout, event schema, CLI commands, `/v1/admin/audit`, `dial_admin_query_audit` MCP tool) all carry the same WIP status. See [`07-migration-and-rollout.md`](07-migration-and-rollout.md) Phase 7 for the rollout placement and rationale.

### 3.1 Requirements

| ID | Requirement |
|----|---|
| R-Audit-1 | **Change audit log.** Every Configuration API mutation recorded with timestamp, admin identity (`requestedBy`), entity type, canonical entity ID, operation, post-mutation state snapshot, diff summary, batch correlation. Vault-style intent log: PENDING before mutation, APPLIED/FAILED after. Storage: Redis Streams (hot, queryable) + blob archival (cold, durable). Scope: all Configuration API mutations across both `public/` and `platform/` buckets. Audit captures actor mutations only — validity transitions are derived runtime state surfaced through per-entity-GET / `/v1/admin/health/config` / Prometheus channels ([`02-architecture.md`](02-architecture.md) §4.1), not as audit events. User publication workflow (`PublicationService`) auditing deferred to Phase 4+. |
| R-Audit-2 | **Audit log query API.** Filterable by: time range, `requestedBy`, entity type, entity ID, bucket, batch ID, operation, status. Paginated. CLI support via `dial-cli audit`. |

### 3.2 Design: Vault-style intent log

Storage: **Redis Streams (hot) + blob archival (cold)**. Write pattern: **Vault-style intent log**.

**Audit scope: Configuration API controller (workflow-based, not bucket-based).** *(Previously scoped to Phase 3 — deferred to Phase 7. Scope diagram preserved for Phase 7 design reference.)*
Audit covers ALL mutations through the Configuration API — both `public/` and `platform/` buckets. This is a single interception point with a uniform actor model (admin JWT) and low volume (dozens of events/day). Splitting by bucket would leave half the admin operations unaudited — models, apps, schemas in `public/` would be invisible. User publication workflow operations (`PublicationService`) are a separate code path with different volume characteristics — deferred separately.

```
Configuration API audit scope (single interception point):
  ├── public/ bucket: models, applications, toolsets, schemas
  └── platform/ bucket: roles, keys, routes, interceptors, settings

Separate audit scope (different code path, higher volume):
  └── PublicationService operations: create/approve/reject, file uploads, prompt publications
```

**Mutation flow:**

```
1. Write PENDING audit event to Redis Stream (before mutation)
2. Execute the actual mutation (ResourceService.put/delete or ApplicationService/ToolSetService)
3. Write APPLIED or FAILED completion event to Redis Stream
```

**Operation derivation.** The audit `operation` field (`create | update | delete`) is derived from the HTTP method plus the pre-state observed inside the write transaction: `DELETE` → `delete`; `PUT` → `create` if no prior entity existed at the URL (the same read the preserve-on-omit and `If-None-Match: *` paths already perform) else `update`. The per-entity-write controller already holds the `LockService` lock around the pre-read + merge + put bracket (see [§2.5](#25-api-write-only-policy)), so the pre-state read is colocated with the write and the audit `operation` derivation runs without a separate round-trip and without a TOCTOU race. Bulk `POST /v1/admin/apply` follows the same rule on each per-entity step.

### 3.3 Event schema

Single `state` field, canonical resource ID:

```json
{
  "id": "evt-20260409-abc123",
  "timestamp": "2026-04-09T14:30:00Z",
  "requestedBy": "admin@company.com",
  "approvedBy": null,
  "entityType": "models",
  "entityId": "models/public/anthropic.claude-sonnet-4-6",
  "bucket": "public",
  "operation": "update",
  "status": "APPLIED",
  "state": { /* post-mutation entity snapshot */ },
  "diff": { "endpoint": "changed", "pricing.prompt": "changed" },
  "batch_id": null,
  "batch_index": null,
  "batch_size": null
}
```

**Actor fields:**
- `requestedBy` — who initiated the change. For Configuration API mutations: always the admin JWT identity.
- `approvedBy` — reserved for the future publication workflow audit, where user creates and admin approves. Always null for Configuration API mutations.

**Audit records actor mutations only.** The audit log captures what *admins did*, not derived runtime state. Validity transitions (an entity becoming invalid because a referenced interceptor was removed, then later becoming valid again when the interceptor returns) are not audited as separate events — they are derivable by correlating mutation events with the current per-entity-GET / health-endpoint snapshot. The visibility surface for entity validity is the three runtime channels in [`02-architecture.md`](02-architecture.md) §4.1: per-entity GET `status` field + `/v1/admin/health/config` aggregator, Prometheus metrics, and the cluster-wide skip-and-continue path.

**Audit rollback in Phase 7 is read-mostly.** `dial-cli audit history` and `dial-cli audit snapshot` work against any past state. `dial-cli audit rollback` re-applies a prior snapshot through the standard write path, which means it is subject to current-version validation — if the snapshot's payload no longer satisfies validation (renamed field, removed schema reference, deprecated enum), the rollback is rejected the same way a manual `PUT` of that payload would be. A recovery mechanism for restoring snapshots whose payload is incompatible with the current entity model (a write-time validation bypass, an in-place schema-tolerant load, or a hybrid) is tracked as OQ-31 in [`08-open-questions-and-references.md`](08-open-questions-and-references.md) and is intentionally out of scope for Phase 7's MVP.

### 3.4 Lifecycle and storage

**Hot tier — Redis Streams (`dial:audit:events`).** Queryable via `XREAD` / `XRANGE`. `MAXLEN` bounds memory; if reached, the PENDING-write critical path returns `503` (§3.7) — events are never silently dropped.

**Cold tier — blob archival.** A periodic job moves Stream entries older than the archival threshold to blob and trims via `XTRIM MINID`. A separate cleanup job removes blob events and snapshots past the retention window.

| Setting | Default | Purpose |
|---|---|---|
| `admin.audit.archive.threshold` | `24h` | Stream entries older than this are eligible for archival |
| `admin.audit.archive.interval` | `5m` | Archival job cadence (must be `<<` threshold) |
| `admin.audit.retention.events` | `30d` | Blob retention for archived events |
| `admin.audit.retention.snapshots` | `30d` | Blob retention for state snapshots |
| `admin.audit.snapshot.interval` | `24h` | Daily full state snapshot cadence |
| `admin.audit.cleanup.schedule` | `0 2 * * *` | Daily cleanup cron for past-retention events + snapshots |
| `admin.audit.reconciliation.interval` | `15m` | Orphaned-PENDING resolver cadence |
| `admin.audit.reconciliation.orphanThreshold` | `5m` | A PENDING older than this with no completion is considered orphaned |

**Concurrency.** Archival, snapshot, reconciliation, and cleanup jobs each acquire a Redisson `RLock` so only one replica runs each job at a time.

**Cursor and crash safety.** The archival job tracks the last-archived Stream ID in a blob marker (`audit/_cursor`); the cursor advances only after the JSONL blob fsyncs. Replay from the cursor on crash produces no duplicates because Stream IDs are monotonic. `XTRIM MINID` runs only against the persisted cursor.

**Blob layout.** Audit lives at the top-level `audit/` prefix — bucket-agnostic, covering mutations in both `public/` and `platform/`.

- Events: `audit/YYYY/MM/DD/events-YYYYMMDDTHHMMSSZ.jsonl` — one file per archival run; lexicographic sort = chronological order.
- Snapshots: `audit/snapshots/YYYY-MM-DDTHH:MM:SSZ.json` — full state of all audited entities across both buckets.
- Cursor: `audit/_cursor` — last-archived Stream ID.

**Boundary snapshot preservation.** Cleanup always retains at least one snapshot at the retention boundary so point-in-time reconstruction stays possible for any timestamp inside the retention window.

**Reconciliation** (optional). Detects orphaned PENDING events — entries with no APPLIED/FAILED completion older than `orphanThreshold`. Resolves by reading the entity's current state and comparing against the PENDING `state` snapshot to write the missing APPLIED or FAILED completion.

**Metrics** (Prometheus): `dial_audit_stream_length` (gauge, `XLEN`), `dial_audit_archive_lag_seconds` (gauge, age of oldest unarchived entry), `dial_audit_archive_runs_total{result}` (counter), and `dial_config_api_audit_write_failed_total` (counter, already named in §3.7). These feed the §3.7 SEV-2 thresholds (warn ~70% `MAXLEN`, page ~90%).

### 3.5 Audit query API

```
GET /v1/admin/audit
```

Filters: `entityType`, `entityId`, `bucket`, `batch_id`, `requestedBy`, `operation`, `status`, `since`, `until`. Paginated.

- `operation` — `create | update | delete`
- `status` — `PENDING | APPLIED | FAILED` (useful for reconciliation and incident triage)

The query API transparently spans both tiers: filters resolved against Stream entries first, then over the relevant blob date partitions. Operators do not need to know whether an event is hot or cold.

### 3.6 CLI surface

DevOps-facing audit commands are documented in [`06-cli-user-guide.md`](06-cli-user-guide.md) §Audit Log. Representative commands:

```shell
dial-cli audit history models/public/gpt-4 --from 2026-03-09 --to 2026-04-09
dial-cli audit log --batch batch_xyz789
dial-cli audit snapshot --at 2026-04-01 --entity-type models -o yaml
dial-cli audit rollback models/public/gpt-4 --to-event evt_a1b2c3d4
dial-cli audit reconcile --dry-run
```

### 3.7 Criticality

> *(Phase 7 — deferred. In Phases 1–6, admin writes proceed without an audit gate; the contract below describes the Phase 7 design draft, not current behavior. Phase 1–6 attribution relies on DIAL Core structured `/v1/admin/*` application logs and external Git/ConfigMap versioning — none substitute for a real audit trail; that gap is the explicit cost of Phase 7 deferral.)*

**Audit blocks the mutation.** If the PENDING write to Redis Stream fails, the config change is aborted. This is the Vault model — the audit trail cannot lag or be silently dropped. A write that isn't audited doesn't happen.

**Operational consequence (runbook).** Because the PENDING write is in the critical path, Redis Streams availability becomes the SLO for all admin config mutations. If Redis is partitioned, overloaded, or its stream storage is exhausted, admin-gated writes (per-entity `PUT` / `DELETE` to `public/` and `platform/`, plus `/v1/admin/apply` and any other `/v1/admin/*` mutating op) will return `503 Service Unavailable` with a body identifying audit-write failure as the cause. **Scope of the 503 is limited to admin write endpoints only.** Unaffected by an audit-stream outage: (a) all `GET` endpoints (per-entity reads, listings, `GET /v1/admin/audit` itself for query — the read tier is independent of the write-tier PENDING gate; the deferred `GET /v1/admin/export` will follow the same unaffected-by-audit-outage rule when it ships — see [IMPLEMENTATION.md §5.5 Defer.1](IMPLEMENTATION.md)), (b) the unauthenticated `/health` Kubernetes liveness probe, (c) the `MergedConfigStore` rebuild path (it reads from Redis HASH / blob, not the Stream), and (d) all runtime traffic — chat completions, embeddings, file uploads, and the entire user Resource API. Pod scale-up and skip-and-continue invariants from §4.1 of `02-architecture.md` are preserved; only the admin mutation surface is gated by the Stream SLO. Operators should:

- Alert on `config_api_audit_write_failed_total` Prometheus counter with a low threshold (any sustained rate > 0 is a SEV-2).
- Monitor Redis Stream length for `dial:audit:events`; set a warning at ~70% of configured `MAXLEN` and a page at ~90%. Stream trimming runs after archival (§3.4); if archival lags, the stream grows.
- Treat any degraded admin-write surface as an incident — do not bypass the audit path manually (there is no bypass flag by design). The fallback during an incident is to defer admin changes until Redis is healthy.
- Expect `dial-cli apply -f config/` to fail fast on the first audit-write failure — it will not silently apply half a manifest set.

A follow-up design may introduce a circuit-breaker with explicit "audit-degraded" mode if incident frequency warrants it, but this is intentionally out of scope for Phase 7's MVP: a silent degradation path is worse than a loud outage for a compliance-relevant audit log.

### 3.8 Retention (default)

~24h in Redis Streams (the archival threshold — §3.4); 30d in blob (`admin.audit.retention.events`, configurable). Daily snapshots persist for the same 30d window and enable point-in-time reconstruction even after archived events are pruned. The query API in §3.5 transparently spans both tiers.

---

## Summary checklist for a security reviewer

- [ ] All admin-gated endpoints — per-entity CRUD on `public/` and `platform/` (`/v1/{type}/{bucket}/{name}`) plus all cross-entity ops (`/v1/admin/*`) — route through `ConfigAuthorizationService` — no inline `hasAdminAccess()`.
- [ ] `AdminRoleAuthorizationService` gates both `public/` writes and `platform/` reads/writes.
- [ ] `@EncryptedField` annotation is applied to every secret field listed in §2.2.
- [ ] `SecretFieldProcessor` encrypts on write, decrypts on rebuild, and masks on API response.
- [ ] KMS is configured for the `platform/` bucket in every non-dev environment.
- [ ] Phase 5+ `${SECRET:...}` syntax is reserved but not implemented yet.

**Audit checklist items (Phase 7 — deferred):**

- [ ] (Phase 7) PENDING → APPLIED/FAILED audit event is written for every mutation; PENDING write is in the critical path.
- [ ] (Phase 7) Audit event carries full post-mutation `state` and `diff` summary, keyed by canonical resource ID.
- [ ] (Phase 7) Audit query endpoint `/v1/admin/audit` supports the filters in §3.5.

## Next

- Architecture context for the bucket split and MergedConfigStore: [`02-architecture.md`](02-architecture.md)
- API shape: [`03-api-reference.md`](03-api-reference.md)
- CLI audit command reference: [`06-cli-user-guide.md`](06-cli-user-guide.md)
