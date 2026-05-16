# 01 — Problem & Current State

This doc frames *what's wrong* and *what exists today*. Every other lite doc assumes you've read this.

## The six problems

- **P1 — Two configuration planes, no shared mental model.** Some entities live only in the config file (models, interceptors, roles, keys, routes, app-type schemas), some only in the Resource API (files, conversations, prompts), some in both (applications, toolsets). There's no single answer to "where does this entity belong?"

- **P2 — Eventual consistency between DIAL Admin and DIAL Core.** Admin Backend writes a config file (or KeyVault / ConfigMap / etc.) and DIAL Core polls every 60 s. End-to-end propagation: **60–180+ s**. Multi-replica deployments compound this — each pod polls independently with no cross-replica notification.

- **P3 — No audit trail.** Neither plane records who changed what when. Config-file changes are only tracked if the underlying storage is versioned (e.g. Git for Helm values). *Addressed in Phase 7 — deferred. In the interim, structured DIAL Core logs cover all Configuration API writes.*

- **P4 — Poor CLI/automation ergonomics.** No CLI exists. DevOps hand-edit JSON, push via Helm/kubectl, wait for reload. CI/CD is fragile; local development is cumbersome.

- **P5 — No single source of truth API for runtime state.** No endpoint returns the effective config DIAL Core is actually serving. "What's the current state of all models/roles/keys in this instance?" requires inspecting multiple sources.

- **P6 — No environment promotion mechanism.** Moving config from dev → uat → prod is manual per-field substitution (adapter host URLs, icon base URLs, forwardAuthToken defaults). No built-in abstraction for parameterizing across environments.

## Current state — the shape

DIAL Core already enforces a clean **static vs dynamic** split:

| Tier | File | Content | Mutability |
|---|---|---|---|
| **Static** | `aidial.settings.json` | Vert.x, HTTP, blob/Redis, identity providers, encryption keys, admin rules | Immutable — restart required |
| **Dynamic** | `aidial.config.json` | Models, applications, toolsets, interceptors, roles, keys, routes, app-type schemas, global interceptors | Hot-reload — polled every 60 s |

This split is fine. The problem is that dynamic config is delivered through a polled file-watcher, while user-owned data (files, conversations, prompts) goes through the Resource API — two delivery mechanisms for two halves of the system's state, with the Admin Backend wedged between them as an intermediary that exports JSON and waits.

## Why the existing machinery is enough

`ResourceService` already provides everything a Configuration API needs:

- Two-tier caching (Redis HASH for hot reads, blob storage for durability)
- Distributed locking (local `ReentrantLock` + Redis spin lock, 300 s TTL)
- ETag-based optimistic concurrency on every write
- Pub/sub events (`ResourceEvent` with CREATE / UPDATE / DELETE) via Redisson `RTopic`
- Compression for large payloads
- 19 resource types already in production, including APPLICATION and TOOL_SET on infinite cache TTL — proof the pattern works for config-like entities

The proposal **extends** this — it does not add new infrastructure.

> See the full version: [`../dial-unified-config/01-problem-and-context.md`](../dial-unified-config/01-problem-and-context.md)
