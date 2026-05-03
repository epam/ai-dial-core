# 07 — Migration & Rollout

> **Audience:** Leads, PM, DevOps. Dev team for the per-phase work breakdown.
> **Reading time:** ~12 minutes.
> **Prerequisites:** [`README.md`](README.md) one-paragraph summary.

This document collects the technical requirements that bound the proposal, the phased rollout plan with value delivered per phase, and the operational changes each phase brings to DevOps teams, Admin operators, and DIAL Core deployment.

---

## 1. Technical Requirements

Requirements are referenced by ID throughout the rest of the proposal. Think of them as the boundary conditions for the design — anything that doesn't satisfy R1–R16 is off-track.

### Architecture

| ID | Requirement |
|----|-------------|
| **R1** | **API-first.** All config changes go through a well-defined API in DIAL Core. CLI and Admin UI are both clients. |
| **R2** | **Single source of truth.** DIAL Core exposes an API reflecting the current effective (merged) runtime configuration for all entity types. |
| **R3** | **Immediate consistency on writer.** Config changes take effect on the pod that processes the write immediately (volatile ref swap). Cross-replica propagation ≤60s via polling (improved to near-instant in Phase 1.5 via pub/sub). |
| **R4** | **Backward compatibility.** Config-file approach continues to work during transition. File-defined entities appear alongside API-managed ones in the unified API (see [`02-architecture.md`](02-architecture.md) §MergedConfigStore). |
| **R5** | **Declarative and imperative modes.** Support both full desired-state apply and individual entity CRUD. |

### CLI Tool

| ID | Requirement |
|----|-------------|
| **R6** | **Environment profiles.** Named environment profiles in `~/.dial-cli/config.yaml` with API URLs, adapter hosts, icon base URLs, auth settings. |
| **R7** | **Adapter presets.** Built-in presets (bedrock, vertexai, openai) that auto-populate endpoint URL patterns and upstream semantics. |
| **R8** | **Promotion with template-based transformation.** The `promote` command supports three modes: as-is copy (no `--template`), template-based re-resolution (`--template <n>`), and auto-detection (`--template auto`). Non-template fields (displayName, features, pricing) carried from source unchanged. Warns if as-is copy contains source-env hostnames. |
| **R9** | **Diff and dry-run.** Every mutation supports `--dry-run`. Standalone `diff` command compares entities between environments. |
| **R10** | **Validation.** Pre-apply schema validation. Automatic before mutations, available standalone. |
| **R11** | **Output formats.** Human-readable table (default), JSON, YAML. |
| **R12** | **Authentication.** API keys, access tokens, or configurable auth. Credentials from environment profile or env vars. |

### Audit

> **STATUS: WIP / DEFERRED to Phase 7.** R13 and R14 below remain as the working design but are not delivered in Phases 1–6. See §Phase 7 below for placement and rationale, and [`04-security-and-audit.md`](04-security-and-audit.md) §3 for the design draft.

| ID | Requirement |
|----|-------------|
| **R13** *(deferred — Phase 7)* | **Change audit log.** Every Configuration API mutation recorded with timestamp, admin identity (`requestedBy`), entity type, canonical entity ID, operation, post-mutation state snapshot, diff summary, batch correlation. Vault-style intent log: PENDING before mutation, APPLIED/FAILED after. Storage: Redis Streams (hot, queryable) + blob archival (cold, durable). Scope: all Configuration API mutations across both `public/` and `platform/` buckets. Audit captures actor mutations only — validity transitions are derived runtime state surfaced through listing/health/Prometheus channels ([`02-architecture.md`](02-architecture.md) §4.1), not as audit events. User publication workflow (`PublicationService`) auditing remains a separate Phase 7+ scope decision. See [`04-security-and-audit.md`](04-security-and-audit.md) §Audit. |
| **R14** *(deferred — Phase 7)* | **Audit log query API.** Filterable by: time range, `requestedBy`, entity type, entity ID, bucket, batch ID, operation, status. Paginated. CLI support via `dial-cli audit`. |

### Secrets

| ID | Requirement |
|----|-------------|
| **R15** | **Secrets segregation.** Secret values protected via field-level encryption that **reuses** the existing `CredentialEncryptionService` crypto primitives (envelope encryption: KMS provider → CEK per bucket → AES-256-GCM with resource-path AAD) and **introduces new code**: the `@EncryptedField` marker annotation (new, in `config/` module), a new `SecretFieldProcessor` that walks entity trees to encrypt/decrypt annotated values, and a dual Jackson `ObjectMapper` setup (blob I/O vs. API response). Newly-encrypted fields: `Key.key` (API-managed keys only — see OQ-12), `Upstream.key`, `Upstream.extraData`, `ResourceAuthSettings.codeVerifier`. `ResourceAuthSettings.clientSecret` is already encrypted today by the existing `ResourceAuthSettingsEncryptionService` called from `ToolSetService.putToolSet()` — the bespoke path is kept as-is. `Application.env` out of scope. API responses mask with `"***"`. Export masks all secret fields. Dev mode (`SimpleKeyManagementService` — existing class) passes through unencrypted with startup warning. Optional `security-admin` role for plaintext secret access. See [`04-security-and-audit.md`](04-security-and-audit.md) §Secrets at Rest. |
| **R16** | **Existing secrets workflow compatibility.** Current KeyVault-mounted config file approach continues to work during transition. |

---

## 2. Phased Rollout

Seven phases. Phase 0 is current research and design. Phases 1–4 deliver the Configuration API and CLI. Phase 5 migrates the Admin Backend. Phase 6 is optional config-file deprecation. Phase 1.5 is called out separately because its scope is small and its value compounds with everything that comes after — but **it depends on Phase 2's write path** (pub/sub events are only meaningful once writes exist), so it ships concurrently with or after Phase 2. The numbering reflects its conceptual placement (a cross-cutting consistency improvement), not its chronology.

### Phase 0: Research & Design (current)

- [x] Current state analysis.
- [x] Storage backend decision — reuse ResourceService (Redis + Blob).
- [x] Architecture design — MergedConfigStore union.
- [x] Bucket strategy — `public/` for user-facing, `platform/` for infrastructure.
- [x] Precedence rule — union, no override (simple names + canonical IDs coexist).
- [x] Apply failure semantics — validate-first gate (CLI) + continue on failure (server).
- [x] CLI language — Java (Picocli + Quarkus + GraalVM).
- [x] Audit log design — Redis Streams + blob archival, Vault-style intent log, state-based schema.
- [x] Post-load processing — single shared `ConfigPostProcessor` invoked once after the union, with per-entity skip-invalid (see OQ-15 in [`08-open-questions-and-references.md`](08-open-questions-and-references.md)).
- [x] Deployment identifier model — full-path canonical IDs internally and in API; simple names preserved for client-facing URLs until multi-tenancy.
- [x] Cross-proposal alignment with MT conceptual design — `EntityLocationStrategy`, `ConfigAuthorizationService`, scope prefixes validated.
- [ ] Finalize Configuration API contract (OpenAPI spec — draft complete, review pending).

### Phase 1: Read-Only Configuration API + CLI Read Commands

**DIAL Core changes:**

- Implement `GET /v1/{entityType}/{bucket}/` (per-bucket listing) and `GET /v1/{entityType}/{bucket}/{name}` (per-entity GET) endpoints by adding a new sibling `RouteTemplate.CONFIG_RESOURCE` entry covering only the new admin-config types — `(models|interceptors|roles|keys|routes|schemas|settings)`. **Existing `RouteTemplate.RESOURCE` (`conversations|prompts|applications|toolsets`) and `RouteTemplate.FILES` (`/v1/files/...`) are left unchanged** — admin reads of `public/files/`, `public/prompts/`, and `public/conversations/` go through their existing controllers with `ConfigAuthorizationService` consulted as an authz preflight (see [`02-architecture.md`](02-architecture.md) §5.1, [`03-api-reference.md`](03-api-reference.md) §1). Public/Owner field projection per [`04-security-and-audit.md`](04-security-and-audit.md) §1.5.
- Implement the `EntityBucketBinding` static `(entityType, bucket)` allowlist (per [`04-security-and-audit.md`](04-security-and-audit.md) §1.2) as a startup assertion (every entry in the new `ResourceTypes` enum must have a binding declared) and as a per-request validation gate run **before** `ConfigAuthorizationService` dispatch. Required from Phase 1 because the read endpoints ship in Phase 1 — without the allowlist, `GET /v1/keys/public/foo` falls through to the `public/`-read `isAuthenticated` branch and any authenticated user could probe for misplaced infrastructure entities. The allowlist is a static map; no runtime cost.
- Implement read-only `GET /v1/admin/export` (snapshot of the current in-memory `Config` for inspection / bootstrap-export workflows). The bulk write surface — `POST /v1/admin/apply` / `validate` and the equivalent `dial-cli apply` / `validate` commands — ships in **Phase 4**, see [`03-api-reference.md`](03-api-reference.md) §7.
- These read directly from the in-memory `volatile Config` ref — zero storage changes.
- Protected by `access.admin.rules` via `ConfigAuthorizationService`.

**CLI:**

- Build `dial-cli` with environment profiles and templates.
- Implement `get`, `list`, `export`, `diff` commands (read-only).
- Package and distribute (GitHub Releases, Homebrew, Docker, JBang).

**Value delivered.** Single source of truth for runtime state (P5). DevOps can inspect any environment from the CLI. Cross-environment diff. No changes to DIAL Core's storage or config loading.

**Risk.** Minimal — read-only endpoints, no behavior change.

### Phase 2: Write API for Models + CLI Write Commands

**Prerequisites (standalone PRs before Phase 2):**

- `ApiKeyStore.addProjectKeys()` made permanently dual-format — accepts both the legacy map-key-as-secret format (used by all existing config files, never broken) and the new name-as-map-key + secret-in-`Key.key` format (used by API-managed keys only). No migration of existing `aidial.config.json` files is required. See OQ-12 in [`08-open-questions-and-references.md`](08-open-questions-and-references.md). **Behavioural change required, not a neutral extension — without it, API-managed keys silently 401.** Today's `ApiKeyStore.java` line 170 runs `value.setKey(apiKey)` unconditionally inside the loop, where `apiKey = entry.getKey()` is the human-readable map key (e.g. `"project_keys/platform/proxyKey1"`). For an API-managed key whose `Key.key` was just decrypted by `SecretFieldProcessor` and contains the actual secret, this overwrite silently replaces the decrypted secret with the canonical name, causing 401 on every subsequent auth attempt. **Required guard:** `if (value.getKey() == null || value.getKey().isBlank()) { value.setKey(apiKey); }` — only set the map key into `Key.key` when the field is empty (legacy file-sourced format); otherwise leave the API-supplied secret in `Key.key` and treat the map key as the human-readable name. Add unit coverage for both formats — file-sourced (map key = secret, `Key.key` null pre-call) and API-managed (map key = name, `Key.key` already set; assertion: pass through `addProjectKeys` unmodified). Note: `@JsonProperty(access = WRITE_ONLY)` (added to `Key.key` by this proposal) does not block deserialization — Jackson's `WRITE_ONLY` means "deserialize-only" (the field is read from request bodies but suppressed in responses), so the API-managed format works as expected.
- **Compile-time blocker bundle for Phase 2 — `platform/` bucket plumbing.** Today's `ResourceDescriptor` only has `PUBLIC_BUCKET`/`PUBLIC_LOCATION` constants; `ResourceDescriptorFactory.fromUrl()` checks `bucket.equals(PUBLIC_BUCKET)` and otherwise tries `encryptionService.decrypt(bucket)` (which throws on `"platform"`); and `ResourceTypes.of(String group)` throws for the new groups (`"models"`, `"interceptors"`, `"roles"`, `"project_keys"`, `"routes"`, `"app_type_schemas"`, `"settings"` — none in today's switch). All Phase 2 controller code that resolves `platform/`-prefixed URLs or constructs descriptors for the new types depends on this bundle landing first. The three changes are inseparable and ship as one PR:
  1. Add `PLATFORM_BUCKET = "platform"` and `PLATFORM_LOCATION = "platform/"` constants on `ResourceDescriptor`.
  2. Confirm `ResourceDescriptor.isPublic()` returns `false` for the `platform` bucket — required so `ConfigAuthorizationService` dispatch is correctly triggered for `platform/` reads/writes (the existing `isPublic()` already returns `false` for any bucket != `PUBLIC_BUCKET`, so this is a verification-by-test, not a code change).
  3. Add an `else if (PLATFORM_BUCKET.equals(bucket))` branch in `ResourceDescriptorFactory.fromUrl()` (the path called by `fromAnyUrl()`) that uses `PLATFORM_LOCATION` directly, before the encryption fallback. Same shape as the existing `PUBLIC_BUCKET` check.
  4. Extend `ResourceTypes.of()` switch with the new groups for the new enum entries (`MODEL`, `APP_TYPE_SCHEMA`, `INTERCEPTOR`, `ROLE`, `PROJECT_KEY`, `ROUTE`, `GLOBAL_SETTINGS`), keyed by their **blob group names** (`"models"`, `"app_type_schemas"`, `"interceptors"`, `"roles"`, `"project_keys"`, `"routes"`, `"settings"`).

  Phase 2 will not compile without all four changes in place.
- **Runtime-critical prerequisite — `ResourceTypes.of()` URL-segment alias acceptance.** *(Structurally outside the compile-time blocker bundle above — listed as a separate top-level prerequisite because it does not block compilation.)* In addition to the blob-group-name arms added in item 4 of the bundle above, `ResourceTypes.of()` must also accept the URL-segment aliases `"schemas"` → `APP_TYPE_SCHEMA` and `"keys"` → `PROJECT_KEY` so URL-segment-driven lookups resolve (see [`02-architecture.md`](02-architecture.md) §5.3). This **fails at runtime with `IllegalArgumentException` on the first request to `/v1/schemas/...` or `/v1/keys/...`, not at compile time** — Phase 2 controller code compiles cleanly without it but the very first URL-segment-driven dispatch throws. **This is a runtime failure, not a compile failure — required before Phase 2 ships to production despite not blocking compilation.** Must be covered by integration tests before Phase 2 ships.
- **`ResourceDescriptor.isPrivate()` and new `isPlatform()` — compile-time blocker.** Add `isPlatform()` returning `bucketLocation.equals(PLATFORM_LOCATION)` and change `isPrivate()` to `!isPublic() && !isPlatform()`. Audit all `isPrivate()` call sites in `server/` before Phase 2 ships. Without this, `platform/`-bucket requests fall through to the user-bucket owner-check path, silently bypassing `ConfigAuthorizationService`. See [`02-architecture.md`](02-architecture.md) §5.3.
- **`ResourceDescriptor.getUrl()` URL-segment vs blob-group distinction — compile-time blocker.** Today both `ResourceDescriptor.getUrl()` and `ResourceDescriptor.getAbsoluteFilePath()` build the type segment from `type.group()`. With the URL-segment aliases for `APP_TYPE_SCHEMA` (`schemas` URL ↔ `app_type_schemas` blob group) and `PROJECT_KEY` (`keys` URL ↔ `project_keys` blob group) introduced by Phase 2, `getUrl()` would diverge from the request URL — a request to `/v1/schemas/public/foo` would round-trip back as `schemas/public/foo` for the request path but `getUrl()` would emit `app_type_schemas/public/foo`. Phase 2 must distinguish URL segment from blob group on `ResourceType` (per [`02-architecture.md`](02-architecture.md) §5.3): pick option (a) — add a `urlSegment()` method on `ResourceType` that defaults to `group()` and returns `"schemas"` / `"keys"` for the two aliasing types, route `getUrl()` through `urlSegment()`, and keep `getAbsoluteFilePath()` on `group()` — or option (b) — carry the original URL segment on `ResourceDescriptor` itself, set during `ResourceDescriptorFactory.fromUrl()` parsing. Option (a) is the smaller, recommended change. Required round-trip test: `ResourceDescriptorFactory.fromUrl("/v1/schemas/public/foo").getUrl() == "schemas/public/foo"`. Required pair test: the same descriptor's `getAbsoluteFilePath()` returns `public/app_type_schemas/foo`. Without this, every API listing / GET response that echoes a canonical ID for an `APP_TYPE_SCHEMA` or `PROJECT_KEY` entity emits the blob group name instead of the URL segment the caller used.
- **`ApiKeyStore.keys` migrates from `volatile HashMap` to `volatile ConcurrentHashMap`, keeping the reference-swap rebuild idiom — compile-time blocker for the keys-controller fast-path.** Today's `ApiKeyStore.keys` is `volatile Map<String, ApiKeyData> keys = new HashMap<>()` and the only mutator is `addProjectKeys(...)` (full-replacement via reference swap). The Phase 2 keys-controller fast-path (per [`02-architecture.md`](02-architecture.md) §4) calls `ApiKeyStore.addOrUpdateKey(name, key)` directly after `ResourceService.put` succeeds — that's a single-key partial mutation, which is **not** thread-safe on a `volatile HashMap` (concurrent readers traversing buckets while a writer mutates entries can observe corrupted state). **Locked choice — keep the volatile-reference swap idiom for `addProjectKeys`; do not use `clear()+putAll()`.** `clear()+putAll()` on a `ConcurrentHashMap` is non-atomic at the map-instance level — a fast-path `removeKey("k")` that lands between `clear()` and `putAll()` is silently undone if the rebuild's input map still contains `k`, opening a brief re-authentication window after `DELETE /v1/keys/...` until the next rebuild. Migrate as a paired change: (a) field becomes `private volatile ConcurrentHashMap<String, ApiKeyData> keys = new ConcurrentHashMap<>()` — `volatile` retained on the reference because rebuilds atomically swap the entire map instance, while `ConcurrentHashMap` provides per-entry happens-before for the fast-path mutators; (b) introduce `addOrUpdateKey(String name, ApiKeyData data)` and `removeKey(String name)` used by the fast-path, both operating on the current `keys` reference; (c) rewrite `addProjectKeys(...)` to **build a fresh `ConcurrentHashMap` from the merged config and atomically swap the reference** (`this.keys = freshMap`). Concurrency note: a fast-path `removeKey` that lands on the pre-swap map is naturally superseded by the post-swap reference (the keys controller's blob `DELETE` happens before the controller calls `removeKey`, so the rebuild's view already excludes the key); a fast-path `addOrUpdateKey` racing with a rebuild swap may be lost on the swapped-in instance — accepted because `rebuildNow()` already covers writer-pod immediacy on the same code path. Externally visible behavior of `addProjectKeys` (full-replacement) is preserved. The fast-path cannot ship without this data-structure migration.
- **~~`ResourceService.put(descriptor, body, EtagHeader etag, boolean skipLock)` public overload~~ — already shipped (verified 2026-05-03).** The Phase 2 preserve-on-omit write path on entities with `@EncryptedField` fields requires the controller to acquire `LockService.lock(descriptor)` once, perform the pre-read inside that scope, merge the ciphertext into the request body, then write under the same lock without re-acquiring it (per [`04-security-and-audit.md`](04-security-and-audit.md) §2.5 atomicity note). The capability is **already in the codebase**: `ResourceService.putResource(ResourceDescriptor descriptor, String body, EtagHeader etag, String author, boolean lock)` is public (`storage/.../ResourceService.java:552`); calling with `lock=false` skips the inner `LockService.lock()` on the precondition that the caller already holds the lock, and performs the same storage work (Redis HASH update, blob fsync queue, `ResourceEvent` publish). Already used externally — `server/.../PublicationService.java:337` calls `resourceService.putResource(publicationsFile, ..., null, false)`. Phase 2 entity-write controllers with `@EncryptedField` fields (currently `Model.upstreams[].key`, `Model.upstreams[].extraData`, `Key.key`) reuse this existing overload; **no new method needed**. Originally proposed as slice 2S.4-pre, dropped on 2026-05-03 (see IMPLEMENTATION.md §5).
- **`ApiKeyStore` update ownership moves to `MergedConfigStore`'s post-processor — compile-time blocker.** `FileConfigStore.load()` today ends with a direct `apiKeyStore.addProjectKeys(config.getKeys())` call (line 105 in current sources). `ApiKeyStore.addProjectKeys` does a full volatile-map replacement (`keys = apiKeyDataMap`), so leaving the `FileConfigStore` call unconditionally in place after Phase 2 would wipe API-managed keys on every 60s file poll, then the debounced `MergedConfigStore` rebuild (~500ms+ later) would restore them — opening a window during which API-managed keys 401. **Phase 2 makes the `FileConfigStore` → `ApiKeyStore` direct call conditional on `apiKeyStore != null`** so standalone `FileConfigStore` callers (integration tests, future tooling that drives `FileConfigStore` without `MergedConfigStore`) keep working unchanged, and wires `MergedConfigStore` to construct `FileConfigStore` with `apiKeyStore = null` so the direct call is skipped on the production path. The `apiKeyStore.addProjectKeys(mergedConfig.getKeys())` invocation is moved into `ConfigPostProcessor` (run from `MergedConfigStore`'s rebuild path), making the rebuild the authoritative owner of `ApiKeyStore` updates whenever `MergedConfigStore` is in the picture. This is required to ship together with the rest of the compile-time blocker bundle so the merged `Config.keys` set is what reaches `ApiKeyStore`. See [`02-architecture.md`](02-architecture.md) §4.
- **`FileConfigStore` constructor accepts an `initialOnReloadCallbacks` parameter (stored in the `onReloadCallbacks` field) — test-critical, no compile failure (locked choice).** Per [`02-architecture.md`](02-architecture.md) §4 (Registration race avoidance), Phase 2 locks **option (a)**: extend `FileConfigStore`'s constructor to accept an optional `List<Consumer<Config>> initialOnReloadCallbacks` parameter (stored in the `onReloadCallbacks` field) and register the supplied callbacks **before** scheduling `vertx.setPeriodic`. `MergedConfigStore` provides its `requestRebuild()` consumer at `FileConfigStore` construction time so the callback list is non-empty before the periodic timer is armed — closing the race window regardless of `config.reload` period. The race window itself is integration-test-specific: production deploys run with the default 60s `config.reload` period (much greater than server startup time), so the periodic timer can never fire before `MergedConfigStore.init()` has registered. Integration tests that drop `config.reload` to single-digit milliseconds are the scenario that exercises the race. This item is therefore a **behavioural / test-correctness fix, not a compile-time blocker** — Phase 2 production code compiles and runs correctly without it; only ms-period integration tests would race. Option (b) — split construction with a later `start()` call — is rejected because it touches more call sites and breaks the existing single-step construction invariant. **Final combined signature.** This change and the `apiKeyStore`-nullable change above land atomically in the same PR; the resulting `FileConfigStore` constructor signature is `FileConfigStore(Vertx vertx, JsonObject settings, @Nullable ApiKeyStore apiKeyStore, List<Consumer<Config>> initialOnReloadCallbacks)`. Authoritative form lives in [`02-architecture.md`](02-architecture.md) §4 (Registration race avoidance).
- `ConfigPostProcessor`'s slash-name rejection (introduced in [`02-architecture.md`](02-architecture.md) §9 to prevent file-vs-API key collisions) is a **breaking behavioural change** from `FileConfigStore`'s today-permissive load — operators must audit existing `aidial.config.json` files for slash-containing entity map keys (e.g. `"azure/gpt-4"`) before rolling out Phase 2. Slash-keyed entries log a warning at load time and are dropped; the rest of the file loads normally, but those specific entities become unavailable. Audit guidance: `jq '.. | objects | keys[]?' aidial.config.json | grep '/'` over each customer config to surface affected entries.
- **Keys-controller `DELETE` ordering invariant — implementation checklist item.** The `removeKey` fast-path is silent-undo-safe only if the rebuild's blob scan begins after the controller's `ResourceService.delete` returns. Phase 2 must implement the keys-controller `DELETE` path in this order: (1) `ResourceService.delete(descriptor)` and wait for it to return; (2) `apiKeyStore.removeKey(name)`; (3) `rebuildNow()`. Required test: a delete-then-rebuild integration test that asserts the post-rebuild merged map does not contain the deleted key. See [`02-architecture.md`](02-architecture.md) §4 (Concurrency note).
- **`MergedConfigStore` pre-init `requestRebuild()` no-op invariant — implementation checklist item.** Any rebuild trigger source (file-poll callback, pub/sub listener, safety-net poll) that fires between `MergedConfigStore` construction and `MergedConfigStore.init()` returning must not drive a rebuild — collaborators (decryption services, post-processor wiring, the invalid-entity sibling store) are not yet finalized. Phase 2 must guard `requestRebuild()` with a `volatile boolean initialized = false` flag set at the end of `init()`; the method short-circuits while `initialized == false`. Required test: an integration test that schedules `requestRebuild()` invocations on a not-yet-initialized `MergedConfigStore` and asserts no rebuild work runs until `init()` completes. See [`02-architecture.md`](02-architecture.md) §4 (Startup initial rebuild).
- **`ResourceDescriptor.getDecodedUrl()` URL-segment vs blob-group round-trip — required test alongside `getUrl()` fix.** The `urlSegment()` migration (per the `ResourceDescriptor.getUrl()` blocker above and [`02-architecture.md`](02-architecture.md) §5.3) must extend to `getDecodedUrl()` as well — both methods derive the type segment from the same source today and both must use `urlSegment()` after Phase 2 so URL-segment-driven round-trips are consistent regardless of which accessor the caller uses. Required round-trip test for `getDecodedUrl()`: `ResourceDescriptorFactory.fromUrl("/v1/schemas/public/foo").getDecodedUrl()` must echo the URL segment (`schemas`) and not the blob group name (`app_type_schemas`); same for `/v1/keys/platform/proxyKey1`.

**DIAL Core changes:**

- Add new resource type `MODEL` in `ResourceTypes` (infinite TTL, `public/` bucket). Extend `ResourceTypes.of()` switch.
- Introduce `PLATFORM_BUCKET` and `PLATFORM_LOCATION` constants in `ResourceDescriptor`. Update `fromAnyUrl()` to handle the platform bucket.
- Implement `MergedConfigStore` — union of `FileConfigStore` + `ResourceService` (no override — see [`02-architecture.md`](02-architecture.md) §MergedConfigStore).
- Add a new `List<Consumer<Config>> onReloadCallbacks` field + registration method on `FileConfigStore`, and invoke the list at the end of `load()` only on a non-null `Config` return, after the `this.config = config` volatile write. `MergedConfigStore` registers its `requestRebuild()` trigger via this hook. Callback invocation must not block the `FileConfigStore` reload thread (`requestRebuild()` is non-blocking per [`02-architecture.md`](02-architecture.md) §11.1). **Implementation note:** invoke callbacks immediately after `this.config = config` is set (line 141 in current source) and before the successful-reload return. This co-location naturally satisfies the "fire only on non-null `Config` return" rule because the catch path (which returns `null`) does not reach that line.
- Extract `ConfigPostProcessor` from `FileConfigStore.load()`. Two-pass design: structural validation (always fatal-to-the-entity, never bypassable) followed by semantic validation (skip-or-abort per the new setting). See OQ-15 and [`02-architecture.md`](02-architecture.md) §4.1, §4.3.
- Implement the **invalid-entity sibling store** on `MergedConfigStore` (`Map<entityType, Map<id, InvalidEntityRecord>>`) and wire it into the listing/get response shape (`status` + `validationWarnings` — see [`03-api-reference.md`](03-api-reference.md) §4).
- Implement the **`config.reload.onInvalidEntity: skip | abort`** static setting (default `abort` — matches today's `FileConfigStore` strict-reload behavior; opt-in `skip` enables per-entity skip-with-visibility). See [`02-architecture.md`](02-architecture.md) §4.1.
- Implement `GET /v1/admin/health/config` returning `{ status: "ok"|"degraded", skipped: [...] }`.
- Add Prometheus metrics: `dial_config_skipped_entities{type,reason}` (gauge), `dial_config_skip_events_total{type,reason}` (counter).
- Implement `POST /v1/models/public/{name}` (create-only — `409` if exists), `PUT /v1/models/public/{name}` (update-only — `404` if missing, optional `If-Match` for ETag concurrency), and `DELETE /v1/models/public/{name}` — all writing to `public/models/` in blob storage via MergedConfigStore. Strict create/update split (no upsert at the single-entity surface) — see [`03-api-reference.md`](03-api-reference.md) §1. Bucket-aware authz via `ConfigAuthorizationService` per [`04-security-and-audit.md`](04-security-and-audit.md) §1.2.
- Implement `POST /v1/admin/validate` for models.
- Writer pod updates `volatile Config` ref immediately; other replicas pick up on poll.

**CLI:**

- Implement `model add`, `model update`, `model delete` with templates.
- CLI provides field-level update via `--set` flags (internally: GET + local merge + PUT).
- Implement `--dry-run`, `--validate`.
- Implement `model promote --from <env> --to <env>`.

**Value delivered.** Full model management via CLI and API. Immediate effect on writer pod. 60s propagation to other replicas.

**Risk.** Medium — introduces `MergedConfigStore`, `ConfigPostProcessor`, and new resource types. Requires testing of union semantics and deployment uniqueness enforcement.

### Phase 1.5: Redis Pub/Sub for Cross-Replica Propagation

Can ship concurrently with or immediately after Phase 2.

**Prerequisites (standalone PR before Phase 1.5):**

- Switch the `ResourceTopic` codec to a `TypedJsonJacksonCodec` constructed from a shared `ObjectMapper` configured with `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false` (and emitting `JsonInclude.NON_NULL` on output). A class-level `@JsonIgnoreProperties(ignoreUnknown = true)` on `ResourceEvent` is **insufficient** because `ResourceTopic.java` constructs the codec via `new TypedJsonJacksonCodec(ResourceEvent.class)` — Redisson's default constructor builds its own internal `ObjectMapper` that does not introspect application-side annotations on the deserialization path, so pre-1.5 replicas can still throw `UnrecognizedPropertyException` on enriched events. This is a **permanent defensive measure**, not a Phase 1.5–only fix: every future field addition to `ResourceEvent` then no longer breaks rolling upgrades. Without it, every `ResourceTopic` subscriber on a pre-1.5 replica — not just the config-rebuild listener but every cache-invalidation consumer using the same codec — fails to deserialize incoming events from upgraded pods. **Must ship as a standalone PR before any Phase 1.5 traffic** so all replicas tolerate the new field by the time upgraded pods start emitting it. Add an integration-test requirement: a subscriber using the unmodified default codec must successfully receive an event carrying `senderPodId` without exception. See [`02-architecture.md`](02-architecture.md) §11.1.

  **Codec wiring — constructor signature change on `ResourceTopic` (storage module).** `ResourceTopic`'s only constructor today takes `(RedissonClient, String topicKey)` and builds the codec internally with `new TypedJsonJacksonCodec(ResourceEvent.class)` — there is no seam to inject a pre-built codec or a shared `ObjectMapper`. Add a new `ResourceTopic(RedissonClient, String topicKey, ObjectMapper)` constructor in the `storage` module. The original `ResourceTopic(RedissonClient, String)` constructor delegates to the new one with a default `ObjectMapper` configured with `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false` and `JsonInclude.NON_NULL` so existing call sites in `storage` keep working without code changes and still get the unknown-field tolerance. The `server` module wires the application's existing `ObjectMapper` into `ResourceTopic` at construction time (via DI / explicit pass-through) so the same configuration the rest of DIAL Core uses for entity serialization is reused for `ResourceEvent`. Required unit test in the `storage` module: construct `ResourceTopic` via the default `(RedissonClient, String)` constructor, publish/subscribe an event payload that carries an unknown field, and confirm the subscriber receives the event without `UnrecognizedPropertyException`. Mirrored in [`02-architecture.md`](02-architecture.md) §11.1.

  **Call-site update — `ResourceService` must wire the shared `ObjectMapper` into `ResourceTopic` (compile-time blocker).** The constructor addition above is necessary but not sufficient: today `ResourceService.java` instantiates the topic via `this.topic = new ResourceTopic(redis, "resource:" + ...)` — the no-mapper constructor. Find the `ResourceTopic` instantiation in `ResourceService.java` (search for `new ResourceTopic(redis,`) and update it to pass the shared `ObjectMapper`. Without updating this call site, the new safe-defaults constructor is unreachable on the cache-invalidation path and the codec swap has **no effect**. Phase 1.5 prerequisites therefore include a paired call-site change: update `ResourceService`'s constructor to accept the application's shared `ObjectMapper` (or to construct one with the safe defaults — `FAIL_ON_UNKNOWN_PROPERTIES = false` + `JsonInclude.NON_NULL`) and pass it into the new `ResourceTopic(RedissonClient, String, ObjectMapper)` constructor. Wiring detail: inject `ObjectMapper` into `ResourceService` via DI / explicit pass-through; an alternative is to extract `ResourceTopic` construction into a factory invoked by both the service and any future direct subscribers. Required unit test: verify `ResourceService.getTopic()` ignores unknown fields via the codec (publish/subscribe an event carrying an unknown field through the service-owned topic instance and confirm no `UnrecognizedPropertyException`). Without this paired change, the cache-invalidation path continues to use the default (no-FAIL_ON_UNKNOWN_PROPERTIES) codec.

**DIAL Core changes:**

- Add a NEW `ResourceTopic.subscribeAll(Consumer<ResourceEvent>)` method on the existing `ResourceTopic` — this method does **not** exist today. Implementation: a new `CopyOnWriteArrayList<Consumer<ResourceEvent>> globalSubscribers` field next to the existing `urlToSubscriptions` map, plus a second loop in `handle()` that iterates global subscribers after the URL-keyed dispatch (existing per-URL subscribers untouched). The current `ResourceTopic.subscribe(Collection<ResourceDescriptor>, …)` API requires explicit per-URL pre-registration and `handle()` silently drops events for URLs never pre-registered — `subscribeAll` is the minimal new surface that lets `MergedConfigStore` listen for every event without enumerating URLs. See [`02-architecture.md`](02-architecture.md) §11.1 for the full thread-safety contract.
- Register a `MergedConfigStore` `subscribeAll` listener at boot on the **same Redis pub/sub broadcast `ResourceService` already publishes on for cache-invalidation**. No new topic, no new event class, no new publish call in the write path — see [`02-architecture.md`](02-architecture.md) §11.1 for the full design and the "why no separate topic" KISS table.
- Extend the existing `ResourceEvent` Lombok `@Data` class (in the `storage` module) with one NEW nullable field, `senderPodId`, annotated `@JsonInclude(NON_NULL)`. Add `@JsonIgnoreProperties(ignoreUnknown = true)` on `ResourceEvent` as defense-in-depth for non-Redisson consumers (standard `ObjectMapper` deserializers). **The primary rolling-upgrade fix is the codec-level change above** — the annotation alone is insufficient for the Redisson codec path per [`02-architecture.md`](02-architecture.md) §11.1. The pod-identity UUID is generated once at pod startup in the `server` module (alongside `MergedConfigStore`) and supplied to `ResourceService` at construction time via a `Supplier<String>` / constructor arg, so `storage` itself stays unaware of pod identity — it just stamps whatever opaque string the supplier returns on every `ResourceEvent` it publishes. Existing consumers of `ResourceTopic` (cache-invalidation path) ignore the field; `@JsonInclude(NON_NULL)` keeps events emitted before the supplier is wired (boot edge case) round-trip-safe. Small field addition on an existing class, not a new event class.
- Filter received `ResourceEvent`s by `senderPodId` (skip self) and then by resource type (per [`02-architecture.md`](02-architecture.md) §6 — `MODEL`, `APP_TYPE_SCHEMA` in `public/`; `INTERCEPTOR`, `ROLE`, `PROJECT_KEY`, `ROUTE`, `GLOBAL_SETTINGS` in `platform/`). Apps, toolsets, files, prompts, conversations, and user-bucket events are filtered out.
- Forward filtered events to `MergedConfigStore.requestRebuild()` — the same in-pod coalescing entry point used by the file-poll timer and the local API-write path.
- Add 500ms trailing-edge debounce on `requestRebuild()` to coalesce bursts (e.g. `dial-cli apply` of an N-entity manifest set produces one rebuild, not N).
- **Polling interval kept at 60s** as the correctness safety-net — pub/sub does not relax it. (Earlier draft suggested 300s; reverted because polling is the correctness primitive and lowering it widens worst-case lag when pub/sub silently drops.)
- **No new Prometheus metrics or dashboards** in scope of this proposal for the pub/sub path — operators rely on existing DIAL Core / Redis / `ResourceService` instrumentation. Polling SLA bounds worst-case staleness regardless of pub/sub delivery, so silent-drop scenarios self-recover within the 60s window without operator intervention.

**Value delivered.** Near-instant cross-replica propagation. Eliminates the 60s consistency window.

**Risk.** Near-zero — pub/sub failure degrades to polling. Existing Redisson client and the existing `ResourceTopic` subscription path are reused, so no new infrastructure surface is introduced.

### Phase 3: Write API for All Entity Types

> Audit was previously bundled into Phase 3. It has been **deferred to Phase 7** (see below). Phase 3 now ships entity-CRUD only.

**Prerequisites (standalone PRs before Phase 3):**

- **`ResourceAuthSettingsEncryptionService.processFields()` extension for `codeVerifier` with lazy plaintext migration.** Find `ResourceAuthSettingsEncryptionService.processFields()` (the method that today only processes `clientSecret`) and extend it to also process `codeVerifier`. Existing toolset blobs in production carry `codeVerifier` as **plaintext** (the field is not encrypted today — see [`04-security-and-audit.md`](04-security-and-audit.md) §2.2 / §2.7), so naive `decryptValue()` on the read path throws `IllegalArgumentException` from `Base64.getDecoder().decode()` on legacy values. Implementation checklist item: in the read path for `codeVerifier`, attempt Base64 decode + AES decrypt, and if the decode fails (catch `IllegalArgumentException` from the decoder, or guard via an `isProbablyBase64(value)` precheck), treat the value as legacy plaintext, return it as-is, and rely on the next toolset write to re-encrypt through the encrypted path. This mirrors the legacy-plaintext fallthrough used by `SecretFieldProcessor`. No separate one-shot migration job is required — migration is lazy, on first re-write per blob.

**DIAL Core changes — Entity CRUD:**

- Extend write API to all remaining entity types:
  - `public/` bucket: admin applications, admin toolsets, applicationTypeSchemas, plus admin-managed shared **files**, **prompts**, **conversations** (per [OQ-21](08-open-questions-and-references.md) — same thin authz layer over the existing Resource API; not routed through `MergedConfigStore`).
  - `platform/` bucket: roles, keys, interceptors, routes.
- Add corresponding resource types (`INTERCEPTOR`, `ROLE`, `PROJECT_KEY`, `ROUTE`, `APP_TYPE_SCHEMA`). The `FILE`, `PROMPT`, and `CONVERSATION` resource types already exist for user-bucket usage and are reused as-is — admin writes target the `public/` bucket via `ConfigAuthorizationService`.
- Admin-managed applications and toolsets in `public/` unify with user-published ones — `DeploymentService` special-casing for config-file apps can be removed.
- Implement **`BlobEntityValidator`** — a pure helper used by the Configuration API listing/get controllers for applications and toolsets. Validates each blob entity against current `Config` (interceptor refs against `Config.interceptors`, schema refs against `Config.applicationTypeSchemas`, dependencies via `deploymentService.findDeployment`) and returns a `List<ValidationWarning>` folded into the response as `status` + `validationWarnings`. Not called from the chat-completion hot path — that path is unchanged from today. See [`02-architecture.md`](02-architecture.md) §4.3 for the lazy-validation rationale and §4.2 for how this surfaces the pre-existing file→blob danglers.

**CLI:**

- Extend write commands to all entity types.

**Value delivered.** Full imperative management of all config entities. Dual-source problem for apps/toolsets eliminated.

### Phase 4: Declarative Mode + Environment Promotion

**CLI:**

- Implement `dial-cli apply -f <path>` with manifest files.
- Apply workflow: parse manifests → sort by dependency → validate all (dry-run gate) → apply sequentially (continue on failure) → report per-entity results.
- Implement variable substitution (`${vars.*}`, `${params.*}`, `${SECRET:*}`, `${ENV_VAR}`).
- Implement `dial-cli export --env <env>` → YAML manifests.

**DIAL Core changes:**

- Implement `POST /v1/admin/apply` bulk endpoint — server sorts by dependency, applies sequentially, continues on failure, returns per-entity results with summary counts.
- Implement `POST /v1/admin/validate` for bulk manifests — same validation as individual writes, but all-at-once with cross-entity reference checks.
- Dependency apply order (fixed): `globalSettings → schemas → interceptors → roles → keys → routes → models → toolsets → applications`.

**Apply failure semantics (decided):**

- Server always continues on failure and reports per-entity results.
- CLI adds a validate-first gate before calling apply.
- No rollback — config entities are largely independent; partial application is acceptable.
- Exit code `0` if all succeeded, `1` if any failed in the batch. Full CLI exit-code contract — `0` (success / nothing to apply), `1` (partial-batch failure), `2` (validation), `3` (auth), `4` (404), `5` (409), `6` (412) — is in [`06-cli-user-guide.md`](06-cli-user-guide.md) §2.8.

**Value delivered.** GitOps-ready workflow. Full environment export/import. Environment promotion (P6).

### Phase 5: DIAL Admin Backend Migration

Phase 5 is a **major Admin Backend refactor**, not a thin adapter swap. The direction is explicit: **config management moves entirely to the Configuration API; Admin Backend's configuration database is removed.** Admin Backend becomes a thin shell — authentication, authorization policy, a web UI, and a handful of auxiliary concerns that don't belong in DIAL Core.

**Admin Backend changes — configuration path:**

- **Remove the configuration database.** The H2/PostgreSQL/MSSQL schemas that today hold models, applications, toolsets, interceptors, roles, keys, and routes are dropped. DIAL Core (via the Configuration API) becomes the single source of truth.
- **Replace CRUD controllers with API pass-through.** Every `/api/v1/*` endpoint in Admin Backend that today writes to its DB is rewritten to call the corresponding Configuration API endpoint on DIAL Core — per-entity CRUD goes to `/v1/{type}/{bucket}/{name}`, bulk/declarative writes go to `/v1/admin/apply`, and exports come from `GET /v1/admin/export`. Admin Backend stays in the call path for: (a) session / CSRF handling tied to the Admin UI, (b) OIDC / Basic Auth login that the UI uses, (c) any UI-specific aggregation or denormalization that doesn't fit the generic API surface.
- **Retire the scheduled export pipeline.** The "write to DB → schedule export → write file / ConfigMap / KeyVault → wait for DIAL Core to poll" pipeline is deleted end-to-end. The 60–180s propagation delay (P2) disappears because there is no file hop.
- **Migration of existing data.** For environments already running Admin Backend: on first boot against the new Configuration API, a one-shot import job reads the Admin Backend DB and issues equivalent per-entity writes (`POST /v1/{type}/{bucket}/{name}`) — or one bulk `POST /v1/admin/apply` — to seed DIAL Core, then the DB schema is retired. This is a one-way migration; once the import completes the DB is no longer consulted for config.

**Admin Backend changes — what stays:**

- **Admin UI / Admin Frontend.** The Next.js frontend continues to exist; it now talks to Admin Backend (for auth / session) and DIAL Core (for config reads/writes) directly, or proxies writes through Admin Backend unchanged from the frontend's perspective.
- **Reporting / analytics / deployment management.** Any Admin Backend features that are not config CRUD stay (OQ-11 frames Admin Backend as a modular UI shell for these).

**Admin Backend changes — what is deprecated and removed:**

- **Multi-destination export (Vault / AWS SM / GCP SM / Azure Key Vault / K8s ConfigMap) is deprecated and removed in Phase 5.** The `FileConfigStore` and config-file approach are unaffected and continue to work (Phase 6 optional deprecation). The exporters' role was specifically to feed `FileConfigStore` consumers (write config to a file / secret store → mount into the DIAL Core pod → `FileConfigStore` polls it); customers using these exporters to feed `FileConfigStore` should migrate to scheduling `GET /v1/admin/export` writes before Phase 5 ships — that is a customer-owned script against the new API, not an Admin Backend feature. Customers with external backup / DR / cross-tool workflows that were incidentally riding on these exporters migrate the same way.

**Admin Backend changes — audit:**

- *(deferred — Phase 7).* Admin Backend's own audit/history tables (if any) survive Phase 5 unchanged because DIAL Core does not yet provide an audit trail. Their retirement happens **with Phase 7**, when DIAL Core's audit API lands and the Admin UI's history view can become a thin read over `GET /v1/admin/audit`.

**Phase ordering and risk:**

- Depends on: Phase 1–4 all landed (the API must be complete enough to cover everything Admin Backend's DB covers today). Phase 5 cannot start earlier.
- Risk: high — touches customer-visible Admin UI, requires coordinated release between `ai-dial-core`, `ai-dial-admin-backend`, and `ai-dial-admin-frontend`, and migrates operational data.
- Mitigation: the Configuration API is already the primary write path starting from Phase 2. Phase 5 is the cleanup step, not a cutover — customers can adopt the new API incrementally (via `dial-cli` or direct calls) before Phase 5 ships, and Admin Backend's DB can coexist with direct API usage during the transition.

**Value delivered.** Single source of truth is actually singular — no more DB-vs-API divergence. Admin UI responsiveness: changes are instant (P2). Operational surface shrinks: one fewer database to run, backup, and version-migrate per DIAL install. P1 fully realized — Admin Backend is a UI skin on the Configuration API.

### Phase 6: Config File Deprecation (optional / long-term)

- Config file becomes optional, used only for seed / initial setup.
- All ongoing config management flows through the API.
- Existing file-based deployments continue to work indefinitely (backward compat).

### Phase 7: Audit & Compliance (deferred — WIP)

> **STATUS: WIP.** Scope, exact phase placement, and timing are not yet committed. This entry exists to make the deferral visible in the rollout narrative and to anchor cross-document `Phase 7` references.

**Why deferred.** R13 + R14 (audit log + query API) bundle a non-trivial subsystem — Redis Streams hot tier, blob archival, intent-log lifecycle, snapshots, reconciliation, query API, CLI surface, MCP tool — onto an already-large entity-management workstream. Reviewer feedback during the review round on 2026-04-30 prioritised landing the entity-management API + CLI + MCP first; audit ships once that surface is stable. Decoupling also lets the audit subsystem be re-scoped (e.g. `PublicationService` audit, advanced filters, retention tiers) without blocking the core API.

**Scope (working draft, not committed):**

- Audit-event schema + Vault-style intent log (PENDING → APPLIED/FAILED) on the Configuration API critical path.
- Storage: Redis Streams (hot) + blob archival (cold) per [`04-security-and-audit.md`](04-security-and-audit.md) §3.4.
- `GET /v1/admin/audit` query API + filters per [`04-security-and-audit.md`](04-security-and-audit.md) §3.5.
- `dial-cli audit` command group (history, log, snapshot, rollback, reconcile) per [`06-cli-user-guide.md`](06-cli-user-guide.md).
- `dial_admin_query_audit` MCP tool per [`09-admin-mcp-spec.md`](09-admin-mcp-spec.md).
- Admin Backend audit-table retirement + Admin UI history-view rewrite (moved here from Phase 5).
- Snapshot / point-in-time reconstruction + boundary-snapshot preservation.
- `PublicationService` audit — to be triaged at Phase-7 planning, may slip to Phase 7.5+.

**Prerequisites:** Phases 1–4 complete (entity-management API + CLI + declarative apply), Admin MCP shipped. Phase 5 (Admin Backend migration) can land in parallel with Phase 7 — they touch different surfaces.

**Risk:** medium — audit is compliance-relevant, the design is mostly settled, but the critical-path PENDING-write behavior (admin writes can `503` on audit-store outage — see [`04-security-and-audit.md`](04-security-and-audit.md) §3.7) is a SLO change that needs operator socialisation before rollout.

**What ships before Phase 7 in lieu of audit:** structured DIAL Core application logs covering all Configuration API writes (per-entity CRUD on `public/`+`platform/` plus all `/v1/admin/*` ops — existing Vert.x + Logback path) — best-effort, not a compliance-grade audit trail. Operators with strict audit needs continue to use existing external mechanisms (Git for config files, Admin Backend's own history tables where present, cloud-provider access logs) until Phase 7 lands.

---

## 3. Operational Changes

### For DevOps Teams

| Before | After |
|--------|-------|
| Edit JSON files → Helm upgrade → wait 60s+ | `dial-cli model add --env uat ...` → immediate |
| Manual copy-paste between environments | `dial-cli promote --from dev --to uat --name models/public/...` |
| No visibility into runtime state | `dial-cli get models --env prod -o yaml` |
| No pre-flight validation | `dial-cli apply -f config/ --validate --dry-run` |
| No config diff | `dial-cli diff --source dev --target uat` |
| CI/CD requires Helm values manipulation | `dial-cli apply -f config/ --env $TARGET` |

Full workflow examples in [`06-cli-user-guide.md`](06-cli-user-guide.md).

### For DIAL Admin Operators

- **Phase 1–4:** No change. Admin continues to work as before (file export).
- **Phase 5:** Admin UI becomes more responsive. Changes are instant. No "waiting for sync" UX.

### For DIAL Core Deployment

- **Phase 1:** No change. Read-only API endpoints added.
- **Phase 2:** New `MODEL` resource type in `public/` bucket. `MergedConfigStore` activated. Config file still works as seed.
- **Phase 3:** Admin apps/toolsets unified with user-published ones in `public/`. Infrastructure entities in `platform/` bucket.
- **Phase 5:** Can optionally simplify deployment by removing Admin Backend's config export job.

---

## Next

- Open questions that remain to close: [`08-open-questions-and-references.md`](08-open-questions-and-references.md)
- Source references and prior art: [`08-open-questions-and-references.md`](08-open-questions-and-references.md) §References
