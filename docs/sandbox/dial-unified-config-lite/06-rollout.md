# 06 — Rollout

How the proposal lands. Coexistence model, one-paragraph phase summary, operational impact.

## Coexistence — union, not big-bang

The locked decision is **union, not override**. File-sourced and API-managed entities live side by side in the same runtime `Config` map, keyed differently (simple name vs canonical ID). Migration is **gradual and per-entity**: create the API entry, update downstream references one at a time, then remove the file entry. No coordinated cutover, no flag day.

This lets the proposal ship incrementally without breaking any existing deployment. A team that wants to migrate one model from file to API can do that on its own schedule; teams that want to keep everything in the config file indefinitely can do that too.

## Phase summary

Phase 1 ships the **read-only Configuration API plus CLI read commands** (`get` / `list`) — this alone gives operators a runtime source of truth they don't have today. *(The full-snapshot `export` read was originally part of Phase 1; deferred from MVP — see `../dial-unified-config/IMPLEMENTATION.md` §5.5 Defer.1.)* Phase 2 adds the **write API for models** plus CLI write commands (`add` / `update` / `delete` / `promote` for the model type), proving the round-trip end to end. Phase 1.5 — Redis pub/sub for cross-replica propagation — ships concurrently with or after Phase 2 once writes exist to propagate. Phase 3 extends the **write API to all remaining entity types**. Phase 4 layers in **declarative `apply` and environment promotion**. Phase 5 **migrates the DIAL Admin Backend** to consume the new API instead of writing config files. Phase 6 — optional config file deprecation — is long-term and operator-driven; even after Phase 5, environments that prefer file-based config keep working. **Phase 7 — Audit & Compliance** — delivers the audit subsystem (intent log, query API, CLI/MCP audit tools) after the entity-management surface, CLI, and MCP have landed.

## Operational impact

| Audience | What changes |
|---|---|
| **DevOps teams** | A first-class CLI replaces hand-edited JSON + Helm push. `dial-cli apply -f` with dry-run and validation lands in Phase 4; reads (`get` / `diff`) land in Phase 1 and are already useful in isolation (`export` deferred — see `../dial-unified-config/IMPLEMENTATION.md` §5.5 Defer.1). Per-entity reads target blob-sourced entries only; file-sourced entries are inspected via a separate read-only `/v1/admin/config/file/*` path — admin role for most types, `keys` denied for every caller (file map keys equal secrets per OQ-12; slice U.4 retired the security-admin tier). CLI integration on top of that path is post-MVP. Per-entity promotion (`<type> promote --from … --to …`) lands in Phase 2 for models and Phase 3 for other types; full-environment declarative promotion via `apply -f` lands in Phase 4 — no more per-field manual substitution. CI/CD pipelines start using API calls with deterministic exit codes instead of polling the file-watcher. |
| **DIAL Admin Backend operators** | Phase 5 migrates the Admin Backend to consume the Configuration API. End-to-end propagation drops from 60–180 s to immediate on the writer pod, ≤ 60 s cross-replica (or near-instant after Phase 1.5). The Admin UI will be updated to use the new Configuration API. |
| **DIAL Core deployment** | New resource types in `ResourceService`. One additional listener on the existing `ResourceTopic` from Phase 1.5. No new infrastructure — no database, no event bus, no consensus system. Static settings gain a small number of new opt-in keys (`config.reload.onInvalidEntity`, `config.write.softValidation`). |

> See the full version: [`../dial-unified-config/07-migration-and-rollout.md`](../dial-unified-config/07-migration-and-rollout.md)
