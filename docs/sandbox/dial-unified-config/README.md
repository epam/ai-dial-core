# Unified Configuration Management for DIAL Core

> **Version**: 2.20
> **Status**: Decisions locked — ready for Phase 1 implementation
> **Last updated**: April 2026

This folder contains the proposal for unifying DIAL Core's configuration management — a native Configuration API plus a `dial-cli` tool that replaces today's file-based, eventually-consistent workflow. The proposal is split into focused topic docs so each audience can read only what's relevant to them.

---

## One-paragraph summary

DIAL Core manages deployment configuration through a dual approach today: a polled JSON config file (`aidial.config.json`) for admin-managed entities and a blob-storage-backed Resource API for user-owned resources. A separate DIAL Admin Backend acts as an intermediary — writing config files and waiting for DIAL Core's 60-second file-watcher to pick up changes. This proposal adds a native Configuration API to DIAL Core for all admin-managed entities (stored via the existing ResourceService — Redis cache + Blob storage), builds a `dial-cli` tool with kubectl-like ergonomics on top of that API, and repositions the DIAL Admin Backend as a UI skin on the same API. The key insight: **DIAL Core already has the machinery.** The ResourceService (two-tier caching, distributed locking, ETag concurrency, pub/sub events) is production-proven. This is an extension of existing patterns, not new infrastructure.

An agent-native surface over the same API — a **DIAL MCP server** — is being scoped in parallel so assistants like Claude Code, Claude Desktop, and in-product DIAL QuickApps can read, analyze, and safely mutate DIAL resources through the same contract the CLI and Admin Backend use. v1 ships as an in-repo Java Gradle module embedded in DIAL Core as a Vert.x verticle, with the architectural discipline to be extractable to a standalone service later. The MCP exposes a single small set of building-block tools (~9 in v1) for both administrators and end-users; authorization is enforced by DIAL Core based on the caller's identity. See [`09-admin-mcp-spec.md`](09-admin-mcp-spec.md).

## Why this matters

| Problem today | After |
|---|---|
| 60–180+ second propagation delay between Admin UI and DIAL Core | Immediate effect on the writer pod (the replica that handled the write); ≤60s cross-replica in Phase 1; near-instant after Phase 1.5 (Redis pub/sub) |
| No audit trail for configuration changes | Intent-log audit (PENDING → APPLIED/FAILED) with point-in-time snapshots and rollback *(deferred — Phase 7)* |
| No CLI tool — DevOps hand-edits JSON, pushes via Helm, waits | `dial-cli apply -f config/ --env uat` with dry-run, validation, environment promotion |
| No single source of truth API for runtime state | `dial-cli get` / `GET /v1/{type}/{bucket}/{name}` (per-entity) and `GET /v1/admin/export` (full snapshot) return the effective merged config DIAL Core is actually serving |
| Manual per-field substitution when promoting configs across environments | Template-based promotion: `dial-cli promote --from dev --to uat --template bedrock-chat` |
| Two overlapping configuration planes (config file vs Resource API) | Union model: both coexist by design, migration is gradual and per-entity |

## Pick your reading path

| If you are… | Start here | Then read |
|---|---|---|
| **Lead / manager / stakeholder** | This README | [`07-migration-and-rollout.md`](07-migration-and-rollout.md) for timeline |
| **New to DIAL Core** | [`01-problem-and-context.md`](01-problem-and-context.md) | Any topic doc |
| **Architect / reviewing the design** | [`01-problem-and-context.md`](01-problem-and-context.md) → [`02-architecture.md`](02-architecture.md) | [`03-api-reference.md`](03-api-reference.md), [`04-security-and-audit.md`](04-security-and-audit.md) |
| **Dev team implementing DIAL Core changes** | [`02-architecture.md`](02-architecture.md) → [`03-api-reference.md`](03-api-reference.md) | [`04-security-and-audit.md`](04-security-and-audit.md), [`07-migration-and-rollout.md`](07-migration-and-rollout.md) |
| **Dev team building `dial-cli`** | [`05-cli-design.md`](05-cli-design.md) | [`03-api-reference.md`](03-api-reference.md), [`06-cli-user-guide.md`](06-cli-user-guide.md) |
| **DevOps / platform engineer (user of the CLI)** | [`06-cli-user-guide.md`](06-cli-user-guide.md) | [`07-migration-and-rollout.md`](07-migration-and-rollout.md) |
| **Security / compliance reviewer** | [`04-security-and-audit.md`](04-security-and-audit.md) | [`02-architecture.md`](02-architecture.md) for context |
| **PM / program management** | This README → [`07-migration-and-rollout.md`](07-migration-and-rollout.md) | [`08-open-questions-and-references.md`](08-open-questions-and-references.md) |
| **Agent / MCP tooling reviewer** | [`09-admin-mcp-spec.md`](09-admin-mcp-spec.md) | [`03-api-reference.md`](03-api-reference.md), [`04-security-and-audit.md`](04-security-and-audit.md) |

## Document index

| # | Doc | Size | Primary audience |
|---|---|---|---|
| — | [`README.md`](README.md) (this file) | ~1 page | All |
| 1 | [`01-problem-and-context.md`](01-problem-and-context.md) | ~7 pages | All (foundation) |
| 2 | [`02-architecture.md`](02-architecture.md) | ~12 pages | Dev team, architects |
| 3 | [`03-api-reference.md`](03-api-reference.md) | ~6 pages | Dev team, API integrators |
| 4 | [`04-security-and-audit.md`](04-security-and-audit.md) | ~6 pages | Security, compliance |
| 5 | [`05-cli-design.md`](05-cli-design.md) | ~6 pages | CLI implementers |
| 6 | [`06-cli-user-guide.md`](06-cli-user-guide.md) | ~10 pages | DevOps / platform |
| 7 | [`07-migration-and-rollout.md`](07-migration-and-rollout.md) | ~5 pages | Leads, PM, DevOps |
| 8 | [`08-open-questions-and-references.md`](08-open-questions-and-references.md) | ~4 pages | Reviewers, stakeholders |
| 9 | [`09-admin-mcp-spec.md`](09-admin-mcp-spec.md) | ~8 pages | Dev team, PM, agent-tooling reviewers |

## Status at a glance

Phases are listed in their numerical order. Phase 1.5 depends on Phase 2's write path (pub/sub events are only meaningful once writes exist) — so it ships **concurrently with or after** Phase 2, despite the earlier number.

| Phase | Scope | Status / Depends on |
|---|---|---|
| Phase 0 | Research & design | In progress — decisions locked, OpenAPI review pending |
| Phase 1 | Read-only Configuration API + CLI read commands | Ready to implement |
| Phase 1.5 | Redis pub/sub for cross-replica propagation | Ships concurrently with or after Phase 2 |
| Phase 2 | Write API for models + CLI write commands | After Phase 1 |
| Phase 3 | Write API for all entity types | After Phase 2 |
| Phase 4 | Declarative apply + environment promotion | After Phase 3 |
| Phase 5 | DIAL Admin Backend migration to the new API | After Phase 4 |
| Phase 6 | Config file deprecation (optional, long-term) | Not scheduled |
| Phase 7 | **Audit & compliance** — intent-log audit, query API, CLI/MCP audit tools (deferred from Phase 3) | After entity-management API + CLI + MCP land |

See [`07-migration-and-rollout.md`](07-migration-and-rollout.md) for scope, risks, and value-delivered per phase.

## Key decisions already locked

The proposal has been iterated extensively. These decisions are final and inform every topic doc:

1. **Storage backend** — Reuse ResourceService (Redis + Blob). No database, no new event bus, no new consensus system.
2. **Bucket strategy** — `public/` for user-facing deployments (models, apps, toolsets, schemas); `platform/` for infrastructure (roles, keys, routes, interceptors, settings). The bucket name reflects the *tier* it serves (top-level scope); future MT scopes (tenant, team, channel) are added via `EntityLocationStrategy` (with `PLATFORM_SCOPE = "platform"` on `EntityLocationStrategy` and `PLATFORM_BUCKET = "platform"` on `ResourceDescriptor` — see [`02-architecture.md`](02-architecture.md) §4).
3. **Coexistence with config files** — Union, not override. Simple names (`"gpt-4"`) and canonical IDs (`"models/public/gpt-4"`) live side by side in the same runtime `Config`. Migration is gradual and per-entity.
4. **CLI language** — Java (Picocli + Quarkus + GraalVM native image). Shares DIAL Core's `config/` Gradle module directly — zero reimplementation of data classes.
5. **Audit storage** — Redis Streams (hot) + blob archival (cold). Vault-style intent log (PENDING → APPLIED/FAILED). **Deferred to Phase 7** — design preserved; delivery follows entity-management API + CLI + MCP.
6. **Secrets at rest** — Field-level AES-256-GCM encryption via existing `CredentialEncryptionService` (envelope encryption, KMS-provider backed). No new infrastructure.
7. **Apply failure semantics** — CLI-side validate-first gate; server applies sequentially and continues on per-entity failure with per-entity results reported.

Open items — 7 remaining, all multi-tenancy forward-compatibility (Post-MT) or Phase 4+ scope questions. See [`08-open-questions-and-references.md`](08-open-questions-and-references.md). The most recently resolved: OQ-21 (admin scope covers files/prompts/conversations as first-class types) and OQ-33 (admin has no access to user buckets — out of scope, no plans).

## Feedback

- **Dev team, architects**: inline comments on `02-architecture.md` and `03-api-reference.md`
- **DevOps teams**: inline comments on `06-cli-user-guide.md` (the feedback questions Q1–Q13, D1–D9 are still live there)
- **Security**: inline comments on `04-security-and-audit.md`
- **Open questions**: see `08-open-questions-and-references.md` §Open
