# Unified Configuration Management for DIAL Core — Review Brief

> **Status:** Decisions locked; Phase 1 ready.
> **Purpose:** This is the *lightweight* review surface — ideas, interfaces, and functionality, without implementation plumbing. For depth, see [`../dial-unified-config/`](../dial-unified-config/).

## Summary

DIAL Core manages deployment configuration through two planes today: a polled JSON config file (`aidial.config.json`) for admin-managed entities, and a blob-backed Resource API for user-owned resources. A separate DIAL Admin Backend acts as intermediary — writing config files and waiting for DIAL Core's 60 s file-watcher to pick them up. **This proposal adds a native Configuration API** to DIAL Core that stores admin-managed entities through the existing `ResourceService` (Redis cache + Blob storage), **builds a `dial-cli` tool** with kubectl-like ergonomics over that API, and repositions the DIAL Admin Backend as a UI on the same contract. An **Admin MCP server** exposing the same surface to assistants is in scope in parallel. The key insight: **DIAL Core already has the machinery** — two-tier caching, distributed locking, ETag concurrency, pub/sub events — so this is an extension of proven patterns, not new infrastructure.

## Why this matters

| Problem today | After |
|---|---|
| 60–180+ s propagation delay between Admin UI and DIAL Core | Immediate effect on the writer pod; ≤ 60 s cross-replica in Phase 1; near-instant after Phase 1.5 (Redis pub/sub) |
| No audit trail for configuration changes | Intent-log audit with PENDING → APPLIED/FAILED, point-in-time snapshots, rollback *(deferred — Phase 7)* |
| No CLI tool — DevOps hand-edit JSON, push via Helm, wait | `dial-cli apply -f config/ --env uat` with dry-run, validation, env promotion |
| No single source of truth API for runtime state | Per-entity `GET` of effective config *(full `/v1/admin/export` snapshot deferred — see `../dial-unified-config/IMPLEMENTATION.md` §5.5 Defer.1)* |
| Manual per-field substitution when promoting configs | Template-based promotion: `dial-cli promote --from dev --to uat --template …` |
| Two overlapping configuration planes | Union model — config file and API coexist by design; migration is gradual and per-entity |

## File index (this folder)

| # | Doc | What you'll find |
|---|---|---|
| — | `README.md` (this file) | Summary, key decisions, file index |
| 1 | [`01-problem.md`](01-problem.md) | The 6 problems and the two-plane current state |
| 2 | [`02-architecture.md`](02-architecture.md) | High-level shape, MergedConfigStore concept, bucket strategy |
| 3 | [`03-api.md`](03-api.md) | Endpoint shapes, ETag concurrency, validation, bulk apply |
| 4 | [`04-security.md`](04-security.md) | Authorization, secrets at rest, audit (deferred) |
| 5 | [`05-cli.md`](05-cli.md) | `dial-cli` command shapes, profiles, templates, promotion |
| 6 | [`06-rollout.md`](06-rollout.md) | Coexistence model, phase summary, operational impact |
| 7 | [`07-mcp.md`](07-mcp.md) | DIAL Admin MCP — why, surface, deployment options |

## Key decisions already locked

These are final and inform every topic doc — reviewers should treat them as contract, not as open questions:

1. **Storage backend** — Reuse `ResourceService` (Redis + Blob). No database, no new event bus, no new consensus system.
2. **Bucket strategy** — `public/` for user-facing deployments (models, applications, toolsets, schemas); `platform/` for infrastructure (roles, keys, routes, interceptors, settings).
3. **Coexistence with config files** — Union, not override. Simple names (`"gpt-4"`) and canonical IDs (`"models/public/gpt-4"`) live side by side. Migration is gradual and per-entity.
4. **CLI language** — Java (Picocli + Quarkus + GraalVM native image). Shares DIAL Core's `config/` Gradle module — zero reimplementation of data classes.
5. **Audit storage** — Redis Streams (hot) + blob archival (cold). Vault-style intent log (PENDING → APPLIED/FAILED). **Deferred to Phase 7** — design preserved; delivery follows entity-management API + CLI + MCP.
6. **Secrets at rest** — Field-level AES-256-GCM via existing `CredentialEncryptionService` (envelope encryption, KMS-backed). No new infrastructure.
7. **Apply failure semantics** — CLI-side validate-first gate; server applies sequentially and continues on per-entity failure with per-entity results reported.

## Open questions

7 open items remain (all multi-tenancy forward-compatibility or Phase 4+ scope). The live register is in the full proposal — see [`../dial-unified-config/08-open-questions-and-references.md`](../dial-unified-config/08-open-questions-and-references.md).

> See the full version: [`../dial-unified-config/README.md`](../dial-unified-config/README.md)
