# Implementation Plan & Execution Playbook

> **Status**: Draft v1 — kickoff plan for Phases 1–3 (+4 nice-to-have) MVP.
> **Audience**: Implementation lead, agent coordinators, contributors, code-owners.
> **Spec**: this file is *execution*; the contract lives in design docs `01`–`09`. Locked review-round decisions that aren't yet folded into the OQ register are tracked in the project memory.
> **Branch**: `feature/unified-config` (long-running). Slices land into it; merges to `development` happen on phase boundaries.

---

## 1. Goal & MVP scope

Ship a working MVP of the Configuration API + `dial-cli` covering Phases 1, 2, 3 (entity CRUD across all types) and ideally the core of Phase 4 (declarative `apply` + `diff`). The bar is **running, tested, reviewable code that lands cleanly against current `development`** — strong enough for the DIAL team to evaluate the proposal against an alternative manual-implementation path.

**MVP includes:**

- Read API for all admin-config entity types (Phase 1).
- Full CRUD for `models` (Phase 2) + every Phase-2 prerequisite plumbing PR named in `07-migration-and-rollout.md`.
- Mechanical extension of CRUD to `roles`, `keys`, `interceptors`, `routes`, `schemas`, `settings`, plus admin-managed `applications`, `toolsets`, `files`, `prompts`, `conversations` (Phase 3).
- `dial-cli` `get` / `add` / `update` / `delete` / `validate` / `promote` / `diff` for all types.
- Cross-replica propagation via Phase 1.5 pub/sub — included because the cost is low and the demo loses authority without it.
- **DIAL Admin MCP server** (MCP-1 per spec 09 §6.1 — all 9 building-block tools) shipping interleaved with Track A: read tools after Phase 1, write tools after Phase 2/3. Third surface over the same Configuration API contract — strengthens the demo by showing CLI + MCP + UI all riding the same wire.

**MVP stretch (Phase 4 core):**

- `POST /v1/admin/apply` + `POST /v1/admin/validate` (multi-entity).
- `dial-cli apply -f` against fully-resolved manifests (no template DSL, no overlays, no bundles). (`dial-cli export` is deferred — see Defer.1 in §5.5.)

**Explicitly out of MVP:**

- Phase 4 advanced CLI ergonomics (templates / overlays / bundles / `${SECRET:*}` / `promote --template auto`).
- Phase 5 (Admin Backend migration), Phase 6 (file deprecation), Phase 7 (audit).

---

## 2. Operating Principles

These principles drive every slice and every agent prompt. The codebase's prior review rounds (project memory) show the code-owner pushes back on the violations they prevent — treat them as review criteria, not aspirations.

### 2.1 Simplicity First

- **Minimum code that solves the slice.** Nothing speculative.
- No features beyond the slice's scope.
- No abstractions for single-use code; one helper used in one place stays inline.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios. Trust internal callers; only validate at system boundaries.
- If 200 lines could be 50, rewrite.
- Self-test: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 2.2 Surgical Changes

- Touch only what the slice requires. Clean up only the mess the slice creates.
- Do **not** "improve" adjacent code, comments, or formatting.
- Do **not** refactor things that aren't broken.
- Match the existing style even if you'd write it differently.
- If you notice unrelated dead code, mention it in the PR description — don't delete it.
- Remove imports / variables / functions that **your changes** orphaned. Don't remove pre-existing dead code.
- Test: every changed line traces directly to the slice's design-doc anchor or scope.

### 2.3 Codebase-specific addenda (from review rounds)

- **Extend existing patterns, don't add new infrastructure.** ResourceService, CredentialEncryptionService, ResourceTopic, FileConfigStore — all reused. Memory shows proposals to add new infra were rejected.
- **Vert.x event-loop discipline.** No blocking calls on the event loop. Wrap blob/Redis/file calls in `vertx.executeBlocking` or use async APIs. Agents commonly miss this.
- **Volatile-reference swap idiom.** `ApiKeyStore.keys`, `Config` ref. Build fresh + atomic-swap; never `clear()+putAll()` (silent-undo-on-race — see Q1 amendment).
- **Strict typing for closed sets, `String` for open id-bearing sets.** `ResourceTypes` enum; `String scope` with named constants. Don't over-type.
- **One vocabulary across bucket / scope / URL / canonical ID.** `platform/` everywhere; never re-introduce `admin/` or `global/` in new code.
- **Wire shape mirrors the existing Resource API.** Per-entity admin-config CRUD is `PUT`-upsert with RFC 7232 conditional headers — `If-None-Match: *` for create-only (412 if exists), `If-Match: <etag>` for CAS update (412 on mismatch). No `POST` at the single-entity surface; the controller returns `405` with `Allow: GET, PUT, DELETE`. Listings live at `/v1/metadata/{type}/{bucket}/{path}` and return `ResourceFolderMetadata` / `ResourceItemMetadata` — metadata only, no entity body, no Public/Owner projection. Full alignment locked 2026-05-20 (see `project_unified_config_review.md`); reverses the earlier strict POST/PUT split and inline-body listing decisions.
- **Checkstyle: 180-char lines, Google style.** `./gradlew checkstyleMain checkstyleTest` before every PR.

---

## 3. Tracks, branching, parallelization

### 3.1 Tracks

Three parallel tracks. Server work depends only on prior server slices; CLI and MCP work depend on the corresponding server slice's wire contract being stable (PR open or merged — not necessarily landed).

| Track | Owner(s) | Scope | Module(s) |
|---|---|---|---|
| **A — Server** | Implementation lead + core-team contributors | `server/`, `storage/`, `config/`, `credentials/` | `:server`, `:storage`, `:config`, `:credentials` |
| **B — CLI** | Second implementer | New sibling `:cli` Gradle module in **the same repo** (Picocli + Quarkus, JVM-mode for MVP — see §3.4 GraalVM deferral) | `:cli` (new) |
| **C — MCP** | Implementation lead + MCP team | New sibling `:mcp` Gradle module per spec 09 §7.1 — embedded as a Vert.x verticle in Core's JVM, mounted at `/mcp`; REST-only loopback to Core's Configuration API for extraction discipline | `:mcp` (new) |

### 3.2 Branching model

```
  slice sub-branches  (feature/unified-config-<id>-<short-title>,
                       e.g. feature/unified-config-1S.0-bootstrap)
           │
           │ local `git merge --squash` (no per-slice PR; commit per §3.5)
           ▼
  feature/unified-config   ◄── lazily integrated with development
           │
           │ ONE big PR after end-to-end user testing — the only formal review
           ▼
       development
```

- **Sub-branches** named `feature/unified-config-<id>-<short-title>` (e.g. `feature/unified-config-1S.0-bootstrap`, `feature/unified-config-2S.11-models-write`). **Hyphen separator (not slash) is required** because the integration branch `feature/unified-config` already exists as a ref — Git refuses to create `feature/unified-config/<x>` when `feature/unified-config` is itself a branch (ref-vs-directory conflict). Prefix still groups slice branches under the integration namespace; `git branch --list 'feature/unified-config-*'` enumerates the entire MVP workstream.
- **Slices integrate via local `git merge --squash`** into `feature/unified-config` — no per-slice PR, no per-slice formal code-owner review. The orchestrator presents the slice diff and a draft commit message in §3.5 format for the user's approval (a halt point), then squash-merges, deletes the sub-branch, and updates the slice register Status to `✅`. One squash-commit per slice keeps the integration branch's log readable as a slice timeline (`git log development..feature/unified-config --oneline` enumerates the MVP).
- **`feature/unified-config` is integrated with `development` lazily** — not on a fixed cadence. Triggers: (a) `development` lands a change that affects in-flight slice work, or (b) a slice author needs a new `development` API. **Default mode is rebase** (force-push allowed; in-flight slice authors rebase their sub-branches onto the new tip; preserves linear history for the final big PR's review). **Per-sync merge override is allowed when situational** — early in MVP with few slices in flight, or when conflicts resolve more cleanly with a merge commit than with rebase-conflict-per-commit. Late in MVP with many slices in flight, prefer rebase to keep history readable for code-owners.
- **No intermediate merges to `development`** during the MVP. The branch accumulates the full Phase 1–3 (+stretch) implementation.
- **Phase boundaries are verification milestones** — run integration suites, smoke-test the demo path, freeze for review. They are *not* merge events; slices already squash-merged as they landed.
- **One big PR `feature/unified-config` → `development`** at MVP-complete, after user-side testing. That PR is the moment the wider DIAL team reviews the full proposal-as-code.

### 3.3 Parallelization rules

- Tracks **B (CLI)** and **C (MCP)** start as soon as Track A's slice **1S.1** (`GET /v1/models/public/{name}`) PR is open. Neither needs it merged — only the wire contract stable.
- Within a track, slices marked **Mechanical** (Phase-3 entity-type sweep) parallelize across multiple worktrees once the pattern is validated on the first one.
- Phase-1.5 pub/sub PRs ship concurrently with Phase-2 write-API PRs; pub/sub merges *after* the write path lands so events have something to fire on.
- **Use `isolation: "worktree"`** on `Agent` calls when launching a slice that's independent from current in-flight work. Keeps coordinator context clean and lets multiple slices proceed concurrently.

### 3.4 CI scope and the GraalVM deferral

**CI scope: Full mirror.** Slice PRs against `feature/unified-config` run the same workflow set as PRs against `development`. The repo's `.github/workflows/pr.yml` already triggers on every PR regardless of target branch; no workflow change needed beyond extending the `pr.yml` `branches` trigger to include `feature/unified-config` once the branch exists. CI is delegated to centralized reusable workflows at `epam/ai-dial-ci@4.0.0` — that ownership and version pin are shared across all `ai-dial-*` repos.

**GraalVM deferral (locked 2026-05-01).** Existing CI runs Temurin 21 only — no GraalVM CE / Mandrel, no `nativeCompile`, no `setup-graalvm` action. Adding GraalVM to the shared `epam/ai-dial-ci` workflows is a cross-team change that would block MVP on infra work; that is the wrong order.

For MVP, `:cli` ships **JVM-mode only** via Picocli + Quarkus. The design doc 05 §6 (which prescribes the Picocli + Quarkus + GraalVM stack) is **not amended** — the design contract still calls for native-image as the production path; this is a scope-reduction in *execution*, tracked here per §8.

Concrete deferrals for MVP:

- `./gradlew :cli:build` produces a runnable JVM JAR (`cli/build/libs/dial-cli-<version>.jar`). Quarkus JVM-mode startup is ~100–500 ms — fine for kubectl-style usage.
- `quarkus.native.*` properties are unset; no Quarkus extension reflection-config work for native compatibility.
- **MVP distribution channels**: Docker image (`ghcr.io/epam/dial-cli`) and runnable JAR (`java -jar dial-cli.jar …`). Both are listed in design 05 §6.
- **Deferred distribution channels**: GitHub Releases native binaries (linux/darwin/windows × amd64/arm64) and Homebrew tap (need GraalVM); JBang channel (deferred from MVP — adds packaging/publishing surface that doesn't pay off until external operators install the CLI).
- **Re-enabling native-image** is a single post-MVP slice that lands once `epam/ai-dial-ci` adds GraalVM support — at that point the design's full distribution matrix becomes deliverable.

### 3.5 Commit message format for slice merges

Each slice produces ONE squash-merge commit on `feature/unified-config`. Use this template — conventional-commit style + slice ID + design-anchor citation, ending with the standard co-author trailer.

```
<type>: <slice-id>: <imperative summary, ≤72 chars total>

<paragraph: what changes and why, ≤300 chars; reference impact on later slices when relevant>

Design anchors: <design-doc §refs>
Tests: <test path or "no new tests">

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

**Type guide:**

- `feat:` — slices that add user-visible features (endpoints, CLI commands, write paths).
- `refactor:` — prereq slices that restructure existing code without behaviour change (e.g. **2S.3-pre** HashMap → ConcurrentHashMap, **2S.6-pre** `apiKeyStore` relocation).
- `chore:` — pure infrastructure / build / docs (rare in the slice register).

**Example:**

```
feat: 1S.0: bootstrap CONFIG_RESOURCE route, ConfigAuthorizationService

Adds the foundational read-API plumbing: sibling RouteTemplate.CONFIG_RESOURCE
regex for the new admin-config types, ConfigAuthorizationService interface with
AdminRoleAuthorizationService impl reading access.admin.rules, and
EntityBucketBinding static allowlist with startup assertion. Mirrors the
ResourceApiTest harness.

Design anchors: 02 §5.1, 03 §1, 04 §1.1–1.2
Tests: server/src/test/.../ConfigApiTest.java

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

The slice-ID prefix in the title makes the integration branch's log readable as a slice timeline.

---

## 4. Per-slice agent loop

One canonical loop applied to every slice. The cost varies — bootstrap slices spend more time in Explore + Architect; mechanical slices skip those.

```
┌──────────────────────────────────────────────────────────────┐
│ COORDINATOR (main thread)                                    │
│   1. Pick slice from register                                │
│   2. Read design-doc anchors + relevant memory entries       │
│   3. Dispatch the agents below in order                      │
└──────────────────────────────────────────────────────────────┘
       │
       ├── EXPLORE  (subagent: feature-dev:code-explorer)         [skip if pattern already known]
       │     "Trace how X works in the current code: entry points,
       │      controllers, services, tests. Output: file paths +
       │      5-line summary per layer."
       │
       ├── ARCHITECT  (subagent: feature-dev:code-architect)
       │     "Given design anchors + Explore output + principles
       │      §2, produce a file-level plan: which classes to add,
       │      which to extend, which tests to write. Cite the
       │      design anchor for each design decision."
       │     → User reviews & approves the plan before implementation.
       │
       ├── IMPLEMENT  (fork or general-purpose agent; optionally `isolation: worktree`)
       │     "Execute the plan exactly. TDD: write the integration
       │      test first using the ResourceApiTest pattern, then
       │      make it pass. Run ./gradlew checkstyleMain :server:test
       │      before reporting done."
       │
       ├── SIMPLIFY  (skill: `simplify`)
       │     "Review changed files for reuse, dead code, and over-
       │      engineering. Apply principles §2.1 / §2.2. Fix issues
       │      found; do not touch unrelated code."
       │
       ├── REVIEW  (subagent: feature-dev:code-reviewer)
       │     "Final pre-PR pass: bugs, Vert.x event-loop violations,
       │      security, naming alignment with locked vocabulary,
       │      test coverage. Confidence-filtered output."
       │
       └── PR + HUMAN REVIEW  (code-owner)
             ↑ the gate that matters; expect substantive feedback
```

**Skip rules:**

- Skip EXPLORE for slices in code areas already explored this session.
- Skip ARCHITECT for purely mechanical slices once the pattern is validated (Phase-3 type-sweep, after the first type lands).
- **Never skip SIMPLIFY or REVIEW.** They are the cheapest pre-PR fix layer.

**`/ultrareview` escalation:** not required per-slice — the per-slice review surface is the user's diff approval at the merge halt (§3.2). Reserve `/ultrareview` for the MVP-complete `feature/unified-config` → `development` PR (the only formal external review checkpoint), or trigger ad-hoc if a slice introduces an abstraction the user wants extra eyes on (e.g. **2S.8** `MergedConfigStore`, **2S.10** `SecretFieldProcessor`, **4S.0** apply endpoint). User-triggered only.

**LSP usage.** The harness exposes Java LSP (JDTLS). Prefer it over textual grep at these moments:

- **EXPLORE**: `documentSymbol` to map a class's shape in one call; `workspaceSymbol` to find a class without knowing its file.
- **ARCHITECT**: verify every design-doc anchor — does the cited class/method exist with the expected signature, on the expected line? Sub-second check; cheaper than discovering staleness mid-IMPLEMENT.
- **IMPLEMENT**: `findReferences` / `incomingCalls` before changing a signature; `goToDefinition` / `goToImplementation` to follow types without re-reading whole files.
- **REVIEW**: `findReferences` on methods the slice touched — confirms §2.2 surgical-cleanup orphaned nothing.

Warmup note. A fresh JDTLS workspace may take 30–60 s to finish indexing — early calls can return `server is starting` or show transient unresolved-import diagnostics; retry once the server is warm. Once `./gradlew build` has populated the Gradle cache, LSP resolves the full dependency graph including the private `org.jclouds.*` package directly from the local cache — no GPR-creds-in-LSP-shell trick required. Diagnostics unrelated to the current slice are ignored regardless — they aren't introduced by your changes.

### 4.1 Halt conditions — when to stop and ask, not improvise

The orchestrator halts the loop and asks the user when reality diverges from the plan. The GraalVM/CI mismatch (locked 2026-05-01) is the canonical example: a plan-blocking discovery the orchestrator should never silently work around. Apply this discipline to every slice — surfacing problems early is much cheaper than discovering them in code-owner review.

**Halt immediately if any of the following occurs.** Do not improvise around them.

1. **Discovered constraint that contradicts the plan.** External factors (CI, infra, dependency versions, library APIs) don't match what the design or slice register assumed.
2. **Existing code differs materially from design-doc anchors.** A class named in the design has been renamed; a method signature has shifted; a file has moved. **Verify with LSP `documentSymbol` / `workspaceSymbol` before assuming the anchor is stale** — the check is sub-second and avoids false-alarms on minor reformatting. Halt only when the divergence is real.
3. **Scope drift.** The slice would need to touch files outside the architect plan, add abstractions not in the plan, or pull in another slice's work.
4. **Tests failing in unanticipated ways.** Integration test fails for a reason the architect plan didn't predict (vs. the expected "I just wrote a failing test, now I make it pass" path).
5. **Cross-slice contract impact.** A reviewer comment on a prior slice, if accepted, would change the contract for the current or a later slice — surface it before the current slice's PR is opened.
6. **Locked-decision conflict.** Progress requires amending IMPLEMENTATION.md §9, the project memory, or a design doc. Lockedness exists for a reason; review-rounds put those decisions there.
7. **Principle conflict.** Implementation would require violating §2 principles (e.g., adding new abstraction over existing patterns, blocking the event loop, breaking the locked vocabulary).
8. **Ambiguity between valid interpretations.** Multiple readings of the design are reasonable; the orchestrator does not pick one silently.

**Halt format.** When stopping, present in this order:

1. **What was discovered** — the concrete fact, with file paths or quoted text.
2. **Why it blocks the current path** — short causal chain.
3. **Two or three options** — each with cost / risk / what it implies for later slices.
4. **Recommendation** — your judgment, with reasoning. The user may override.
5. **Wait.** Do not proceed until the user responds. Do not start related work in parallel "in case the user picks option A" — that contaminates context.

**Anti-patterns to avoid.** Do not: silently retry a failing build with different flags, fork a sub-issue and "come back to it", paper over a divergence with a comment, downgrade a test to make it pass, or push the decision to the code-owner ("they'll catch it in review"). All of these defeat the point of the halt.

### 4.2 Auto-mode policy (for `/dial-mvp-auto`)

The `/dial-mvp-auto` slash command runs multiple slices sequentially with the two routine halts — architect-plan approval and merge-diff approval — gated by self-tests rather than always halting. **Halt conditions §4.1 still always trigger a halt — auto-mode never bypasses them.**

**Design invariant**: auto-approval is **earned, not assumed**. Default to halting on uncertainty. Self-test items are halt triggers, not pass-fail booleans the orchestrator gets to game.

#### §A — ARCHITECT auto-approve self-test

The architect plan auto-proceeds IFF every item below holds. ANY item uncertain or false → halt per §4.1 format.

- [ ] Every design-doc anchor cited in the plan is verified live via LSP (`documentSymbol` / `workspaceSymbol`); no stale anchor.
- [ ] Every file the plan lists touching is either an existing file in the cited code area or a new file with a clear scope-of-creation rationale.
- [ ] No new abstractions, helpers, or interfaces are introduced beyond what the slice register row mentions.
- [ ] The plan's test list includes appropriate test coverage for the slice's surface — at least one integration test using the `ResourceApiTest` pattern when the slice exposes HTTP behaviour; well-targeted unit tests when the slice is a pure-internal refactor with no HTTP surface (Phase-2 prereqs being the typical case).
- [ ] No plan step would require violating §2.1 / §2.2 / §2.3 (e.g., blocking the event loop, replacing existing patterns, adding new infrastructure).
- [ ] No plan step requires changing a locked decision in §9 or in memory.
- [ ] LSP `findReferences` blast-radius on every method the plan modifies stays within the slice register row's scope description.
- [ ] No multiple-valid-interpretation calls were picked silently — if two readings of the design are equally valid, halt.

#### §B — MERGE LOCALLY auto-approve self-test

The slice auto-merges IFF every item below holds. ANY item uncertain or false → halt per §4.1 format.

- [ ] `./gradlew checkstyleMain checkstyleTest :server:test` and any module-specific tests pass.
- [ ] The diff is bounded to files listed in the architect plan; no surprise files added or modified.
- [ ] LSP `findReferences` on every method modified shows no unintended orphans (§2.2).
- [ ] No commented-out code, debug prints, or TODOs added by this slice.
- [ ] The §3.5 commit message draft has all required fields filled (type, slice-ID, summary, design anchors, tests, co-author trailer).
- [ ] The only metadata change to IMPLEMENTATION.md is the slice's own §5 row (Status `🚧` → `✅`, Commit column populated).

#### When to invoke `/dial-mvp-auto`

**Use** for mechanical / semi-mechanical slices where the pattern is locked:

- Phase-3 entity-type sweep (3S.2, after the first type validates the pattern).
- Phase-3 CLI extension (3C.0 — generic parameterized command class).
- Phase-2 prereqs that are isolated refactors (2S.0-pre, 2S.1-pre, 2S.2-pre).

**Don't use** for high-uncertainty slices needing user judgment on the architect plan:

- 1S.0 bootstrap (foundational; high review surface).
- 2S.8 `MergedConfigStore`, 2S.10 `SecretFieldProcessor`, 4S.0 apply endpoint (introduce new abstractions).

For those, use plain `/dial-mvp <slice-id>` so every halt is a real halt.

#### Auditability

The orchestrator prints a one-line digest at every conditional halt:

- ARCHITECT auto-proceed: `[<slice-id>] ARCHITECT auto-proceed (N/N self-test items passed)`.
- MERGE auto-proceed: `[<slice-id>] MERGED auto (N/N self-test items passed) → <short-sha>`.
- Halt: standard §4.1 format (what / why / options / recommendation / wait).

Between slices: `[N/M slices done, next: <slice-id>]`.

---

## 5. Slice Register

**Status legend:** `📋 planned` · `🚧 in-progress` · `🔍 awaiting-merge` · `✅ merged` · `⏸ blocked` · `❌ dropped`

### 5.1 Phase 1 — Read-only Configuration API + CLI read

**Track A — Server**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **1S.0** | Bootstrap: `RouteTemplate.CONFIG_RESOURCE` regex (sibling to `RESOURCE` / `FILES`); `ConfigAuthorizationService` interface + `AdminRoleAuthorizationService` impl reading `access.admin.rules`; `EntityBucketBinding` static allowlist + startup assertion + per-request gate; integration-test harness mirroring `ResourceApiTest`. | — | 02 §5.1, 03 §1, 04 §1.1–1.2 | ✅ | [#1513](https://github.com/epam/ai-dial-core/pull/1513) |
| **1S.1** | `GET /v1/models/public/{name}` reading from in-memory `volatile Config` ref. Public/Owner field projection (`status` always `"valid"` in Phase 1; `source` Owner-only). Synthesize `name` from descriptor. | 1S.0 | 03 §1, §2, §4; 04 §1.5 | ✅ | `e105603d` |
| **1S.2** | `GET /v1/models/public/` listing with `?limit&cursor` pagination (default 100, max 500). `hasMore` always present. Trailing-slash optional. **Amended by U.0 (2026-05-20):** listing route moved to `/v1/metadata/{type}/{bucket}/`; `?cursor` → `?token`; `max=500` → `max=1000`; `hasMore` field dropped; trailing-slash removed from `CONFIG_RESOURCE` regex. U.0 row in §5.5 is authoritative. | 1S.1 | 03 §1, §4 | ✅ | `395a9360` |
| **1S.3** | Extend reads to remaining MergedConfigStore-managed types (`interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`). Bucket-aware authz (`platform/` admin-only). 405 for `POST` on `/v1/settings/platform/global` with `Allow: GET, PUT, DELETE` (singleton has no create surface; `PUT` is upsert and `DELETE` clears the API blob — Phase 2 implements `DELETE` alongside `PUT`). Settings GET projection: `"api"` (blob present) | `"file"` (no blob, file defines fields) | `"default"` (no blob, file silent). | 1S.1, 1S.2 | 03 §1; 04 §1.2 | ✅ | `af64319e` |
| **1S.4** | Read paths for `applications`, `toolsets` via existing `ApplicationService` / `ToolSetService` with `ConfigAuthorizationService` preflight. | 1S.1 | 03 §1; 02 §6 | ✅ | `acfe1ace` |
| **1S.5** | Admin authz preflight on existing `FILES` / `RESOURCE` controllers for `public/` admin reads/writes; deny admin reach into user buckets. | 1S.4 | 03 §1; OQ-21, OQ-33 | ✅ | `7be8db9e` |
| **1S.6** | `GET /v1/admin/export` — full snapshot of in-memory `Config`. JSON + YAML output. **❌ dropped 2026-05-20** (Defer.1 in §5.5): removed from MVP at core-team request — core team has concerns about the current implementation; export is not MVP-critical. Original impl merged at `ec1ac537`; reverted by Defer.1. Design preserved in docs (03 §1/§4/§7, 07 Phase 1, 09 §6.1). | 1S.3 | 03 §1; 07 Phase 1 | ❌ dropped | `ec1ac537` (reverted by Defer.1) |
| **1S.7** | `GET /v1/admin/health/config` returning `{status, skipped[]}` (skipped is `[]` in Phase 1 — invalid-entity store ships in 2S.9). Prometheus metric scaffolds (cardinality-zero in Phase 1). | 1S.0 | 07 Phase 2; 02 §4.1 | ✅ | `2a5a10ac` |

**Track B — CLI**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **1C.0** | New `:cli` Gradle module. Picocli + Quarkus Command Mode skeleton. `~/.dial-cli/config.yaml` profile loader. API-key resolution chain (env var → keystore → `--api-key-file` → no-echo prompt). Direct dependency on `:config` module data classes. | 1S.1 (contract only) | 05 §1, §2, §6 | 📋 | — |
| **1C.1** | `dial-cli env list / current / use / check`. Persist `defaults.env` on `use`. | 1C.0 | 05 §1 | 📋 | — |
| **1C.2** | `dial-cli model get <name>` and `dial-cli get models` (alias). `-o table\|json\|yaml`. | 1C.0, 1S.1 | 05 §1; 06 §2.2 | 📋 | — |
| **1C.3** | Extend `get` / `list` to all entity types. **Mechanical** once 1C.2 lands. | 1C.2, 1S.3, 1S.4 | 05 §1 | 📋 | — |
| **1C.4** | `dial-cli export --env <env>`. Streams `GET /v1/admin/export` to stdout / file. **❌ dropped 2026-05-20** (Defer.1 in §5.5): deferred alongside 1S.6 — the underlying endpoint is gone from MVP. Design preserved in 05 §1, 06 §2.2. | 1C.0, 1S.6 | 05 §1 | ❌ dropped | — |
| **1C.5** | `dial-cli diff --source <env> --target <env>` — read-only, two GETs + structural diff. | 1C.3 | 05 §1 | 📋 | — |
| **1C.6** | `dial-cli completion {bash,zsh,fish}` via Picocli built-in. | 1C.0 | 05 §1 | 📋 | — |

### 5.2 Phase 2 — Write API for models + CLI write

**Track A — Server prerequisites (must land before write controllers)**

These map 1:1 to the named prerequisite PRs in `07-migration-and-rollout.md` §Phase 2 — file paths and required tests are spelled out there.

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **2S.0-pre** | `ApiKeyStore.addProjectKeys()` dual-format guard (`if (value.getKey() == null \|\| isBlank()) { value.setKey(apiKey); }`). Unit coverage for both formats. | — | 07 Phase 2 prereqs; OQ-12 | ✅ | `c551a7b5` |
| **2S.1-pre** | `PLATFORM_BUCKET` / `PLATFORM_LOCATION` constants on `ResourceDescriptor`. `ResourceDescriptorFactory.fromUrl()` `else if PLATFORM_BUCKET` branch. `ResourceTypes.of()` switch extension for new groups + URL-segment aliases (`schemas`, `keys`). | — | 07 Phase 2 prereqs | ✅ | `a56bed3d` |
| **2S.2-pre** | `ResourceType.urlSegment()` (default `group()`; aliases for `APP_TYPE_SCHEMA`/`PROJECT_KEY`). Route `getUrl()` / `getDecodedUrl()` through it; keep `getAbsoluteFilePath()` on `group()`. Round-trip tests required. | 2S.1-pre | 07 Phase 2 prereqs; 02 §5.3 | ✅ | `83645a59` |
| **2S.3-pre** | `ApiKeyStore.keys`: migrate `volatile HashMap` → `volatile ConcurrentHashMap` with reference-swap rebuild. Add `addOrUpdateKey(secret, data)` / `removeKey(secret)` fast-path mutators. Rewrite `addProjectKeys` to build fresh map + atomic swap, putting entries by `value.getKey()` (the secret post-2S.0-pre guard) per OQ-12 — fixes API-managed-key auth lookup. | — | 07 Phase 2 prereqs; OQ-12 | ✅ | `a573bfc5` |
| **2S.4-pre** | ~~`ResourceService.put(descriptor, body, skipLock=true)` package-visible overload.~~ **Dropped 2026-05-03** — `ResourceService.putResource(descriptor, body, etag, author, boolean lock)` already exists publicly (`storage/.../ResourceService.java:552`); `lock=false` provides the documented skipLock semantics. Already used externally (`PublicationService.java:337`). | — | 07 Phase 2 prereqs; 04 §2.5 | ❌ dropped | — |
| **2S.5-pre** | `FileConfigStore` constructor accepts `List<Consumer<Config>> initialOnReloadCallbacks`; registered before `vertx.setPeriodic` to close registration race. | — | 07 Phase 2 prereqs; 02 §4 | ✅ | `fad28222` |
| **2S.6-pre** | Make `FileConfigStore.load() → apiKeyStore.addProjectKeys` call conditional on `apiKeyStore != null`. Move authoritative call into `ConfigPostProcessor` invoked by `MergedConfigStore`. Wire `MergedConfigStore` to construct `FileConfigStore` with `apiKeyStore = null`. | 2S.5-pre, 2S.7-pre | 07 Phase 2 prereqs; 02 §4 | ✅ | `c5a115eb` |
| **2S.7-pre** | Extract `ConfigPostProcessor` from `FileConfigStore.load()` (pure refactor; structural pass only — two-pass split + slash-keyed-name rejection deferred to **2S.9** per its broader cross-entity validation scope). | — | 07 Phase 2 prereqs; 02 §4.1, §9 | ✅ | `65d8cd88` |

**Track A — Server core**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **2S.8** | `MergedConfigStore` — union of `FileConfigStore` + `ResourceService`. `requestRebuild()` non-blocking entry point. `volatile boolean initialized` guard for pre-init no-op. **Option C scope expansion (2026-05-03):** also patches `ConfigResourceController.handleSingleOrList` / `handleSchemaGet` for canonical-ID-first lookup so 1S.1 read paths surface blob entities. | 2S.5-pre, 2S.6-pre, 2S.7-pre | 02 §4; 04 §4.3 | ✅ | `4f8b7936` |
| **2S.9** | Invalid-entity sibling store. Listing/get response shape with `status` + `validationWarnings`. `config.reload.onInvalidEntity: skip\|abort` setting (default `abort` — opt-in `skip` enables the sibling store / status surface). Prometheus `dial_config_skipped_entities`, `dial_config_skip_events_total`. **Absorbs from 2S.7-pre (2026-05-03):** `ConfigPostProcessor` two-pass split (structural always-fatal → semantic skip\|abort) and slash-keyed-name rejection (warn + drop) across models / applications / interceptors / roles / routes / toolsets — these features are the natural fit for 2S.9's cross-entity validation scope. Also renames the 1S.7 health endpoint status `"healthy"` → `"ok"`/`"degraded"` per 02 §4.1. | 2S.8 | 02 §4.1, §4.3; 03 §4 | ✅ | `a8f15949` |
| **2S.10** | `SecretFieldProcessor` + `@EncryptedField` annotation in `:config`. Dual `ObjectMapper` (blob I/O vs API response). Mask `***` on Public-view; preserve-on-omit (and `***` sentinel) on `PUT`. Reuses `CredentialEncryptionService` primitives. | — | 04 §2.4–2.6 | ✅ | `7d9485a9` |
| **2S.11** | `MODEL` `ResourceTypes` entry. `POST /v1/models/public/{name}` (409 on conflict). `PUT /v1/models/public/{name}` (404 on missing, optional `If-Match` → 412). `DELETE`. Strict POST/PUT split. Bucket-aware authz. ETag in response header. **Amended by U.0 (2026-05-20):** POST surface dropped (returns 405); PUT is now upsert with `If-None-Match: *` / `If-Match: <etag>`; 409-on-POST and 404-on-missing-PUT are retired. See U.0 row below. | 2S.1-pre, 2S.2-pre, 2S.8, 2S.9, 2S.10 | 03 §1, §3; 07 Phase 2 | ✅ | `33543a70` |
| **2S.12** | `POST /v1/admin/validate` — model-scoped (Phase 4 extends to other types and bulk). | 2S.11 | 03 §6 | ✅ | `c92d14c0` |
| **2S.13** | Cross-reference validation on per-entity write — strict-by-default `422`; `config.write.softValidation` opt-in. | 2S.11 | 03 §6; 02 §9 | ✅ | `7204beae` |
| **2S.14** | Writer-pod immediate `volatile Config` swap via `rebuildNow()` after write. Keys-controller `DELETE` ordering invariant (delete blob → `removeKey` → `rebuildNow`). | 2S.11 | 02 §4 | ✅ | `dac53193` |
| **2S.15** | Canonical IDs in `entity.getName()` for API-managed entries. Drop `resetSimpleName` in `MergedConfigStore.rebuild()` so `Model.name` / `Interceptor.name` / `Role.name` / `Route.name` carry their canonical map key (`models/public/foo`) instead of being reset back to the simple name. Closes the OQ-23 contract: legacy `/openai/models`, `/openai/deployments`, `Role.limits` lookups in `RateLimiter`, log fields, and header propagation now surface canonical IDs for API-managed deployments — clients can copy a listing's identifier verbatim into `/openai/deployments/{id}/chat/completions`. New admin Configuration API listing at `/v1/{type}/{bucket}/` unchanged (projects `simpleName(mapKey)` from the controller per design 03 §4). File-sourced entities continue to expose simple names. **Operator-visible:** `Role.limits` for API-managed models keyed by canonical ID; doc note added to 06 §3. | 2S.8 | 02 §4 (resolution table); 03 §4; 06 §3; OQ-16, OQ-23 | ✅ | `e0e1039a` |

**Track B — CLI (models-only writes)**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **2C.0** | `dial-cli model add`. Wire: `PUT … If-None-Match: *`. `--dry-run`. Exit codes per 06 §2.8 (`0` / `5` / `2` / `3`) — exit `5` is `412` from the `If-None-Match: *` create-only gate, no longer `409` from a strict POST. No `--template` yet (Phase 4). **Amended by U.0 (2026-05-20):** wire verb is PUT-upsert with conditional header, not POST. See U.0 row below. | 1C.2, 2S.11 | 05 §1; 06 §2.8 | 📋 | — |
| **2C.1** | `dial-cli model update` (PUT — upsert when `--if-match` absent) with `--set k=v` (GET → local-merge → PUT). `--if-match` adds the CAS guard. Exit codes (`0` / `6` / `2` / `3`) — no exit `4` because the unconditional PUT is upsert (creates if missing); exit `6` is `412` from `If-Match` mismatch when `--if-match` is passed. **Amended by U.0 (2026-05-20):** PUT is now upsert, not update-only. See U.0 row below. | 2C.0 | 05 §1 (Update ergonomics) | 📋 | — |
| **2C.2** | `dial-cli model delete` with `--if-match`. | 2C.0 | 05 §1 | 📋 | — |
| **2C.3** | `dial-cli model validate` against `POST /v1/admin/validate`. | 2S.12, 2C.0 | 05 §1 | 📋 | — |
| **2C.4** | `dial-cli model promote --from --to` (as-is + explicit `--template` only — no `auto` reverse-match in MVP). | 2C.0 | 05 §4 | 📋 | — |
| **2C.5** | `dial-cli model diff --source --target` (single-type). | 2C.0 | 05 §1 | 📋 | — |

### 5.3 Phase 1.5 — Redis pub/sub (concurrent with Phase 2 write path)

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **1.5S.0-pre** | `ResourceTopic` codec: shared `ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES = false` + `JsonInclude.NON_NULL`. New `ResourceTopic(redis, key, mapper)` constructor; legacy delegates with safe defaults. `ResourceService` wires shared mapper. **Standalone PR before any 1.5 traffic.** | — | 07 Phase 1.5 prereqs; 02 §11.1 | ✅ | `4a0dc6d2` |
| **1.5S.1** | `ResourceTopic.subscribeAll(Consumer<ResourceEvent>)`. New `globalSubscribers` `CopyOnWriteArrayList`; second loop in `handle()`. | 1.5S.0-pre | 02 §11.1 | ✅ | `d3227841` |
| **1.5S.2** | `ResourceEvent.senderPodId` field (`@JsonInclude(NON_NULL)`, `@JsonIgnoreProperties(ignoreUnknown = true)`). Pod-UUID generated at `:server` boot, supplied to `ResourceService` via `Supplier<String>`. **Scope expansion 2026-05-04 (auto-mode batch):** also adds `"senderPodId":"@ignore"` to event-shape assertions in `ResourceApiTest` and `FileApiTest` to satisfy `NotExactComparator`'s strict size check — mechanical follow-on from the wire-shape change. | 1.5S.0-pre | 02 §11.1 | ✅ | `5dabec81` |
| **1.5S.3** | `MergedConfigStore` `subscribeAll` listener. Filter by `senderPodId` (skip-self) + resource type. 500ms trailing-edge debounce on `requestRebuild()`. Polling stays at 60s. | 1.5S.1, 1.5S.2, 2S.8 | 02 §11.1; 07 Phase 1.5 | ✅ | `3f8cc23d` |

### 5.4 Phase 3 — Write API for all entity types (mechanical extension)

**Track A — Server**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **3S.0-pre** | `ResourceAuthSettingsEncryptionService.processFields()` extension for `codeVerifier` with lazy plaintext fallback (catch base64 decode error → return as-is → re-encrypt on next write). | — | 07 Phase 3 prereqs; 04 §2.7 | ✅ | `56c54f4c` |
| **3S.1** | `BlobEntityValidator` helper for apps/toolsets — validates against current `Config` (interceptor refs, schema refs, deployment dependencies). Folded into Configuration API listing/get response only; chat-completion hot path unchanged. | 2S.9 | 07 Phase 3; 02 §4.3 | ✅ | `778d8f1c` |
| **3S.2** | Write APIs (PUT-upsert/DELETE) for `schemas`, `interceptors`, `roles`, `keys` (with dual-format compatibility from 2S.0-pre + DELETE ordering invariant from 2S.14), `routes`. `PUT` honors `If-None-Match: *` (412 create-only gate) and `If-Match: <etag>` (412 CAS guard). `POST` returns `405` with `Allow: GET, PUT, DELETE`. Start with one type to validate the pattern; subsequent types **Mechanical**. Generic per-type adapter dispatch (single parameterized write path). Cross-references stay scoped to 2S.13 (Model only) — new-type cross-refs deferred to Phase 4 per design 03 §6. **Scope narrowed 2026-05-04** (auto-mode halt): settings split into sibling slice 3S.2-settings; reasoning: settings GET projection from blob is missing (`MergedConfigStore.MANAGED_TYPES` excludes `GLOBAL_SETTINGS` and `handleSettingsGet` projects only file/default), and writes are inseparable from GET projection — splitting keeps both slices cognitively coherent. **Amended by U.0 (2026-05-20):** wire surface is PUT-upsert + DELETE, no POST. See U.0 row below. | 2S.11, 2S.13, 3S.0-pre | 03 §1, §3; 07 Phase 3 | ✅ | `161d5220` |
| **3S.2-settings** | Settings singleton: `PUT /v1/settings/platform/global` upsert + `DELETE` clears API blob, plus the API-blob projection on GET so `source: "api"` becomes reachable. Adds singleton-special handling in MergedConfigStore (or parallel blob-read on GET — architect decides at slice time). 405 on POST with `Allow: GET, PUT, DELETE`. **Added 2026-05-04** to scope-out from 3S.2. | 3S.2 | 03 §1, §3; 07 Phase 3 | ✅ | `821d1468` |
| **3S.3** | Admin write paths for `applications`, `toolsets` in `public/` via existing `ApplicationService` / `ToolSetService` unified with user-published. **Scope narrowed 2026-05-04** (auto-mode halt): test-only sweep mirroring 3S.4 — production code shipped under 1S.5's preflight + existing `ResourceController`. The "Removes `DeploymentService` config-file special-case" framing is implicit (admin writes never touch `DeploymentService`); the literal removal of the file-config branch from `findDeployment` was descoped because file-defined apps/toolsets remain an operator-facing config surface in MVP — that refactor is post-MVP work. | 3S.1 | 07 Phase 3; 02 §6 | ✅ | `b1a47e61` |
| **3S.4** | Admin write paths for `files`, `prompts`, `conversations` in `public/` via existing controllers + `ConfigAuthorizationService` preflight. Reuses existing resource types. **Production code shipped under 1S.5; this slice ships only the gap-filling integration tests.** | 1S.5 | 03 §1; OQ-21 | ✅ | `d66af8a1` |

**Track B — CLI**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **3C.0** | Generic Picocli command class parameterized by entity type so `add` / `update` / `delete` / `validate` / `promote` / `diff` ship for all remaining types. (If reviewer prefers per-type symmetry, split — but the principle §2.1 favors one parameterized class.) | 2C.5, 3S.2, 3S.3, 3S.4 | 05 §1 | 📋 | — |

### 5.5 Phase 4 — Declarative apply + diff (NICE TO HAVE)

> **MVP-cut**: deliver **4S.0**, **4S.1**, **4C.0** (apply with fully-resolved manifests). Defer the template DSL, overlays, bundles, and reverse-match `auto` promote.

**Track A — Server**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **4S.0** | `POST /v1/admin/apply` — bulk upsert; dependency-ordered sequential (`globalSettings → schemas → interceptors → roles → keys → routes → models → toolsets → applications`); continues on failure; per-entity status array. `precheck: true\|false` (default `true`); `softValidation` orthogonal; proposed-config validation always-on. **Unknown-kind narrowing 2026-05-04 (architect-plan halt):** `kind: Bundle` → batch-level **400** (CLI-only, structural rejection per 03 §7 line 349); other unknown kinds (e.g. `File`/`Prompt`/`Conversation` — out of 4S.0 scope) → per-entity `FAILED`, batch continues. Reconciles 03 §7's "400 for the offending entry" with the per-entity-status-array model: `Bundle` is structural-malformed payload; other unknowns are entry-level errors emitted via the per-entity `status` channel. | 3S.2, 3S.3 | 03 §7; 07 Phase 4 | ✅ | `c658d7f3` |
| **4S.1** | `POST /v1/admin/validate` — multi-entity, batch-aware with `precheck` semantics. **Phase-2 single-entity surface replaced (2026-05-04)**: shared precheck engine via package-private promotions of `validateOnly`/`mutateScratch`/`newScratch`/`entityId` + records on `AdminApplyController` (no apply behavior change). Validate diverges from apply by FAILing unknown kinds (stricter — CLI validate-first gate) and adds back the 2S.12 spec-node sentinel rejection. Status set: `valid`/`FAILED`/`skipped`; precheck-true + any FAILED → 422 with valid-entries re-mapped to skipped; response shape mirrors apply minus `applied`. | 4S.0 | 03 §6 | ✅ | `a2ea0663` |
| **4S.3** | **Global admin-write serialization.** New `AdminWriteLockService` thin facade (single key `"admin-writes"`) over existing `LockService`. Wrap `AdminApplyController.applyBatch` and `ConfigResourceController` per-entity `PUT`/`DELETE` (+ Settings PUT/DELETE) so every admin write path acquires the global lock **before** any per-resource lock. Amends `02-architecture.md` "per-entity serialization" anchor with new §4.4 "Cross-pod admin-write serialization"; clarifies `03-api-reference.md` §7 apply contract; lite arch + lite apply receive the same paragraphs. Rationale: admin writes are rare on real envs; full sequential ordering eliminates cross-pod cross-entity interleavings without observable user impact. Tested via `AdminWriteSerializationTest` (apply + per-entity POST + per-entity PUT/DELETE, all block while the lock is externally held). **ID note:** `4S.2` was reserved on 2026-05-09 (memory) for a deferred apply-summary-split slice; this slice claims `4S.3`. **Amended by U.0 (2026-05-20):** POST removed from lock scope — POST at the single-entity surface returns 405 before write logic; only PUT/DELETE acquire the admin-write lock. | 4S.1 | 02 §4.4; 03 §7 | ✅ | `4d13e59c` |
| **4S.4** | **Partial-update fast path for `MergedConfigStore` (resolves [OQ-32](08-open-questions-and-references.md)).** New `EntityChange` value record; `MergedConfigStore.applyEntityWrite` / `applyEntityDelete` / `applyBatch` / `applySettingsWrite` / `applySettingsDelete` — single-entity mutation under `rebuildLock`, no blob LIST/GET, single volatile-swap. New targeted helpers on `ConfigPostProcessor` (`validateSingleModel`, `validateSingleInterceptor`, `validateSingleRole`, `cascadeInterceptorDelete`, `sortRoutesInPlace`); existing `processSemantic` untouched. `ConfigResourceController` POST/PUT/DELETE callsites and settings PUT/DELETE switch from `rebuildNow()` to the per-entity calls; `AdminApplyController` accumulates `EntityChange`s and flushes one `applyBatch` after the apply loop. Decrypt-in-place pattern after `encryptFields`+blob put yields a fully-plaintext entity for the merged Config (handles PUT preserve-on-omit's mixed plaintext/ciphertext source). INTERCEPTOR writes resurrect previously-invalid models from `invalidEntities` when their cross-refs now resolve; INTERCEPTOR deletes cascade-invalidate dependent models under MODE_SKIP. `ApiKeyStore` ordering unchanged — controller fast-path call retained; partial-update path does NOT touch `ApiKeyStore`. `rebuildNow()` retained as fallback for `reload()` / admin `/reload`. **Out of scope: replica path** — `onResourceEvent` still calls `requestRebuild()` (event has no body; fetching from event-loop would re-introduce IO). Tested via new `MergedConfigStorePartialUpdateTest` (7 cases: INTERCEPTOR-delete cascade, INTERCEPTOR-write resurrection, PROJECT_KEY-write avoids ApiKeyStore, ROUTE ordering, applyBatch failure-accumulation, GLOBAL_SETTINGS overlay+restore, volatile-swap reference identity). Amends 02 §4 rebuild-trigger paragraph; closes OQ-32 in doc 08. Lite arch receives the same paragraph. **Sourced from PR #1529 reviewer feedback (thread r3232995101, @astsiapanay 2026-05-13 → 2026-05-18).** | 4S.3 | 02 §4; 08 OQ-32 | ✅ | `a9f718ad` |
| **4S.5** | **Replica-pod partial-update on pub/sub (extends [OQ-32](08-open-questions-and-references.md) closure to replicas).** `MergedConfigStore.onResourceEvent` stays on the event loop for filter-and-dispatch; new `applyReplicaEvent(descriptor, action)` runs on `taskExecutor` and: (a) for DELETE — snapshots PROJECT_KEY secret from in-memory `Config.keys` then `apiKeyStore.removeKey` (mirrors writer ordering), routes GLOBAL_SETTINGS to `applySettingsDelete`, others to `applyEntityDelete`; (b) for CREATE/UPDATE — `resourceService.getResource(descriptor)` (Redis two-tier cached after writer's put), null body → treat as delete, else parse + typed deserialize + `secretFieldProcessor.decryptFields`, then for PROJECT_KEY `apiKeyStore.addOrUpdateKey` BEFORE `applyEntityWrite`, for GLOBAL_SETTINGS `applySettingsWrite`, else `applyEntityWrite`. Any exception → log + `requestRebuild()` fallback (preserves correctness SLA; pub/sub stays a latency optimization). **Fetch-on-receipt chosen over embed-body in `ResourceEvent`**: keeps `ResourceEvent` schema stable across the codebase, secrets opaque in pub/sub frames, large entities (App schemas) out of every frame, reuses Redis two-tier cache. Self-event filter on `senderPodId` unchanged. Tested via new `MergedConfigStoreReplicaUpdateTest` (PROJECT_KEY write → addOrUpdateKey-then-applyEntityWrite; PROJECT_KEY delete → secret-snapshot-then-removeKey-then-applyEntityDelete; GLOBAL_SETTINGS write/delete; CREATE with null fetch falls back to delete; fetch exception falls back to `requestRebuild`; self-pod event filtered). Amends 02 §4 rebuild-trigger paragraph (replica fast path); lite arch matching paragraph; OQ-32 closure note extended in doc 08. **Sourced from PR #1529 review-thread r3232995101 continuation (2026-05-19 `/dial-uc-debug` investigation).** | 4S.4 | 02 §4; 08 OQ-32 | ✅ | `3a61117e` |

**Track B — CLI**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **4C.0** | `dial-cli apply -f <path>` — single-doc and multi-doc YAML manifest parsing, validate-first gate (`POST /v1/admin/validate`) then `POST /v1/admin/apply`. `--dry-run`. Exit codes per 06 §2.8. **No template DSL, no overlays, no bundles in MVP — manifests must be fully resolved.** | 4S.0, 4S.1 | 03 §7; 05 §5.1 | 📋 | — |

**Polish round (post-MVP, follow-on to user-reported `/dial-uc-debug` issues — 2026-05-08):**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **Polish.1** | Listing canonical IDs for API entries + dedup-by-full-key. `ConfigResourceController.handleGet` lambdas project the canonical map key as `name` for API entries (`fromApi(key) ? key : simpleName(key)`); `respondList` / `handleSchemaGet` listing branches dedup the row map by full Config map key (not simple name) so file/API simple-name twins appear as distinct rows. Schema single-entity GET canonical match also emits canonical name. Simple-name fallback in GET preserved — file entries remain GET-able. **Tests flipped:** `MergedConfigStoreApiTest` (single-GET + listing assertions), `ModelWriteApiTest.testPost201HappyPath` / `testPostImmediatelyVisibleOnGet`, `CanonicalIdListingTest.testApiManagedModelAdminGetProjectsCanonicalId` (renamed). **New regression guard:** `MergedConfigStoreApiTest.testFileAndApiTwinsAppearAsSeparateListingRows` locks the dedup-by-key invariant. Amends design 03 §4 *name field synthesis* + listing example payloads + new *Listing dedup* paragraph; locked-decision amendment captured in `project_unified_config_review.md`. **Sourced from `/dial-uc-debug` round 2026-05-08 (Issue 1).** Architect-plan halt at scope check (the original "drop fallback" plan would have made file entries listing-only, breaking ~10 fixture-using test classes); user picked option (b') — keep fallback, fix listing dedup + projection only. | 2S.15 | 03 §4 (amended) | ✅ | `811d235c` |
| **Polish.2** | Eight perf/correctness fixes bundled from PR #1529 reviewer feedback (KirylKurnosenka + astsiapanay, 2026-05-12/14). **MergedConfigStore:** drop `volatile` from `initialized` and replace `AtomicLong pendingRebuildTimerId` with plain `long` (both now read only under `synchronized(this)`); pre-register skip-event `Counter`s in an `EnumMap<ResourceTypes, Map<String, Counter>> skipCounters` for the cartesian of `MANAGED_TYPES × {parse_error, validation_error, decryption_error}` to drop per-skip registry lookup; new `warnIfReplaced` static logs a warning when any `Map.put(canonicalId, …)` in `addBlobEntity` overwrites a prior entry. **SecretFieldProcessor:** static `ConcurrentHashMap<Class<?>, List<Field>> FIELDS_CACHE` and `ConcurrentHashMap<Class<?>, Boolean> HAS_ENCRYPTED_FIELD_CACHE` memoise the reflective field walks; `setAccessible(true)` moved to cache-fill time, **wrapped in try/catch** to tolerate JPMS rejection on `java.lang.Enum.name` for entity classes that inherit from `Enum` (regression caught at §B gate). **ConfigPostProcessor:** `static final Pattern RESOURCE_KEY_PATTERN`. Pushback replies posted on the PR for the two declined items (#5 full-rebuild design intent, #8 reference-identity check) and a context-answer for the codeVerifier-legacy question. **Process retrospective:** auto-mode discipline drift — implementation was done inline (Edit tool) instead of via the agent loop; user caught it and rolled back to SIMPLIFY + REVIEW subagents on the diff, which surfaced the EnumMap consistency switch and the latent NPE-guard removal. JPMS regression then caught at §B `:server:test` after env cleanup (stale embedded Redis). | 2S.15, Polish.1 | n/a (review feedback) | ✅ | `50d8f61b` |
| **Defer.1** | **Defer `/v1/admin/export` endpoint + `dial-cli export` from MVP.** Core team has concerns about the current implementation; export is not MVP-critical and is deferred to a later phase alongside audit. **Code removed:** `server/.../controller/AdminExportController.java`, `server/.../AdminExportTest.java`, `server/.../SecretMaskingApiTest.java` (its only assertion path was `GET /v1/admin/export`; per-entity-GET secret masking remains covered by 2S.10's `SecretFieldProcessor` tests and the dual-`ObjectMapper` coverage); `RouteTemplate.CONFIG_EXPORT` entry; `ControllerSelector.get(CONFIG_EXPORT, …)` handler; one Javadoc comment in `ConfigModelListTest.java`. **Slice register:** 1S.6 (merged `ec1ac537`) → ❌ dropped; 1C.4 (planned) → ❌ dropped. **Doc changes (full + lite, design preserved — marked "deferred — Defer.1"):** README §"after" cell; 02 §10 gradual-migration paragraph; 03 §1 endpoint listing, §2 wrapper note, §4 listing-context paragraphs, §7 phase-gate note; 04 §1.2 ops-endpoint comment, §3.7 503-unaffected list; 05 §1 commands tables; 06 §2.2 listing notes + export example; 07 Phase 1 bullet, Phase 4 CLI bullet, Phase 5 Admin Backend + multi-destination paragraphs; 08 OQ-10 / OQ-11 / OQ-17 parentheticals; 09 §6.1 `dial_admin_get_runtime_config` row + §6.2 `dial_admin_apply_manifests` detection mechanism. **MCP impact:** `dial_admin_get_runtime_config` marked deferred (depends on export); `dial_admin_apply_manifests` `confirm` delete-detection mechanism falls back to either conservative always-confirm or per-type listing enumeration (blob-only, no file-source diff) — implementer choice at MCP-1 time. **Memory:** `project_unified_config_review.md` 2026-05-20 entry. **Sourced from `/dial-uc-debug` round 2026-05-20 (core-team deferral ask).** | 1S.6, 1C.4 | 02 §10; 03 §1, §4, §7 (amended); 04 §1.2, §3.7 (amended); 05 §1; 06 §2.2; 07 Phase 1/4/5; 08 OQ-10/11/17; 09 §6.1, §6.2 | ✅ | `9d85c35d` |
| **U.5** | **`extraData` duality split — plaintext `extraData` + encrypted `secretExtraData` (pending core-team decision on [OQ-34](08-open-questions-and-references.md)).** Core team raised (2026-06-03) that `extraData` is a wide property — more often non-secret (region-only Bedrock `{"region":"us-east-1"}`) than secret (Bedrock IAM credentials), sometimes mixing both in one JSON document — and the post-U.4 state (wholesale `ENC[...]` at rest + `WRITE_ONLY` on every GET, no reveal path) makes the common non-secret case un-inspectable. Design proposed in 04 §2.11 / OQ-34; this row is the execution sketch, **blocked until the core team accepts**. **Server changes (on acceptance):** `config/.../Upstream.java` — drop `@EncryptedField` + `@JsonProperty(WRITE_ONLY)` from `extraData` (restores the pre-U.4 user-facing serialization on `/v1/applications` / `/v1/toolsets`); add `secretExtraData` with `@EncryptedField` + `@JsonProperty(WRITE_ONLY)` + `@JsonDeserialize(JsonToStringDeserializer)` + `@ToString.Exclude` (inherits the full 04 §2.5 secret contract — `SecretFieldProcessor`, dual-mapper, blob-I/O `ToStringSerializer` modifier, preserve-on-omit — zero new crypto code). New merge helper producing the `X-UPSTREAM-EXTRA-DATA` header value: one field populated → verbatim passthrough (any JSON shape / arbitrary string — `JsonToStringDeserializer` accepts scalars; custom-app schema types `dial:extraData` as `"string"`); both populated → both must parse as JSON objects, shallow top-level merge, overlapping top-level keys → write-time 422 (validation in the model write path + `AdminValidateController`). Header construction call sites (`BaseDeploymentPostController` ~line 202, `ResponseItemController` ~line 86) switch to the merged value; hot-path log statements keep `getExtraData()` — non-secret part only — closing the pre-existing plaintext-secret-in-logs leak (`DeploymentPostController` ~270/341, `ResponsesController` ~204/333, `BaseDeploymentPostController` ~142). **Test changes:** reflective `@EncryptedField` sweep (`EncryptedFieldNegativeAnnotationTest` or equivalent) updated — `extraData` out, `secretExtraData` in; `SecretFieldProcessorTest` / `DualMapperTest` / `ModelWriteApiTest` preserve-on-omit + secret-absent-on-GET coverage moves to `secretExtraData`; `extraData` visible-on-Owner-GET asserted; merge-contract tests (verbatim single-field incl. non-object values, object merge, overlap 422, non-object-with-both-populated 422); restore pre-U.4 `extraData` fixtures in `CustomApplicationApiTest` / `ApplicationDeploymentApiTest`. **Doc changes (on acceptance):** 04 §2.2 / §2.4 / §2.5 / summary checklist + §2.11 promoted from proposed to locked; 02 §8 (string round-trip invariant narrows to `secretExtraData`); 03 §2 example payload + §3.1 preserve-on-omit list; lite 03 / lite 04; 08 OQ-34 moves to the resolved register. **Locked decisions reopened (on acceptance):** 2026-05-01 (b) "encrypt `extraData` wholesale, no per-field carve-outs — deliberate trade-off"; U.4's sub-decision adding `WRITE_ONLY` to `Upstream.extraData`. **Memory:** 2026-06-03 entry in `project_unified_config_review.md` (proposal pending — not a lock change yet). **Sourced from `/dial-uc-debug` round 2026-06-03 (core-team duality concern).** **OQ-34 ACCEPTED as proposed by core team 2026-06-08; code merged. Design-doc syncs (04 §2.2/§2.4/§2.5/§2.11→locked, 02 §8, 03 §2/§3.1, lite docs, 08 OQ-34→resolved) remain pending as a follow-up doc-sync.** | 2S.10, U.4 | 04 §2.11; 08 OQ-34 | ✅ | `d985c9eb` |
| **U.4** | **Remove `security-admin` role and `"***"` mask sentinel for secrets.** DIAL team agreed (review call surfaced 2026-05-25) to postpone the plaintext-reveal feature; the design is preserved in 04 §2.6 marked deferred. Reverses four locked review-round decisions (2026-04-30 §2.5–§2.6 reveal flow; 2026-05-21 §1.5 secrets-on-their-own-track; 2026-05-21 U.1 `keys` security-admin carve-out; OQ-12 `"***"` masking + reveal). **Server changes:** drop `ConfigAuthorizationService.isSecurityAdmin`, `AdminRoleAuthorizationService.isSecurityAdmin`, `AccessService.securityAdminRules` field + `hasSecurityAdminAccess()` + `securityAdminRules(JsonObject)`. Delete `EncryptedFieldMaskModifier` (the `***`-emitter) and remove its registration from `ProxyUtil.MAPPER`; the `EncryptedFieldAnnotationIntrospector` override stays on `BLOB_MAPPER` only (the blob-write path needs `@EncryptedField` fields visible to persist ciphertext). Add `@JsonProperty(WRITE_ONLY)` to `Upstream.extraData` (the only previously-unannotated `@EncryptedField` field). `ConfigResourceController.handleGet` drops the `?reveal_secrets=true` gate, parameter threading, and BLOB_MAPPER branch; `projectItem` no longer takes a `revealSecrets` flag. `FileConfigController` denies `/keys` for every caller (unconditional 403, replacing the security-admin carve-out); drops the `?reveal_secrets=true` gate. `SecretFieldProcessor.MASK_SENTINEL` / `validateNoMaskSentinel` / `maskInPayload` retired; new `stripEncryptedFields` helper backs `ConfigResourceController.projectInvalidItem` (drops encrypted fields rather than masking). `mergePreservingOmittedSecrets` treats only null / missing as the omitted signal — a literal `"***"` is a real value. `AdminValidateController` drops `sentinelCheck()` and the `SecretFieldProcessor` dependency; `ControllerSelector` call site updated. **Test changes:** `DualMapperTest` rewritten (MAPPER drops encrypted fields rather than emitting `"***"`). `SecretFieldProcessorTest` drops sentinel-rejection tests; `maskInPayload` tests → `stripEncryptedFields`; `mergePreservingOmittedSecrets_treatsMaskAsLiteralValue` asserts the new contract. `ConfigKeyTest` / `FileConfigApiTest` flipped — file `keys` denied 403 across admin / user / security-admin claims. `ModelWriteApiTest` removes sentinel and reveal_secrets test paths; secret-absent on GET asserted. `ConfigEntityWriteApiTest` removes `KEY_BODY_SENTINEL` fixture + sentinel-rejection test. `AdminValidateApiTest` removes V07/V08 sentinel tests. `ConfigApiTest` asserts `upstream.key == null` on GET (was `"***"`). `ResourceBaseTest` drops the `"security-admin"` claim from the JWT-validator mock. `FileConfigControllerTest` drops `isSecurityAdmin` mocks. `"extraData": null` fixture entries in `CustomApplicationApiTest` and `ApplicationDeploymentApiTest` removed (WRITE_ONLY drops the field entirely rather than emitting `null`). **Doc changes:** 04 §1.5.1 (drop security-admin carve-out, replace with deny-keys); 04 §1.5 secrets-track note retired; 04 §2.5 rewritten (no sentinel column, no `"***"` rejection on create); 04 §2.6 marked deferred with design preserved; 03 §1 endpoint comments + §3.1 preserve-on-omit paragraph; 08 OQ-12 amended (reveal-via-security-admin sentence retired). **Memory:** new 2026-05-25 entry in `project_unified_config_review.md` documenting the four-lock reversal. **Sourced from `/dial-uc-debug` round 2026-05-26** (DIAL-team review-call deferral). | 2S.10, U.1 | 04 §1.5, §1.5.1, §2.5, §2.6 (amended); 03 §1, §3.1 (amended); 08 OQ-12 (amended) | ✅ | `a7f5410d` |
| **U.2** | **Per-type Public allowlist for `public/`-bucket entities (implements the 2026-05-21 §1.5 projection-lock amendment).** Today's `ConfigResourceController.projectItem` (`server/.../controller/ConfigResourceController.java:760-769`) serializes the entire entity through Jackson's default mapper and only masks `@EncryptedField` values — so `GET /v1/models/public/{name}` currently exposes `Model.upstreams`, `Upstream.endpoint`, interceptor references, and other infrastructure fields to any authenticated reader of `public/`. The Public / Owner view classes (`Views.java`), per-field `@JsonView` annotations, and the fail-closed `DEFAULT_VIEW_INCLUSION = false` mapper configuration that earlier slice anchors (1S.1, 2S.11, design 04 §1.5) refer to **were never implemented** — only the `name` / `status` envelope and the admin-gated `validationWarnings` carve-out (in `projectInvalidItem`) exist today. This slice builds the projection contract end-to-end. **Realization choice — architect-plan halt:** pick either (a) Jackson `@JsonView(Public)` / `@JsonView(Owner)` annotations on `config/.../Model.java`, `Application.java`, etc., gated by a dedicated projection `ObjectMapper` with `DEFAULT_VIEW_INCLUSION = false` (blob mapper + file mapper keep their default `true` so round-trip through `aidial.config.json` and blob storage is unaffected), OR (b) new per-type public DTOs continuing today's `ModelData` / `ApplicationData` pattern with hand-curated builders. Both produce the same observable allowlist; (a) is more declarative and keeps the contract next to the entity, (b) matches existing precedent and avoids touching `config/` entity classes that are a CLI Gradle dependency. **Server changes:** new `Views.java` with `class Public { }` + `class Owner extends Public { }` (only relevant for realization (a)); per-type Public-allowlist definition matching `ModelData` (id, displayName, displayVersion, iconUrl, description, features, capabilities, tokenizerModel, limits, pricing, attachmentTypes, defaults, owner, createdAt, updatedAt — **not** upstreams, overrideName, fieldsHashingOrder, interceptors), `ApplicationData` (identity + display + applicationProperties + applicationTypeSchemaId + function + routes + viewerUrl + editorUrl + attachment types + defaults — **not** endpoint, env, interceptors), and analogous lists for `toolsets`, `schemas`, admin-managed `files` / `prompts` / `conversations`; rewrite `ConfigResourceController.projectItem` to call the projection (passing the caller's Public/Owner role); rewrite `projectSchemaItem` to apply the same view; `projectInvalidItem` already gates `validationWarnings` on admin — re-thread it through the new `Views` mechanism for consistency. New `ConfigAuthorizationService.projectionFor(ctx, descriptor)` returning `Class<?>` (Owner when admin or bucket-owner, Public otherwise) — the design 04 §1.5 dispatch helper that 1S.0's interface anticipated but never landed (the Javadoc at `ConfigAuthorizationService.java:32` already references it). Listing endpoint at `/v1/metadata/{type}/{bucket}/{path}` is **unchanged** — U.0 already made it `ResourceItemMetadata`-only (no entity body, no projection). **Allowlist additions for new fields are an explicit decision recorded in design 04 §1.5** — `DEFAULT_VIEW_INCLUSION = false` (realization a) or the absence of a setter on the DTO (realization b) ensures the failure mode is invisibility, not silent Public exposure. **Test changes:** new `ConfigPublicProjectionTest` (or similar) with admin / bucket-owner / plain-authenticated matrix per `public/`-bucket type, asserting (i) infrastructure fields (`upstreams`, `endpoint`, `extraData`, `interceptors`, `Application.env`) are **absent** from non-admin / non-owner responses, (ii) the Public allowlist matches today's `/openai/models` / `/openai/applications` field sets one-for-one (snapshot/golden-file pin per type to catch silent additions in code review), (iii) Owner readers see all entity fields except secrets, (iv) secrets remain masked as `"***"` for Owner (the allowlist + secret-masking layers compose orthogonally — `?reveal_secrets=true` + security-admin is the only path to plaintext), (v) `validationWarnings` continues to be Owner-only. Existing `ConfigModelReadTest`, `ConfigInterceptorTest`, `ConfigRoleTest`, `ConfigKeyTest`, `ConfigRouteTest`, `ConfigSchemaTest`, `ConfigSettingsTest`, `ModelWriteApiTest`, `InvalidEntityApiTest`, `MergedConfigStoreApiTest`, `ApplicationApiTest`, `ToolSetApiTest`, etc. — any test that asserts a non-admin reader sees an infrastructure field has to flip (the new contract makes that field admin-only). **Doc changes:** 04 §1.5 has the contract paragraphs from the 2026-05-21 amendment (no further amendment); 03 §2 / §4 example payloads must split into Public vs Owner shapes per type (today the examples are mostly Owner-shaped with secrets masked — Public examples need to be added for `public/`-bucket types); lite 04 §Public vs Owner views already carries the high-level contract from this session's edit. **Locked decisions reopened:** none — this slice **implements** the 2026-05-21 amendment to the 2026-04-30 projection lock; the amendment itself is already in the memory file. **Memory:** the 2026-05-21 projection-lock amendment entry in `project_unified_config_review.md` is the contract this slice satisfies; no new memory entry needed unless the realization choice (a vs b) is itself locked at architect time. **Dependencies:** `1S.1` (per-entity GET surface), `U.0` (PUT-upsert contract + metadata listing), `U.1` (blob-only per-entity GET, `source` field retired — eliminates a Public-vs-Owner concern). **Sourced from `/dial-uc-debug` round 2026-05-21** (verification found that the 2026-04-30 lock's premise — "today's `/openai/models` exposes upstreams/endpoints to authenticated users" — was factually wrong; today's `/openai/models` uses a hand-curated public DTO that *already excludes* those fields, so the proposal as written would have regressed the public posture rather than preserved it). | 1S.1, U.0, U.1 | 04 §1.5 (the 2026-05-21 amendment is the spec); 03 §2, §4 (example payloads to split Public/Owner per type) | 📋 | — |
| **U.1** | **Separate `/v1/admin/config/file/*` surface for file-sourced entries; per-entity GET becomes blob-only; `source` field retired entirely.** Core team flagged that the per-entity GET (`/v1/{type}/{bucket}/{name}`) was the last surface mixing file and blob sources — under the prior simple-name fallback in `ConfigResourceController.handleSingleGet`, file-defined `Config.keys` entries (whose map keys equal secret values per OQ-12) could be addressed via URL, leaking the secrets into logs, traces, metrics labels, and error messages. **Server changes:** drop the simple-name fallback in `ConfigResourceController.handleSingleGet` / `handleSchemaGet`; rewrite `handleSettingsGet` to be blob-only (404 when no API blob, no file/default projection); strip the `source` field from `projectItem`, `projectSchemaItem`, `projectInvalidItem` — the URL itself discloses the source so the field has no remaining consumer. Add new `RouteTemplate.ADMIN_FILE_CONFIG` (`^/v1/admin/config/file/(?<type>models|interceptors|roles|keys|routes|schemas|settings)(?:/(?<name>.+))?$`); new `FileConfigController` (read-only listing + per-entity GET) wired in `ControllerSelector`. Authz: admin role for every supported type EXCEPT `keys`, which requires the existing `ConfigAuthorizationService.isSecurityAdmin` tier (file map keys equal secrets per OQ-12). New `MergedConfigStore.getFileSourcedConfig()` accessor lets the file-config surface project the *file-side* singleton settings view independently of the merged Config's API overlay. **Test changes:** new `FileConfigApiTest` with admin / security-admin / non-admin matrix across all 7 types plus settings; flip every test that asserted `source: "file"` or `source: "api"` on per-entity GET (`ConfigRoleTest`, `ConfigInterceptorTest`, `ConfigKeyTest`, `ConfigRouteTest`, `ConfigModelReadTest`, `InvalidEntityApiTest`, `ModelWriteApiTest`); rewrite `ConfigSettingsTest` for blob-only per-entity behaviour with file/default projection on `/v1/admin/config/file/settings/global`; update `MergedConfigStoreApiTest`'s file-readable assertion to use the new endpoint; `AdminApplyApiTest` / `AdminValidateApiTest` settings assertions flipped. `ConfigBootstrapTest` auth tests updated (file entries now 404 on per-entity GET, but auth still gates 401/403 before lookup). **Doc changes:** 02 §4 operator-vs-runtime paragraph; 03 §1 new file-config endpoints + per-entity blob-only note + singleton rewrite; 03 §2 drop `source` from response shape; 04 §1 new "File-config inspection surface" subsection + drop `source` from Public/Owner views in §1.5; 08 OQ-10 amendment (singleton three-state projection retired). **Locked decisions reopened:** Polish.1's "simple-name fallback in GET preserved — file entries remain GET-able"; OQ-10's singleton three-state `source` projection; the entire `source` field across the per-entity Configuration API. **Memory:** new 2026-05-21 entry in `project_unified_config_review.md` documenting the three reopenings. **Sourced from `/dial-uc-debug` round 2026-05-20 → 2026-05-21 (core-team concerns about embedding file entities in the per-entity API; user-driven design via four AskUserQuestion forks: keep status-quo / separate API / B-then-C / keys-only carve-out — picked separate-API option; then user-driven escalation to fully symmetric file/blob separation on the singleton too, retiring the `source` field entirely). | 2S.15, Polish.1, Polish.2, U.0 | 02 §4 (amended); 03 §1, §2 (amended); 04 §1, §1.5 (amended); 08 OQ-10 (amended) | ✅ | `f45cd868` |
| **U.3** | **Admin-only `forwardAuthToken` write carve-out for `applications` / `toolsets`.** `Application.forwardAuthToken` / `ToolSet.forwardAuthToken` (inherited from `Deployment`) is a security-sensitive flag — when `true`, the deployment receives the caller's downstream auth token. `ApplicationService.prepareApplication` (line 514) and `ToolSetService.putToolSet` (line 92) unconditionally strip the field to `false` on every write — pre-existing policy from commit `00c75ecc` (module split, pre-dates unified-config), correct for user-bucket writes but locks out admin writes too. **Server changes:** `ApplicationService.putApplication` and `ToolSetService.putToolSet` gain a `boolean preserveForwardAuthToken` parameter that conditionalizes the strip. Callers explicit: `ResourceController.putResource` computes `descriptor.isPublic() && accessService.hasAdminAccess(context)` and passes through (admin writes to `public/` preserve; user-bucket and non-admin writes strip — defense in depth at the authz layer rejects non-admin `public/` writes earlier); `AdminApplyController.applyApplication` / `applyToolSet` pass `true` (bulk apply is always admin); `PublicationService.approve` passes `false` — user-authored content must not smuggle in `forwardAuthToken: true` via admin publication approval. **Test changes:** new `ConfigAdminAppToolsetWriteTest` cases: admin PUT to `/v1/applications/public/...` and `/v1/toolsets/public/...` with `forward_auth_token: true` → GET returns `true`; user-bucket PUT with the same → GET returns `false`. Existing call sites in `PerRequestPermissionsApiTest`, `DeploymentPostApiTest`, `ToolSetServiceTest` updated to pass `false` (preserve today's behavior at those test fixtures). **Doc changes:** 04 §1.4 (new paragraph documenting the carve-out); lite 04 (one-line note). **Models unaffected:** `Model.forwardAuthToken` has no analogous strip — admin can already set it on a model via `ConfigResourceController` writes (which go through `ResourceService.putResource` directly). **Sourced from `/dial-uc-debug` round 2026-05-26.** | 1S.5, 3S.3 | 04 §1.4 (amended); IMPLEMENTATION.md §5.5 | ✅ | `ed5b652a` |
| **U.0** | **Wire-shape unification with the existing Resource API.** Reverses four locked review-round decisions (strict POST/PUT split locked 2026-04-25; trailing-slash listing locked 2026-04-30; inline-body listing + Public/Owner projection locked 2026-04-30; `?cursor`/`hasMore`/`max=500` pagination locked 2026-04-30) at the core team's request. **Server changes:** drop trailing-slash branch from `RouteTemplate.CONFIG_RESOURCE` regex; add sibling `RouteTemplate.CONFIG_RESOURCE_METADATA` (`^/v1/metadata/(models\|interceptors\|roles\|keys\|routes\|schemas\|settings)/(?<bucket>[a-zA-Z0-9_-]+)/(?<path>.*)$`); wire into `ControllerSelector`. Rewrite `ConfigResourceController`: delete `handlePost` (returns 405 with `Allow: GET, PUT, DELETE`); collapse `handlePut` into PUT-upsert honoring `If-None-Match: *` (412 create-only gate) and `If-Match: <etag>` (412 CAS guard) via `ProxyUtil.etag(...)` / `EtagHeader`; add `If-None-Match: <etag>` 304 path on GET; reroute folder listing through `ResourceService.getMetadata(descriptor)` returning `ResourceFolderMetadata` with `ResourceItemMetadata` items (blob-only — file entries stay reachable via `/v1/admin/export`); rename pagination params `cursor` → `token`, drop `hasMore` envelope field, bump max to 1000. `AdminApplyController` per-entity step issues `PUT` (already does — confirm no POST remains). Drop the singleton settings special-case in apply (uniform PUT for every type). **Test changes:** flip every test that sends POST to `PUT … If-None-Match: *`; flip listing tests from `/v1/{type}/{bucket}/` to `/v1/metadata/{type}/{bucket}/` + new envelope assertions; add 304-on-If-None-Match coverage; add 405-on-POST coverage; rename or drop `MergedConfigStoreApiTest.testFileAndApiTwinsAppearAsSeparateListingRows` (Polish.1 regression guard) — file twins no longer appear in metadata listings under blob-only semantics. **Doc changes:** 02 §2/§4.1/§4.3/§5.1; 03 §1/§2/§3/§4/§6/§7 (load-bearing rewrite); 04 §1.5/§2.5/§3.2; 05 §1; 06 §2.8; 09 §6.1. Memory: 2026-05-20 entry in `project_unified_config_review.md` documenting the four-lock reversal. **Sourced from `/dial-uc-debug` round 2026-05-20 (core team unification ask).** Architect-plan halt at scope check (the change reopens four locked decisions); user approved direct reopening and picked Full Alignment + blob-only listings + dropped listing projection. **Round-3 SIMPLIFY/REVIEW polish:** GET 304 path moved off the event loop via `taskExecutor.submit`; PUT-upsert collapsed to a single `getResourceWithMetadata` pre-read (one round-trip under the admin-write lock instead of two); metadata controller verb-guarded (non-GET/HEAD → 405 with `Allow: GET`); shared `ProxyUtil.MetadataQuery` helper extracted; settings 405 on metadata route emits `Allow: GET` (RFC 9110 §15.5.6 — `Allow` lists verbs valid on the *requested* resource, not on a sibling URL); shared `descriptorFor(ResourceTypes)` helper unifying the per-type switch; `SETTINGS_TYPE` string literal replaced with `ResourceTypes.GLOBAL_SETTINGS`; narrative slice-ID comments removed; lite-version docs swept in lockstep. | 2S.15, Polish.1, Polish.2 | 02 §4.1, §4.3, §5.1 (amended); 03 §1, §3, §4, §6, §7 (amended); 04 §1.5, §2.5, §3.2 (amended); 05 §1; 06 §2.8; 09 §6.1 | ✅ | `e9f09442` |

**Deferred beyond MVP** (if Phase-4 demand emerges post-MVP):

- **4C.1** Template DSL (`extends`, `includes`, `!if`, `!for`, function set) — 05 §3
- **4C.2** Overlays (base + overlay) — 05 §5.2
- **4C.3** Bundles — 05 §5.3
- **4C.4** `${SECRET:*}` resolution — 05 §3.1
- **4C.5** `promote --template auto` reverse-match — 05 §4

---

### 5.6 MCP-1 — DIAL Admin MCP server (Track C, interleaved with Phases 1–3)

> Spec: `09-admin-mcp-spec.md`. MCP-1 ships all 9 building-block tools per §6.1; read tools depend on Phase 1 reads, write tools depend on Phase 2/3 writes. MCP-2 (service-account OIDC) and audit tools are deferred — see footer.

**Track C — MCP**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **M.0-pre** | `:mcp` Gradle module bootstrap. Add `include 'mcp'` to `settings.gradle`; `mcp/build.gradle` with `implementation project(':config')` + `io.modelcontextprotocol.sdk:mcp` + Lombok wiring (matches sibling modules). Wire `/mcp` path-prefix branch into `Proxy.handleRequest()` short-circuiting to a new `McpRequestHandler`. Deploy MCP verticle in `AiDial.start()` when `mcp.enabled = true`. Wire `mcp.*` settings into `aidial.settings.json` defaults. Document extraction discipline in `mcp/CONTRIBUTING.md` (REST-only loopback, no direct service injection). | — | 09 §1, §7.1, §7.2, §8 kickoff checklist | 📋 | — |
| **M.0.1-pre** | Threading bridge: pick captured-context dispatch (option a per §7.2 recommendation) or worker-pool dispatch (option b); wire Vert.x context lifecycle for tool handlers. `DialClient` HTTP wrapper (REST-only loopback facade — the swap point for future extraction). Integration test harness mirroring `ResourceApiTest` style. | M.0-pre | 09 §7.2 | 📋 | — |
| **M.0.2-pre** | Per-session rate-limiting + concurrency cap (defaults: `mcp.rateLimit.callsPerMinute = 60`, `burstCapacity = 10`, `mcp.concurrency.maxConcurrentCallsPerSession = 5`). Token-bucket per session-id. Structured error with `retry_after` hint on overflow. | M.0-pre | 09 §7.1 (M10), §9 risk row 1 | 📋 | — |
| **M.1.0** | Read tools bootstrap: `dial_admin_describe_schema(type)`, `dial_admin_list_entities(type, bucket, env, filter?, token?)`, `dial_admin_get_entity(type, id, env)`. In-process registry lookup against `:config` JSON Schema generators (M9 — `dial_admin_describe_schema` is a function call, not an HTTP round-trip). Bucket-alias resolution (`private` / `public` / `platform` per §6.2); lazy per-session bucket fetch on first `private` use. Listing returns `ResourceFolderMetadata` with `ResourceItemMetadata` items per [`03-api-reference.md`](03-api-reference.md) §4 — single `items[]` array, no projection on items. Pagination via `?token=&limit=` (max 1000 per page) matching the existing Resource API metadata endpoint. Error shaping with remediation hints. | 1S.1 (contract), M.0-pre, M.0.1-pre, M.0.2-pre | 09 §1, §6.1–§6.4, §7.4, §7.5 | 📋 | — |
| **M.1.1** | Read-tools entity-type sweep across `models`, `applications`, `toolsets`, `interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`, `files`, `prompts`, `conversations`. Reuse formatter + alias-resolution from M.1.0. Singleton-type special-case: `settings` blocks `dial_admin_list_entities` with `405` + remediation hint to use `dial_admin_get_entity(type='settings', id='settings/platform/global')`. **Mechanical** after M.1.0 pattern locked. | M.1.0, 1S.3, 1S.4 | 09 §6.3–§6.4 | 📋 | — |
| **M.2.0** | Write tools bootstrap: `dial_create_resource(id, spec, validate_only?)`, `dial_update_resource(id, spec, if_match?, validate_only?)`, `dial_delete_resource(id, confirm, if_match?)`. ETag header handling on reads/writes. `validate_only` dry-run forwarded to Core. `confirm: true` guard on delete. Structured error response on `404` / `412` (covers both `E_ALREADY_EXISTS` on `If-None-Match: *` and `E_STALE_ETAG` on `If-Match`) with remediation. Note: `409 Conflict` is no longer reachable on the per-entity surface after U.0 wire unification. | 2S.11 (model write), 2S.10 (secret-field handling), M.1.0 | 09 §6.1 (tools 4–6), §6.5, §6.6, §7.4 | 📋 | — |
| **M.2.1** | Write-tools entity-type sweep matching M.1.1 scope. Singleton-type special-case: `dial_admin_delete_entity` for `settings` clears the API blob (revert-to-file projection per [OQ-10](08-open-questions-and-references.md)) rather than removing a named entry from a collection; `dial_admin_list_entities` for `settings` returns 405 (already in M.1.1). `dial_admin_create_entity` for `settings` maps to `PUT … If-None-Match: *` (universal under U.0) and returns 412 (`E_ALREADY_EXISTS`) when an API blob is already present. Forwards keys-controller DELETE ordering to Core (Core enforces per 2S.14). **Mechanical** after M.2.0 pattern locked. **Amended by U.0 (2026-05-20):** 405-on-POST is universal across all admin-config types (handled in M.2.0), not a settings-specific carve-out. | M.2.0, 3S.2, 3S.3, 3S.4 | 09 §6.1 (tools 4–6) | 📋 | — |
| **M.3.0** | File tools: `dial_upload_file(id, content \| source_url, content_type?, max_bytes?)`, `dial_download_file(id, max_bytes?)`. Exact-one-of (`content` XOR `source_url`) via `oneOf` input schema (§6.7). SSRF protection on `source_url` (RFC 1918 / link-local / loopback / cloud-metadata blocklist; allow-list via `mcp.upload.sourceUrl.allowedUrlPrefixes`; feature opt-in via `mcp.upload.sourceUrl.enabled = false` default). Base64 binary content. Image-content block on download for `image/*` MIME (§6.8). | 1S.5 (admin authz preflight), 3S.4 (file write), M.0-pre, M.0.1-pre | 09 §6.1 (tools 7–8), §6.7–§6.8, §7.1 | 📋 | — |
| **M.4.0** | Publication tool: `dial_publish_resource(id, target)`. Forwards to `POST /v1/ops/publication/create` (`DialClient` wraps `Publication` request body with `resources[]` + `targetFolder`). Initiates async PENDING; admin approval required before resource is publicly visible. **Note**: targets the existing Resource Operations API, not the Configuration API — see spec §6.1 tool 9 note. | 3S.4 (files/prompts/conversations write), 1.5S.3 (pub/sub for state observability), M.0-pre | 09 §6.1 (tool 9), §3.2 illustrative composition | 📋 | — |
| **M.5.0** | Auth + correlation headers. Forward credentials verbatim (admin API key as `API-KEY` header; user JWT as `Authorization: Bearer`). Stateless routing — no MCP-side session state beyond per-session bucket cache. Correlation headers on every Core call: `X-DIAL-Client: dial-mcp/<version>`, `X-DIAL-Client-Session: <uuid>`, `X-DIAL-Client-Agent: claude-code\|claude-desktop\|quickapp\|ci\|other`. Headers reach Core's structured logs in v1; full audit integration awaits Phase 7. | M.0-pre, all preceding M.* slices | 09 §7.4, §7.5 | 📋 | — |
| **M.6.0** | Integration testing + tool documentation. End-to-end tests for all 9 tools against staged Core via `ResourceApiTest`-style harness. Tool descriptions: 1–2 example invocations per tool (M4 requirement). Validate extraction discipline (no direct service injection; `DialClient` swap point live; dependency-graph CI check passes). | All M.* slices | 09 §7.1 extraction discipline rules 1–6, §8 kickoff checklist | 📋 | — |

**Deferred (not in MVP):**

- **MCP-2 — service-account OIDC for CI agents.** v1 CI agents use admin API keys per spec §7.4 fallback. Not decomposed for MVP. See spec §8.
- **MCP audit tools** (`dial_query_audit`, `dial_get_entity_history`, `dial_snapshot_at_time`, `dial_rollback_entity`). Deferred to Core Phase 7 audit subsystem. See spec §11.
- **MCP-future tools** (`dial_apply_manifests`, `dial_get_effective_policy`, `dial_diff_environments`, `dial_export`, `dial_search_resources`, `dial_deploy_codeapp`). Spec §11; depend on Core surface that doesn't exist yet or on real operator demand.
- **Stdio transport for laptop devs.** Resolved as deferred (MCP-OQ-8) — laptop developers point Claude Desktop at `http://<host>:<port>/mcp` instead. Stdio launcher reassessed once real demand exists.
- **Module extraction to standalone service.** Spec §11 — kept viable by §7.1 extraction discipline (REST-only loopback, no direct service injection). Triggers: release-cadence becomes blocking, or external owner takes over, or Python ecosystem alignment matters more than in-repo type sharing.

---

## 6. Smallest demo path (if days budget tightens)

```
1S.0 → 1S.1 → 1S.2                         # bootstrap + read API for models
1C.0 → 1C.2                                # read CLI for models
  ↓
2S.0-pre … 2S.7-pre                        # all Phase-2 prereqs
  ↓
2S.8 → 2S.9 → 2S.10 → 2S.11 → 2S.12 →     # model writes server-side
2S.13 → 2S.14
  ↓
2C.0 → 2C.1 → 2C.2 → 2C.3                  # model writes via CLI
  ↓
1.5S.0-pre → 1.5S.1 → 1.5S.2 → 1.5S.3      # cross-replica propagation
  ↓
3S.0-pre → 3S.2 (roles + keys + interceptors only) → 3C.0 (those types)
  ↓
M.0-pre → M.0.1-pre → M.0.2-pre → M.1.0 → M.1.1   # MCP read surface
  ↓
M.2.0 → M.2.1 → M.5.0 → M.6.0                     # MCP write surface + auth + tests
  ↓
DEMO (API + CLI + MCP — three surfaces, one contract)
```

~34 PRs, end-to-end across the API + CLI + MCP + cross-replica + multiple entity types. Phase-3 entity-sweep can be partial; reviewer feedback determines where to stop. M.3.0 (file tools) and M.4.0 (publication) are *recommended* for the demo but can be cut if days budget tightens.

---

## 7. Cross-cutting concerns

### 7.1 Testing

- **Pattern**: every server slice has at least one integration test in the style of `server/src/test/java/.../ResourceApiTest.java` (embedded Redis + OkHttp `MockWebServer`).
- **CLI slices**: Picocli's `CommandLine.execute()` with captured stdout/stderr; test against a stubbed in-process API or against the real server harness (the latter is heavier but truer for `apply` flows).
- **No mocks of internal collaborators in integration tests.** Codebase prefers integration over unit-with-mocks for service-level work.

### 7.2 Encryption

- Only **2S.10**+ touches encryption. Until then, no entity write paths exist that produce encrypted content.
- **3S.0-pre**'s lazy plaintext fallback for `codeVerifier` is the *only* migration of existing data — no forced sweeps.
- Dev mode (`SimpleKeyManagementService`) passes through unencrypted with startup warning. Don't break that.

### 7.3 Vert.x event-loop

Pre-PR checklist: grep your diff for `Thread.sleep`, `.get()` on a `Future`, blocking I/O, etc. on the event loop. Use `vertx.executeBlocking` or async APIs. The code-reviewer subagent prompt should call this out explicitly.

### 7.4 Build & checkstyle

```bash
./gradlew checkstyleMain checkstyleTest \
  :server:test :storage:test :config:test :credentials:test
# Add :cli:test :cli:checkstyleMain once Track B starts.
```

Run before opening every PR.

### 7.5 LSP (Java language server)

The harness exposes JDTLS-backed LSP queries (`documentSymbol`, `workspaceSymbol`, `findReferences`, `goToDefinition`, `goToImplementation`, `hover`, `prepareCallHierarchy`, `incomingCalls`, `outgoingCalls`). The orchestrator uses these per §4 — they're the cheapest way to verify design-doc anchors and to bound the blast radius of a planned change.

**Warmup state.** A fresh JDTLS workspace takes 30–60 s to index. Early queries may return `server is starting` or transient unresolved-import diagnostics — retry once the server is warm. Verified 2026-05-01 against `BlobStorage.java`, `AzureCredentialProvider.java`, and `DefaultCredentialProvider.java`: once warm, JDTLS resolves the full dependency graph including the private `epam:jclouds-package` directly from the local Gradle cache. No GPR-env-var-in-LSP-shell trick is required as long as `./gradlew build` has run successfully once to populate the cache.

**Diagnostic noise.** LSP responses include unrelated workspace diagnostics (deprecation warnings, unused imports across the codebase). The orchestrator ignores diagnostics not introduced by the current slice — surfacing them would violate §2.2 (Surgical Changes).

### 7.6 Code-owner alignment

Memory shows the reviewer pushes back on:

- Naming inconsistencies.
- New abstractions over existing patterns.
- Missing detail (the "details required" pushback on §3.4 audit).
- Permissive defaults (the soft → strict `softValidation` flip).

Slice **1S.0** PR will absorb most naming-alignment feedback. Plan for 2–3 review rounds on it. Subsequent slices benefit from the locked vocabulary.

---

## 8. How to amend / add slices

When mid-implementation feedback shifts the design:

1. Amend the relevant design doc (`01`–`09`) — that's the contract.
2. Add a one-line entry in the project memory file with the date, the locked decision, and the docs touched.
3. Update the affected slice's "Design anchors" cell here.
4. If the change adds work, add a new slice with the next free ID in the relevant phase section. **Don't re-number existing slices** — IDs are stable references.
5. If the change drops work, mark the affected slice `❌ dropped` with a one-line "why".

Treat this file the same as the design docs: PRs that change scope must update both.

---

## 9. Decisions log

Locked answers to the kickoff questions (see `project_unified_config_implementation.md` memory entry for full decision context):

- **CLI module location**: same repo as a sibling `:cli` Gradle module *(locked 2026-05-01)*.
- **Branch hygiene**: lazy integration of `feature/unified-config` with `development`. **Default = rebase** (force-push allowed; in-flight slice authors rebase their sub-branches onto the new tip; linear history). **Per-sync merge override allowed** when situational — early in MVP, complex conflicts. Late in MVP, prefer rebase. *(locked 2026-05-01)*.
- **Sub-branch naming**: `feature/unified-config-<id>-<short-title>` (hyphen separator forced by Git ref-vs-directory constraint — see §3.2) *(locked 2026-05-01; separator amended 2026-05-02)*.
- **Integration branch model**: per-slice **local** `git merge --squash` into `feature/unified-config` after the user approves the slice diff and commit message — no per-slice PR, no per-slice formal code-owner review; commit format per §3.5; single big PR to `development` only at MVP-complete after user-side testing; no intermediate `development` merges *(locked 2026-05-01; PR-free local-merge confirmed 2026-05-02)*.
- **CI scope**: full mirror of `development`'s GH Actions matrix on every slice PR (centralized at `epam/ai-dial-ci@4.0.0`) *(locked 2026-05-01)*.
- **GraalVM**: deferred to post-MVP — CLI ships JVM-mode for MVP; design doc 05 §6 unchanged; native-image becomes a post-MVP slice once `epam/ai-dial-ci` gains GraalVM support *(locked 2026-05-01)*. See §3.4.

**Open:** none. Slice **1S.0** is unblocked.

---

## 10. How to resume work in a new session

The plan and memory persist across sessions; the conversation does not. Use one of two methods to pick up where the orchestrator left off.

### 10.1 Slash command (recommended)

A project-scoped slash command lives at `.claude/commands/dial-mvp.md` (committed to the repo, available to anyone working in it).

```
# Pick the next 📋 slice in dependency order:
/dial-mvp

# Jump directly to a specific slice:
/dial-mvp 1S.0
/dial-mvp 2S.11

# Print a status report (counts by status; in-flight slices) and stop:
/dial-mvp status
```

The command primes the orchestrator with this file + project memory, presents the slice register state, and runs the agent loop §4. It honors the halt conditions in §4.1 — expect the orchestrator to stop at the architect plan and at any divergence, asking before proceeding.

### 10.2 Manual kickoff (no slash command)

If the slash command isn't installed, paste this prompt into a fresh session:

> Read `docs/sandbox/dial-unified-config/IMPLEMENTATION.md` and the project memory entries `project_unified_config_review.md` + `project_unified_config_implementation.md`. You are the orchestrator for the dial-unified-config MVP. Show me the current state of the slice register (which slices are 📋 / 🚧 / 🔍 / ✅), recommend the next 📋 slice in dependency order, and wait for me to pick. When I pick, run the agent loop in §4, halting at the architect-plan step for my approval and at any halt condition in §4.1.

### 10.3 Status updates between halts

At every halt the orchestrator prints:

- Current slice ID and step (e.g., "Slice 1S.0 — ARCHITECT step").
- What was just done.
- What's next, or what input is needed from the user.

This keeps the user in the loop without needing to check a dashboard.
