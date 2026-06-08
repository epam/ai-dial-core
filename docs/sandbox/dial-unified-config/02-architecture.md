# 02 — Solution Architecture

> **Audience:** DIAL Core dev team, architects.
> **Reading time:** ~30 minutes.
> **Prerequisites:** [`01-problem-and-context.md`](01-problem-and-context.md).

This document is the technical design for the unified Configuration API. It covers the high-level architecture, the new `MergedConfigStore` component, bucket layout, which entities flow through which code path, name resolution rules, migration implications when a file-sourced entity becomes API-managed, and why we're reusing existing storage rather than introducing anything new.

Security-focused content (authorization, secrets-at-rest, audit) lives in [`04-security-and-audit.md`](04-security-and-audit.md). The API surface lives in [`03-api-reference.md`](03-api-reference.md).

---

## 1. High-Level Architecture

```
┌────────────────┐     ┌──────────────────────────────────────────────┐
│   dial-cli     │────▶│              DIAL Core (Vert.x)              │
│                │     │                                              │
├────────────────┤     │  ┌──────────────────────────────────────┐    │
│  DIAL Admin    │────▶│  │  Configuration API (CRUD + ops)      │    │
│  Backend       │     │  │  /v1/{type}/* + /v1/admin/* (new)    │    │
└────────────────┘     │  └──────────┬───────────────────────────┘    │
                       │             │                                │
┌────────────────┐     │  ┌──────────▼───────────────────────────┐    │
│  CI/CD         │────▶│  │  MergedConfigStore (new)             │    │
│  Pipeline      │     │  │  FileConfigStore ← seed/fallback     │    │
└────────────────┘     │  │  ResourceService ← admin entities     │    │
                       │  │  → volatile Config ref (existing)     │    │
                       │  └──────────┬───────────┬───────────────┘    │
                       │             │           │                    │
                       │  ┌──────────▼──┐  ┌────▼──────────────┐     │
                       │  │   Redis     │  │  Blob Storage     │     │
                       │  │   (cache +  │  │  (durable source  │     │
                       │  │   locks +   │  │   of truth)       │     │
                       │  │   pub/sub)  │  │                   │     │
                       │  └─────────────┘  └───────────────────┘     │
                       └──────────────────────────────────────────────┘
```

## 2. Core Principle: Extend, Don't Rebuild

The proposal deliberately reuses existing DIAL Core infrastructure:

| Component | Existing | New/Changed |
|-----------|----------|-------------|
| Blob storage (jclouds) | ✅ Production | Reuse as-is |
| Redis (Redisson) | ✅ Production | Reuse + add pub/sub topic (Phase 1.5) |
| ResourceService (two-tier cache) | ✅ Production | Add new resource types for config entities |
| Distributed locking (LockService) | ✅ Production | Reuse as-is via `LockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS, …)` — both the `public/` + `platform/` bucket locks serialize every admin write **and** the full-rebuild blob scan (reload / rebuildNow / debounced timer) cluster-wide (see §4.4) |
| ETag concurrency | ✅ Production | Reuse as-is |
| ConfigStore interface | ✅ Exists | New `MergedConfigStore` implementation |
| FileConfigStore | ✅ Production | Preserved for seed/fallback |
| DeploymentService merge | ✅ Production | Extended to read from MergedConfigStore |
| Admin access rules | ✅ Production | Reuse for API authorization |
| HTTP endpoints | ✅ Production | New `/v1/{type}/{bucket}/*` CRUD routes — implemented as **sibling** `RouteTemplate.CONFIG_RESOURCE` (per-entity CRUD) + `RouteTemplate.CONFIG_RESOURCE_METADATA` (folder/item metadata listing) entries for the new admin-config types (`models`, `interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`), mirroring the existing `RESOURCE` / `RESOURCE_METADATA` / `FILES` / `FILES_METADATA` quartet. `RouteTemplate.RESOURCE` (`conversations`, `prompts`, `applications`, `toolsets`) and `RouteTemplate.FILES` (`/v1/files/...`) are left unchanged so the existing files / prompts / conversations dispatch paths keep their dedicated controllers. Plus `/v1/admin/*` for cross-entity ops (apply, validate, export, audit, health/config). |
| CLI tool | ❌ None | New `dial-cli` |
| Configuration API | ❌ None | New endpoints in DIAL Core |

## 3. Path Format Reference (DIAL Core Convention)

DIAL Core uses **two path formats** for the same resource. This document uses both — each context specifies which format is meant.

| Context | Format | Convention | Example |
|---------|--------|------------|---------|
| API identifiers, `ResourceDescriptor.getUrl()`, canonical IDs, `deployment.getName()`, client-facing | **Resource URL** | `{type}/{bucket}/{path}` | `models/public/gpt-4` |
| Blob storage layout, `getAbsoluteFilePath()`, `EntityLocationStrategy` output, storage diagrams | **Blob Path** | `{bucket}/{type}/{path}` | `public/models/gpt-4` |

When this doc describes **storage layout** (bucket diagrams, `EntityLocationStrategy`), it uses blob path format. When it describes **identifiers** (canonical IDs, API URLs, OQ-17), it uses resource URL format.

## 4. MergedConfigStore — Union of Both Sources

A new `ConfigStore` implementation that builds the runtime `Config` by combining file-based and API-managed entities as a **union** (not a merge with override):

```
MergedConfigStore.getConfig():
  1. Load from FileConfigStore → Config with simple-name keys ("gpt-4")
  2. Resolve entity locations via EntityLocationStrategy (pluggable)
  3. Load API-managed entities from ResourceService → canonical-ID keys ("models/public/gpt-4")
  4. Union both into the same Config maps (no key collision — different namespaces)
  5. Run ConfigPostProcessor (sort routes, validate per entity, ApiKeyStore callback)
  6. Volatile ref swap
```

**Per-entity validation, abort-by-default:** `ConfigPostProcessor` validates each entity individually. By default, if any entity is invalid (corrupt JSON in blob, bad toolset name pattern, broken route regex), the whole reload is aborted and the previous `Config` is preserved — matching today's `FileConfigStore` strict-reload behavior. The opt-in setting `config.reload.onInvalidEntity: skip` switches to per-entity skip-with-visibility: the invalid entity is **logged as a warning, omitted from in-memory `Config`, and recorded in the invalid-entity sibling store** so it remains visible to operators on the API and CLI surfaces. Skip mode is offered because the blob-backed surface is larger than today's file-only surface and operators running pod-scale-up during partial outages may prefer that one corrupted entity not block all updates. See §4.1 for the full failure-semantics design (strategies, default rationale, and the cross-reference / visibility surfaces that make skip mode safe), §4.2 for the pre-existing cross-reference inconsistency this surface also covers, and §4.3 for the invalid-entity visibility surface.

**Union semantics (no override, no shadowing):** Config-file entities keep their simple names (`"gpt-4"`). API-managed entities use canonical IDs (`"models/public/gpt-4"`). Both coexist in the same `Config.models` map as separate entries — they never collide because they use different key formats. There is no "API overrides file" precedence rule.

**Gradual migration from file to API:** The union model naturally supports gradual deprecation. When migrating an entity, both the file version (`"gpt-4"`) and the API version (`"models/public/gpt-4"`) can coexist for as long as needed. Downstream references (rate limits, interceptor chains, client URLs) migrate one at a time from the old name to the new. When all references have been updated, the config-file entry is removed. This avoids the risk of a big-bang coordinated cutover — each entity migrates independently at its own pace.

**Why union, not merge-with-override:** The original proposal used "API overrides file for same key" — this required expanding config-file names to canonical IDs on load, which broke rate limit lookups (`Role.limits` is keyed by simple names), orphaned rate limit counters, broke interceptor chain resolution, and created confusing fallback-on-delete behavior. Union is simpler, preserves all existing behavior, and avoids a class of silent breakage.

**Cross-source references:** Entities reference each other by whatever name they have. A config-file model references config-file interceptors by simple name. An API-managed model references interceptors by whatever name those interceptors have in the Config map (simple name if file-sourced, canonical ID if API-sourced). During gradual migration, references update incrementally — some may point to the old simple name while others point to the new canonical ID, and both resolve correctly because both entries exist in the Config map.

**Rate limit compatibility:** `Role.limits` continues to work unchanged. Config-file deployments use simple-name keys in the limits map. API-managed deployments use canonical-ID keys. Example:

```json
"roles": {
    "power-user": {
        "limits": {
            "gpt-4": { "minute": "200000" },
            "models/public/new-model": { "minute": "100000" }
        }
    }
}
```

**File → blob persistence (where the migration story lives).** File-defined entities do migrate into blob storage, but **gradually and per-entity**, not as a flag-day rewrite — each migration step turns one file entity into an API-managed blob entity through `PUT /v1/{type}/{bucket}/{name}` (with `If-None-Match: *` for create-only semantics), after which the file entry can be removed. The full mechanics are in §10 (File → API Entity Cutover) and §10.1 (Why coexistence, not big-bang migration). The optional terminal state where an environment runs without `aidial.config.json` at all is [Phase 6 in `07-migration-and-rollout.md`](07-migration-and-rollout.md), and even that is operator-driven, not forced.

**Rebuild serialization.** `MergedConfigStore` rebuilds are serialized — only one rebuild runs at a time. Concurrent triggers (poll timer, API write, pub/sub notification) are coalesced: if a rebuild is in progress, subsequent triggers mark "rebuild needed" and a fresh rebuild runs after the current one completes.

**`ApiKeyStore` update path under `MergedConfigStore` — single owner, no double-write.** Today `FileConfigStore.load()` ends with a direct call `apiKeyStore.addProjectKeys(config.getKeys())`, and `ApiKeyStore.addProjectKeys` swaps its volatile internal map with the supplied set (full replacement). If both `FileConfigStore` and `MergedConfigStore` independently fed `ApiKeyStore`, every 60s file poll would overwrite API-managed keys with the file-only key map and then `MergedConfigStore`'s post-callback rebuild would (after the 500ms debounce) overwrite again with the merged set — a window of ~500ms+ on every reload during which API-managed keys would 401. **Phase 2 chooses option (a):** the `FileConfigStore` → `ApiKeyStore` direct call is **conditional**: `FileConfigStore.load()` retains its `apiKeyStore.addProjectKeys` call when `apiKeyStore` is non-null (preserving today's standalone-`FileConfigStore` use cases — including integration tests that drive `FileConfigStore` directly), but under `MergedConfigStore` the wiring passes `apiKeyStore = null` to `FileConfigStore` so the direct call is skipped, and the `ApiKeyStore` update is performed exclusively by `ConfigPostProcessor` (run inside `MergedConfigStore`'s rebuild path) so that `apiKeyStore.addProjectKeys(mergedConfig.getKeys())` fires exactly once per rebuild against the merged file+API key set. This makes `ConfigPostProcessor` the authoritative owner of `ApiKeyStore` updates whenever `MergedConfigStore` is in the picture, while leaving standalone `FileConfigStore` callers (tests, future tooling) unbroken. See [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 2 prerequisites for the compile-time blocker bundle.

**`ApiKeyStore.addProjectKeys()` — guard against silent secret corruption.** `ApiKeyStore.java` line 170 today executes `value.setKey(apiKey)` unconditionally inside the loop, where `apiKey = entry.getKey()` is the human-readable map key. For API-managed keys whose `Key.key` is already populated with the decrypted secret (post-`SecretFieldProcessor`), this overwrite would silently replace the decrypted secret with the canonical name (e.g. `"project_keys/platform/proxyKey1"`), causing 401 on every subsequent auth attempt. Phase 2 must guard the assignment: `if (value.getKey() == null || value.getKey().isBlank()) { value.setKey(apiKey); }` — only set the map key into `Key.key` when the field is empty (legacy file-sourced format); otherwise leave the API-supplied secret in place and treat the map key as the human-readable name. Tracked as a Phase 2 prerequisite item in [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 2 prerequisites alongside the compile-time blocker bundle.

**Decryption-failure exclusion invariant — `addProjectKeys` never sees an API-managed key with empty `Key.key`.** The guard above falls back to `value.setKey(apiKey)` when `Key.key` is empty, so it must never fire on an API-managed entry whose decryption silently produced an empty string — otherwise the canonical resource name (e.g. `"project_keys/platform/proxyKey1"`) would be installed as the secret value and would silently authenticate any caller who presented that name. The invariant that prevents this: **entities where `SecretFieldProcessor` decryption fails are excluded from `Config` entirely by `MergedConfigStore` — they never reach `addProjectKeys`.** API-managed keys with successful decryption always have `Key.key` populated; with failed decryption, the entire entry is omitted from `Config`. The fallback `value.setKey(apiKey)` therefore only fires for legacy file-sourced keys where `Key.key` is not pre-populated. Document this exclusion explicitly so the guard is *not* relied on as a defense against decrypt-failure-yields-empty-string — that case is closed at the prior layer. Cross-reference [`04-security-and-audit.md`](04-security-and-audit.md) §2.3 for the decrypt-failure exclusion behaviour.

**Pluggable entity location (future-proofing for multi-tenancy):**

Entity locations are resolved through an `EntityLocationStrategy` interface, not hardcoded paths. **This interface returns blob-path format** (`{bucket}/{type}/`) — the Configuration API translates to resource-URL format (`{type}/{bucket}/`) when returning identifiers to clients.

The `entityType` parameter is the existing `ResourceTypes` enum (`storage/.../resource/ResourceTypes.java`, extended in Phase 2 with `MODEL`, `APP_TYPE_SCHEMA`, `INTERCEPTOR`, `ROLE`, `PROJECT_KEY`, `ROUTE`, `GLOBAL_SETTINGS` per §5.3). Using the typed enum gives compile-time safety, IDE autocomplete, and prevents the interface from accepting an arbitrary string. The `scope` parameter stays as `String` because future MT scopes are *parameterized* (`tenants/{id}`, `teams/{id}`, `channels/{id}`) — an enum is the wrong shape for an open, id-bearing set. The `PLATFORM_SCOPE` constant on the interface documents the only scope currently in use; its value matches the bucket name.

**Names are single-segment in Phase 1–4.** The new admin-config entity types (`models`, `interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`) use a single name segment matching `^[A-Za-z0-9._%:-]+$`. The check runs on the **URL-decoded** segment value (`ResourceDescriptorFactory.fromEncoded` decodes via `UrlUtil.decodePath` before validation), so a literal `%` in a name must be sent on the wire as `%25`; `:` may travel verbatim since it is non-reserved in path segments per RFC 3986. The character set is deliberately conservative; it may be extended on request from clients who can describe a concrete workflow the restriction breaks. Multi-segment hierarchical paths for these types are deferred to the multi-tenant scopes (`tenants/{id}`, `teams/{id}`, `channels/{id}`) introduced via `EntityLocationStrategy`. Existing user-data types (`files`, `conversations`, `prompts`) keep their arbitrary-depth paths through their unchanged controllers — this constraint applies only to types newly introduced by this proposal.

```java
public interface EntityLocationStrategy {
    /** Default scope in single-tenant deployments. Value matches the bucket name.
     *  MT implementations return additional tenant-/team-/channel-scoped paths. */
    String PLATFORM_SCOPE = "platform";

    /** Resolve the blob storage path prefix for a given entity type and scope.
     *  Returns blob-path format: "{bucket}/{type}/", or null if the entity type
     *  is not managed through MergedConfigStore (apps, toolsets — see §6). */
    String resolvePath(ResourceTypes entityType, String scope);

    /** List all scopes that should be merged for a given entity type. */
    List<String> listScopes(ResourceTypes entityType);
}

// Default implementation (Phase 2):
public class PlatformEntityLocationStrategy implements EntityLocationStrategy {
    public String resolvePath(ResourceTypes entityType, String scope) {
        return isHotPath(entityType)
            ? (isUserFacing(entityType)
                ? "public/" + entityType.group() + "/"      // models, schemas → blob: public/models/
                : "platform/" + entityType.group() + "/")   // roles, keys, routes, interceptors → blob: platform/roles/
            : null;  // apps, toolsets → NOT managed through MergedConfigStore (see §6)
    }

    public List<String> listScopes(ResourceTypes entityType) {
        return List.of(PLATFORM_SCOPE);  // single scope today; MT adds tenants/{id}, teams/{id}, channels/{id}.
    }
}
```

**Path format translation example:**
- `EntityLocationStrategy` returns blob path: `public/models/` (bucket first)
- Blob stores resource at: `public/models/gpt-4` (blob path)
- `ResourceDescriptor.getUrl()` returns: `models/public/gpt-4` (type first — resource URL)
- Admin API response uses: `"id": "models/public/gpt-4"` (resource URL — canonical ID)

**Rebuild trigger**: `MergedConfigStore` exposes three in-pod entry points — `requestRebuild()` (debounced asynchronous coalescing queue used by file-poll callback, pub/sub listener, and safety-net poll), `rebuildNow()` (synchronous full rebuild, used by `reload()`/the admin `/reload` endpoint and as the safety-net fallback), and the **partial-update API** `applyEntityWrite` / `applyEntityDelete` / `applyBatch` / `applySettingsWrite` / `applySettingsDelete` introduced in slice 4S.4 (the per-entity fast path used by the API write controllers on the writer pod — resolves [OQ-32](08-open-questions-and-references.md)). `FileConfigStore` has neither — its `vertx.setPeriodic(period, …, e -> load(false))` reloads its own file-derived `Config` directly. `requestRebuild()` is the coalescing queue for non-writer triggers: every such trigger source enqueues onto it, the 500ms trailing-edge debounce in §11.1 collapses bursts, and exactly one rebuild runs per debounce window. The partial-update methods run synchronously under the same `rebuildLock` that serializes full rebuilds and never touch blob storage — they mutate the affected per-type map of the merged `Config`, run targeted post-processing (per-type cross-ref check; `sortRoutes` for ROUTE; transitive un-skip / cascade for INTERCEPTOR), and atomically swap the volatile `Config` reference. Trigger sources:
- On `FileConfigStore` reload completion — `MergedConfigStore` registers a `Consumer<Config>` callback that `FileConfigStore` invokes after each successful `load()` (via its existing `vertx.setPeriodic` timer (default 60s, configurable via `config.reload`)). **The callback registration is new code in `FileConfigStore`**: today `FileConfigStore.load()` sets `this.config = config` and returns with no outbound notification, so Phase 2 adds a small observer-pattern hook — a new `List<Consumer<Config>> onReloadCallbacks` field on `FileConfigStore` and a constructor parameter (`initialOnReloadCallbacks`) so callbacks are registered before the periodic timer is scheduled — no post-construction `register()` method (see Registration race avoidance below). Sequencing contract for the callback: (a) callbacks fire only on a non-null `Config` return from `load()` — `load(false)` may return `null` if reload conditions aren't met, and a null result must skip the callback list entirely; (b) callbacks are invoked **after** the `this.config = config` volatile write so any callback re-reading `getConfig()` sees the post-swap value; (c) callbacks must not block the `FileConfigStore` reload thread — `MergedConfigStore.requestRebuild()` is non-blocking and event-loop-safe (atomic state mutation + `vertx.setTimer` for the debounce, blocking work dispatched via `executeBlocking` per the Vert.x threading model in §11.1), so calling it from the callback is safe. Calling `requestRebuild()` from this callback is what keeps the merged view in sync with file-config drift without `MergedConfigStore` running its own redundant 60s timer or polling `FileConfigStore.getConfig()` and racing the file-store's own load. There is **one** 60s file-poll on the pod (the existing `FileConfigStore` timer); the merged store rides on top of it via the callback.
- On ResourceService write (immediate on the writer pod) — handles API changes; the writer pod's local volatile-`Config` is updated as part of the write path before the HTTP response returns. **Slice 4S.4 partial-update fast path (writer pod).** After `ResourceService.put`/`delete` succeeds, the API write controller calls `mergedConfigStore.applyEntityWrite(type, canonicalId, decryptedEntity)` (or `applyEntityDelete`, `applyBatch`, `applySettingsWrite/Delete`) — single-entity in-memory mutation under `rebuildLock`, no blob LIST, no blob GET, no Redis read. The previous full-`rebuildNow()` on every write is retired for these callers; `rebuildNow()` survives as the fallback for the admin `/reload` endpoint and the polling safety net. The "immediate on writer pod" guarantee is provided by the partial-update swap, which returns only after the volatile-`Config` reference is replaced. Cross-reference invariants are preserved on a per-type basis: MODEL writes run `validateCrossReferences` against the new interceptor map; INTERCEPTOR writes resurrect previously-skipped models from the `invalidEntities` sibling store when their references now resolve; INTERCEPTOR deletes cascade-invalidate dependent models under MODE_SKIP. ROUTE writes re-run `sortRoutes` over the new map; APP_TYPE_SCHEMA / GLOBAL_SETTINGS writes have no cross-ref work. **Slice 4S.5 partial-update fast path (replicas).** On `ResourceTopic` event receipt, `MergedConfigStore.onResourceEvent` filters self-pod events (`senderPodId == thisPodId`) and non-managed types, parses the URL to a `ResourceDescriptor`, and dispatches `applyReplicaEvent(descriptor, action)` onto `taskExecutor` (virtual thread). `applyReplicaEvent` runs the writer-mirror sequence: for DELETE, GLOBAL_SETTINGS → `applySettingsDelete`; PROJECT_KEY → snapshot plaintext secret from the current `Config.keys` map then `apiKeyStore.removeKey` before `applyEntityDelete`; others → `applyEntityDelete`. For CREATE/UPDATE, `resourceService.getResource(descriptor)` fetches the body (Redis two-tier cached after writer's put); a null body means the writer deleted the entity between publish and fetch (pub/sub race) and is treated as DELETE. Otherwise the body is parsed, typed-deserialized (APP_TYPE_SCHEMA stays as a raw JSON string), and `secretFieldProcessor.decryptFields` decrypts in-place; PROJECT_KEY then snapshots the prior secret from the in-memory `Config.keys`, calls `apiKeyStore.addOrUpdateKey(secret, ApiKeyData{originalKey})` before `applyEntityWrite`, and (rotation invariant) calls `apiKeyStore.removeKey(oldSecret)` when the secret changed; GLOBAL_SETTINGS calls `applySettingsWrite`; others call `applyEntityWrite`. Any exception logs a warning and falls back to `requestRebuild()` — the polling correctness SLA still applies, and the cross-replica safety-net poll covers eventual convergence. **Fetch-on-receipt over embed-body** (locked decision): keeps `ResourceEvent`'s `{url, action, etag, senderPodId}` shape stable across the codebase, secrets opaque in pub/sub frames, large entities out of every frame, and reuses the Redis two-tier cache that the writer's put already primes. **Keys-controller `ApiKeyStore` fast-path retained.** The keys controller still calls `ApiKeyStore.addOrUpdateKey(secret, data)` / `removeKey(secret)` directly before `applyEntityWrite` / `applyEntityDelete`; the partial-update path does **not** internally touch `ApiKeyStore` on the writer so the existing ordering invariant is preserved. The previous "`ConfigPostProcessor.addProjectKeys` re-run inside rebuild" pathway is no longer hit on the writer pod for keys writes — `ApiKeyStore` consistency is maintained by the controller's direct mutator call before the merged `Config` swap, and by `applyReplicaEvent` mirroring that ordering on each replica.

**Sequencing — fast-path `addOrUpdateKey` vs `applyEntityWrite` on the writer pod, and `apiKeyStore` mirror on replicas.** On the writer pod the controller calls `apiKeyStore.addOrUpdateKey` **before** `applyEntityWrite` — covering the window between `ResourceService.put` returning and the volatile-`Config` swap. On replicas (slice 4S.5) `applyReplicaEvent` mirrors the same ordering: it calls `apiKeyStore.addOrUpdateKey` after the fetch+decrypt step and before `applyEntityWrite`; for DELETE it snapshots the prior secret from the current `Config.keys` and calls `apiKeyStore.removeKey` before `applyEntityDelete`. The fast-path is therefore not redundant on either side (it covers the apply step's own execution window) and is idempotent with respect to any subsequent `requestRebuild()` fallback whose `ConfigPostProcessor.addProjectKeys` would re-establish the same mapping. **Rotation invariant:** on a PUT/UPDATE whose plaintext secret differs from the prior secret, `apiKeyStore.removeKey(oldSecret)` MUST accompany `apiKeyStore.addOrUpdateKey(newSecret, …)` so the old secret stops authenticating immediately. On the writer pod (`ConfigResourceController` / `AdminApplyController`) the prior secret is recovered by decrypting the already-read existing blob; recovery is deliberately non-fatal (corrupt prior blob → log + proceed, the new secret is authoritative and a stale entry is reclaimed at the next full rebuild). On replicas `applyReplicaEvent` snapshots `oldSecret` from the in-memory `Config.keys` before the apply swap. `addProjectKeys` now takes explicit file/API partitions (`addProjectKeys(fileKeysBySecret, apiKeysByCanonicalId)`) and builds one map swapped atomically once (single-atomic-swap preserved), retiring the prior map-key-shape inference that distinguished file vs API entries by the presence of `/`.

**Concurrency model for the keys fast-path — `ApiKeyStore.keys` migrates from a `volatile Map` field backed by a `HashMap` to a `volatile ConcurrentHashMap`, with a reference-swap rebuild idiom.** Today's `ApiKeyStore.keys` is declared `private volatile Map<String, ApiKeyData> keys = new HashMap<>()` (the field type is the `Map` interface, backed by a `HashMap` instance) and the only mutator is `addProjectKeys(...)` which builds a fresh `HashMap` and reassigns the volatile reference (full-replacement). Single-key partial mutation on a `HashMap` instance held behind a volatile reference is **not** thread-safe — concurrent readers traversing buckets while a writer mutates entries can observe corrupted state. The fast-path `addOrUpdateKey(name, key)` is by definition a single-key partial mutation, so Phase 2 must change the field declaration from `private volatile Map<String, ApiKeyData> keys = new HashMap<>()` to `private volatile ConcurrentHashMap<String, ApiKeyData> keys = new ConcurrentHashMap<>()` before introducing the fast-path.

**Locked choice — keep the volatile-reference swap idiom for `addProjectKeys(...)`** rather than the `clear()+putAll()` rewrite considered in earlier drafts. `clear()+putAll()` on a `ConcurrentHashMap` is non-atomic at the map-instance level: a fast-path `removeKey("k")` that lands between `clear()` and `putAll()` is silently undone if the rebuild's input map still contains `k`, opening a brief re-authentication window after `DELETE /v1/keys/...` until the next rebuild. Phase 2 changes (paired):
- The field declaration becomes `private volatile ConcurrentHashMap<String, ApiKeyData> keys = new ConcurrentHashMap<>()` — `volatile` retained on the reference because rebuilds atomically swap the entire map instance; `ConcurrentHashMap` provides per-entry happens-before guarantees for the fast-path mutators.
- Introduce `addOrUpdateKey(String name, ApiKeyData data)` and `removeKey(String name)` used by the keys-controller fast-path; both operate on the **current** `keys` reference via the concurrent map's `put` / `remove`.
- Rewrite `addProjectKeys(...)` to **build a fresh `ConcurrentHashMap` from the merged config and atomically swap the reference** (`this.keys = freshMap`). The mutation primitive stays as atomic-reference-swap; the data-structure type changes only to support concurrent fast-path mutation between rebuilds. **Two-partition signature:** `addProjectKeys(Map<String,Key> fileKeysBySecret, Map<String,Key> apiKeysByCanonicalId)` takes the source classification explicitly rather than inferring it from map-key shape. The file partition is keyed by the plaintext secret (the secret may be Base64 and contain `/`, so a blank `Key.key` is back-filled verbatim from the map key with **no** `/` check); the API partition is keyed by canonical ID, never the secret, so a blank `Key.key` after decrypt is skipped + logged (fail closed) rather than back-filled. Both partitions fold into **one** `ConcurrentHashMap` swapped once (single-atomic-swap invariant). `FileConfigStore` passes `(config.getKeys(), Map.of())`; `MergedConfigStore.rebuild` passes the base file keys as the file partition and the PROJECT_KEY blob entries (collected during the scan, keyed by canonical ID) as the API partition — the merged `Config.getKeys()` map still folds both for config consumers, only the `ApiKeyStore` feed is partitioned.

**Concurrency note (accepted trade-off).** A fast-path `removeKey` that lands on the pre-swap map instance is naturally superseded by the post-swap reference, which contains the rebuild's own view of the deletion (the keys controller's blob `DELETE` happens **before** the controller calls `removeKey`, so the rebuild reading the merged config after the blob delete already excludes the key). A fast-path `addOrUpdateKey` racing with a rebuild swap may be lost on the swapped-in instance — this is accepted because `rebuildNow()` already covers writer-pod immediacy on the same code path; on the writer pod, the controller's `rebuildNow()` invocation runs synchronously after `ResourceService.put` and produces a fresh map containing the new key, so the swap is the *more recent* state, not a regression.

**Ordering invariant for `removeKey` against rebuild — required to prevent silent undo.** The "rebuild's own view of the deletion" property above is only true if the rebuild's `ResourceService` scan of the project-keys blob path begins *after* the keys-controller `DELETE` path's `ResourceService.delete` returns. Phase 2 enforces this ordering explicitly: on `DELETE /v1/keys/...`, the keys controller (a) calls `ResourceService.delete(descriptor)` and waits for it to return, (b) calls `apiKeyStore.removeKey(name)` on the current map reference, then (c) invokes `rebuildNow()` — the rebuild's blob scan therefore observes the post-delete state and the freshly-built map does not contain the deleted key. Without this ordering, a rebuild that reads `ResourceService` before the blob `DELETE` commits would put the key back into `freshMap`, and the post-swap reference would silently re-introduce a key the operator just deleted. The invariant mirrors the contract noted for `addOrUpdateKey` above. Tracked as a Phase 2 implementation checklist item in [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 2 prerequisites.

Tracked as a Phase 2 prerequisites compile-time blocker item in [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 2 alongside the keys-controller fast-path.
- On a separate `MergedConfigStore` 60s safety-net poll for **ResourceService drift only** — re-reads the `MergedConfigStore`-managed resource types from `ResourceService` and calls `requestRebuild()` if any have changed since the last rebuild. This timer covers the cross-replica case where a Phase 1.5 `ResourceTopic` event was silently dropped — it is the polling correctness primitive cited above. It does **not** read `FileConfigStore` (file changes flow through the callback bullet above), so the two timers don't race on file state.
- On `ResourceTopic` event (Phase 1.5; partial-update fast path since slice 4S.5) — handles cross-replica propagation. Every `ResourceService` write already publishes a `ResourceEvent` on the existing `ResourceTopic` for cache-invalidation purposes; `MergedConfigStore` adds one more listener to that same topic, filters to the resource types it manages (per §6 storage-strategy table), and dispatches survivors to `applyReplicaEvent` via `taskExecutor` (slice 4S.5 — see the writer/replica fast-path paragraph above). Exceptions in `applyReplicaEvent` fall back to the same `requestRebuild()` queue, preserving the polling SLA for malformed bodies or cross-replica race losses. **No new topic, no new event class, no new publish call** — Phase 1.5 is a listener-and-filter on an existing broadcast. The full mechanics (filter shape, debounce window, self-event handling, ordering semantics, observability metrics) are specified in §11.1.

**Startup initial rebuild — explicit, not callback-driven.** `FileConfigStore` calls `load(true)` from its constructor, before any external code can register on the new `onReloadCallbacks` list, so `MergedConfigStore`'s callback-based rebuild misses this initial load event. To cover the cold-boot case, `MergedConfigStore.init()` (invoked during server startup, after `FileConfigStore` construction) performs an explicit initial rebuild by reading `FileConfigStore.get()` and the current `ResourceService` state directly — this seeds the volatile-`Config` swap once at boot. The `onReloadCallbacks` hook then handles every subsequent reload. Without this explicit init step, the merged volatile-`Config` would remain empty until the next 60s file poll fired and re-invoked `load()`.

**Pre-init `requestRebuild()` invariant — must be a no-op (or queued) until `init()` returns.** A periodic-timer fire (or any other trigger source) that lands between `MergedConfigStore` construction and `MergedConfigStore.init()` must not drive a rebuild on a not-yet-initialized store — the rebuild path depends on collaborators (decryption services, post-processor wiring, the invalid-entity sibling store) that `init()` finalizes. Phase 2 makes this an explicit startup ordering invariant: `requestRebuild()` MUST be a no-op (or set the `rebuildPending` flag and return without scheduling work) until `init()` has completed. The simplest implementation is a `volatile boolean initialized = false` flag set at the end of `init()`; `requestRebuild()` short-circuits while `initialized == false`. Any pre-init triggers are naturally subsumed by the explicit initial rebuild `init()` performs. Tracked as a Phase 2 implementation checklist item in [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 2 prerequisites.

**Registration race avoidance — `MergedConfigStore` registers its `onReloadCallbacks` consumer before `FileConfigStore`'s `vertx.setPeriodic` reload timer fires.** The single-construction-step pattern (`new FileConfigStore(...)` → constructor invokes `load(true)` and immediately starts `setPeriodic(60s, ..., e -> load(false))` → `MergedConfigStore` registers its callback later in `init()`) is only safe in production where the 60s reload period is much greater than the seconds-long server startup time, so the periodic timer fires no earlier than 60s after construction. Integration tests that drop `config.reload` to single-digit milliseconds can race — the timer fires before `MergedConfigStore.init()` has registered, and `requestRebuild()` is never invoked from that load. **Locked choice — option (a):** extend `FileConfigStore`'s constructor to accept an optional `List<Consumer<Config>> initialOnReloadCallbacks` parameter and register them before scheduling `vertx.setPeriodic`. The single-step construction model is preserved; `MergedConfigStore` provides its consumer at `FileConfigStore` construction time so the callback list is non-empty before the periodic timer is scheduled, eliminating the race window regardless of `config.reload` period. Option (b) — split construction with a later `start()` call — is **rejected** because it touches more call sites and breaks the existing constructor invariant (today's callers rely on `new FileConfigStore(...)` producing a fully-running store).

**Final `FileConfigStore` constructor signature (Phase 2).** The two changes above (nullable `apiKeyStore` per the §4 single-owner item, and the new `initialOnReloadCallbacks` list per this race-avoidance item) compose into one constructor signature:

```java
FileConfigStore(Vertx vertx, JsonObject settings, @Nullable ApiKeyStore apiKeyStore, List<Consumer<Config>> initialOnReloadCallbacks)
```

Both changes are atomic — they ship in the same PR alongside the rest of the Phase 2 compile-time blocker bundle. Standalone `FileConfigStore` callers (integration tests, future tooling) pass a non-null `apiKeyStore` and an empty `initialOnReloadCallbacks` list to preserve today's behaviour; `MergedConfigStore` passes `apiKeyStore = null` and supplies its `requestRebuild()` consumer in the callback list. See [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 2 prerequisites for the cross-reference.

**Pub/sub is a latency optimization, not a delivery guarantee.** Polling is the correctness SLA — the `MergedConfigStore` 60s safety-net poll for ResourceService drift (last bullet above) is what guarantees every replica converges, even if pub/sub silently drops. Pub/sub reduces 0–60s propagation lag to ≤ debounce window when delivery succeeds, and is silently no-op when it doesn't (pod missed message during restart, broker eviction, network partition). See §11.1 for the full design, failure-mode behavior, and operator observability.

*(Singleton `globalSettings` is a separate case — it has no map keys to coexist as, so the union model in this section does not apply to it. Per slice U.1 (2026-05-21), the singleton per-entity GET is **blob-only** — returns the API blob if present, `404` otherwise; file-defined and schema-default values surface only via `/v1/admin/config/file/settings/global`. See [OQ-10](08-open-questions-and-references.md) for the full singleton resolution.)*

**Operator surface vs runtime resolution (slice U.1).** The union lives in the runtime `Config` for chat-completion routing — `findDeployment`, `RateLimiter`, the route matcher, and the interceptor chain resolver all keep using both simple-name (file) and canonical-ID (API) keys. The Configuration API operator surface is **not** the union: per-entity GET (`/v1/{type}/{bucket}/{name}`) and metadata listing (`/v1/metadata/...`) are blob-only — file entries do not appear on either. Operators inspect file entries via a dedicated read-only path, `/v1/admin/config/file/{type}[/{name}]`, with the security-admin role gating the `keys` type because file `Config.keys` map keys equal secrets (see [`04-security-and-audit.md`](04-security-and-audit.md) §1.5.1). Splitting the surface this way keeps the operator-facing API clean (one source per endpoint, no per-entity `source` field to disambiguate, no name-collision corner cases like file `gpt-4` vs API `models/public/gpt-4`) while runtime resolution remains untouched.

### 4.1 Failure semantics on reload

The opt-in per-entity skip mechanic available on `MergedConfigStore` is a deliberate alternative to `FileConfigStore`'s whole-config atomicity. This subsection lays out the strategies considered, the default, the opt-in alternative, cross-reference handling, and the visibility channels that make skip-and-continue safe when an operator opts in.

**Strategies considered:**

| Strategy | Trade-off |
|---|---|
| **A. Abort reload, keep previous Config** *(default)* | Strong reload-time invariant: Config either advances cleanly or stays where it was — matches today's `FileConfigStore` behavior extended to blob entities (post-deserialization semantic failures only — JSON parse failures on individual blob entities are always per-entity skipped because Jackson can deserialize each blob in isolation, unlike `FileConfigStore` which reads a single file as one tree). One bad entity blocks updates to all entities until the operator fixes it. Doesn't extend to file→blob danglers (§4.2). On cold boot when no previous Config exists, this entails strategy C — loud startup failure if any blob entity is corrupt. |
| **B. Skip invalid entity + continue** *(opt-in via `config.reload.onInvalidEntity: skip`)* | Resilient to blob corruption. Pod scale-up works during partial outages — a new replica boots with a degraded Config and serves valid entities. Cross-references can dangle silently *unless* surfaced — mitigated by transitive skip and the visibility channels below. |
| **C. Fail at startup** *(behavior of A during cold boot when no previous Config exists)* | Loud failure on fresh deployment when any single blob entity is corrupt. HPA scale-out, rolling updates, eviction recovery, and DR boot all become fragile to entity-level corruption that may be unrelated to the traffic the new pod would serve — operators who need scale-up resilience opt into strategy B. |

**Default: A.** Strategy B is available as an opt-in via the static setting `config.reload.onInvalidEntity: skip | abort` (default `abort`). Operators who need pod scale-up resilience during partial blob-corruption incidents — and who accept dangling cross-references being surfaced via the visibility channels below rather than blocking the reload — set `skip`. The strict-by-default contract aligns with `config.write.softValidation: false` (§9): "no broken entities accepted" is the headline behavior across both write-side and reload-side validation; opt-in modes exist for operators with specific operational needs.

**Scope of per-entity skip (when opted in).** Per-entity skip applies to *post-deserialization* errors only — semantic validation, cross-references, deployment uniqueness, post-load processing. A JSON parse failure on a config file remains a whole-reload failure regardless of `onInvalidEntity` because Jackson cannot deserialize a partial tree (`FileConfigStore.loadConfig()` reads the whole file into a single tree before conversion). Blob-stored entities are deserialized one at a time, so a corrupt single-entity blob payload can be skipped under `skip` mode.

**Cross-reference handling on skip — transitive.** When entity *X* is skipped, any entity *Y* with a *required* reference to *X* is also marked invalid and skipped from in-memory `Config`. Required references = those that would cause request-time failure (interceptor in a deployment's chain, schema for a schema-rich application). Optional references (a role's limit-key naming a deployment that doesn't exist yet) emit a warning only, not a skip — this matches today's `Role.limits` semantics where unmatched keys are tolerated.

**Three visibility channels.** Skip-and-continue is only safe if the inconsistency is visible to operators:

1. **Health endpoint** — `GET /v1/admin/health/config` returns `{ "status": "ok" | "degraded", "skipped": [{ "id": "...", "reason": "..." }, ...] }`. Operator-facing, admin-authenticated. This is **separate from the existing unauthenticated `/health` liveliness probe** (`Proxy.HEALTH_CHECK_PATH`) used by Kubernetes — that endpoint is unchanged. Kubernetes liveness/readiness probes continue to use `/health`; the new endpoint is for operator dashboards and alerting that need to distinguish "Core up" from "Core up but degraded."
2. **Per-entity GET + health endpoint** — `GET /v1/{type}/{bucket}/{name}` for any blob-managed entity returns `status: "valid" | "invalid"` (top-level Public) and Owner-only `validationWarnings`. The aggregate operator surface is `GET /v1/admin/health/config` (channel 1 above) which enumerates every invalid entity across all admin-config types in one call. The folder metadata listing `GET /v1/metadata/{type}/{bucket}/{path}` does **not** carry status — listings are metadata-only per [`03-api-reference.md`](03-api-reference.md) §4, matching the existing Resource API's listing shape. See §4.3 for the per-entity GET projection and [`04-security-and-audit.md`](04-security-and-audit.md) §1.5 for the Public/Owner field-projection rules. *(Slice U.1, 2026-05-21: the previously Owner-only `source: "file" | "api"` field is retired entirely — the URL itself discloses the source.)*
3. **Prometheus metrics** — `dial_config_skipped_entities{type,reason}` (gauge) and `dial_config_skip_events_total{type,reason}` (counter). Alertable from existing operator dashboards.

The audit log (when it lands — **deferred to Phase 7**, see [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 7) will intentionally *not* be a fourth channel. Audit captures admin mutations only; validity transitions are derived runtime state and live on the three channels above. *"When did this entity break?"* will then be answerable by correlating mutation events for the upstream config change (interceptor removed, schema replaced) with the listing snapshot. See [`04-security-and-audit.md`](04-security-and-audit.md) §3.3 for the rationale (also WIP).

### 4.2 Pre-existing cross-reference inconsistency

The skip-and-continue model formalizes a behavior the system already has at the file→blob boundary. Today, with no MergedConfigStore in the picture:

- An admin updates a schema in `aidial.config.json` → `FileConfigStore` reloads atomically → blob applications that referenced the old shape are now non-conformant → **Core does not notice**. `@CustomApplicationsConformToTypeSchemas` runs on `Config`, not on blob.
- An admin renames or removes an interceptor in the file → blob applications with that interceptor in their chain become broken → **Core does not notice**. The break surfaces at request time when the chain resolver fails to find the entry.

`FileConfigStore`'s whole-config atomicity covers the file path only; blob-backed entities (apps, toolsets) have always been eventually consistent at the cross-reference layer, with no health or per-entity-GET signal flagging the inconsistency. Phase 3 deliberately surfaces this pre-existing inconsistency through **lazy validation** on the admin-API per-entity-GET path (§4.3) — the per-entity-GET controller computes validation status against current `Config` on every read and tags the response with `status: "invalid"` and `validationWarnings`. The chat-completion hot path is unchanged: invalid blob apps still serve until request-time failure (`404` on missing interceptor, schema mismatch on schema-rich app). Phase 3 does not "fix" the eventual consistency — it adds the operator-visible signal that has been missing. Folder metadata listings (`GET /v1/metadata/...`) carry no status field (full alignment with the existing Resource API); operators discover invalid entries via `GET /v1/admin/health/config` or by following up a listing with a per-entity GET.

### 4.3 Invalid-entity visibility surface

Invalid entities live in blob (durable) and are exposed on the per-entity-GET API as first-class responses with status metadata. The natural causes of invalid status are upstream changes (referenced interceptor or schema removed via the file or the API) and version drift after a Core upgrade introduces stricter validation. Write-time validation rejects creating an invalid entity directly (`ResourceController.validateCustomApplication` rejects unknown interceptor or dependency refs at lines 231–249), so blob entities only become invalid passively, as side effects of upstream mutations.

**Visibility scope.** Status surfaces on the **per-entity GET** response (`GET /v1/{type}/{bucket}/{name}`) and on the aggregate operator endpoint (`GET /v1/admin/health/config`). The folder metadata listing (`GET /v1/metadata/{type}/{bucket}/{path}`) returns `ResourceItemMetadata` items with no status — full alignment with the existing Resource API's listing shape per [`03-api-reference.md`](03-api-reference.md) §4. Operators querying "which models are broken?" use the health endpoint; operators inspecting a specific entity use the per-entity GET.

**Two validation patterns, chosen by storage model:**

| Pattern | Applies to | Mechanics |
|---|---|---|
| **Pre-computed** | Entities that flow through `MergedConfigStore` — models, roles, schemas, interceptors, routes, keys, settings | Validation runs at `MergedConfigStore` rebuild. Invalid entries are skipped from in-memory `Config` and recorded in the `invalidEntities` sibling store. Hot path filters them out — invalid entries are never findable through `Config`. |
| **Lazy** | Blob-native entities that do *not* flow through `MergedConfigStore` — applications, toolsets (see §6) | Validation is computed on every admin-API per-entity-GET, against the current `Config`. Hot path is **unchanged from today's behavior** — `findDeployment` returns the blob entity as before; cross-reference failures still surface at request time as `404` (e.g. missing interceptor — `DeploymentPostController.handleInterceptor` lines 134–143). Visibility comes from the per-entity-GET surface, not from filtering the request path. |

The asymmetry is deliberate. Pre-computing validation for blob-native entities at rebuild would require tracking thousands of blob items in memory at all times — exactly what §6's "no double-counting in `Config`" rationale was designed to avoid. Lazy validation is the standard pattern for systems with eventually-consistent cross-references — Kubernetes `status.conditions`, AWS IAM policy validation (read returns the policy, separate `validate-policy` action exists), MongoDB `validationLevel: moderate`, GitHub Actions workflows referencing deleted secrets. Today, blob apps with broken interceptor refs already serve via `findDeployment` and fail at request time — Phase 3 does not change that, it adds the visibility.

**Layered model:**

| Layer | Pre-computed (MergedConfigStore-managed) | Lazy (apps, toolsets) |
|---|---|---|
| **Blob (durable state)** | Stores entity payload only — no validation metadata persisted. | Same. |
| **In-memory `Config`** (hot path) | Holds **only valid** entities. `RateLimiter`, deployment resolver, route matcher never see invalid entries. | Apps/toolsets are not in `Config` (per §6). `findDeployment` cascade falls through to `ApplicationService` / `ToolSetService` — same as today. |
| **`MergedConfigStore.invalidEntities`** | `Map<entityType, Map<id, InvalidEntityRecord>>` — `id`, `etag`, `validationWarnings: [...]`, `lastModified`, `source`. **In-memory derived state — regenerated from blob on every rebuild, never persisted independently.** Cleared on rebuild when an entity becomes valid again (missing schema restored, Core upgraded). After pod restart, the store is empty until the first rebuild completes — benign; the per-entity-GET and health endpoint surfaces return "no invalid entries" until the rebuild populates. | None — there is no sibling store for blob-native entities. |
| **`BlobEntityValidator`** (helper, new in Phase 3) | n/a | Pure function: `validate(entity, currentConfig) → List<ValidationWarning>`. Checks interceptor refs against `Config.interceptors`, schema refs against `Config.applicationTypeSchemas`, dependencies via `deploymentService.findDeployment`. Called by the per-entity-GET controller and the `/v1/admin/health/config` aggregator, not by the chat-completion request path or by folder metadata listings. |
| **Hot path (chat-completion)** | Filters out invalid entries automatically (they are not in `Config`). | Unchanged from today. `findDeployment` returns the entity; if a referenced interceptor is missing, `handleInterceptor` returns `404` to the client (existing behavior). |
| **Admin-API surface** | Per-entity-GET controllers read both `Config` (valid) and `invalidEntities` (invalid) when computing the response wrapper. Folder metadata listings carry no status; the `/v1/admin/health/config` endpoint aggregates the `invalidEntities` map for operator overview. | Per-entity-GET controllers call `BlobEntityValidator` per item against current `Config`, fold warnings into the response. The same helper backs the health endpoint's per-type sweep. Cost: ~1ms per item; admin-API isn't latency-sensitive. |

**Per-entity GET response shape (Owner view example — Public view omits `validationWarnings`; slice U.1 retired the `source` field entirely)** — also in [`03-api-reference.md`](03-api-reference.md) §2. Field projection follows the Public/Owner views in [`04-security-and-audit.md`](04-security-and-audit.md) §1.5:

```json
{
  "type": "chat",
  "name": "models/public/old-broken-model",
  "endpoint": "...",
  "status": "invalid",
  "validationWarnings": [
    { "field": "applicationTypeSchemaId",
      "message": "Schema 'schemas/public/old-schema-v1' not found" },
    { "field": "interceptors[0]",
      "message": "Interceptor 'deprecated-guardrail' not found" }
  ]
}
```

Per-entity shape: entity-intrinsic fields stay top-level (matching today's user Resource API shape); `status` is a top-level field visible to **Public and Owner** so any reader can see whether the entity is functional; `validationWarnings` is **Owner-only** (admin or bucket-owner) — omitted entirely for Public callers. The flat shape uses Jackson `@JsonView` with `DEFAULT_VIEW_INCLUSION = false` on the admin-CRUD `ObjectMapper`: every field carries an explicit view annotation, and a forgotten annotation makes the field invisible everywhere (fail-closed at write time, not silently public). See [`04-security-and-audit.md`](04-security-and-audit.md) §1.5 for the full rule and rationale. *(Slice U.1, 2026-05-21: the previously Owner-only `source: "file" | "api"` field is retired — the URL itself discloses the source; file entries surface only via `/v1/admin/config/file/*` — see [`04-security-and-audit.md`](04-security-and-audit.md) §1.5.1.)*

The `status` and `validationWarnings` fields live on the **per-entity-GET response wrapper**, **not on the entity data classes** (`Model`, `Role`, `Application`, …). Those classes are shared with `FileConfigStore`, are imported as a Gradle dependency by the CLI, and round-trip through `aidial.config.json` — adding runtime status fields on them would leak into the file format and the CLI types. The folder metadata listing surface (`GET /v1/metadata/...`) returns the same `ResourceItemMetadata` shape the existing Resource API uses (`url`, `name`, `etag`, `createdAt`, `updatedAt`, `author`) with no status fields — full alignment.

`etag` is returned in the HTTP `ETag` header on per-entity-GET (not in the body) and on each `ResourceItemMetadata` in the metadata listing. `lastModified` lives on `ResourceItemMetadata` as `updatedAt` (existing Resource API convention) but is not surfaced on per-entity-GET response bodies (YAGNI for the entity wrapper — revisit if a use case shows up).

**Audit rollback (deferred — Phase 7).** When the audit subsystem lands, `dial-cli audit history` and `dial-cli audit snapshot` will work against any past state and `dial-cli audit rollback` will re-apply a prior snapshot through the standard write path — meaning it is subject to current-version validation. If the snapshot's payload no longer satisfies validation (renamed field, removed schema reference, deprecated enum), the rollback is rejected with the same error a manual `PUT` of that payload would produce. A subsequent phase will need to add a recovery mechanism for restoring snapshots whose payload is incompatible with the current entity model. See OQ-31 in [`08-open-questions-and-references.md`](08-open-questions-and-references.md).

### 4.4 Cross-pod admin-write and rebuild serialization

Every admin write path — per-entity `PUT`/`DELETE` on `/v1/{type}/{bucket}/{name}` for the admin scope (both `public/` admin-managed types and `platform/`) and bulk `POST /v1/admin/apply` — acquires **both** the `public/` and `platform/` bucket locks via `LockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS, …)` around its write phase. Concurrent admin writes on different DIAL Core pods are therefore strictly serialized at the entity-set level, not just per-resource-key.

**Why both bucket locks rather than per-entity.** The per-resource `ResourceService` lock prevents same-key races but admits cross-entity interleavings between concurrent batches on different pods — e.g. pod A's `apply` writes interceptor `I`, pod B's `apply` writes model `M` referencing `I`, and the two writes can land in an order that violates within-batch invariants the apply path otherwise upholds (§4 "rebuild serialization", `03-api-reference.md` §7 proposed-config validation). Admin entities span both buckets (models/schemas in `public/`; interceptors/roles/keys/routes in `platform/`) and a single apply batch may write to both, so the write phase takes both bucket locks in canonical sorted order (`underBucketLocks` sorts internally) — serializing every admin write cluster-wide. Admin writes are rare on real environments (dozens/day at most — see §6 "Why this approach is correct"), so full serialization costs nothing on this workload, and bucket-keyed locks slot into the existing per-bucket `ResourceService` lock fabric without new lock vocabulary.

**Rebuild reads share the same locks (PR #1529 thread `r3302786719`).** A writer pod's apply runs `resourceService.putResource(...)` once per entity in a multi-blob batch, then performs the in-memory `Config` swap *after* every blob is written. During that interval the blob store carries partial-batch state, and a peer pod whose full-rebuild blob scan runs in that window would seed a torn `Config`. So the three full-rebuild entry points — `MergedConfigStore.reload()` (admin `/reload`), `rebuildNow()`, and the debounced timer-fire rebuild (file-poll callback + pub/sub-failure fallback) — acquire the same `ADMIN_BUCKET_LOCATIONS` before scanning. The lock-holding writer pod thus owns the read horizon for both buckets across the cluster until its volatile swap completes.

**Deliberately *not* locked.** `MergedConfigStore.init()` runs once at pod startup, before the pod accepts traffic and before any concurrent admin writer, so it scans without the distributed lock. The replica fast path (`applyReplicaEvent`, driven by per-entity pub/sub events) also does not take the lock: it is a best-effort latency optimization that, on any inconsistency or exception, falls back to `requestRebuild()` and is ultimately backstopped by the polling correctness SLA (§11.1). Locking the high-frequency per-event path would serialize every replica against every writer on the Redis lock for no correctness gain over that fallback. The partial-update `apply*` methods take only the intra-pod `rebuildLock`; their controller callers already hold the outer bucket locks.

**Lock-ordering invariant.** Callers acquire `ADMIN_BUCKET_LOCATIONS` in canonical sorted order (`underBucketLocks` enforces this) **before** any per-resource `ResourceService` lock. `LockService.lock` is **not** reentrant at the Redis layer, so a thread already holding these bucket locks must not re-enter `underBucketLocks` — the write controllers therefore call the lock-free `apply*` methods, never `reload`/`rebuildNow`. Non-admin paths (publications, code-interpreter, user resource CRUD) never acquire these locks, so the consistent ordering precludes deadlock with the existing per-resource lock fabric.

**Forward-compatibility with MT.** Under future multi-tenancy, each top-level scope (`platform/`, `tenants/{id}`, `teams/{id}`, …) gets its own bucket-lock entry in the `underBucketLocks` set — admin writes (and the rebuild reads) that span scopes acquire the union of their scopes' locks in sorted order, preserving deadlock-freedom. No design change is required when MT scopes are introduced.

## 5. Bucket Strategy: `public/` for User-Facing, `platform/` for Infrastructure

The design principle: **`public/` is "stuff available to users", `platform/` is infrastructure users never interact with directly.**

### 5.1 Bucket Layout (blob storage paths — bucket first)

```
public/ bucket (existing — extended with admin-managed deployments):
  ├── applications/       ← user-published apps (existing)
  ├── toolsets/            ← user-published toolsets (existing)
  ├── files/               ← published files (existing)
  ├── prompts/             ← published prompts (existing)
  ├── publications/        ← publication metadata (existing)
  ├── models/              ← NEW: admin-managed models (via MergedConfigStore)
  └── app_type_schemas/    ← NEW: admin-managed application type schemas (via MergedConfigStore)

platform/ bucket (new — infrastructure config, top-level scope):
  ├── roles/               ← admin-managed roles (via MergedConfigStore)
  ├── keys/                ← admin-managed API keys (via MergedConfigStore)
  ├── routes/              ← admin-managed routes (via MergedConfigStore)
  ├── interceptors/        ← admin-managed interceptors (via MergedConfigStore)
  └── settings/            ← global settings singleton (via MergedConfigStore)
```

Note on `settings/`: this is the singleton resource that holds DIAL Core's **root-level `Config` fields** — `globalInterceptors`, `retriableErrorCodes`, and any future top-level fields that aren't per-entity collections. It is **not** the static-settings file (`aidial.settings.json` — Vert.x options, blob/Redis connection, identity providers, encryption keys); those remain bootstrap-time, file-only, and outside the scope of this proposal. The singleton is exposed at `GET/PUT/DELETE /v1/settings/platform/global` (uniform `{type}/{bucket}/{name}` shape; `global` is the synthetic singleton name; future MT scopes plug in as `/v1/settings/{tenant-id}/...` without reshaping the route). `PUT` is upsert (sets/replaces the API blob); `DELETE` clears the API blob and reverts the projection to file-sourced (or schema-default) values — see [`03-api-reference.md`](03-api-reference.md) §1 for the wire format and three-state projection table, and [OQ-10](08-open-questions-and-references.md) for the file/API tie-break and revert path.

The blob-or-not distinction is a `MergedConfigStore` concern at the runtime layer. `MergedConfigStore` resolves the singleton on every rebuild via `ResourceService.hasResource(platform/settings/global)` — present means the API blob's whole-object value populates `Config.globalInterceptors` / `retriableErrorCodes`; absent means the file-sourced fields project (or schema defaults if the file is silent). The runtime continues to consume these values for chat-completion routing, etc. *(Slice U.1, 2026-05-21: the per-entity API surface is now single-source — `GET /v1/settings/platform/global` returns the API blob if present, `404` otherwise. The file/default projection no longer surfaces on the per-entity URL; it lives on `/v1/admin/config/file/settings/global`. There is no separate "bootstrap" step that pre-creates the blob; runtime fallback to file/default continues unchanged, only the operator-facing API GET no longer surfaces the fallback chain.)*

Note on apps/toolsets: admin-managed applications and toolsets are stored in `public/applications/` and `public/toolsets/` via existing `ApplicationService`/`ToolSetService` — NOT via MergedConfigStore. See §6 for the entity storage strategy.

Note on files/prompts/conversations: admin-managed shared instances live in `public/files/`, `public/prompts/`, `public/conversations/` via the existing Resource API path (same pattern as apps/toolsets — see §6). User-owned files/prompts/conversations in user buckets are unchanged. Per [OQ-21](08-open-questions-and-references.md), these three types are first-class admin entities; per [OQ-33](08-open-questions-and-references.md), admin has no access to user-bucket instances.

**Bucket name rationale.** `platform/` is named for the *tier* it serves (the top-level scope, alongside future MT scopes — tenant, team, channel — per the in-flight MT conceptual design). It is deliberately *not* role-named (`admin/`) because the bucket is a storage partition, not a permission boundary — write access is gated by `ConfigAuthorizationService` (see [`04-security-and-audit.md`](04-security-and-audit.md) §1) based on caller role + bucket, regardless of URL path. The `EntityLocationStrategy.scope` value matches the bucket name (`PLATFORM_SCOPE = "platform"`); future MT adds sibling scope values `"tenants/{id}"`, `"teams/{id}"`, `"channels/{id}"` through a different strategy implementation — see OQ-22.

**Bucket-aware dispatch — `RESOURCE` controller handles both user and admin paths for `applications`/`toolsets`/`prompts`/`conversations`.** The existing `RouteTemplate.RESOURCE` regex already matches `applications`, `toolsets`, `prompts`, `conversations`. The new `RouteTemplate.CONFIG_RESOURCE` regex covers **only** the seven genuinely new admin-config types (`models`, `interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`) — it does **not** add `applications`/`toolsets`/`prompts`/`conversations` (which would create overlapping matches with `RESOURCE`). Admin writes to `public/applications/...`, `public/toolsets/...`, `public/prompts/...`, `public/conversations/...` therefore go through the **same `RESOURCE`-routed controllers** the user Resource API uses today. The distinction between admin and user authorization is **not at the routing layer** but inside the controller: when the parsed `bucket` is `"public"` and the verb is a write, the controller invokes `ConfigAuthorizationService.isAuthorized(ctx, type, name, "public", verb)` (which gates on the admin role per [`04-security-and-audit.md`](04-security-and-audit.md) §1.2); when the parsed bucket is an encrypted user bucket (`Uxxx...`), the controller falls back to the existing owner-check it has always run. The `files` controller (`RouteTemplate.FILES`) follows the same pattern. Net effect: routing is by URL shape, authz is by `(role, verb, type, bucket)` — `CONFIG_RESOURCE` exists only to give the seven new admin-only types a controller; `RESOURCE` carries the dual-mode admin/user dispatch for the four resource types that already had user-bucket lifecycle.

**URL namespace rationale.** Per-entity CRUD lives at `/v1/{type}/{bucket}/{name}` and per-bucket / per-item metadata listing lives at `/v1/metadata/{type}/{bucket}/{path}` — implemented as sibling `RouteTemplate.CONFIG_RESOURCE` and `RouteTemplate.CONFIG_RESOURCE_METADATA` entries for the new admin-config types (`models`, `interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`). The existing `RouteTemplate.RESOURCE` / `RESOURCE_METADATA` (`conversations`, `prompts`, `applications`, `toolsets`) and `RouteTemplate.FILES` / `FILES_METADATA` entries are unchanged — admin writes to `public/files/...`, `public/prompts/...`, and `public/conversations/...` flow through the existing files / resource controllers, with `ConfigAuthorizationService` consulted ahead of the controller's existing per-bucket logic. The new admin-config types are read/written and listed in the **same shape** as the existing Resource API — `PUT`-upsert with `If-None-Match: *` / `If-Match: <etag>` per [`03-api-reference.md`](03-api-reference.md) §3, folder metadata listing at the `/v1/metadata/...` sibling per §4. This is the full-alignment design: operators and SDK authors learn one wire shape, not two. The full admin entity-type set is `models, applications, toolsets, interceptors, roles, keys, routes, schemas, settings, files, prompts, conversations` — admin manages **shared** instances of `files`, `prompts`, and `conversations` in `public/` (icons, theme assets, default templates, curated examples) via the same URL pattern, see [OQ-21](08-open-questions-and-references.md). The bucket segment carries the scope (`public/` for user-facing, `platform/` for infrastructure, user buckets `Uxxx...` for personal resources); a single `ConfigAuthorizationService` dispatches authz from `(role, verb, type, bucket)`. Cross-entity operator endpoints — `apply`, `validate`, `export`, `audit` (Phase 7), `health/config` — keep the `/v1/admin/*` prefix because they don't fit the per-entity-CRUD shape (they span types, span buckets, or have no entity at all). The singleton settings resource sits at `/v1/settings/platform/global` rather than `/v1/admin/settings` so it follows the uniform `{type}/{bucket}/{name}` shape and naturally extends to future MT scopes. Admin has **no** read or write access to user buckets — that is locked by `ConfigAuthorizationService` and out of scope for this proposal, see [OQ-33](08-open-questions-and-references.md).

### 5.2 Why this split

**Models, applications, toolsets in `public/`** — these are deployments that appear in user-facing listing endpoints (`/openai/models`, `/openai/applications`, `/openai/toolsets`). Users select them in the chat UI. They are conceptually "published to everyone" — the same as a user-published application that went through the publication workflow, just without the approval step (because admins don't need approval). Putting admin-managed deployments alongside user-published ones means `DeploymentService.listDeployments()` already queries `public/` and gets both from the same source.

**ApplicationTypeSchemas in `public/`** — schemas are referenced by applications that users see. Clients query them via `GET /v1/application_type_schemas/schemas`. They belong with the resources they describe.

**Interceptors in `platform/`** — interceptors are NOT user-facing. Users don't select or interact with interceptors directly. They are middleware that admins attach to deployments. They don't appear in user-facing listings. They belong with infrastructure configuration.

**Roles, keys, routes in `platform/`** — these are pure infrastructure. Roles define rate limits. Keys are secrets (write-only, never exposed). Routes define internal proxy rules. Users never interact with any of these.

### 5.3 New Resource Types

| Enum entry | `ResourceTypes.of()` group | Bucket | URL segment (Configuration API) | Compressed | Cache TTL | User-facing? |
|---|---|---|---|:---:|---|:---:|
| `MODEL` | `models` | `public/` | `/v1/models/{bucket}/{name}` | Yes | 30 days | Yes |
| `APP_TYPE_SCHEMA` | `app_type_schemas` | `public/` | `/v1/schemas/{bucket}/{name}` | Yes | 30 days | Yes |
| `INTERCEPTOR` | `interceptors` | `platform/` | `/v1/interceptors/{bucket}/{name}` | Yes | 30 days | No |
| `ROLE` | `roles` | `platform/` | `/v1/roles/{bucket}/{name}` | Yes | 30 days | No |
| `PROJECT_KEY` | `project_keys` | `platform/` | `/v1/keys/{bucket}/{name}` | Yes | 30 days | No |
| `ROUTE` | `routes` | `platform/` | `/v1/routes/{bucket}/{name}` | Yes | 30 days | No |
| `GLOBAL_SETTINGS` | `settings` | `platform/` | `/v1/settings/platform/global` (singleton) | Yes | 30 days | No |

These are Redis *cache* TTLs (blob storage is the source of truth — §10). The 30-day value mirrors `APPLICATION`/`TOOL_SET`: the prior `Long.MAX_VALUE` sentinel is no longer usable since `ResourceService.getExpiration()` feeds the TTL straight into Redis `PEXPIRE`, which rejects `Long.MAX_VALUE` as an invalid expire time.

The three names that intentionally diverge — enum / blob group / URL segment — are documented above so implementers don't conflate them:
- `APP_TYPE_SCHEMA` (enum) → `app_type_schemas` (blob group, plural-snake-case to match `ResourceTypes` convention) → `schemas` (URL segment, short/idiomatic).
- `PROJECT_KEY` (enum, disambiguates from the existing `API_KEY_DATA` type used for runtime API-key auth records) → `project_keys` (blob group) → `keys` (URL segment, short/idiomatic).
- `GLOBAL_SETTINGS` (enum) → `settings` (blob group) → `settings` (URL segment).

**`ResourceTypes.of()` URL-segment alias rule.** The CONFIG_RESOURCE regex (see [`03-api-reference.md`](03-api-reference.md) §1) captures URL segments — `schemas`, `keys`, `settings` — directly from the request path. `ResourceTypes.of()` is keyed by the **blob group name** (`app_type_schemas`, `project_keys`, `settings`), so a naive `ResourceTypes.of("schemas")` from controller code would throw `IllegalArgumentException`. Phase 2 must either (a) extend `ResourceTypes.of()` with explicit alias `case "schemas" -> APP_TYPE_SCHEMA` and `case "keys" -> PROJECT_KEY` arms alongside the canonical group-name arms, or (b) require all controller code to translate URL segments to enum values via a dedicated `ResourceTypes.fromUrlSegment(String)` helper. Option (a) is the smaller change. Whichever approach is chosen, controllers building blob paths must use the enum's `group()` method (not the URL segment) so blob writes land at `public/app_type_schemas/...` and `platform/project_keys/...`, not `public/schemas/...` or `platform/keys/...`.

**Existing `/v1/application_type_schemas/...` controller — relationship to new `/v1/schemas/...`.** The current `RouteTemplate.APP_SCHEMAS` controller (`/v1/application_type_schemas/(schemas|schema|meta_schema)`) is a meta-endpoint that returns *the JSON Schema definitions used to validate application-type bodies* — it is **not** an entity CRUD route and remains unchanged. The new `/v1/schemas/{bucket}/{name}` route, by contrast, is per-entity CRUD over `APP_TYPE_SCHEMA` resources. The two endpoints coexist with no name overlap; the Phase 2 brief should explicitly call this out so reviewers don't read the new route as a replacement for the existing one.

Note: APPLICATION and TOOL_SET resource types already exist in `public/` — no new types needed for admin-managed apps/toolsets. The Configuration API writes to the same resource types that user-published apps already use. FILE, PROMPT, and CONVERSATION resource types also already exist and are reused as-is for admin-managed shared instances in `public/` (see §6 and [OQ-21](08-open-questions-and-references.md)).

**Implementation integration points (Phase 2 — compile-time blocker bundle).** These four changes are inseparable: any Phase 2 controller code that resolves `platform/`-prefixed URLs or constructs descriptors for the new types fails to compile or throws at runtime without all of them in place. Tracked as a single prerequisite item in [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 2 prerequisites.
- `ResourceTypes.java` `of(String group)` switch statement must be extended with the new group names — currently throws `IllegalArgumentException` for `"models"`, `"interceptors"`, `"roles"`, `"project_keys"`, `"routes"`, `"app_type_schemas"`, `"settings"` (none in today's switch). The new entries key on the **blob group name** (the `group` field on the enum), and `of()` must also accept the URL-segment aliases `"schemas"` → `APP_TYPE_SCHEMA` and `"keys"` → `PROJECT_KEY` so URL-segment-driven lookups resolve (see M7 below). Controllers building blob paths must use the enum's `group()` method (not the URL segment) to avoid confusion.
- `ResourceDescriptor.java` needs new constants: `PLATFORM_BUCKET = "platform"`, `PLATFORM_LOCATION = "platform/"`. Currently only `PUBLIC_BUCKET = "public"` / `PUBLIC_LOCATION = "public/"` exist. `ResourceDescriptor.isPublic()` must return `false` for the `platform` bucket (today's implementation already returns `false` for any bucket != `PUBLIC_BUCKET`, so verification-by-test is sufficient) — this is what correctly triggers `ConfigAuthorizationService` dispatch for `platform/` reads/writes.
- **`isPlatform()` helper + `isPrivate()` semantic correction.** Today `ResourceDescriptor.isPrivate()` is implemented as `!isPublic()`, which means any non-`public/` bucket (including the new `platform/`) returns `isPrivate() == true`. Throughout the existing codebase, `isPrivate()` is the gate for "encrypted user-bucket" behaviour driving owner-check authorization paths; without correction, every `isPrivate()` caller would misclassify `platform/` descriptors as user-owned and fall through to owner checks. Phase 2 must (a) add `boolean isPlatform()` returning `bucketLocation.equals(PLATFORM_LOCATION)` to `ResourceDescriptor`, and (b) change `isPrivate()` to `!isPublic() && !isPlatform()` so "private" continues to mean "user-owned encrypted bucket" only. Audit all `isPrivate()` call sites under `server/` to ensure no path that gates user-bucket-only behaviour incorrectly accepts a `platform/` descriptor; add a startup assertion or test coverage that exercises a `platform/` descriptor through every `isPrivate()`-gated branch.
- `ResourceDescriptorFactory.fromAnyUrl()` (which calls `fromUrl()`) must handle platform bucket URLs (e.g., `roles/platform/viewer`). Currently `fromUrl()` checks `bucket.equals(PUBLIC_BUCKET)` and otherwise tries `encryptionService.decrypt(bucket)` — passing `"platform"` throws because it's not a valid encrypted user-bucket name. Add an `else if (PLATFORM_BUCKET.equals(bucket))` branch before the encryption fallback that uses `PLATFORM_LOCATION` directly, mirroring the existing `PUBLIC_BUCKET` branch.
- **Distinguish URL segment from blob group on `ResourceType` — round-trip invariant.** `ResourceDescriptor.getUrl()`, `ResourceDescriptor.getDecodedUrl()`, and `ResourceDescriptor.getAbsoluteFilePath()` today derive their type segment from `type.group()`. With the URL-segment aliases introduced for `APP_TYPE_SCHEMA` (URL `schemas` ↔ blob group `app_type_schemas`) and `PROJECT_KEY` (URL `keys` ↔ blob group `project_keys`), `getUrl()` and `getDecodedUrl()` would both emit `"app_type_schemas/public/foo"` for a request that arrived as `/v1/schemas/public/foo` — diverging from the user-visible URL the caller used. Phase 2 must fix this by either (a) **adding a `urlSegment()` method on `ResourceType`** that defaults to `group()` for types where the two names coincide and returns `"schemas"` for `APP_TYPE_SCHEMA` / `"keys"` for `PROJECT_KEY`, then having `ResourceDescriptor.getUrl()` and `ResourceDescriptor.getDecodedUrl()` use `urlSegment()` while `getAbsoluteFilePath()` continues to use `group()`; or (b) **carrying the original URL segment on `ResourceDescriptor` itself** (set by `ResourceDescriptorFactory.fromUrl()` when parsing the request) and emitting it from both URL accessors. Option (a) is the smaller change and keeps the URL-segment information centralized on the enum. Either way, the user-visible canonical URL must match the request URL exactly across both accessors. Required round-trip tests: `ResourceDescriptorFactory.fromUrl("/v1/schemas/public/foo").getUrl() == "schemas/public/foo"` **and** `.getDecodedUrl() == "schemas/public/foo"` (and the same for `/v1/keys/platform/proxyKey1`); the corresponding blob path remains `public/app_type_schemas/foo` / `platform/project_keys/proxyKey1`. Without this fix, every API response that echoes a canonical ID for an `APP_TYPE_SCHEMA` or `PROJECT_KEY` entity would emit the blob group name instead of the URL segment the caller asked for.

### 5.4 Naming Collision Prevention

With the union model (§4), config-file entities use simple names and API-managed entities use canonical IDs — they never collide in the same map. Cross-type collisions are naturally eliminated by the path structure: `models/public/my-thing` and `applications/public/my-thing` are different identifiers. Within the same type, the Configuration API validates that the blob path does not already exist (via `If-None-Match: *` on create, or ETag on update).

**Publication workflow integration (Phase 3):** Extend `PublicationService.approvePublication()` to check proposed target names against all entries in the current merged Config (both simple-name and canonical-ID keys), rejecting publications that would create naming collisions.

> **Access control for these buckets — authorization design, admin role checks, `ConfigAuthorizationService` interface — is covered in [`04-security-and-audit.md`](04-security-and-audit.md).**

## 6. Entity Storage Strategy: What Goes Through MergedConfigStore

Not all entity types should go through MergedConfigStore. Applications and toolsets already have a working ResourceService-based CRUD and discovery path via `ApplicationService` and `ToolSetService`. Routing them through MergedConfigStore would cause **double-counting in listing controllers** — `ApplicationController.getApplications()` reads from BOTH `Config.applications` (map) AND `deploymentService.listDeployments()` (blob). If MergedConfigStore loaded admin apps into Config, those same apps would also appear in the blob listing.

**Storage strategy by entity type:**

| Entity | Has Config path? | Has ResourceService path? | Goes through MergedConfigStore? | Rationale |
|--------|:---:|:---:|:---:|---|
| **Models** | ✅ Config-only | ❌ No blob path today | ✅ Yes | Hot-path reads from `config.getModels()`. No blob listing path exists — `ModelController` reads exclusively from Config. |
| **Roles** | ✅ Config-only | ❌ No blob path today | ✅ Yes | `RateLimiter` iterates ALL roles on every request. Must be in-memory. |
| **Routes** | ✅ Config-only | ❌ No blob path today | ✅ Yes | `GlobalRouteController` iterates all routes in order on every unmatched request. |
| **Keys** | ✅ Config-only | ❌ No blob path today | ✅ Yes | `ApiKeyStore` needs in-memory HashMap for O(1) authentication. |
| **Interceptors** | ✅ Config-only | ❌ No blob path today | ✅ Yes | Interceptor chain resolution on every deployment request. |
| **AppTypeSchemas** | ✅ Config-only | ❌ No blob path today | ✅ Yes | Referenced by applications, queried by clients. |
| **Applications** | ✅ Config map | ✅ `ApplicationService` | ❌ **No** | Already discoverable via blob. Adding to Config would cause double-counting. |
| **Toolsets** | ✅ Config map | ✅ `ToolSetService` | ❌ **No** | Same as applications. |
| **Files** | ❌ No | ✅ Resource API | ❌ **No** | Admin manages shared assets in `public/files/`; user files in user buckets unchanged. Not on hot path; thin authz layer over existing path. |
| **Prompts** | ❌ No | ✅ Resource API | ❌ **No** | Admin manages shared/default templates in `public/prompts/`; user prompts in user buckets unchanged. Same pattern. |
| **Conversations** | ❌ No | ✅ Resource API | ❌ **No** | Admin manages curated/example conversations in `public/conversations/`; user conversations in user buckets unchanged. Same pattern. |

**How admin app/toolset/file/prompt/conversation writes work:**

The Configuration API for apps, toolsets, files, prompts, and conversations is a thin authorization layer over existing services. *(`AuditService.log(...)` step shown below activates only when Phase 7 audit ships; until then, the path is `authorize → service.put → blob`.)*

```
PUT /v1/applications/public/my-admin-app
  → ConfigAuthorizationService.isAuthorized(role=admin, verb=PUT, bucket=public)
  → ApplicationService.putApplication(descriptor, body) → ResourceService → blob
  → AuditService.log(PENDING → APPLIED)              # Phase 7

PUT /v1/files/public/icons/dial-logo.png
  → ConfigAuthorizationService.isAuthorized(role=admin, verb=PUT, bucket=public)
  → ResourceService.put(descriptor, body) → blob
  → AuditService.log(PENDING → APPLIED)              # Phase 7
```

**How model unification works (Phase 2):**

Admin-managed models stored in blob (`public/models/gpt-4`) are loaded by `MergedConfigStore` into `Config.models` — the same in-memory map that `ModelController.getModels()` and `DeploymentService.findDeployment()` already read from. The unification happens inside `MergedConfigStore`, not at the listing level — `ModelController` code is unchanged. There is no blob listing path for models (unlike applications which use `deploymentService.listDeployments()`), so no double-counting risk exists.

Config-file legacy apps/toolsets remain in `Config.applications` / `Config.toolsets` (unchanged). Admin API-created apps go directly to blob via `ApplicationService`. User-published apps go to blob via publication workflow. Each app exists in exactly one source — no deduplication needed.

**Summary architecture:**

```
MergedConfigStore (hot-path, in-memory Config volatile ref):
  └── models, roles, routes, keys, interceptors, schemas, globalSettings
      ├── Source 1: FileConfigStore (seed/backward compat)
      ├── Source 2: ResourceService blob (API-managed, platform/ and public/ buckets)
      └── Union: no key collision (simple names + canonical IDs coexist)

ApplicationService / ToolSetService (existing ResourceService path):
  └── applications, toolsets
      ├── Config-file apps/toolsets → Config.applications / Config.toolsets (unchanged)
      ├── Admin API apps/toolsets → ResourceService → public/ blob (new write path)
      ├── User-published apps/toolsets → ResourceService → public/ blob (unchanged)
      └── Private user apps/toolsets → ResourceService → user bucket (unchanged)
```

### 6.1 `ConfigAuthorizationService` insertion point in existing per-type controllers

Admin writes to `public/applications/...`, `public/toolsets/...`, `public/files/...`, `public/prompts/...`, `public/conversations/...` flow through the **same** controllers (`ApplicationController`, `ToolSetController`, the existing files / prompts / conversations controllers) that the user Resource API uses today (per §5.1 — `RouteTemplate.RESOURCE` and `RouteTemplate.FILES` are unchanged). The `ConfigAuthorizationService` "preflight" inserts at the top of each write-verb handler (`POST` / `PUT` / `DELETE`), branching on the parsed bucket before any existing user-bucket logic runs:

```java
// Top of each write handler in the per-type controller
if (descriptor.isPublic()) {
    configAuthorizationService.requireAuthorized(ctx, type, name, "public", operation);
} else if (descriptor.isPlatform()) {
    configAuthorizationService.requireAuthorized(ctx, type, name, "platform", operation);
} else {
    /* existing user-bucket owner-check, unchanged */
}
```

`requireAuthorized` is the throwing variant of `isAuthorized` (returns 403 on denial). For `public/`/`platform/` paths the existing user-bucket owner-check is bypassed entirely; for user buckets (`Uxxx...`) the controller falls through to the owner-check it has always run. This keeps the existing user-bucket lifecycle untouched and adds exactly one branch at the top of each write handler.

The publication approval path (`PublicationService.approvePublication`) already requires the admin role through a separate mechanism, so a Configuration-API admin write that lands a publication-target entity is gated once at the controller (`ConfigAuthorizationService`) and once at the service (`PublicationService`); these are two checks of the same role for two different operations and do not constitute double-gating of the same write. User writes to encrypted user buckets continue through the existing owner-check unchanged — the new branch is structurally a no-op on that path.

## 7. Why MergedConfigStore, Not Direct ResourceService for Everything?

An obvious alternative: skip ConfigStore entirely and load models, roles, routes, keys, and interceptors directly from ResourceService — the same way applications work today. This was analyzed and rejected for three specific reasons:

**1. Request hot-path latency.** Every chat completion request reads models, roles, routes, and interceptors via `context.getConfig()`. With Config in-memory: `HashMap.get()` ≈ 50ns, total config reads < 1μs, zero I/O. With ResourceService per-entity: Redis GET ≈ 0.5–2ms per entity. A single request touching model + roles + interceptors adds 5–15ms of Redis round-trips. At 1000+ RPS, this is 5–15 seconds of cumulative Redis I/O per second.

**2. RateLimiter and routes need ALL entities simultaneously.** `RateLimiter.getLimitByUser()` iterates the entire roles map on every request. `GlobalRouteController` iterates all routes in sorted order. Per-entity lazy loading from ResourceService would either require loading everything per request (negating caching) or building per-type in-process caches (reinventing Config with more moving parts).

**3. Blast radius.** 15+ source files read `context.getConfig()` on request hot paths (`RateLimiter`, `GlobalRouteController`, `DeploymentService`, `ModelController`, `DeploymentController`, `ApplicationController`, `UpstreamRouteProvider`, `AccessService`, `ApiKeyStore`, `ApplicationSchemaService`). MergedConfigStore changes one class; everything downstream keeps working with zero changes.

The Config volatile reference IS effectively a read-through in-process cache. MergedConfigStore unifies storage in ResourceService while preserving the zero-cost read path.

## 8. Implementation Edge Cases

Three serialization/validation issues identified from source code analysis that need handling during implementation:

**ApplicationTypeSchemas format difference.** The config file stores `applicationTypeSchemas` as a JSON array, deserialized via `JsonArrayToSchemaMapDeserializer` into `Map<String, String>` keyed by `$id`. When stored as individual blob resources in `public/app_type_schemas/`, each schema is a separate blob. **Per-blob format:** each schema blob is stored as a raw JSON string (the schema body serialized verbatim). `MergedConfigStore` reads the blob as a `String` and inserts it into `Config.applicationTypeSchemas` keyed by the `$id` field extracted by parsing the schema JSON — no Jackson entity class is involved at the blob level. The resulting in-memory `Map<String, String>` is identical to what `FileConfigStore` produces from the file-side array, so `ApplicationSchemaService` and the rest of the read path are unchanged.

**Route `Pattern` serialization — verification, not custom code.** `Route.java` uses `List<Pattern>` for path matching — `java.util.regex.Pattern` objects compiled at config load time. `jackson-databind` ships with native `Pattern` serialization support (built-in `PatternSerializer` / `PatternDeserializer` since the 2.x line), serializing the regex string in and out without any custom code. An earlier draft of this document called for a custom Jackson serializer/deserializer for this field; that requirement is **withdrawn** — the default codec already handles it. Phase 2 test plan must include a round-trip serialization unit test on `Route` to confirm the existing default codec produces a compact regex-string representation for blob storage and deserializes it back to a compiled `Pattern`. No custom serializer is required unless the round-trip test fails.

**`@CustomApplicationsConformToTypeSchemas` double validation.** `Config.java` has a class-level validation annotation (`@CustomApplicationsConformToTypeSchemas`) that checks all applications conform to their referenced schemas. When `MergedConfigStore` builds the merged Config, this validation runs on the combined set — including API-managed apps that were already validated on write. This is harmless (double validation, not incorrect behavior) but worth noting for performance if there are many schema-rich applications.

**Key/Upstream secret serialization (dual mapper applies to both).** Two existing fields are affected:

- `Key.key` — today carries only `@ToString.Exclude`; there is **no** `@JsonProperty(access = WRITE_ONLY)` on it, and the existing `Config.keys` map uses the secret value as the map key (see OQ-12 for the model fix). This proposal adds **three** annotations: `@JsonProperty(access = WRITE_ONLY)` (so the API response never echoes the secret), `@EncryptedField` (so the new `SecretFieldProcessor` encrypts on blob write), and the existing `@ToString.Exclude` is preserved. *(File-format clarification: in `aidial.config.json` today, the JSON object key IS the secret — Jackson deserializes `"<secretValue>": { "project": "...", "roles": [...] }`, and `addProjectKeys()` then calls `value.setKey(apiKey)` to copy the map key into `Key.key`. After Phase 2, API-managed keys reverse this convention: `Key.key` is the secret, set during creation and decrypted by `SecretFieldProcessor`, and the outer map key is the human-readable canonical name. The dual-format `addProjectKeys()` guard in §4 is what keeps both shapes working in the same `Config.keys` map. See OQ-12.)*
- `Upstream.key` — already carries `@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)` and `@ToString.Exclude` in the current codebase (`config/src/main/java/com/epam/aidial/core/config/Upstream.java`). This proposal adds **only** the new `@EncryptedField` marker; `WRITE_ONLY` and `@ToString.Exclude` are unchanged. Note the asymmetry: for `Upstream.key` the dual-mapper's `WRITE_ONLY` bypass is what makes encryption observable on blob (without it, the value is silently dropped); for `Key.key` the bypass is also necessary once Phase 2 adds `WRITE_ONLY` (before that addition, the bypass is a no-op).

**`Upstream.extraData` serialization — symmetric string round-trip required.** `extraData` already deserializes any JSON structure into a Java `String` via `@JsonDeserialize(JsonToStringDeserializer.class)`. After `SecretFieldProcessor` encryption the in-memory value is the literal Java `String` `"ENC[..."`. **Hard invariant:** on any blob-write path for entities containing `Upstream.extraData` (every `MergedConfigStore`-managed write), `SecretFieldProcessor` MUST run before the blob-I/O `ObjectMapper` serializes the entity — there is no code path that writes `Upstream.extraData` to blob without encryption. See [`04-security-and-audit.md`](04-security-and-audit.md) §2.2 for the corresponding Phase 2 test requirement. The blob-I/O `ObjectMapper` must serialize this AS a JSON string (not as a JSON object — there is no JSON structure to recover from the ciphertext, and the read-side deserializer expects a string anyway). **`Upstream` lives in the shared `config/` module, and its current default Jackson behaviour serializes `extraData` as a JSON object on every user-facing GET (`/v1/applications`, `/v1/toolsets`).** Adding a class-level `@JsonSerialize(using = ToStringSerializer.class)` to the field would change that user-facing API response shape from a JSON object to a quoted JSON string everywhere, breaking existing clients. Phase 2 therefore wires this via option (b): extend the blob-I/O `BeanSerializerModifier` documented below to additionally force string output for any property carrying `@EncryptedField` (equivalently, only on the blob-I/O `ObjectMapper` used by `MergedConfigStore`-managed entities). The `Upstream.java` class in the `config/` module stays unchanged so the user-facing API response shape is preserved. Option (a) — annotating the field directly — is rejected because it propagates to every `ObjectMapper` and would constitute a breaking change requiring versioned API migration. See [`04-security-and-audit.md`](04-security-and-audit.md) §2.4 for the operator-visibility consequence.

**Activation rule for the blob-I/O `BeanSerializerModifier` — `@EncryptedField` annotation alone, not `WRITE_ONLY`.** Two distinct serialization concerns are wired into the same `BeanSerializerModifier`, but they are gated by **different** predicates:
1. **Re-include past `WRITE_ONLY`** — for properties that carry both `@EncryptedField` and `@JsonProperty(access = WRITE_ONLY)` (today: `Upstream.key`, and `Key.key` once Phase 2 adds `WRITE_ONLY`), the modifier overrides the default `WRITE_ONLY` suppression so the encrypted value reaches the blob.
2. **Force string output (`ToStringSerializer`)** — for properties that carry `@EncryptedField` regardless of whether they also carry `WRITE_ONLY`. This branch must activate on annotation presence alone, because `Upstream.extraData` carries `@EncryptedField` but **not** `WRITE_ONLY` — gating string-output on `WRITE_ONLY` would skip `extraData` entirely and the blob would receive a JSON-object serialization of a Java `String` (Jackson's default behaviour for a typed `String` field is `ToString`, but with the `JsonToStringDeserializer` shape this field's effective serialization can be ambiguous — explicit `ToStringSerializer` removes any doubt).

Phase 2 implementation checklist item: blob-I/O `BeanSerializerModifier` — for any property carrying `@EncryptedField`, install `ToStringSerializer` so the in-memory `String` value (which after `SecretFieldProcessor` may be `"ENC[...]"`) emits as a JSON string literal — independent of whether the property also carries `@JsonProperty(access = WRITE_ONLY)`. Add a round-trip serialization test specifically for `Upstream.extraData` to the Phase 2 test plan: write an entity with `extraData = "{\"region\":\"us-east-1\"}"` through the blob-I/O mapper, read it back, and assert the in-memory `String` round-trips byte-for-byte (covering the no-`WRITE_ONLY` path).

**Dual-mapper scope — applies only to entities `MergedConfigStore` writes.** The blob-I/O `ObjectMapper` configured with the `WRITE_ONLY`-bypass for `@EncryptedField` properties is wired into the **new** `MergedConfigStore` write path only (the entities flowing through `MergedConfigStore` per §6 — models, schemas, interceptors, roles, project keys, routes, settings). `ApplicationService` and `ToolSetService` keep their existing Jackson configurations untouched: applications and toolsets never carry `@EncryptedField` on their persisted fields (`Upstream.key` and `Upstream.extraData` only become `@EncryptedField`-targeted when written via `MergedConfigStore` — and applications/toolsets are not routed through it per §6). Existing user Resource API responses for apps/toolsets continue to suppress `Upstream.key` via `WRITE_ONLY` exactly as today. This containment is what keeps "newly encrypted at rest" honest — only blob writes from the new admin-config controllers persist `ENC[...]` ciphertext for `Upstream.key`; the existing app/toolset blobs are unaffected.

The new `SecretFieldProcessor` encrypts both target fields on blob write and decrypts on rebuild (see [`04-security-and-audit.md`](04-security-and-audit.md)). The dual-mapper is two `ObjectMapper` configurations — one for blob I/O (includes secrets as encrypted `ENC[...]` strings via a Jackson `BeanSerializerModifier` that re-includes properties carrying `@EncryptedField` regardless of `WRITE_ONLY`), one for API responses (preserves the default `WRITE_ONLY` suppression and additionally masks `@EncryptedField` values it does see, e.g., during `?reveal_secrets=true` paths). The annotation and the dual-mapper plumbing are new code — not a reuse of existing serialization.

## 9. Name Resolution Rules

**Strict exact-match.** All name resolution in DIAL Core is exact `Map.get(key)` or `HashMap.get(key)` — there is no fuzzy matching, prefix stripping, or namespace-aware resolution anywhere in the codebase. The union model (§4) relies on this property: config-file entity `"gpt-4"` and API-managed entity `"models/public/gpt-4"` coexist in the same map because `Map.get("gpt-4")` never accidentally matches `"models/public/gpt-4"`.

**File-entity name sanitation (enforced in `ConfigPostProcessor`).** The union's safety depends on file-sourced and API-sourced keys never colliding. The API side already enforces per-segment `^[A-Za-z0-9._-]+$` (see [`03-api-reference.md`](03-api-reference.md) §1), so canonical IDs cannot contain arbitrary characters. To close the symmetric gap on the file side, `ConfigPostProcessor` rejects any file-sourced entity whose map key contains `/` (which would make the key look like a canonical ID and shadow an API entity). Rejection is per-entity, consistent with the skip-invalid policy (§4): the offending entry is logged as a warning and dropped; the rest of the file loads normally. This makes the "union, not shadow" invariant enforced by code, not just convention.

> **Breaking behavioural change.** Today's `FileConfigStore` accepts any map key including those with `/`. Phase 2 introduces this rejection rule, which silently drops slash-containing entries on the first reload. Operators must audit existing `aidial.config.json` files for slash-keyed entities before rolling out Phase 2 — see [`07-migration-and-rollout.md`](07-migration-and-rollout.md) Phase 2 prerequisites for the audit command.

**Resolution table — every code path that reads entity names:**

| Code Path | What It Reads | Lookup Method | Key Format |
|-----------|---------------|---------------|------------|
| `findDeployment(id)` | `Config.selectDeployment(id)` → `models/apps/toolsets/interceptors` maps | `map.get(id)` — exact match | Whatever the client sends: simple or canonical |
| `getInterceptors()` | `deployment.getInterceptors()` list → `Config.interceptors` map | `map.get(name)` per interceptor name | Whatever is in the deployment's interceptor list |
| `globalInterceptors` | `Config.globalInterceptors` list → `Config.interceptors` map | `map.get(name)` per name in list | Whatever is in globalInterceptors setting |
| `RateLimiter` | `role.getLimits().get(deploymentName)` | `map.get(name)` — exact match against `deployment.getName()` | Simple name for file entities, canonical for API entities |
| Rate limit counters | `{userBucket}/limits/{entityName}/tokens` | Blob path constructed from `deployment.getName()` | Persisted — if name changes, counters orphaned |
| Load balancer cache | `UpstreamRouteProvider` caches by deployment name | Cache key = `deployment.getName()` | Changes on name change |
| `ApplicationSchemaService` | `deployment.applicationTypeSchemaId` → `Config.applicationTypeSchemas` map | `map.get(schemaId)` | Schema `$id` URI — independent of deployment naming |

**Consequence:** Every cross-reference in the system uses the **exact name as it appears in the Config map**. There is no translation layer. When a config-file model `"gpt-4"` is referenced in `Role.limits`, the key must be literally `"gpt-4"`. When an API-managed model `"models/public/new-model"` is referenced, the key must be literally `"models/public/new-model"`.

**Cross-reference integrity — strict by default, opt-in soft.**

The Configuration API **rejects per-entity writes with `422 Unprocessable Entity` if any cross-reference cannot be resolved against the current live `Config`**. "No broken entities accepted" is the headline contract — an admin cannot create a model that names a not-yet-existing interceptor as an individual `PUT`. The 422 body uses the same `validationWarnings` shape as the per-entity-GET response (§4.3).

**Soft mode is opt-in via static setting `config.write.softValidation: true` (default `false`).** When `true`, unresolved cross-references on per-entity writes degrade to **warnings** — the write succeeds and the dangling reference surfaces through the per-entity-GET `status: "invalid"` + `validationWarnings` channel (§4.3), the `/v1/admin/health/config` aggregator, and the cluster-wide skip-and-continue path (§4.1). Soft mode exists because the union model (§4 / OQ-5 / §10) supports gradual file→API migration where references temporarily dangle during cutover (a role's `limits` map still keyed by `"gpt-4"` while the API model is `"models/public/gpt-4"`); operators who want that workflow opt into soft. The today-shape behavior — file-side removal of an interceptor leaving blob applications dangling without Core noticing (§4.2) — is preserved under soft mode and tightened under strict default.

**`PublicationService`-style review/approval is not introduced** for admin-managed entities. That workflow exists because user-is-requester / admin-is-gatekeeper. For admin-managed entities, the admin is already the gatekeeper (OQ-14); adding approval just means admin-approves-admin.

**Working with strict default — three patterns:**

1. `POST /v1/admin/validate` returns warnings without mutating — useful for dry-run before a strict write.
2. `POST /v1/admin/apply` evaluates references against the **proposed-config state** (virtual Config including not-yet-applied entities from the same batch) so within-batch references always resolve. Two orthogonal flags govern apply behavior: `precheck: true | false` (per-call, default `true`) controls *batch atomicity* — under `true`, the server pre-validates the whole batch and aborts on any error before any mutation, regardless of `softValidation`. `softValidation` (server-wide setting from §9) controls *per-entity acceptance during application* — under `false`, per-entity validation failures during apply become per-entity `FAILED`; under `true`, they land in blob as `status: "invalid"` instead of being rejected. The two flags compose orthogonally — operators in soft mode can still pass `precheck: true` to request fail-fast atomicity for a specific batch. See [`03-api-reference.md`](03-api-reference.md) §7 for the full four-cell matrix.
3. CLI `dial-cli apply --strict` treats warnings as blocking errors (CLI-side). Redundant under server-side strict default, but still useful when the operator wants per-manifest local validation before sending.

**Migration impact.** Operators doing file→API cutover under strict default cannot do step-by-step individual writes when references span the file/API boundary. Two supported workflows: (a) use `dial-cli apply` to land related entities in one batch (server-side proposed-config validation resolves within-batch references — see [`03-api-reference.md`](03-api-reference.md) §7); (b) temporarily flip `config.write.softValidation: true` for the migration window and back to `false` afterwards. The union semantics in §10 apply under both modes.

```json
{
  "valid": true,
  "warnings": [
    {
      "entityId": "models/public/new-model",
      "field": "interceptors[0]",
      "message": "Interceptor 'content-filter' not found in current config. Ensure it exists before this model receives traffic.",
      "severity": "WARNING"
    }
  ]
}
```

The CLI `--strict` flag treats warnings as blocking errors: `dial-cli apply -f config/ --strict` fails if any unresolved references exist.

## 10. Migration Implications: File → API Entity Cutover

When a config-file entity is migrated to API management, its name changes from simple (`"gpt-4"`) to canonical (`"models/public/gpt-4"`). The strict exact-match rule means every reference must update. What breaks:

| Reference Type | Before | After | Action Required |
|----------------|--------|-------|-----------------|
| Client chat completion URL | `/openai/deployments/gpt-4/chat/completions` | `/openai/deployments/models/public/gpt-4/chat/completions` | Update all clients, SDKs, integrations |
| Rate limit keys in roles | `"limits": { "gpt-4": {...} }` | `"limits": { "models/public/gpt-4": {...} }` | Update all role definitions |
| Interceptor chains | `"interceptors": ["my-interceptor"]` | `"interceptors": ["interceptors/platform/my-interceptor"]` | Update all deployments referencing the interceptor |
| `globalInterceptors` list | `["my-interceptor"]` | `["interceptors/platform/my-interceptor"]` | Update globalSettings |
| Rate limit counters | `{bucket}/limits/gpt-4/tokens` | `{bucket}/limits/models%2Fpublic%2Fgpt-4/tokens` | Counters reset (old ones orphaned) |
| Load balancer state | Cached under `"gpt-4"` | Cached under `"models/public/gpt-4"` | Auto-refreshes on next rebuild |

**Note:** Canonical-ID URLs (`/openai/deployments/models/public/gpt-4/chat/completions`) are already the established pattern for Resource API apps — `(?<id>.+?)` regex in `RouteTemplate.POST_DEPLOYMENT` captures multi-segment paths. This is not a new URL format for DIAL.

This migration is **gradual, not big-bang**. Both the file version and API version coexist during the transition. References migrate incrementally — some roles may still point to `"gpt-4"` while others already reference `"models/public/gpt-4"`. The `dial-cli apply --strict` workflow validates that all references resolve, helping operators track migration progress. *(The intended `dial-cli export` companion that produces a manifest of the current runtime state is deferred from MVP — see [IMPLEMENTATION.md §5.5 Defer.1](IMPLEMENTATION.md); operators consult `aidial.config.json` directly during MVP.)* The config-file entry is removed only after all downstream references have been updated.

**Migration under strict-validation default.** Per [§9](#9-name-resolution-rules), `config.write.softValidation: false` is the default — per-entity writes that span the file→API boundary will be rejected if their cross-references dangle. Two supported workflows: (a) **batched apply** — land related entities (the new model + the role updates pointing at its canonical ID + any interceptor migrations) in one `dial-cli apply` invocation; server-side proposed-config validation resolves within-batch references and the migration step lands cleanly under strict default. (b) **soft-mode window** — temporarily set `config.write.softValidation: true` for the migration window, do the per-entity moves with dangling references tolerated, and flip back to `false` afterwards (the lingering invalid entries surface on per-entity-GET as `status: "invalid"` and on the `/v1/admin/health/config` aggregator so the operator can track outstanding references). Both workflows preserve the union semantics in this section.

### 10.1 Why coexistence, not big-bang migration

The reasonable alternative to the union model is a one-time migration: at some flag day, every file-sourced entity becomes API-managed, every reference is updated, and `aidial.config.json` is retired. We rejected this in favor of indefinite coexistence for four reasons, in order of weight:

**The migration is customer-side, not vendor-side.** Customers own their `aidial.config.json` — it lives in their Helm values, KeyVault mounts, Admin Backend exports, and Git repos. We do not ship one config file to one location; we ship a Core that reads whatever the operator points it at. A "one-time migration" therefore plays out as one event *per customer*, each requiring its own coordinated maintenance window. The union model lets each customer migrate at their own pace. A forced cutover would require us to schedule downtime across every deployment we have visibility into and many we don't.

**The change is customer-visible at the URL layer.** File→API cutover changes the chat-completion URL pattern (§10 table — `/openai/deployments/gpt-4/...` becomes `/openai/deployments/models/public/gpt-4/...`). Every chat client, SDK consumer, and third-party integration referencing the simple ID has to update simultaneously. Big-bang means a synchronized SDK release across an ecosystem we don't control.

**Rate-limit counters reset on rename.** Counters are persisted on blob keyed by `deployment.getName()` (§10 table — `{bucket}/limits/gpt-4/tokens` becomes `…/models%2Fpublic%2Fgpt-4/tokens`). Big-bang resets every customer's rate-limit budgets simultaneously. Gradual lets each customer absorb the reset entity-by-entity at a time of their choosing.

**Backward compatibility is requirement R4, not a preference.** R4 ([`07-migration-and-rollout.md`](07-migration-and-rollout.md) §1) — *"Config-file approach continues to work during transition. File-defined entities appear alongside API-managed ones in the unified API."* A one-time migration violates R4 by definition. Reopening that requirement is a separate conversation about what we're willing to break for customers, and it should not happen implicitly through migration mechanics.

The cost the one-time alternative tries to buy down — permanent dual code paths — is real but small: only a thin union *read* path in `MergedConfigStore` (§4, §7). New writes always go through the API, so there is no permanent dual *write* path. Phase 6 ([`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 6) makes config-file deprecation possible per environment, but optional and operator-driven, not a forced flag day.

## 11. Storage Backend Decision: Use Existing ResourceService

### Decision

Use existing ResourceService (Redis + Blob). **Don't introduce new infrastructure, extend what already works.**

### Why this is the right choice

| Factor | Assessment |
|--------|------------|
| **Scale** | Hundreds to low-thousands of config entities. Trivial for ResourceService which handles user resources at scale. |
| **Read pattern** | Read-heavy. Config reads remain from in-memory `volatile Config` ref (O(1) map lookup) — exactly as today. ResourceService is the durable backing store, not the hot read path. |
| **Write pattern** | Write-rare (dozens/day). ResourceService handles this trivially with distributed locking and ETag concurrency. |
| **Cross-replica** | Redis cache is shared. Polling provides eventual consistency (60s). Pub/sub (Phase 1.5) provides near-instant. |
| **Durability** | Blob storage is the source of truth. Survives Redis loss. |
| **Audit** *(Phase 7 — deferred)* | ResourceService events (CREATE/UPDATE/DELETE) will provide the foundation for the Phase 7 audit subsystem; not delivered in Phases 1–6. |
| **New dependencies** | Zero. Blob + Redis already deployed in every DIAL environment. |
| **Proven patterns** | APPLICATION and TOOL_SET already use long-lived (30-day cache-TTL) ResourceService storage in `public/` bucket. Admin-managed models join them there. |

### What we don't need

- **Database** (PostgreSQL) — overkill for config. Adds operational burden. DIAL Admin Backend uses a database, but that's its concern, not DIAL Core's.
- **New event bus** — ResourceService pub/sub events already exist.
- **etcd or distributed consensus** — Config writes are infrequent and serialized by distributed locks. No need for multi-master replication.

### Cross-replica propagation strategy (phased)

**Phase 1 (MVP):** Writer pod updates its own `volatile Config` ref immediately. Other replicas pick up changes on next `FileConfigStore` poll (up to 60s). This is already a major improvement over the Admin Backend file export chain.

**Phase 1.5 (fast follow):** Add Redis pub/sub notification on config write so other replicas rebuild within ~debounce-window milliseconds rather than waiting for the next 60s poll. The detailed topic protocol, subscription lifecycle, debounce mechanics, and failure-mode behavior are in §11.1 immediately below. The high-level rationale:

- **Pub/sub is a latency optimization on top of polling, not a replacement for it.** Redis pub/sub has weak delivery guarantees (fire-and-forget, no per-subscriber persistence, no retry, in-memory only) — a missed message is silently dropped. Polling is the correctness primitive; pub/sub only narrows the propagation window when delivery succeeds. This trade-off is explicitly chosen — adding a stronger transport (Redis Streams consumer groups, a real broker) would land a heavier dependency for a latency win on a write-rare path.
- Polling interval is **kept at 60s** as the safety-net SLA — pub/sub does not relax it. (Earlier drafts suggested 300s; reverted because polling is the correctness primitive and lowering it widens the worst-case lag when pub/sub silently drops.)
- Risk profile: pub/sub failure degrades to polling behavior — never breaks correctness, only widens the propagation window from "≤ debounce" back to "≤ 60s". Document this contract for operators rather than treating pub/sub as best-effort-but-usually-fine.

### 11.1 Pub/sub mechanics — reuse the existing `ResourceTopic`

This subsection specifies how the cross-replica synchronization actually works at the implementation level. The §4 "Rebuild trigger" and "Rebuild serialization" paragraphs describe the in-pod coalescing behavior; this subsection specifies the cross-pod transport that feeds those triggers.

**Reuse, don't add.** Every admin-config entity flows through `ResourceService` (per §11's storage decision). `ResourceService.put` / `ResourceService.delete` already publish a `ResourceEvent` on the existing `ResourceTopic` for cache-invalidation. Every replica's `ResourceService` is already subscribed to that topic via the existing per-resource subscription path. **Phase 1.5 just adds one cross-cutting listener** to that same topic — no new `RTopic`, no new event class, no new publish call in the write path. An earlier draft introduced a `dial:config:changed` topic + a `ConfigChangeEvent` record; both have been removed in favor of consuming what `ResourceService` already emits.

**`ResourceEvent` payload shape — `senderPodId` is a NEW field on the existing record.** The existing record carries `{url, action, timestamp, etag}` where `url` is the canonical resource URL (`<type>/<bucket>/<name>`, e.g. `models/public/gpt-4`) and `action ∈ {CREATE, UPDATE, DELETE}`. Phase 1.5 **adds a new nullable field `senderPodId` to `ResourceEvent`** — annotated `@JsonInclude(NON_NULL)` so events emitted before the supplier wires up (boot edge case) and rolling-upgrade serialization round-trip safely (see "Self-event filter" below for the full contract). The field does not exist on the current record. The `(type, bucket)` pair the listener filters on is derived from `url` via `ResourceDescriptorFactory.fromAnyUrl(event.getUrl())` — no payload-shape change beyond the new `senderPodId` field.

**`ResourceTopic.subscribeAll()` — NEW broadcast-listener API to be added.** This method **does not exist today** — `ResourceTopic`'s only subscribe API is `subscribe(Collection<ResourceDescriptor>, Consumer<ResourceEvent>)` which requires explicit per-URL pre-registration. The current `handle(event)` implementation iterates `urlToSubscriptions.getOrDefault(event.getUrl(), Set.of())` only — events for URLs that were never pre-registered are **silently discarded**, and there is no global-subscriber path. `MergedConfigStore` cannot enumerate every config-resource URL in advance (entities are added/removed at runtime), and listening on the underlying Redisson `RTopic` directly would couple `MergedConfigStore` to private state of `ResourceTopic`. Phase 1.5 therefore **adds a new public method** to `ResourceTopic`:

```java
// NEW method to be added in storage/.../service/ResourceTopic.java
public Subscription subscribeAll(Consumer<ResourceEvent> subscriber) { ... }
```

Implementation changes to `ResourceTopic` (all NEW):
- Add a new field `CopyOnWriteArrayList<Consumer<ResourceEvent>> globalSubscribers` next to the existing `urlToSubscriptions` map. `CopyOnWriteArrayList` is chosen so iteration during `handle()` does not need to lock against concurrent `subscribeAll` / `unsubscribe` from other threads — the listener thread reads a stable snapshot.
- Modify `handle(event)` to invoke the existing URL-keyed dispatch first (unchanged behavior for current per-URL subscribers), then iterate `globalSubscribers` and invoke each. Order: per-URL subscribers first, global subscribers second — keeps existing cache-invalidation semantics observable before `MergedConfigStore`'s rebuild trigger fires.
- `subscribeAll(consumer)` returns a `Subscription` whose `close()` removes the consumer from `globalSubscribers`.

Thread-safety note: `subscribeAll` may be called from the `server` boot path while `handle()` is concurrently dispatching events from the Redisson listener thread; the `CopyOnWriteArrayList` covers this without explicit synchronization. The change is small (one field + one loop in `handle` + one method) and additive — existing per-URL subscribers are untouched.

**Subscriber callback.** `MergedConfigStore` registers a `subscribeAll` listener at boot and forwards events to its rebuild executor (full snippet — including the self-event filter described below — appears under "Self-event filter"):

```java
resourceTopic.subscribeAll(event -> {
    if (thisPodId.equals(event.getSenderPodId())) return;        // self-event filter — see below
    ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(event.getUrl(), encryption);
    if (!isMergedConfigType(descriptor.getType(), descriptor.getBucketName())) return;
    mergedConfigStore.requestRebuild();   // debounced; coalesces with concurrent triggers
});
```

`isMergedConfigType` is a static filter — the resource types that flow through `MergedConfigStore` are fixed by §6's storage-strategy table. It returns `true` for:

| Bucket | Resource types |
|---|---|
| `public/` | `MODEL`, `APP_TYPE_SCHEMA` |
| `platform/` | `INTERCEPTOR`, `ROLE`, `PROJECT_KEY`, `ROUTE`, `GLOBAL_SETTINGS` |

It returns `false` for everything else — `APPLICATION`, `TOOL_SET`, `FILE`, `PROMPT`, `CONVERSATION`, user-bucket writes, publications, etc. Those entities don't flow through `MergedConfigStore` (per §6 — they're served via the existing `ApplicationService` / `ToolSetService` / Resource API path), so a `ResourceTopic` event for them does not require a rebuild. `fromAnyUrl` is the same parser the rest of DIAL uses for incoming resource URLs; failure to parse (malformed URL) logs a warning and skips the event. The filter is a constant-time lookup; cost is negligible compared to the existing cache-invalidation work the same listener thread already does.

**Phase ordering — Phase 1.5 depends on Phase 2's `ResourceTypes` + `ResourceDescriptorFactory` work.** `fromAnyUrl(event.getUrl(), ...)` invokes `ResourceTypes.of(group)` internally; the new enum entries (`MODEL`, `APP_TYPE_SCHEMA`, `INTERCEPTOR`, `ROLE`, `PROJECT_KEY`, `ROUTE`, `GLOBAL_SETTINGS`) and the `platform/` bucket branch in `ResourceDescriptorFactory.fromUrl()` (see [`07-migration-and-rollout.md`](07-migration-and-rollout.md) Phase 2 prerequisites) must land before the Phase 1.5 listener can parse events for the new types. This is consistent with the phase positioning ("Phase 1.5 ships concurrently with or immediately after Phase 2") — calling out the concrete code dependency so the implementation order is unambiguous.

**`requestRebuild()` and `rebuildNow()` — two coalescing entry points.** Both are new code introduced by this proposal in `MergedConfigStore` (not inherited from `FileConfigStore`, whose `vertx.setPeriodic` directly calls its own `load(false)`). `requestRebuild()` is the debounced asynchronous queue used by the 60s safety-net poll timer, the `FileConfigStore` reload callback, and the Phase 1.5 pub/sub listener — all three sources share one queue per §4 ("Rebuild serialization") and a single rebuild is coalesced per debounce window. `rebuildNow()` is the synchronous entry point used by the API write path on the writer pod; it bypasses the debounce, returns only after the rebuild completes and the volatile-`Config` swap is visible, and is what backs the "immediate on writer pod" guarantee. `rebuildNow()` still serializes against any rebuild already running (the same `rebuildInProgress` CAS guard documented in "Vert.x threading model" below); concurrent debounced triggers that arrive during a `rebuildNow()` execution mark `rebuildPending` and run in the next debounce window after `rebuildNow()` returns.

**Debounce window — trailing-edge.** `requestRebuild()` implements a 500ms trailing-edge debounce: each request resets a 500ms timer; when the timer fires without further requests, exactly one rebuild runs. A burst of 100 events from `dial-cli apply` collapses into one rebuild ~500ms after the last event. Implementation: a single `AtomicReference<ScheduledFuture>` per pod — no Redis primitive, no shared state.

**Vert.x threading model.** `requestRebuild()` is called from three contexts: (a) the `ResourceService` worker thread (API write path), (b) the Redisson listener thread (Phase 1.5 pub/sub callback), and (c) the Vert.x event-loop thread that fires the `setPeriodic` 60s safety-net timer. The actual rebuild — re-reading entities from `ResourceService` and decrypting `@EncryptedField` values (~1ms × N fields per §2.9 of `04-security-and-audit.md`; ~100ms for 50 entities × 2 fields) — is blocking I/O and **must not run on the event loop**. Locked threading model: `requestRebuild()` itself is non-blocking (atomic state mutation only — sets a `volatile boolean rebuildPending = true`, schedules or resets the debounce timer via `vertx.setTimer`); when the debounce fires, the rebuild work is dispatched via `vertx.executeBlocking(promise -> { /* rebuild */; promise.complete(); }, false)` (the `false` is the "ordered" flag — rebuilds may run in parallel from the executor's POV, but the `rebuildInProgress` `AtomicBoolean` CAS guard ensures only one runs at a time). Concurrency is thus: (i) `AtomicBoolean rebuildInProgress.compareAndSet(false, true)` before starting; (ii) on completion, `compareAndSet(true, false)` and check `rebuildPending` for any trigger that arrived during the rebuild — if set, schedule another debounce cycle. Two CAS atomics + one `volatile` flag + one debounce timer; no `synchronized` blocks, no thread-affinity assumptions.

**Self-event filter — `senderPodId` on `ResourceEvent`.** The publisher pod also receives its own `ResourceEvent` (Redis broadcasts to all subscribers). Its local volatile-`Config` was already refreshed in the write path, so the duplicate rebuild from receiving its own event is wasted work. Phase 1.5 filters self-events by extending `ResourceEvent` with one new nullable field, `senderPodId`. Each pod compares incoming events against `thisPodId` and skips its own:

```java
private final String thisPodId = UUID.randomUUID().toString();   // pod-local, set at boot

resourceTopic.subscribeAll(event -> {
    if (thisPodId.equals(event.getSenderPodId())) return;          // skip self-event
    ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(event.getUrl(), encryption);
    if (!isMergedConfigType(descriptor.getType(), descriptor.getBucketName())) return;
    mergedConfigStore.requestRebuild();
});
```

The field is added to `ResourceEvent` (small extension to the existing Lombok `@Data` class in the `storage` module — no new event class). The pod identity itself is generated in the `server` module (`MergedConfigStore` or its container) and supplied to `ResourceService` at construction time as a `Supplier<String> senderPodIdSupplier` (or a `String senderPodId` constructor parameter) — `ResourceService.publishEvent()` invokes the supplier and stamps the value on every published `ResourceEvent`. This keeps the `storage` module free of any pod-identity concept; `ResourceService` only sees an opaque string. Consumers of `ResourceTopic` that don't care about origin (existing per-URL cache-invalidation subscribers) ignore the field — `ResourceEvent` already carries `@JsonInclude(NON_NULL)`, so events serialized with `senderPodId == null` (boot ordering edge case) round-trip safely. **Rolling-upgrade safety.** Pre-Phase-1.5 replicas may receive events from already-upgraded pods carrying the new `senderPodId` field. The class-level `@JsonIgnoreProperties(ignoreUnknown = true)` annotation alone is **insufficient** here because `ResourceTopic.java` constructs the codec via `redis.getTopic(topicKey, new TypedJsonJacksonCodec(ResourceEvent.class))` — Redisson's `TypedJsonJacksonCodec` default constructor builds its own internal `ObjectMapper` and does **not** introspect application-side annotations on the deserialization path, so the annotation can be silently ignored and a pre-1.5 replica would still throw `UnrecognizedPropertyException` on any incoming event carrying `senderPodId` (every `ResourceTopic` subscriber, not just the config-rebuild listener — every cache-invalidation consumer uses the same codec). The fix is at the codec level: switch the `ResourceTopic` codec to a `TypedJsonJacksonCodec` constructed from a shared `ObjectMapper` configured with `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false` (and emitting `JsonInclude.NON_NULL` on output). This must ship as a standalone PR ahead of the Phase 1.5 listener-and-filter work — see [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 1.5 prerequisites. Test requirement: an integration test where a subscriber using the unmodified default codec successfully receives an event carrying `senderPodId` without exception. **Constructor wiring.** `ResourceTopic`'s only constructor today takes `(RedissonClient, String topicKey)` and builds the codec internally — there is no injection seam. Phase 1.5 prerequisites add a new `ResourceTopic(RedissonClient, String, ObjectMapper)` constructor in the `storage` module; the legacy `(RedissonClient, String)` constructor delegates to the new one with a default `ObjectMapper` configured for unknown-field tolerance + `JsonInclude.NON_NULL`. The `server` module wires the application's existing `ObjectMapper` into `ResourceTopic` at construction time so the same configuration the rest of DIAL Core uses is reused for `ResourceEvent`. **Paired call-site change — without it the codec swap is a no-op.** `ResourceService.java` (line ~143–144) today constructs the topic via `this.topic = new ResourceTopic(redis, "resource:" + ...)` — the no-mapper constructor. Phase 1.5 prerequisites must also update `ResourceService`'s constructor to accept the application's shared `ObjectMapper` (or to construct one with the safe defaults) and pass it into the new `ResourceTopic(RedissonClient, String, ObjectMapper)` constructor; without this call-site change the cache-invalidation path keeps the default codec and the swap has no effect. Wiring detail: inject `ObjectMapper` into `ResourceService` via DI / explicit pass-through, or extract `ResourceTopic` construction into a factory invoked by both the service and any future direct subscribers. Storage module unit test: construct `ResourceTopic` via the default constructor and confirm the codec ignores unknown fields without exception. Server-module unit test: verify `ResourceService.getTopic()` ignores unknown fields via the codec. The filter saves one rebuild (~50–100 ms) on the writer pod per local write — on a write-rare workload this isn't a hot-path concern, but the alternative ("accept the duplicate") was rejected because the duplicate rebuild is observable as redundant work in profiling and the filter cost is one field + one equality check.

**Subscription lifecycle.** Reuses `ResourceTopic`'s existing lifecycle — DIAL Core today already subscribes the underlying Redisson `RTopic` at boot, fails-loudly if Redis is unreachable, relies on Redisson auto-reconnect, and releases the listener on shutdown. Calling `subscribeAll` adds one global subscriber to that already-running topic listener, so it inherits all of the above for free; `MergedConfigStore` releases the returned `Subscription` on shutdown.

**Ordering semantics.** Redis pub/sub does **not** guarantee ordering across publishers. With multiple writer pods, two events for the same entity can arrive in either order on a given subscriber. Correctness is preserved because:
- Each event triggers a rebuild that re-reads blob (the source of truth) — pub/sub is a *signal* that something changed, not the change itself.
- Blob writes are serialized per-entity by `LockService`'s distributed lock (see §2 — "Distributed locking via `LockService`"); admin writes and the full-rebuild blob scans are additionally serialized cluster-wide by the `ADMIN_BUCKET_LOCATIONS` bucket locks (§4.4), so even cross-entity admin batches commit consistently across replicas and no full rebuild scans partial-batch state. (The per-event replica fast path is best-effort and self-heals via its `requestRebuild()` fallback rather than holding the lock.)
- Within one replica, the rebuild executor serializes execution per §4, so the rebuild always observes blob in a consistent post-write state.

Worst case from out-of-order events: "a redundant rebuild" — the same `Config` constructed twice — never an inconsistent observation.

**Interaction with the `MergedConfigStore` rebuild-serialization layer (§4).** The two layers compose orthogonally:

| Layer | Concern | Mechanism |
|---|---|---|
| Cross-pod (this section) | Notifying *other* replicas a change happened | `ResourceTopic` listener (existing topic) |
| In-pod (§4) | Coalescing concurrent rebuild triggers (poll + API + pub/sub) into one serialized rebuild | Single rebuild executor + "rebuild needed" flag |

The pub/sub callback hands work to the in-pod rebuild executor and returns; it does not bypass or pre-empt any other rebuild. If a rebuild is already running when a pub/sub message arrives, the in-pod layer marks "rebuild needed" and runs another rebuild after the current one completes — same behavior as if the trigger had come from the local poll timer.

**Observability — out of scope for this proposal.** No new Prometheus metrics or dashboards are introduced for cross-replica pub/sub in Phase 1.5. Operators rely on existing DIAL Core / Redis / `ResourceService` instrumentation; the polling fallback (60s) bounds worst-case staleness regardless of pub/sub delivery, so silent-drop scenarios self-recover within the polling SLA without operator intervention. Adding dedicated metrics is a follow-up if operator feedback after rollout shows the polling SLA is insufficient as the only signal.

**Why this is the right shape — KISS check.** The earlier draft carried a separate topic, a custom event record, and a parallel publish call. None of those bought anything Phase 1.5 actually consumes:

| Earlier design | Why it was dropped |
|---|---|
| Separate `dial:config:changed` `RTopic` | Admin config goes through `ResourceService`, which already publishes on `ResourceTopic`. Same Redisson client, same broadcast, every replica already subscribed. |
| Custom `ConfigChangeEvent` record (`entityType`, `entityId`, `bucket`, `operation`, `senderPodId`, `publishedAtMs`) | Phase 1.5 does a full rebuild regardless of payload. The granularity was payload for the deferred OQ-32 path; the existing `ResourceEvent` already carries `{type, bucket, name, action}`, which is enough for OQ-32 if/when it lands. |
| `topic.publishAsync` in the per-entity write path | Redundant with `ResourceService.put`'s existing publish. |

The one piece *kept* — extending `ResourceEvent` with `senderPodId` for the self-event filter — is a small field addition on an existing record, not a new event class. It's the smallest plumbing that avoids the writer-pod's redundant rebuild on every local write.

Phase 1.5 is the `subscribeAll` listener block above, the static filter, the `senderPodId` + `@JsonIgnoreProperties` extensions on `ResourceEvent`, the new `ResourceTopic.subscribeAll` method, and the trailing-edge debounce. About 25 lines.

**What is *not* in Phase 1.5 — partial-update optimization.** The subscriber-side action is unconditionally a full `MergedConfigStore.rebuildFromResources()` rebuild, even though the existing `ResourceEvent` carries enough information (`type`, `bucket`, `name`, `action`) for surgical per-entity update. See the "Partial-update optimization (deferred)" paragraph immediately below and [OQ-32](08-open-questions-and-references.md) for the cost-budget threshold that would re-open this.

**Partial-update optimization (deferred — see [OQ-32](08-open-questions-and-references.md)).** Phase 1.5 keeps full `rebuildFromResources()` on every received `ResourceEvent`, debounced to one rebuild per 500ms window. The existing `ResourceEvent` payload (`{type, bucket, name, action}` — what `ResourceService` already publishes for cache-invalidation) already carries the granularity needed for surgical update — replace/insert/delete one entry in `Config`, rerun targeted post-processing (route resort iff a route changed; `ApiKeyStore` `put`/`remove` iff a key changed; cross-ref revalidation only for the changed entity + its referrers), volatile swap. Cost ceiling for the full-rebuild path: entity count × `@EncryptedField` decrypt cost (~1ms/field, [`04-security-and-audit.md`](04-security-and-audit.md) §2.9) × debounced rate. For Phase 1.5 workloads (write-rare, dozens/day), full rebuild is acceptable. Tracked as OQ-32 for re-evaluation if entity counts or write rates rise — the hard part is transitive cross-ref invalidation, not the partial swap itself.

---

## Next

- API surface: [`03-api-reference.md`](03-api-reference.md)
- Security / authorization / secrets / audit: [`04-security-and-audit.md`](04-security-and-audit.md)
- CLI design (how the API is consumed): [`05-cli-design.md`](05-cli-design.md)
- Implementation plan: [`07-migration-and-rollout.md`](07-migration-and-rollout.md)
