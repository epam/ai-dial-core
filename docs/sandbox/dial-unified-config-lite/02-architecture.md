# 02 — Architecture

Solution shape for reviewers. Concepts and the components they live on — not class wiring.

## High-level

```
┌────────────────┐     ┌──────────────────────────────────────────┐
│   dial-cli     │────▶│            DIAL Core (Vert.x)            │
├────────────────┤     │ ┌──────────────────────────────────┐     │
│  DIAL Admin    │────▶│ │  Configuration API               │     │
│  Backend       │     │ │  /v1/{type}/* + /v1/admin/* (new)│     │
├────────────────┤     │ └──────────┬───────────────────────┘     │
│  Admin MCP     │────▶│            │                             │
├────────────────┤     │ ┌──────────▼───────────────────────┐     │
│  CI/CD         │────▶│ │  MergedConfigStore (new)         │     │
└────────────────┘     │ │  FileConfigStore  ← seed/file    │     │
                       │ │  ResourceService  ← API entities │     │
                       │ │  → volatile Config (existing)    │     │
                       │ └──────────┬───────────┬───────────┘     │
                       │   ┌────────▼──┐  ┌─────▼───────────┐     │
                       │   │  Redis    │  │  Blob Storage   │     │
                       │   │  cache    │  │  source of      │     │
                       │   │  locks    │  │  truth          │     │
                       │   │  pub/sub  │  │                 │     │
                       │   └───────────┘  └─────────────────┘     │
                       └──────────────────────────────────────────┘
```

## Core principle: extend, don't rebuild

The proposal deliberately reuses existing DIAL Core infrastructure:

| Already in DIAL Core | What this proposal adds |
|---|---|
| Blob storage (JClouds), Redis (Redisson) | New resource types for config entities |
| `ResourceService` — two-tier cache (Redis + Blob) | `MergedConfigStore` riding on top of it |
| `LockService` — distributed locking | Reused as-is via `underBucketLocks([public/, platform/])` — serializes every admin write **and** the full-rebuild blob scan (reload / rebuildNow / debounced timer) cluster-wide |
| ETag-based optimistic concurrency | Reused as-is |
| `FileConfigStore` — periodic polled reload | Preserved as seed/fallback |
| Existing `ResourceTopic` pub/sub | One more listener for cross-replica propagation |
| Admin access rules | Reused for API authorization |
| — | `Configuration API` HTTP routes |
| — | `dial-cli` |

Anything listed in the **Already in DIAL Core** column the proposal does not redesign.

## `MergedConfigStore` — the union model

`MergedConfigStore` is a new `ConfigStore` implementation that builds the runtime `Config` by combining file-based and API-managed entities as a **union**, not a merge with override.

- File-sourced entities keep their simple names: `"gpt-4"`.
- API-managed entities use canonical IDs: `"models/public/gpt-4"`.
- Both live in the same `Config.models` map as separate entries. They cannot collide because they use different key formats.

There is no "API overrides file" precedence. **Migration is gradual and per-entity**: an entity moves from file to API by creating the API entry, updating downstream references one at a time, and then removing the file entry. No flag-day, no big-bang cutover.

Cross-replica freshness is delivered in two layers:

- **Phase 1**: every replica's existing 60 s `FileConfigStore` poll triggers a `MergedConfigStore` rebuild. A safety-net poll on `ResourceService` covers cross-replica drift.
- **Phase 1.5**: a listener on the existing `ResourceTopic` collapses the lag to "≤ debounce window" when pub/sub delivery succeeds. Polling remains the correctness SLA — pub/sub is a latency optimization, not a delivery guarantee.

**Writer-pod partial-update fast path (slice 4S.4).** On the writer pod, an admin write does not trigger a full rebuild. After the blob put/delete succeeds, the controller calls `MergedConfigStore.applyEntityWrite` / `applyEntityDelete` / `applyBatch` / `applySettingsWrite` / `applySettingsDelete` — single-entity mutation under the rebuild lock, no blob LIST, no blob GET, single volatile-swap of the merged `Config`. Per-type post-processing runs targeted: model cross-ref check on MODEL writes; transitive un-skip of previously-invalid models on INTERCEPTOR writes; cascade-invalidate on INTERCEPTOR deletes; `sortRoutes` on ROUTE writes. The full `rebuildNow()` survives as the fallback for `reload()` / admin `/reload`.

**Replica-pod partial-update fast path (slice 4S.5).** On `ResourceTopic` event receipt, `onResourceEvent` dispatches `applyReplicaEvent(descriptor, action)` onto a virtual thread which fetches the body from the Redis-backed `ResourceService` (writer's put primes the cache), parses + typed-deserializes + decrypts, and calls the same `applyEntityWrite` / `applyEntityDelete` / `applySettingsWrite` / `applySettingsDelete` surface as the writer pod. PROJECT_KEY mirrors the writer's `ApiKeyStore` ordering (`addOrUpdateKey` before `applyEntityWrite`; secret snapshot from current `Config.keys` then `removeKey` before `applyEntityDelete`). A null fetch on CREATE/UPDATE is treated as DELETE (writer-race convergence). Any exception falls back to `requestRebuild()` (debounced full rebuild) — the polling correctness SLA still applies. **Fetch-on-receipt** is the locked choice over embedding the body in `ResourceEvent`: keeps the event shape stable across the codebase, secrets opaque in pub/sub frames, large entities out of every frame, and reuses the Redis two-tier cache.

Per-entity validation runs on each rebuild. The default is **strict abort** (matching today's `FileConfigStore` reload semantics); an opt-in `config.reload.onInvalidEntity: skip` switches to per-entity skip-with-visibility, so a single corrupt blob doesn't block scale-up. The same posture applies to writes: cross-references that don't resolve **block the write with `422`** by default; an opt-in `config.write.softValidation: true` accepts dangling references and surfaces them via `status: "invalid"` for gradual file→API migration. "No broken entities accepted" is the headline contract across both paths.

**Operator surface vs runtime resolution.** The union lives in the runtime `Config` for chat-completion routing — `findDeployment`, `RateLimiter`, the route matcher, and the interceptor chain resolver all keep using both simple-name (file) and canonical-ID (API) keys. The Configuration API operator surface is **not** the union: per-entity GET (`/v1/{type}/{bucket}/{name}`) and metadata listing (`/v1/metadata/...`) are blob-only — file entries do not appear on either. Operators inspect file entries via a dedicated read-only path, `/v1/admin/config/file/{type}[/{name}]`, with the `keys` type denied for every caller (admin included) because file `Config.keys` map keys equal secrets — slice U.4 retired the security-admin tier that previously gated it (see [`04-security.md`](04-security.md)). Splitting the surface this way keeps the operator-facing API clean (one source per endpoint, no per-entity `source` field to disambiguate, no name-collision corner cases like file `gpt-4` vs API `models/public/gpt-4`) while runtime resolution remains untouched.

## Bucket strategy: `public/` vs `platform/`

Two buckets, chosen by *who the entity is for*:

| Bucket | Entity types | Audience |
|---|---|---|
| `public/` | models, applications, toolsets, app-type schemas | User-facing deployments — what end users see and call |
| `platform/` | roles, keys, routes, interceptors, global settings | Operator/infrastructure — what runs the platform |

The bucket name reflects the *tier* (top-level scope), not a path detail. Future multi-tenant scopes (tenants, teams, channels) are added via an `EntityLocationStrategy` interface — single-tenant deployments use the platform scope today; multi-tenant deployments parameterize on `{id}`.

A storage example for one user-facing model and one platform role:

```
public/models/gpt-4        →  resource URL  models/public/gpt-4
platform/roles/power-user  →  resource URL  roles/platform/power-user
```

DIAL Core uses two path formats for the same resource: blob layout is bucket-first (`public/models/gpt-4`), API/canonical identifiers are type-first (`models/public/gpt-4`). The proposal does not change this convention — it inherits it from the existing Resource API.

**Names are single-segment.** Each new admin-config entity uses a single name segment (`^[A-Za-z0-9._%:-]+$`), checked on the URL-decoded value (a literal `%` is sent as `%25`). Conservative by design — extendable on client request when a concrete workflow is broken. Nested paths / subfolders are **out of scope** until the multi-tenant scopes (`tenants/{id}`, `teams/{id}`, `channels/{id}`) land via `EntityLocationStrategy`. The existing user-data types (files / conversations / prompts) keep their arbitrary-depth paths through unchanged controllers.

## Storage decision: ResourceService, no new infrastructure

The locked decision is **reuse `ResourceService`** — Redis cache + Blob storage — for every admin-managed entity type. The proposal does not introduce:

- a relational database
- a new event bus
- a new consensus or coordination system

`ResourceService` already provides two-tier caching, distributed locking, ETag concurrency, and pub/sub events. Applications and toolsets already run on it with infinite cache TTL — config-like entities fit the same pattern.

**Cross-pod admin-write and rebuild serialization.** Per-resource locking prevents same-key races but admits cross-entity interleavings between concurrent admin batches on different pods. Every admin write surface — per-entity `PUT` / `DELETE` and bulk `POST /v1/admin/apply` — therefore acquires **both** the `public/` and `platform/` bucket locks (`LockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS, …)`, sorted internally) **before** any per-resource lock, around the write phase including the local `MergedConfigStore` rebuild. Admin writes are rare (dozens/day at most on real envs) so the simpler, fully sequential model is preferable to finer-grained schemes that would still allow cross-bucket interleavings. **The full-rebuild blob scan shares the same locks** (PR #1529 thread `r3302786719`): a writer commits its multi-blob batch and in-memory swap before any peer pod's rebuild can scan, so no replica seeds a torn `Config` from partial-batch state. The three full-rebuild entry points — `reload()`, `rebuildNow()`, and the debounced timer rebuild — take the locks; `init()` (startup-only) and the per-event replica fast path (`applyReplicaEvent`, best-effort with a `requestRebuild()` fallback + polling SLA) deliberately do not. Lock-ordering invariant — bucket locks first, then per-resource lock — keeps non-admin paths deadlock-free since they never take these locks; `LockService.lock` is non-reentrant, so the write controllers call the lock-free `apply*` methods, never `reload`/`rebuildNow`. Forward-compatible with MT: future scopes (`tenants/{id}`, `teams/{id}`) get their own per-scope lock under the same mechanism.

## What flows through `MergedConfigStore`

Not every admin-managed entity goes through `MergedConfigStore`. The split:

- **Through `MergedConfigStore`** (small set, in-memory in `Config`): models, interceptors, roles, project keys, routes, app-type schemas, global settings.
- **Direct `ResourceService`** (potentially large, lazy-loaded): applications, toolsets, plus admin-managed `public/` instances of `files`, `prompts`, and `conversations` (user-owned instances stay on the existing Resource API path). These were already blob-native; they're enumerated lazily on demand, not pre-loaded into `Config`.

This keeps `Config` small while giving every admin-managed type the same uniform API shape.

> See the full version: [`../dial-unified-config/02-architecture.md`](../dial-unified-config/02-architecture.md)
