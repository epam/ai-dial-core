# 01 — Problem Statement & Current State

> **Audience:** Everyone. This is foundational context for the rest of the documents.
> **Reading time:** ~15 minutes.
> **Prerequisites:** Basic familiarity with DIAL Core.

This document answers two questions: *what's wrong with configuration management today*, and *how does DIAL Core currently handle configuration*. If you're new to the proposal, read this first — every other document assumes you have this context.

For the high-level proposal and navigation, see [`README.md`](README.md).

---

## 1. Problem Statement

### P1 — Conceptual complexity of two configuration planes

Some entity types live exclusively in the config file (models, interceptors, roles, keys, routes, applicationTypeSchemas), some exclusively in the Resource API (files, conversations, prompts), and some in both (applications, toolsets). There is no single mental model for where a given entity should be managed.

### P2 — Eventual consistency between DIAL Admin and DIAL Core

DIAL Admin Backend writes config files (or KeyVault/ConfigMap/etc.) and waits for DIAL Core's `FileConfigStore` to detect the change via a 60-second polling timer. Total propagation delay: Admin export time + volume mount sync + poll interval = **60–180+ seconds**. Multi-replica deployments compound this: each pod polls independently with no cross-replica notification.

### P3 — No audit trail *(addressed in Phase 7 — deferred)*

Neither the config-file approach nor the Resource API provides a unified audit log of who changed what and when. Config file changes are tracked only if the underlying storage has versioning (e.g., Git for Helm values). The audit subsystem designed in [`04-security-and-audit.md`](04-security-and-audit.md) §3 is **deferred to Phase 7** (after entity-management API + CLI + MCP) — see [`07-migration-and-rollout.md`](07-migration-and-rollout.md) §Phase 7. Phases 1–6 do not deliver an audit trail; structured DIAL Core application logs cover all Configuration API writes (per-entity CRUD plus `/v1/admin/*` ops) in the interim.

### P4 — Poor CLI/automation ergonomics

No CLI tool exists. DevOps engineers must hand-edit JSON, push via Helm/kubectl, and wait for reload. CI/CD pipelines are fragile and local development workflows are cumbersome.

### P5 — No single source of truth API for runtime state

There is no API endpoint that returns the current effective configuration as DIAL Core sees it at runtime. Operators cannot answer "what is the current state of all models/roles/keys in this DIAL instance?" without inspecting multiple sources.

### P6 — No environment promotion mechanism

Moving configuration between environments (dev → uat → prod) requires manual per-field substitution of environment-specific values (adapter host URLs, icon base URLs, forwardAuthToken defaults). There is no built-in abstraction for parameterizing DIAL config across environments.

---

## 2. Current State Analysis

### 2.1 Static vs Dynamic Settings (already split)

DIAL Core already enforces a principled two-tier split:

| Tier | File | Content | Mutability |
|------|------|---------|------------|
| **Static Settings** | `aidial.settings.json` | Infrastructure: Vert.x, HTTP server/client, blob storage provider, Redis connection, identity providers, encryption keys, admin access rules, application controller | **Immutable at runtime** — requires pod restart |
| **Dynamic Settings** | `aidial.config.json` (one or more files, deep-merged) | Platform entities: models, applications, toolsets, interceptors, roles, keys, routes, applicationTypeSchemas, globalInterceptors | **Hot-reloadable** — polled every 60s by `FileConfigStore` |

This split directly maps to the bootstrap-vs-runtime boundary. Static settings are what DIAL Core needs to start up and connect to its own dependencies. Dynamic settings are what it serves to users.

### 2.2 Entity Types and Their Current Homes

| Entity Type | Config File | Resource API | Admin Backend CRUD | Notes |
|---|:---:|:---:|:---:|---|
| **Models** | ✅ | ❌ | ✅ | Config-only. Endpoint, upstreams, features, pricing, limits |
| **Applications** | ✅ | ✅ | ✅ | Dual-source. Config = static admin. Resource = user-owned/schema-rich with deploy/undeploy lifecycle |
| **Toolsets** | ✅ | ✅ | ✅ | Dual-source. Resource API toolsets support auth_settings/credentials (OAUTH/API_KEY) |
| **Interceptors** | ✅ | ❌ | ✅ | Config-only |
| **Roles** | ✅ | ❌ | ✅ | Config-only. Token/request/cost limits, sharing limits |
| **Keys** | ✅ | ❌ | ✅ | Config-only. Write-only in JSON serialization (never exposed via API) |
| **Routes** (global) | ✅ | ❌ | ✅ | Config-only. Path patterns, methods, upstreams, userRoles |
| **ApplicationTypeSchemas** | ✅ | ❌ | ✅ | Config-only. JSON meta-schemas for schema-rich applications |
| **Files** | ❌ | ✅ | ❌ | Resource-only. User and system files |
| **Conversations** | ❌ | ✅ | ❌ | Resource-only. User data |
| **Prompts** | ❌ | ✅ | ❌ | Resource-only. User templates |

**Note on Admin Backend CRUD:** The Admin Backend doesn't write to DIAL Core's config files directly in all cases. For entities that DIAL Core manages via the Resource API (applications, toolsets), the Admin Backend has **special adapter endpoints** that proxy CRUD operations to DIAL Core's existing Resource API. Only for config-file-only entities (models, roles, keys, interceptors, routes, schemas) does the Admin Backend write to the config file and wait for DIAL Core's file-watcher to reload. This distinction is important — the Configuration API proposed in this work replaces the file-write path for config-file entities, while the Admin Backend's Resource API proxying for apps/toolsets continues to work (see [`02-architecture.md`](02-architecture.md) §Entity Storage Strategy).

### 2.3 FileConfigStore Mechanics

From source analysis (`FileConfigStore.java`):

- Multiple config file paths from `config.files` setting (JSON array)
- **Deep merge** via Jackson `readerForUpdating()`: objects merge recursively, arrays concatenated (or overwritten per `config.jsonMergeStrategy.overwriteArrays`)
- **Volatile reference swap**: `volatile Config config` field atomically replaced on successful reload — lock-free reads from all Vert.x event loop threads
- **Periodic polling** via `vertx.setPeriodic()`, default 60s — NOT filesystem watchers
- **Fail-safe**: parse/load errors on periodic reload (`fail=false`) are logged as warnings and the previous valid `Config` continues serving (the volatile field is not updated). Startup failures (`fail=true`) rethrow and crash the pod.
- **Post-load processing**: routes sorted by `order`, deployment names set from map keys, uniqueness enforced across all deployment types, API keys passed to `ApiKeyStore.addProjectKeys()`

**Static-settings keys that govern this loader.** The first table lists the keys already in `aidial.settings.json` today; the second lists new keys this proposal adds. The two are kept separate so a reader doesn't assume the proposed keys exist in the current schema.

*Existing keys (already in `aidial.settings.json`):*

| Setting key | Purpose |
|---|---|
| `config.files` | JSON array of config-file paths to load and deep-merge. |
| `config.reload` | Polling interval in milliseconds for `vertx.setPeriodic()` (default 60000). |
| `config.jsonMergeStrategy.overwriteArrays` | Merge strategy for JSON arrays — concatenate (default) vs. overwrite. |

*Proposed new keys (added by this proposal):*

| Setting key | Purpose |
|---|---|
| `config.reload.onInvalidEntity` | `skip \| abort` — per-entity skip-on-invalid-entity vs. whole-reload abort. See [`02-architecture.md`](02-architecture.md) §4.1. Default `abort` (matches today's `FileConfigStore` strict-reload behavior; opt-in `skip` for per-entity skip-with-visibility). |
| `config.write.softValidation` | `true \| false` — accept writes with dangling cross-references (soft) vs. reject with `422` (strict). See [`02-architecture.md`](02-architecture.md) §9. Default `false`. |

### 2.4 Deployment Resolution — Config File Takes Precedence

From `DeploymentService.java`:

```
findDeployment(id):
  1. config.selectDeployment(id)     → try applications, models, toolsets, interceptors maps
  2. if found → check userRoles → return (config wins)
  3. if not found → toResourceDescriptor(id) → applicationService or toolSetService (blob lookup)
```

**Config-file entities always shadow Resource API entities with the same name.** Listing merges three Resource API sources (private, shared, public) but config-file deployments are added separately by controllers.

(This is the current behaviour. The union model in [`02-architecture.md`](02-architecture.md) §4 eliminates shadowing by enforcing distinct key namespaces — file entries use simple names like `gpt-4`, API entries use canonical IDs like `models/public/gpt-4` — so the same-name collision this warns about cannot arise after Phase 2.)

### 2.5 ResourceService — Already Built for This

The two-tier Resource Service architecture (`ResourceService.java`) is production-proven:

```
Write path:  Lock → Redis HASH (if body ≤ maxSizeToCache) → sync queue → async blob write → pub/sub event
Read path:   Lock → Redis cache → blob fallback → cache result
Delete:      Lock → ETag check → mark deleted in Redis → delete from blob → pub/sub event
```

Key characteristics relevant to config storage:
- **Distributed locking** via `LockService` (local ReentrantLock + Redis spin lock, 300s TTL per the Lua script)
- **ETag-based optimistic concurrency** on every write
- **Pub/sub events** (`ResourceEvent` with `Action.CREATE/UPDATE/DELETE`) already implemented, published via Redisson `RTopic`
- **Compression** (gzip above `compressionMinSize` — configurable, not a fixed threshold; transparent to callers)
- **APPLICATION and TOOL_SET** already use infinite cache TTL (`Long.MAX_VALUE`) — proving this pattern works for config-like entities
- **19 resource types** with differentiated caching/compression — adding more is a well-established pattern

### 2.6 DIAL Admin Backend — Existing Intermediary

The `ai-dial-admin-backend` (Spring Boot 3.x / Java 17) is a separate service with its own database (H2/PostgreSQL/MSSQL). It provides:

- Full REST CRUD under `/api/v1` for models, applications, toolsets, interceptors, roles, keys, routes
- Import of existing `aidial.config.json` files
- Export to multiple destinations: filesystem, Kubernetes ConfigMap/Secret, Azure Key Vault, HashiCorp Vault, AWS Secrets Manager, GCP Secret Manager
- OIDC and Basic Auth support
- Web UI frontend (`ai-dial-admin-frontend`, Next.js)

The integration pattern today:

```
Admin UI → Admin Backend REST API → Database → Scheduled JSON export →
  Filesystem/ConfigMap/KeyVault → DIAL Core periodic file poll (up to 60s) → Config swap
```

### 2.7 Identifier Format Mismatch

| Aspect | Config File | Resource API |
|--------|-------------|--------------|
| ID format | Flat string: `"chat-gpt-35-turbo"` | Path-based: `applications/{encBucket}/my-app` |
| Namespace | Global (unique across all deployment types) | Per-bucket (user/project scoped) |
| Lookup | O(1) map lookup | URL parse → decrypt bucket → Redis/blob read |
| Access control | `userRoles` field | Bucket ownership + sharing + publication rules |

### 2.8 Cross-Replica Consistency

Current state: **none for config**. Each replica polls `FileConfigStore` independently on its own timer. A config change can take 0–60s to propagate to any given replica, and there's no coordination between replicas. For Resource API entities, consistency comes through shared Redis cache and blob storage.

---

## Next

- Solution: [`02-architecture.md`](02-architecture.md)
- API: [`03-api-reference.md`](03-api-reference.md)
- Timeline: [`07-migration-and-rollout.md`](07-migration-and-rollout.md)
