---
name: validate-config-on-every-write
description: Make a rule that the config rebuild enforces on an entity also run before the blob is written, on PUT, /v1/admin/validate and /v1/admin/apply. Use when adding a validation to ConfigPostProcessor or auditing an entity type whose rule runs only at reload.
argument-hint: "[<entity-type>] [<rule>]"
allowed-tools: Read, Grep, Glob
---

# Validate on every write path

Arguments: `$ARGUMENTS` — optionally the entity type (e.g. `route`, `translator`) and the rule to enforce (e.g.
`BEARER upstream needs a key`). With none, audit every entity type for rules that run only at reload.

**Rule:** whatever the rebuild (`ConfigPostProcessor`) rejects, a write must reject first.

**Why:** a rule checked only at reload lets a write store a blob the next rebuild rejects. The caller gets a `500` (apply
says `APPLIED`), the blob stays, and under `onInvalidEntity: abort` the pod will not start. Seen with model invariants
(#2001) and route `BEARER` upstream without a `key` (#2225).

## Steps

1. Find the entity's rebuild rules (`processSemantic`, `validateSingle<Entity>`). A rule that reads only the entity is an
   invariant: hard at every gate. A rule that reads another entity (interceptor names) follows `softValidation`.
2. Put the invariants in one `validate<Entity>Invariants(entity, ..., warnings)` and call it from the rebuild too, so the
   gate cannot drift (see `validateModelInvariants`, `validateRouteInvariants`).
3. Call it before the write on every path. An empty `-> { }` arm or a missing `instanceof` branch is the gap.

   | Path | Place | Failure |
   |---|---|---|
   | `PUT` | `ConfigResourceController.handlePut`, after the secret merge, before `putResource` | `422` via `rejectWithValidationWarnings` |
   | validate | `ConfigValidationService.validateOnly` | `FAILED` |
   | apply | `ConfigApplyService`, before the blob write | `FAILED` |

   Applications and toolsets: hook in `ApplicationService` / `ToolSetService`.
4. Tests in the existing API classes: `PUT` gives `422`, `GET` gives `404`, a retry with `If-None-Match: *` gives `200`
   (nothing was written); an update omitting a secret passes; validate and apply with `precheck: false` give `FAILED`.
5. Add the requirement to `openspec/specs/config-store/spec.md` and any operator docs.

## Traps

- `PUT` checks the merged entity; apply checks the manifest as sent.
- The gate has no entity name yet; keep message prefixes null-safe.
- Do not change the rebuild's behaviour.

Present the plan and wait for approval before editing code; use the OpenSpec flow.
