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
- `dial-cli apply -f` and `dial-cli export` against fully-resolved manifests (no template DSL, no overlays, no bundles).

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
- **Strict POST/PUT split.** `POST` = 409 on conflict; `PUT` = 404 on missing; no upsert at the single-entity surface (singleton `PUT /v1/settings/platform/global` is the lone exception — upsert by nature; `DELETE` on the same URL clears the API blob and reverts the projection to file/default).
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
- **MVP distribution channels**: Docker image (`ghcr.io/epam/dial-cli`), runnable JAR (`java -jar dial-cli.jar …`), and **bundled inside the `ai-dial-core` image** as an alpha convenience channel (same uber-jar copied to `/opt/cli/dial-cli.jar` with a `/usr/local/bin/dial-cli` wrapper — see slice **Dist.1** in §5.5). All three are listed in design 05 §6.
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
| **1S.2** | `GET /v1/models/public/` listing with `?limit&cursor` pagination (default 100, max 500). `hasMore` always present. Trailing-slash optional. | 1S.1 | 03 §1, §4 | ✅ | `395a9360` |
| **1S.3** | Extend reads to remaining MergedConfigStore-managed types (`interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`). Bucket-aware authz (`platform/` admin-only). 405 for `POST` on `/v1/settings/platform/global` with `Allow: GET, PUT, DELETE` (singleton has no create surface; `PUT` is upsert and `DELETE` clears the API blob — Phase 2 implements `DELETE` alongside `PUT`). Settings GET projection: `"api"` (blob present) | `"file"` (no blob, file defines fields) | `"default"` (no blob, file silent). | 1S.1, 1S.2 | 03 §1; 04 §1.2 | ✅ | `af64319e` |
| **1S.4** | Read paths for `applications`, `toolsets` via existing `ApplicationService` / `ToolSetService` with `ConfigAuthorizationService` preflight. | 1S.1 | 03 §1; 02 §6 | ✅ | `acfe1ace` |
| **1S.5** | Admin authz preflight on existing `FILES` / `RESOURCE` controllers for `public/` admin reads/writes; deny admin reach into user buckets. | 1S.4 | 03 §1; OQ-21, OQ-33 | ✅ | `7be8db9e` |
| **1S.6** | `GET /v1/admin/export` — full snapshot of in-memory `Config`. JSON + YAML output. | 1S.3 | 03 §1; 07 Phase 1 | ✅ | `ec1ac537` |
| **1S.7** | `GET /v1/admin/health/config` returning `{status, skipped[]}` (skipped is `[]` in Phase 1 — invalid-entity store ships in 2S.9). Prometheus metric scaffolds (cardinality-zero in Phase 1). | 1S.0 | 07 Phase 2; 02 §4.1 | ✅ | `2a5a10ac` |

**Track B — CLI**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **1C.0** | New `:cli` Gradle module. Picocli + Quarkus Command Mode skeleton. `~/.dial-cli/config.yaml` profile loader. API-key resolution chain (env var → `--api-key-file` → no-echo prompt). Direct dependency on `:config` module data classes. **Scope narrowed 2026-05-05** (architect-plan halt — Ambiguity B1): keystore tier deferred to post-MVP — design 06 §2.1 says keystore is populated by `dial-cli auth login --store`, but `auth login` is itself deferred per design 05 §1 (waits for OIDC). MVP chain follows design 06 §2.1 ordering minus the unreachable keystore tier; keystore re-enables when `auth login` ships. **Ambiguity A**: chain order follows design 06 §2.1 (the contract) over the slice row's earlier paraphrase. | 1S.1 (contract only) | 05 §1, §2, §6 | ✅ | `ff2ae5d4` |
| **1C.1** | `dial-cli env list / current / use / check`. Persist `defaults.env` on `use`. **Scope clarified 2026-05-05** (architect-plan halt — Ambiguity §A.8): `env check` is **config-only** (resolves env, validates `api_url`, reports credential source non-interactively); network reachability deferred to 1C.2 once the HTTP client lands per Reading A. Reading B (network probe in 1C.1) was rejected to keep HTTP-client introduction firmly in 1C.2's pattern-establishing scope. | 1C.0 | 05 §1 | ✅ | `9f4efd72` |
| **1C.2** | `dial-cli model get <name>` and `dial-cli get models` (alias). `-o table\|json\|yaml`. **Pattern-establishing slice for HTTP + output (2026-05-05)**: 9 architect-locked decisions ratified — single-page listing `?limit=100` (no pagination flags), table cols `NAME + ENDPOINT`, default bucket `public/`, `Api-Key` header, HTTP→exit-code mapping (401/403→3, 404→4, 5xx→1), JDK `HttpClient` + `HttpServer` test stub (no new deps), canonical-id pass-through. Reviewer-driven fixes: `identifierToPath` rejects ambiguous partial canonical IDs (`public/gpt-4` → exit 2), URL-encodes simple names; `HttpClient` configured `followRedirects(NORMAL)`; URI parse failures wrapped to `NetworkException`. `model list` shipped alongside `model get` since `get models` alias depends on it. | 1C.0, 1S.1 | 05 §1; 06 §2.2 | ✅ | `f1fcbf30` |
| **1C.3** | Extend `get` / `list` to all entity types. **Mechanical** once 1C.2 lands. **Scope narrowed 2026-05-05** (architect-plan halt — Reading A): covers the 8 admin-config types from `1S.3` + `1S.4` only (`applications`, `toolsets`, `interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`). Files/prompts/conversations deferred to a follow-on slice — `1S.5` is intentionally not in 1C.3's dep set; FILES/RESOURCE controllers' listing response shape diverges from the `{items:[...]}` envelope used by CONFIG_RESOURCE/RESOURCE-managed types. EntityReader extracted from ModelCommand; per-type bucket map (public/ for models/apps/toolsets, platform/ for others) + per-type table shape (NAME-only default, models keep NAME+ENDPOINT). SettingsCommand is singleton — Get only, no name arg. Reviewer-driven fixes: null-bucket guard in identifierToPath; `hasMore=true` warning. | 1C.2, 1S.3, 1S.4 | 05 §1 | ✅ | `c987893a` |
| **1C.4** | `dial-cli export --env <env>`. Streams `GET /v1/admin/export` to stdout / file. **Format negotiation (2026-05-05)**: global `-o` maps to `Accept` header — `yaml`→`application/yaml`, `json`/`table` (default)→`application/json` (table-fallback is silent permissive). `-f/--output-file <path>` writes to file (creates parent dirs, rejects directory paths). Reviewer-driven fixes: UTF-8 charset pinned in `BodyHandlers.ofString` to prevent ISO-8859-1 fallback mojibake on non-ASCII entity names; pre-write directory check. `EntityReader.resolveEnv` + `ResolvedEnv` promoted to package-private as the shared env-resolution seam. | 1C.0, 1S.6 | 05 §1 | ✅ | `862bbea2` |
| **1C.5** | `dial-cli diff --source <env> --target <env>` — read-only, two GETs + structural diff. **Approach (2026-05-05)**: hits `/v1/admin/export` once per env (1S.6/1C.4 path); structural Jackson-tree diff via new `JsonDiff` util — object recursion with dotted-path notation (`models.gpt-4.endpoint`); arrays/scalars/type-mismatches treated as opaque CHANGED; output is path-only (`+ path` / `- path` / `~ path` lines). `EntityReader.resolveEnv` extended with `(root, spec, explicitEnv)` overload. Reviewer-driven fixes: global `--api-url` override is ignored when explicit env is given (prevents diff hitting same URL twice); empty-path edge case in `Change.toString()`; added Api-Key header assertion + `--api-url` regression test. Note: 1C.3 dep is satisfied; 1C.4 (or 1S.6) is implicitly required for the export endpoint. | 1C.3 | 05 §1 | ✅ | `4e5f69ad` |
| **1C.6** | `dial-cli completion {bash,zsh,fish}` via Picocli built-in. **Fish caveat (2026-05-05)**: Picocli's `AutoComplete` only generates bash/zsh; the same script works for both (zsh bash-compat). Fish is not supported by Picocli — `completion fish` exits 2 with a "not yet supported" message; documented for post-MVP follow-up. Unknown shells / missing arg also exit 2 with a helpful message. | 1C.0 | 05 §1 | ✅ | `ef978b33` |

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
| **2S.11** | `MODEL` `ResourceTypes` entry. `POST /v1/models/public/{name}` (409 on conflict). `PUT /v1/models/public/{name}` (404 on missing, optional `If-Match` → 412). `DELETE`. Strict POST/PUT split. Bucket-aware authz. ETag in response header. | 2S.1-pre, 2S.2-pre, 2S.8, 2S.9, 2S.10 | 03 §1, §3; 07 Phase 2 | ✅ | `33543a70` |
| **2S.12** | `POST /v1/admin/validate` — model-scoped (Phase 4 extends to other types and bulk). | 2S.11 | 03 §6 | ✅ | `c92d14c0` |
| **2S.13** | Cross-reference validation on per-entity write — strict-by-default `422`; `config.write.softValidation` opt-in. | 2S.11 | 03 §6; 02 §9 | ✅ | `7204beae` |
| **2S.14** | Writer-pod immediate `volatile Config` swap via `rebuildNow()` after write. Keys-controller `DELETE` ordering invariant (delete blob → `removeKey` → `rebuildNow`). | 2S.11 | 02 §4 | ✅ | `dac53193` |
| **2S.15** | Canonical IDs in `entity.getName()` for API-managed entries. Drop `resetSimpleName` in `MergedConfigStore.rebuild()` so `Model.name` / `Interceptor.name` / `Role.name` / `Route.name` carry their canonical map key (`models/public/foo`) instead of being reset back to the simple name. Closes the OQ-23 contract: legacy `/openai/models`, `/openai/deployments`, `Role.limits` lookups in `RateLimiter`, log fields, and header propagation now surface canonical IDs for API-managed deployments — clients can copy a listing's identifier verbatim into `/openai/deployments/{id}/chat/completions`. New admin Configuration API listing at `/v1/{type}/{bucket}/` unchanged (projects `simpleName(mapKey)` from the controller per design 03 §4). File-sourced entities continue to expose simple names. **Operator-visible:** `Role.limits` for API-managed models keyed by canonical ID; doc note added to 06 §3. | 2S.8 | 02 §4 (resolution table); 03 §4; 06 §3; OQ-16, OQ-23 | ✅ | `e0e1039a` |

**Track B — CLI (models-only writes)**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **2C.0** | `dial-cli model add` (POST). `--dry-run`. Exit codes per 06 §2.8 (`0` / `5` / `2` / `3`). No `--template` yet (Phase 4). **Design calls (2026-05-05)**: `--name` required flag (canonical id only — simple names exit 2 per 05 §1); `--from-file` JSON/YAML by extension; `--dry-run` = local preview, no HTTP; `Content-Type: application/json` always (YAML re-serialized). Reviewer-driven additions: `403 → 3` and `500 → 1` end-to-end test cases for the Add subcommand (unit-level coverage already in `CliHttpClientTest`). `EntityWriter` sibling to `EntityReader`; `requireCanonicalId` hardcodes `public/` bucket — 3C.0 will parameterize. `Response` record gained `etag` field for future `--if-match` (2C.1). | 1C.2, 2S.11 | 05 §1; 06 §2.8 | ✅ | `6ba4aa5a` |
| **2C.1** | `dial-cli model update` (PUT) with `--set k=v` (GET → local-merge → PUT). `--if-match`. Exit codes (`0` / `4` / `6` / `2`). **Design calls (2026-05-05)**: positional `<canonical-id>` per 05 §1 line 58 (vs `--name` for `add` per 2C.0); `--set` repeatable, split on first `=`, dotted-path expansion (no array indexing), values JSON-coerced (try `readTree` then fall back to `TextNode`); auto `If-Match` from GET's `ETag` per 06 §2.4 line 373-374, `--if-match X` overrides; no `--from-file` (slice row says `--set` only). Reviewer-driven fixes: `applySets` rejects overwriting a non-object intermediate (e.g. `--set pricing.prompt=...` against `"pricing":1.5`) with exit 2 instead of silently destroying the scalar; added `modelUpdate401OnGetExitsThree` for symmetry with `modelGet401ExitsThree`. `CliHttpClient.toExitCode` extended with `412→6`. | 2C.0 | 05 §1 (Update ergonomics) | ✅ | `c2bdd1a0` |
| **2C.2** | `dial-cli model delete` with `--if-match`. **Design calls (2026-05-05)**: positional `<canonical-id>` (matches 2C.1 `update` shape; design 05 §1 line 59); **no auto If-Match** — delete is one-shot, no GET-merge-PUT TOCTOU concern; user opts in with explicit `--if-match` flag. `--dry-run` prints `Would delete <id>` and skips the HTTP call. Reviewer-driven additions: `modelDelete401ExitsThree` for parity with `modelGet401ExitsThree` / `modelAdd401ExitsThree`. | 2C.0 | 05 §1 | ✅ | `de4e8334` |
| **2C.3** | `dial-cli model validate` against `POST /v1/admin/validate`. **Design calls (2026-05-05)**: `--name <canonical-id>` + `--from-file <path>` both required (parallel with 2C.0 `add`); single-Model manifest envelope `{manifests:[{kind:"Model", name:<simple-name>, spec:<body>}], precheck:true}` — `name` is the simple name (server expects this per `AdminValidateApiTest`), `precheck:true` always sent explicitly (atomic-reject semantics; `--precheck` flag deferred). 200 with `failed:0` → exit 0 + `Valid: <id>`; any other 200/422 → exit 2 with per-entity FAILED rows on stderr (`skipped` rows are not printed as failures); `CliHttpClient.toExitCode` extended with `422→2`. Reviewer-driven fixes: explicit `precheck:true` in envelope (don't rely on server default); status filter is `FAILED` only (not "anything-not-valid", which would mis-classify `skipped`); added `modelValidate422SkippedNotPrintedAsFailure` and `modelValidate401ExitsThree`. | 2S.12, 2C.0 | 03 §6; 05 §1 line 66 | ✅ | `4fafbdbc` |
| **2C.4** | `dial-cli model promote --from --to` — as-is mode only (template support deferred to 4C.1 per §5.5). **NARROWED 2026-05-05** (architect-plan halt, Option A): the original row promised "as-is + explicit `--template` only", but §5.5 defers the template DSL beyond MVP — `--template <name>` would have no engine to resolve `${vars.X}` / `${entity.X}` / `${params.X}` substitutions. Three options surfaced; user picked A (narrow scope). The slice ships only the bare GET-source → POST-target-/admin/apply path with single-Model manifest envelope (`precheck:true` always; apply's built-in precheck substitutes for an extra /admin/validate roundtrip per design 05 §4 step 6). Env-specific-hostname warning from 05 §4 step 5 also deferred (depends on the same template engine to know which fields to scan). `applied_invalid` status surfaces as a stderr warning with exit 0 (entity IS applied; warning per reviewer CONF 85 fix). `--template` re-enables when 4C.1 lands post-MVP. | 2C.0 | 05 §4 (workflow steps 1, 7-8); §5.5 deferral | ✅ | `188f1438` |
| **2C.5** | `dial-cli model diff --source --target` (single-type). **Design calls (2026-05-05)**: optional `--name <canonical-id>` selects between single-entity diff (404 → root absent, not exit 4 — operator wants to see "model exists in target but not source" as `+ field` lines) and list-diff (no `--name`). List-diff transforms `items[]` → `{name → entity}` ObjectNode before running `JsonDiff`, so output reads as `+ added` / `- removed` / `~ shared.field` rather than the opaque `~ items` JsonDiff would produce on the raw array. Reuses 1C.5's `EntityReader.resolveEnv(root, spec, explicitEnv)` per-env-fetch pattern; logic inlined in `ModelCommand.Diff` (matches top-level `DiffCommand` style). `hasMore=true` on either env → stderr `[warn]` prefix per env (1C.3 precedent). Reviewer-driven fix: replace `MissingNode.getInstance()` sentinel with `null` (MissingNode is meaningful inside `JsonDiff.walk` and would silently mis-classify fetch failures as ADDED entries on a refactor); added `modelDiffListEmptySourceShowsAllAdded` test. | 2C.0 | 05 §1 line 68 | ✅ | `4adfd76b` |

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
| **3S.2** | Write APIs (POST/PUT/DELETE) for `schemas`, `interceptors`, `roles`, `keys` (with dual-format compatibility from 2S.0-pre + DELETE ordering invariant from 2S.14), `routes`. Start with one type to validate the pattern; subsequent types **Mechanical**. Generic per-type adapter dispatch (single parameterized write path). Cross-references stay scoped to 2S.13 (Model only) — new-type cross-refs deferred to Phase 4 per design 03 §6. **Scope narrowed 2026-05-04** (auto-mode halt): settings split into sibling slice 3S.2-settings; reasoning: settings GET projection from blob is missing (`MergedConfigStore.MANAGED_TYPES` excludes `GLOBAL_SETTINGS` and `handleSettingsGet` projects only file/default), and writes are inseparable from GET projection — splitting keeps both slices cognitively coherent. | 2S.11, 2S.13, 3S.0-pre | 03 §1, §3; 07 Phase 3 | ✅ | `161d5220` |
| **3S.2-settings** | Settings singleton: `PUT /v1/settings/platform/global` upsert + `DELETE` clears API blob, plus the API-blob projection on GET so `source: "api"` becomes reachable. Adds singleton-special handling in MergedConfigStore (or parallel blob-read on GET — architect decides at slice time). 405 on POST with `Allow: GET, PUT, DELETE`. **Added 2026-05-04** to scope-out from 3S.2. | 3S.2 | 03 §1, §3; 07 Phase 3 | ✅ | `821d1468` |
| **3S.3** | Admin write paths for `applications`, `toolsets` in `public/` via existing `ApplicationService` / `ToolSetService` unified with user-published. **Scope narrowed 2026-05-04** (auto-mode halt): test-only sweep mirroring 3S.4 — production code shipped under 1S.5's preflight + existing `ResourceController`. The "Removes `DeploymentService` config-file special-case" framing is implicit (admin writes never touch `DeploymentService`); the literal removal of the file-config branch from `findDeployment` was descoped because file-defined apps/toolsets remain an operator-facing config surface in MVP — that refactor is post-MVP work. | 3S.1 | 07 Phase 3; 02 §6 | ✅ | `b1a47e61` |
| **3S.4** | Admin write paths for `files`, `prompts`, `conversations` in `public/` via existing controllers + `ConfigAuthorizationService` preflight. Reuses existing resource types. **Production code shipped under 1S.5; this slice ships only the gap-filling integration tests.** | 1S.5 | 03 §1; OQ-21 | ✅ | `d66af8a1` |

**Track B — CLI**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **3C.0** | Per-type Picocli command classes (Approach B — mirrors ModelCommand 1:1) so `add` / `update` / `delete` / `validate` / `promote` / `diff` ship for all remaining types. **Decisions (2026-05-05):** approach B over A/C — Picocli @ParentCommand typing forces per-type wrappers and the existing per-type read-only classes are §2.2-correct to extend. Settings ships symmetric verbs (Update/Delete/Validate/Promote/Diff, no Add — POST 405 — and no List — singleton); user picked symmetric over the architect's reduced-set initial proposal. EntityWriter gains bucket-parameter overloads; 5/6-arg signatures stay as forwarders to keep ModelCommand untouched. New `EntityDiff` helper shared by 7 per-type Diff classes + 1 singleton variant. **Schemas bucket bug fix folded in:** `EntityReader.TYPE_DEFAULT_BUCKET` had `schemas → platform` from 1C.3 but server `EntityBucketBinding` binds schemas to `public`; fix is two lines (1 source + 1 test) directly motivated by the new schema-write commands needing correct bucket routing. | 2C.5, 3S.2, 3S.3, 3S.4 | 05 §1; 06 §3 | ✅ | `88a95869` |

### 5.5 Phase 4 — Declarative apply + diff (NICE TO HAVE)

> **MVP-cut**: deliver **4S.0**, **4S.1**, **4C.0** (apply with fully-resolved manifests). Defer the template DSL, overlays, bundles, and reverse-match `auto` promote.

**Track A — Server**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **4S.0** | `POST /v1/admin/apply` — bulk upsert; dependency-ordered sequential (`globalSettings → schemas → interceptors → roles → keys → routes → models → toolsets → applications`); continues on failure; per-entity status array. `precheck: true\|false` (default `true`); `softValidation` orthogonal; proposed-config validation always-on. **Unknown-kind narrowing 2026-05-04 (architect-plan halt):** `kind: Bundle` → batch-level **400** (CLI-only, structural rejection per 03 §7 line 349); other unknown kinds (e.g. `File`/`Prompt`/`Conversation` — out of 4S.0 scope) → per-entity `FAILED`, batch continues. Reconciles 03 §7's "400 for the offending entry" with the per-entity-status-array model: `Bundle` is structural-malformed payload; other unknowns are entry-level errors emitted via the per-entity `status` channel. | 3S.2, 3S.3 | 03 §7; 07 Phase 4 | ✅ | `c658d7f3` |
| **4S.1** | `POST /v1/admin/validate` — multi-entity, batch-aware with `precheck` semantics. **Phase-2 single-entity surface replaced (2026-05-04)**: shared precheck engine via package-private promotions of `validateOnly`/`mutateScratch`/`newScratch`/`entityId` + records on `AdminApplyController` (no apply behavior change). Validate diverges from apply by FAILing unknown kinds (stricter — CLI validate-first gate) and adds back the 2S.12 spec-node sentinel rejection. Status set: `valid`/`FAILED`/`skipped`; precheck-true + any FAILED → 422 with valid-entries re-mapped to skipped; response shape mirrors apply minus `applied`. | 4S.0 | 03 §6 | ✅ | `a2ea0663` |

**Track B — CLI**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **4C.0** | `dial-cli apply -f <path>` — single-doc and multi-doc YAML manifest parsing, validate-first gate (`POST /v1/admin/validate`) then `POST /v1/admin/apply`. `--dry-run`. Exit codes per 06 §2.8. **No template DSL, no overlays, no bundles in MVP — manifests must be fully resolved.** | 4S.0, 4S.1 | 03 §7; 05 §5.1; 06 §2.7-§2.8 | ✅ | `74acbba5` |

**Polish round (post-MVP, follow-on to user-reported `/dial-uc-debug` issues — 2026-05-08):**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **Polish.1** | Listing canonical IDs for API entries + dedup-by-full-key. `ConfigResourceController.handleGet` lambdas project the canonical map key as `name` for API entries (`fromApi(key) ? key : simpleName(key)`); `respondList` / `handleSchemaGet` listing branches dedup the row map by full Config map key (not simple name) so file/API simple-name twins appear as distinct rows. Schema single-entity GET canonical match also emits canonical name. Simple-name fallback in GET preserved — file entries remain GET-able. **Tests flipped:** `MergedConfigStoreApiTest` (single-GET + listing assertions), `ModelWriteApiTest.testPost201HappyPath` / `testPostImmediatelyVisibleOnGet`, `CanonicalIdListingTest.testApiManagedModelAdminGetProjectsCanonicalId` (renamed). **New regression guard:** `MergedConfigStoreApiTest.testFileAndApiTwinsAppearAsSeparateListingRows` locks the dedup-by-key invariant. Amends design 03 §4 *name field synthesis* + listing example payloads + new *Listing dedup* paragraph; locked-decision amendment captured in `project_unified_config_review.md`. **Sourced from `/dial-uc-debug` round 2026-05-08 (Issue 1).** Architect-plan halt at scope check (the original "drop fallback" plan would have made file entries listing-only, breaking ~10 fixture-using test classes); user picked option (b') — keep fallback, fix listing dedup + projection only. | 2S.15 | 03 §4 (amended) | ✅ | `811d235c` |
| **Cli.3** | CLI debug round-up — Issues 2/4/5 from `/dial-uc-debug` 2026-05-08. (a) `DialCli` global flags (`-o`, `--env`, `--api-url`, `--api-key-file`, `--config`, `--dry-run`, `-v`) carry `ScopeType.INHERIT` so they bind at any depth — `dial-cli model get foo -o yaml` now works the same as `dial-cli -o yaml model get foo`. (b) `EntityReader.formatHttpError(status, body, requestPath)` translates 404/409/412 into operator-friendly stderr lines (`Not found: <id>`, `Already exists: <id>`, `Stale ETag: <id>`) — used from every CLI HTTP call site (`EntityReader.doGet`, all `EntityWriter` paths). (c) `EntityWriter.updateEntity` strips controller-projected fields (`name`, `status`, `source`, `validationWarnings`) from the GET response before merging `--set` and PUT-ing — the server synthesizes `name` from the URL on read and rejects the others as unrecognized; pre-Cli.3 a GET → `--set` → PUT round-trip 400'd with `Unrecognized field "status"`. (d) Default table shape gains SOURCE + STATUS columns (`NAME, SOURCE, STATUS` for non-models; `NAME, SOURCE, STATUS, ENDPOINT` for models) so file vs api entries are obvious. **Tests flipped:** `ModelCommandTest` 7 cases switched from `r.err.contains("404"/"409"/"412")` to friendly-message assertions; `modelUpdate200…SendsMergedBodyAndAutoIfMatch` now asserts `name` is **absent** from PUT body (regression guard for the strip-projection-fields fix). **New test:** `modelGetOutputFlagAfterSubcommand` locks the `-o` ScopeType.INHERIT contract. | Polish.1 (server projection) | 06 §2.4 (Update ergonomics, Note on `update --set`); 04 §1.5 (projection); 03 §4 | ✅ | `1da4a89f` |
| **Cli.4** | `--from-file` envelope detection on `model add` / `model validate` (and the eight sibling `<entity> add` / `<entity> validate` commands). `EntityWriter.loadSpecOrFail` parses the file, detects manifest-envelope shape (`{kind, name?, spec}` — same shape as `sample/dial-cli/manifests/*.yaml` and `dial-cli apply -f`), validates `kind` matches the command's expected kind, warns when the envelope's `name` differs from `--name` (flag remains authoritative — same envelope file can be staged into several names), and returns the inner `spec` as JSON. Files without envelope shape pass through unchanged (raw-spec backward compat). Threads `kind` through every `EntityWriter.addEntity` caller (`Model` for `ModelCommand`, `Application`/`ToolSet`/`Schema`/`Interceptor`/`Role`/`Key`/`Route` via the `KIND` constants on each command class). `validateEntity` already had `kind`. **Tests:** `modelAddAcceptsManifestEnvelope`, `modelAddRejectsWrongKindEnvelope`, `modelAddEnvelopeNameMismatchWarnsButProceeds`, `modelAddRawSpecBackwardCompat`, `modelValidateAcceptsManifestEnvelope`, `modelValidateRejectsWrongKindEnvelope`. **Sourced from `/dial-uc-debug` round 2026-05-09 (Issue 1).** Issue 2 from the same round (`--dry-run` after subcommand) was already fixed by Cli.3's `ScopeType.INHERIT`; the live repro that exposed it was a stale jar built before `1da4a89f`. | Cli.3 | 06 §2.4 (`--from-file accepts two shapes`) | ✅ | `299885a2` |

**Deferred beyond MVP** (if Phase-4 demand emerges post-MVP):

- **4C.1** Template DSL (`extends`, `includes`, `!if`, `!for`, function set) — 05 §3
- **4C.2** Overlays (base + overlay) — 05 §5.2
- **4C.3** Bundles — 05 §5.3
- **4C.4** `${SECRET:*}` resolution — 05 §3.1
- **4C.5** `promote --template auto` reverse-match — 05 §4
- **4S.2** Server: split apply per-entity outcomes into `created` / `updated` / `unchanged` (today only `applied` / `applied_invalid` / `FAILED` / `skipped`) so the CLI can render the design 06 §2.7 summary buckets without N extra round-trips. Surfaced during 4C.0 architect plan (§1.1 deviation). — 03 §7
- **4C.6** CLI: render `created / updated / unchanged / failed` summary on `apply` (depends on **4S.2** wire change). 4C.0 ships an `applied / failed` aggregate as the closest-available stand-in. — 06 §2.7
- **4C.7** CLI: `dial-cli apply -f <directory>` recursive walk over `.yaml` / `.yml` / `.json` files. **Techdebt** — slice 4C.0 ships file-only by design (slice-register row scope) but design 06 §2.7 / §2.8 examples assume directory input. — 06 §2.7
- **Dist.1** Build / distribution: bundle the `:cli` Quarkus uber-jar into the `ai-dial-core` Docker image at `/opt/cli/dial-cli.jar` with a `/usr/local/bin/dial-cli` wrapper, so the same image DevOps already pins for the server can be reused as a CLI runner in config-management CI pipelines (mirrors the planned standalone `ghcr.io/epam/dial-cli` image; alpha convenience channel — not a replacement). Touches `Dockerfile` only; no production code changes. — 05 §6, 06 §1.1.1
- **Dist.2** Newcomer playground: ship `sample/dial-cli/` (sibling of the existing `sample/aidial.config.json` / `sample/aidial.settings.json`) with a single-environment profile + one manifest per writable entity type (Model, Application, ToolSet, Schema, Interceptor, Role, Key, Route, Settings) + a 5-minute README quickstart. Every manifest carries a leading `---` document marker so plain `cat manifests/*.yaml | dial-cli apply -f -` (via temp file in MVP — 4C.7 deferred) works as a single multi-doc batch. Verified end-to-end via `dial-cli apply -f --dry-run`: 9 entities parsed, canonical IDs stripped to simple names per 4C.0. — 06 §1, 06 §3

---

### 5.6 MCP-1 — DIAL Admin MCP server (Track C, interleaved with Phases 1–3)

> Spec: `09-admin-mcp-spec.md`. MCP-1 ships all 9 building-block tools per §6.1; read tools depend on Phase 1 reads, write tools depend on Phase 2/3 writes. MCP-2 (service-account OIDC) and audit tools are deferred — see footer.

**Track C — MCP**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **M.0-pre** | `:mcp` Gradle module bootstrap. Add `include 'mcp'` to `settings.gradle`; `mcp/build.gradle` with `implementation project(':config')` + Lombok wiring (matches sibling modules). Wire `/mcp` path-prefix branch into `Proxy.handleRequest()` short-circuiting to a new `McpRequestHandler`. Deploy MCP verticle in `AiDial.start()` when `mcp.enabled = true`. Wire `mcp.*` settings into `aidial.settings.json` defaults. Document extraction discipline in `mcp/CONTRIBUTING.md` (REST-only loopback, no direct service injection). **Scope narrowed 2026-05-06** (start-of-slice halt — discovered constraint #1): the Java MCP SDK `io.modelcontextprotocol.sdk:mcp` (latest GA `0.8.1`) ships only Servlet + Spring WebFlux/WebMvc server-transport adapters — no Vert.x `HttpServerRequest` adapter exists. Bridging the SDK's `McpServerTransportProvider` SPI to Vert.x is substantive new infrastructure that would blow §2.1/§2.2 if folded into a "module bootstrap" slice. Three options surfaced; user picked Option A: M.0-pre ships skeleton + 503 stub; SDK dep + transport adapter carved into sibling `M.0.0-bridge` (inserted below). Spec contract unchanged (analogous to the §3.4 GraalVM execution-scope reduction). | — | 09 §1, §7.1, §7.2, §8 kickoff checklist | ✅ | `5a60bf96` |
| **M.0.0-bridge** | Vert.x ↔ MCP-SDK transport adapter. Add `io.modelcontextprotocol.sdk:mcp` dep to `mcp/build.gradle`. Implement `McpServerTransportProvider` against Vert.x: bridge `HttpServerRequest` body buffering, async response, SSE chunked write, and abort lifecycle to the SDK's Streamable HTTP contract; preserve HTTP/SSE backward-compat. Replace M.0-pre's 503 stub in `McpRequestHandler` with real SDK dispatch. **Architect picked Option A — custom Vert.x SPI 2026-05-06**: B (embedded Servlet container) added a parallel async model behind Vert.x — eliminated under §2.1/§2.3; C (framework swap) was disposed of on sight. SDK dep narrowed to `mcp-core:1.1.2` + `mcp-json-jackson2:1.1.2` with `com.networknt:json-schema-validator` excluded — the umbrella `mcp:1.1.2` artifact transitively forces networknt 3.0.0, breaking `:config`'s 1.5.2 baseline; surgical fix is the exclude + a no-op `JsonSchemaValidator` supplied to `McpServer.builder()` (safe because zero tools registered until M.1.x). Threading invariant locked at the transport boundary: `.block()` only inside `vertx.executeBlocking(...)`; SSE writes from Reactor scheduler threads marshalled back via `responseContext.runOnContext(...)`, where `responseContext` is captured on the event loop in `handlePost` *before* entering `executeBlocking` (CONF 82 fix — avoids relying on Vert.x worker-thread context inheritance). Tool-handler dispatch context (captured-context vs worker-pool, §7.2 a/b) deliberately deferred to M.0.1-pre. Reviewer-driven fixes: `notifyClients`/`closeGracefully` rewritten as proper `Flux.fromIterable(...).flatMap(...).then()` chains with no `.block()` (CONF 85 — would have blocked the event loop on first M.1.x tool registration); CONTRIBUTING.md "Status" section refreshed to reflect the live transport (CONF 88). | M.0-pre | 09 §7.1, §7.2, §8 kickoff checklist | ✅ | `bc1459ed` |
| **M.0.1-pre** | Threading bridge: pick captured-context dispatch (option a per §7.2 recommendation) or worker-pool dispatch (option b); wire Vert.x context lifecycle for tool handlers. `DialClient` HTTP wrapper (REST-only loopback facade — the swap point for future extraction). Integration test harness mirroring `ResourceApiTest` style. **Architect locked option (a) captured-context 2026-05-06**: spec recommendation; `WebClient` returns Vert.x `Future` so option (b)'s WorkerExecutor adds a thread hop with no benefit. `DialClient` exposes a single low-level `request(method, path, authHeaders, correlationHeaders, body) -> Mono<DialResponse>` (per-resource wrappers carved to M.1.x per §2.1). Bridge shape locked verbatim: `Mono.create(sink -> context.runOnContext(v -> webClient.requestAbs(...).onSuccess(...).onFailure(sink::error)))` — same shape `VertxMcpTransportProvider.sendMessage()` already uses. Loopback URL resolves env `MCP_DIAL_TARGET_URL` → settings `mcp.dialTargetUrl` → default `http://localhost:8080`. `McpVerticle.start()` captures `Context` once via `vertx.getOrCreateContext()`, resolves URL, constructs `DialClient` as a private field; `McpToolRegistry` deliberately NOT introduced (§2.1 — registry lands in M.1.x when there are tools to register). `:mcp` test (`DialClientTest`) stubs Core via `vertx.createHttpServer(0)` echo (no live Core, no new test deps); `:server` test (`McpDialClientLoopbackTest`) extends `ResourceBaseTest` and round-trips `GET /v1/bucket` against real Core (CONTRIBUTING.md rule 6 — cross-module test deps on `:server` test classes are forbidden, so live-Core round-trip lives in `:server`). Reviewer-driven fix: added `networkFailurePropagatesAsMonoError` test (CONF 80 — `onFailure → sink::error` had zero coverage). 1043 tests total (1034 :server + 9 :mcp), 0 failures. | M.0-pre, M.0.0-bridge | 09 §7.2 | ✅ | `f07c7861` |
| **M.0.2-pre** | Per-session rate-limiting + concurrency cap (defaults: `mcp.rateLimit.callsPerMinute = 60`, `burstCapacity = 10`, `mcp.concurrency.maxConcurrentCallsPerSession = 5`). Token-bucket per session-id. Structured error with `retry_after` hint on overflow. **Locked 2026-05-06**: enforcement at transport layer (`VertxMcpTransportProvider.dispatchPost`) rather than `DialClient` decoration — Java MCP SDK 1.1.2 does not expose session-id at tool-handler time (open SDK issue #435), so the M.0.1-pre memory hint to decorate `DialClient.request(...)` is moot. Overflow returns a JSON-RPC error response (HTTP 200, code `-32000`, `data.retry_after`) marshalled back to the event loop via captured `responseContext.runOnContext(...)` (matches M.0.0-bridge CONF 82 SSE pattern). `retry_after=1` minimum for both rate-limit and concurrency-cap denials. Reviewer-driven fix: pre-multiply `elapsed`-clamp via `maxElapsedNanos = NANOS_PER_MINUTE * burstCapacity / callsPerMinute` field guards against `long` overflow on idle sessions (would silently lock out sessions idle >42h at default config; fix is one CAS-loop line + regression test). `DialClient` deliberately untouched. 10 unit tests in `:mcp` (no Vert.x, no Mockito, no Thread.sleep — `LongSupplier` clock); end-to-end overflow integration test deferred to M.1.x when real tool handlers exist. | M.0-pre | 09 §7.1 (M10), §9 risk row 1 | ✅ | `31cd5e46` |
| **M.1.0** | Read tools bootstrap: `dial_describe_schema(type)`, `dial_list_resources(path, recursive?, filter?, format?, cursor?)`, `dial_get_resource(id, format?)`. In-process registry lookup against `:config` JSON Schema generators (M9 — `dial_describe_schema` is a function call, not an HTTP round-trip). Bucket-alias resolution (`private` / `public` / `platform` per §6.2); lazy per-session bucket fetch on first `private` use. Two-array list envelope (§6.3: `items` + `folders`). Format projection (`summary` mode list / `detailed` default get) per §6.4. Error shaping with remediation hints. **Locked 2026-05-07**: (Constraint 1, halt) Core has no POJO→JSON-Schema generator — added `com.github.victools:jsonschema-generator:4.38.0` to `:mcp` build (only viable option preserving §M9 lockstep guarantee; hand-written schemas would defeat it). DIAL meta-schema (`MetaSchemaHolder`) returned for `type='schemas'`. (Constraint 2) `ConfigResourceController` GETs do NOT emit `ETag` — accepted; `etag: null` surfaces for config types in M.1.x. (Constraint 3) `DialResponse` extended to 3-component record `(int, String, MultiMap headers)` so file ETag becomes reachable in M.1.1 and M.2.x PUT-response ETag in M.2.0. **Auth model collapsed (user override)**: caller's `Api-Key`/`Authorization: Bearer` headers extracted from inbound `HttpServerRequest` in `dispatchPost` and published via `McpTransportContext.create(Map)` so the SDK plumbs them into `McpAsyncServerExchange.transportContext()`; tool handlers read via `ToolContext.authHeaders(exchange)` and pass to `DialClient.request(...)` verbatim. No env var, no `AIDIAL_MCP_API_KEY`. **SDK #435 stale**: `McpAsyncServerExchange.sessionId()` IS public in 1.1.2 (verified via javap of `mcp-core-1.1.2.jar`); the M.0.2-pre memory note about issue #435 is stale and corrected here — `private` cache keys on `exchange.sessionId()` directly. Pilot type set: `models` (public bucket), `roles` (platform bucket), `settings` (singleton 405 short-circuit); `dial_describe_schema` covers 9 types (8 POJO + meta-schema), `list`/`get` validate only the 3 pilot types; M.1.1 mechanically expands. Reviewer-driven fixes: (Pre-merge HIGH) `SessionBucketCache.resolvePrivate(null, ...)` would NPE inside `ConcurrentHashMap.computeIfAbsent` — added explicit null-guard returning `Mono.error(IllegalStateException)`; (Pre-merge MEDIUM) plain `.cache()` on the bucket-fetch Mono replays errors forever, permanently poisoning a session after one transient failure — replaced with `.doOnError(e -> cache.remove(sid)).cache()` so success caches indefinitely (M7 intent) and errors evict to allow next-call retry. SIMPLIFY pass folded 8 fixes: SchemaGenerator singleton via DCL (was rebuilt per-cache-miss), shared `McpJson.MAPPER` (3 per-class mappers consolidated), `ResourceId.parseListPath`/`toListCorePath` unified the two parallel parsers, dead `correlationHeaders` parameter removed from `SessionBucketCache.resolvePrivate` (always `Map.of()` from callers), `RESERVED_KEYS` lifted to static, single-call `request.arguments()` lookup, Jackson-built not-implemented envelope (was string-concat). Build/test gate: `:mcp:test` 20 → 41 (+21 net), `:server:test` 1041 (incl. `McpReadToolsTest` 7 cases), 0 failures, 0 errors. | 1S.1 (contract), M.0-pre, M.0.1-pre, M.0.2-pre | 09 §1, §6.1–§6.4, §7.4, §7.5 | ✅ | `4bcff44c` |
| **M.1.1** | Read-tools entity-type sweep across `models`, `applications`, `toolsets`, `interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`, `files`, `prompts`, `conversations`. Reuse formatter + alias-resolution from M.1.0. Singleton-type special-case: `settings` blocks `dial_list_resources` with `405` + remediation hint to use `dial_get_resource(id='settings/platform/global')`. **Locked 2026-05-07**: per-type Core path routing centralized in `ResourceId` — config types (`models, interceptors, roles, keys, routes, schemas, settings`) hit `/v1/{type}/{bucket}/...`; metadata-list types (`applications, toolsets, files, prompts, conversations`) route listings through `/v1/metadata/{type}/{bucket}/...`; `files` additionally routes individual GETs through `/v1/metadata/...` (raw bytes via `/v1/files/...` are deferred to `dial_download_file` in M.3.0). Hierarchical-types envelope splits upstream `ResourceFolderMetadata.items[]` by `nodeType: FOLDER\|ITEM` per spec §6.3; `nextToken` maps to `nextCursor`. `recursive=true` and `cursor` are rejected on flat types with remediation hints (Core's config-resource controller is single-page-no-cursor). `SUMMARY_FIELDS` table populated for all 12 types per §6.4 — metadata-derived items (apps/toolsets/files/prompts/conversations) silently no-op fields the metadata response doesn't carry; N+1 enrichment is post-MVP explicit defer. `dial_describe_schema` unchanged (M.1.0 covered the 8 POJO types + meta-schema; files/prompts/conversations stay as not-implemented envelope — hand-writing schemas defeats §M9). Reviewer-driven fixes (pre-merge): (HIGH) cursor silently dropped on flat types — added `cursorNotSupported` guard mirroring the recursive rejection; (HIGH) envelope `path` used the alias bucket while child ids used resolved bucket — both now use resolved bucket so listings always return canonical ids per spec §6.2. SIMPLIFY pass folded 8 fixes: `shape()` 6 args → 3 (`ResourceId, resolvedBucket, format`), `summaryFields()` defensive-copy drop, `parentPath()` extracted, Jackson `path()` replaces `has()+get()` doubled probes, `LinkedHashMap` removed from `appendQuery`, `usesMetadataList`→`supportsRecursive`, milestone-narrating javadoc replaced with stable contract docs, `toCorePath`/`toListCorePath` javadoc pinned to the `parse`/`parseListPath` pairing rule. Build/test gate: `:mcp:test` 41 → 54 (+13 net), `:server:test` 1041 → 1045 (+4 net via `McpReadToolsTest`), 0 failures, 0 errors; checkstyle clean. Pre-existing flake observed once in `ApplicationDeploymentApiTest.testApplicationRestarted` (passed on rerun; unrelated to slice). | M.1.0, 1S.3, 1S.4 | 09 §6.3–§6.4 | ✅ | `23c00e23` |
| **M.2.0** | Write tools bootstrap: `dial_create_resource(id, spec, validate_only?)`, `dial_update_resource(id, spec, if_match?, validate_only?)`, `dial_delete_resource(id, confirm, if_match?)`. ETag header handling on reads/writes. `validate_only` dry-run forwarded to Core. `confirm: true` guard on delete. Structured error response on 409 / 404 / 412 with remediation. **Locked 2026-05-07**: (Constraint 1, halt) Core has NO `validate_only` query param on per-resource write endpoints — `ConfigResourceController.handlePost/handlePut/handleDelete` reject only `reveal_secrets` and `limit`; `validateOnly` exists only as a Java local in `AdminValidateController` / `AdminApplyController`. User-locked Option C: when `validate_only=true` MCP routes to `POST /v1/admin/validate` (slice 2S.12 + 4S.1) with single-entity manifest envelope `{manifests:[{kind, name, spec}], precheck:true}`. (Constraint 2, design pivot) Hierarchical types (`applications, toolsets, prompts, conversations`) have no POST surface — `ResourceController` is PUT-upsert + DELETE only. **ETag-idiom approach** (user-locked): `dial_create_resource` for ResourceController types sends `PUT + If-None-Match: *` so existing-resource yields 412 "Resource already exists" (Core `EtagHeader.validateIfNoneMatch`); `dial_update_resource` synthesizes `PUT + If-Match: *` (when no user etag) so missing-resource yields 412 "Resource must exist" (Core `EtagHeader.validateIfMatch`). MCP layer uses **request-side disambiguation** (Ambiguity D2 — locked): `EtagIdiom` enum on each tool's `shape()` flags which header was sent — `IF_NONE_MATCH_STAR` 412 → MCP 409 conflict, `IF_MATCH_STAR_SYNTHETIC` 412 → MCP 404 not found, `IF_MATCH_USER` 412 → real stale-etag error. Body pattern-matching deliberately rejected. **Per-controller routing**: ConfigResourceController types (`models, interceptors, roles, keys, routes, schemas`) use POST for create + PUT for update (Core's explicit 409/404 paths); ResourceController types use the etag-idiom layered onto PUT. **TYPE_TO_KIND** constant in `ResourceId` mirrors `AdminApplyController.KIND_URL_SEGMENT` inverse for the 9 admin-config kinds; `prompts`/`conversations`/`files` absent — `validate_only=true` rejected for those types. **Out of M.2.0 scope** (deferred to M.2.1): `files` (binary, M.3.0 owns upload/download) and `settings` (singleton — POST→405 by Core, no create surface). Both rejected with structured remediation; `McpWriteToolsTest` covers create-side. **`confirm: true` MCP-side gate** before any HTTP call — Core has no such check; integration test `deleteResourceWithoutConfirm` asserts the resource remains present. **Test C2 extraction**: `McpTestSupport` extracted from `McpReadToolsTest` — both `McpReadToolsTest` (11 cases) and new `McpWriteToolsTest` (16 cases) consume it. Reviewer pre-merge HIGH: `shapeValidate` echoed alias bucket in response `id` field — spec §6.2 violation ("listings always return canonical (resolved) ids"). Fix: `validate_only` branch threads `resolvedBucket` into `shapeValidate(resp, parsed, bucket)`; integration test `createResourceValidateOnlyResolvesPrivateBucketInResponseId` asserts the resolved bucket appears in the response id. SIMPLIFY pass folded 8 fixes: `isResourceControllerType` moved from `CreateResourceTool` static to `ResourceId` instance method (single-class home for per-type routing); `shape(resp, parsed, resolvedBucket, idiom)` signature replaced `new ResourceId(parsed.type(), bucket, parsed.name())` allocation; HashMap correlation builders → branched `Map.of`; dead `MODEL_SPEC_INVALID` constant + dead `stringProp` local removed; WHAT-only EtagIdiom javadoc dropped; remediation strings rewritten to drop slice-milestone references; `Set`/`HashSet` imports added. Build/test gate: `:mcp:test` 54 → 75 (+21 net), `:server:test` 1045 → 1060 (+15 net via `McpWriteToolsTest`; `McpReadToolsTest` 11/11 unchanged after `McpTestSupport` extraction), 0 failures, checkstyle clean. | 2S.11 (model write), 2S.10 (secret-field handling), M.1.0 | 09 §6.1 (tools 4–6), §6.5, §6.6, §7.4 | ✅ | `313b351e` |
| **M.2.1** | Write-tools entity-type sweep matching M.1.1 scope. Singleton-type special-case: `settings` `POST` returns `405` + remediation hint (no POST surface; use `PUT` for upsert). Forwards keys-controller DELETE ordering to Core (Core enforces per 2S.14). **Mechanical** after M.2.0 pattern locked. **Locked 2026-05-07**: absorbed the two M.2.0-deferred specials. (1) `settings` singleton — `dial_update_resource` and `dial_delete_resource` now route through ConfigResourceController (PUT-upsert / DELETE clears API blob via 3S.2-settings); `dial_create_resource` keeps the rejection with refreshed remediation pointing at `dial_update_resource` (POST has no create surface — Core would 405). (2) `files` — `dial_delete_resource` now allowed via plain `DELETE /v1/files/{bucket}/{name}`; `dial_create_resource`/`dial_update_resource` keep the rejection pointing at `dial_upload_file` from M.3.0. **`ResourceId.toMutationCorePath(resolvedBucket)` added** — returns plain `/v1/{type}/{bucket}/{name}` (no metadata-prefix). DeleteResourceTool switched to `toMutationCorePath` so files DELETE hits the standard `/v1/files/...` controller, not the M.1.1 metadata-GET route; Create/Update keep `toCorePath` (their files-rejection makes the difference moot). Reviewer-driven coverage gap: missing 404 case for files DELETE through the new path — added `deleteResourceFileMissingReturns404Error` (call `dial_delete_resource` on non-existent file path → asserts `HTTP 404` shape). SIMPLIFY pass folded 2 fixes: dropped awkward "(3S.2-settings)" parenthetical from `DeleteResourceTool` javadoc; trimmed transport-leaky "(POST returns 405 — no create surface)" parenthetical from settings rejection text — remediation strings stay timeless and action-oriented. Build/test gate: `:mcp:test` 75 → 76 (+1 net via `toMutationCorePathSkipsMetadataPrefixForFiles`), `:server:test` 1060 → 1064 (+4 net via `updateResourceSettingsUpsertsViaPut` / `deleteResourceSettingsClearsApiBlob` / `deleteResourceFileFromUserBucket` / `deleteResourceFileMissingReturns404Error`; existing `createResourceSettingsIsRejectedWithRemediation` updated to assert remediation now mentions `dial_update_resource`), 0 failures, checkstyle clean. Pre-existing flake observed once in `McpWriteToolsTest.toolsListExposesAllSixTools` (handshake startup race; passes in isolation; not introduced by M.2.1). **Keys-controller DELETE ordering** (2S.14) is server-enforced — MCP just forwards DELETE; no MCP-side machinery needed. | M.2.0, 3S.2, 3S.3, 3S.4 | 09 §6.1 (tools 4–6) | ✅ | `b12cf0df` |
| **M.3.0** | File tools: `dial_upload_file(id, content \| source_url, content_type?, max_bytes?)`, `dial_download_file(id, max_bytes?)`. Exact-one-of (`content` XOR `source_url`) via `oneOf` input schema (§6.7). SSRF protection on `source_url` (RFC 1918 / link-local / loopback / cloud-metadata blocklist; allow-list via `mcp.upload.sourceUrl.allowedUrlPrefixes`; feature opt-in via `mcp.upload.sourceUrl.enabled = false` default). Base64 binary content. Image-content block on download for `image/*` MIME (§6.8). **Locked 2026-05-07**: (Constraint 1) MCP-SDK 1.1.2 typed `JsonSchema` record has no `oneOf` slot — `content` XOR `source_url` enforced **handler-side** with the constraint described in the schema's `description`; the alternate `inputSchema(McpJsonMapper, String)` overload deserializes back into the same record and silently drops `oneOf`. (Constraint 2) `DialClient` was String-body-only — extended with two **strictly additive** methods: `requestMultipart(...)` for `MultipartForm` upload (Core's file controller requires `multipart/form-data`; field name ignored, per-part `Content-Type` carries the blob MIME), and `requestBinary(...)` returning a new `DialBinaryResponse(int, byte[], MultiMap)` for download (capturing as String would mangle non-UTF-8 bytes). Existing `request(...)` + `DialResponse` untouched. (Constraint 3) The external-fetch `WebClient` for `source_url` is a **separate** WebClient (not the loopback `DialClient`), held by `UploadFileTool`, configured `setFollowRedirects(false)` + 10s connect/idle timeouts + per-request `.timeout(10s)`; auth headers are NOT forwarded to it — would leak admin API keys to external hosts. (Constraint 4) **`fetchSourceUrl` enters Vert.x context** — `WebClient` requires the captured verticle context, mirrors `DialClient.requestMultipart` shape (REVIEW catch); `validate()` runs on `Schedulers.boundedElastic()` so sync DNS never lands on the event loop; Content-Length pre-check rejects oversized bodies before buffering (DoS / OOM guard). (Constraint 5) `SourceUrlGuard.Cidr` consolidated into existing `:config/IpAddressRange.parseCidr(String)` extracted from `IpAddressRangeDeserializer.toIpAddressRange` — one CIDR implementation for both the client-IP allow-list and the SSRF blocklist; SIMPLIFY-pass call. **Paused 2026-05-07 (§4.1 halt #4)**: 2 more tool registrations made the pre-existing `/mcp` deploy-order race near-deterministic (~2 random Mcp\*Test cases fail per `:server:test` run with HTTP 503 at handshake). Carved sibling slice **M.3.1-handshake-readiness** which fixed it via a deployment-ready latch in `McpRequestHandler`. M.3.0 resumed cleanly after M.3.1 squashed; full `:server:test` GREEN on rebase. **REVIEW pass closures**: (SSRF-1) `allowedPrefixes` `startsWith` was case-sensitive — attacker bypass via `HTTPS://EXAMPLE.COM/`; fixed by lowercasing both sides for the prefix match + lowercasing host before resolver (RFC 1035 DNS case-insensitive). (SSRF-2) DNS-rebinding TOCTOU between the guard's resolve and `WebClient`'s re-resolve; documented as known v1 limitation — proper fix needs a custom Vert.x address resolver + HTTPS SNI handling, out of slice scope; default-deny posture stands as the primary defense. (SSRF-3) `enabled=true && blockedCidrs=[]` would have silently opened the SSRF surface; constructor now refuses to start. **Out of M.3.0 scope** (deferred): rate-limit/concurrency interactions on the source-URL fetch path (M.0.2-pre owns those); custom DNS resolver for rebinding mitigation (post-MVP). Build/test gate: `:mcp:test` 80 → 93 (+13 SourceUrlGuardTest cases, 2 of them REVIEW-driven), `:server:test` 1064 → 1076 (+12 McpFileToolsTest cases; the 8-tools count flow updates `McpReadToolsTest` and `McpWriteToolsTest`), 0 failures, checkstyle clean. Pre-existing flake observed once in `ApplicationDeploymentApiTest.testApplicationRestarted` (passed on rerun; unrelated to slice). | 1S.5 (admin authz preflight), 3S.4 (file write), M.0-pre, M.0.1-pre, M.3.1-handshake-readiness | 09 §6.1 (tools 7–8), §6.7–§6.8, §7.1 | ✅ | `9ee807eb` |
| **M.3.1-handshake-readiness** | Defer Core's `/mcp` mount until `McpVerticle.start()` completes. Today `AiDial.start()` binds the HTTP listener and only then `vertx.deployVerticle(McpVerticle)` — for the deployment window, Core's `McpRequestHandler` returns the M.0-pre 503 stub. Pre-existing race surfaced as flaky `:server:test` runs since M.1.0; M.3.0 made it near-deterministic by registering 2 more tools. Fix: deployment-ready `Future<Void>` captured from `vertx.deployVerticle(...).onSuccess/.onFailure` in `AiDial.start()`, consumed by `McpRequestHandler.handle()` — fast path when latch already complete; slow path pauses the request body, sets a 2s timer, and resumes/dispatches once the future resolves (timer-fired → 503; future-fail → 503; future-success → delegate to SDK). **Implementation discovery (2026-05-07)**: bare wait-then-dispatch was insufficient — HC5 read-times-out at 180s if the body is dropped during the wait, because the SDK transport installs `bodyHandler` only post-dispatch. `request.pause()` at slow-path entry + `request.resume()` after `dispatch()` registers the bodyHandler is load-bearing; pinned by `waitsForLatchThenDispatchesWhenDeploymentCompletesLater`'s body-survival assertion. Out of scope: rate-limit/concurrency interactions (M.0.2-pre owns those), fall-back retry (this is a server-side fix, not a client retry). Tests: 4-case `McpRequestHandlerTest` in `:mcp` covering both fast-path branches, slow-path success with body-survival pin, and slow-path timeout. | M.0-pre, M.0.0-bridge | 09 §7.1, §7.2 | ✅ | `f7769e16` |
| **M.4.0** | Publication tool: `dial_publish_resource(id, target)`. Forwards to `POST /v1/ops/publication/create` (`DialClient` wraps `Publication` request body with `resources[]` + `targetFolder`). Initiates async PENDING; admin approval required before resource is publicly visible. **Note**: targets the existing Resource Operations API, not the Configuration API — see spec §6.1 tool 9 note. **Locked 2026-05-07**: (Constraint 1) MCP-SDK 1.1.2 typed `JsonSchema` accepts only the two required string args (`id`, `target`); the spec's cross-cutting `validate_only` / `confirm` / `if_match` are deliberately absent — Core's publication-create has no dry-run and the lifecycle is non-destructive (PENDING gates the publish). (Constraint 2) `targetFolder` is passed verbatim to Core after preflight (`startsWith("public/")` + `endsWith("/")`); the preflight produces a cheaper, MCP-shaped error than waiting for Core's `IllegalArgumentException` and is the only handler-side validation beyond `ResourceId.parse`. (Constraint 3) `targetUrl` per `Publication.Resource` is **client-computed** (`{type}/{target}{leafName}`) — the service does NOT derive it from `targetFolder`; intermediate folders in source `name` strip from `targetUrl` per spec §3.2 (test: `publishMultiLevelNamePlacesLeafAtTargetRoot`). (Constraint 4) Source URL = `{type}/{resolvedBucket}/{name}`; Core's `ResourceDescriptorFactory.fromPrivateUrl` validates type and decrypts the bucket — MCP type-allowlists nothing, type errors surface as Core's `IllegalArgumentException`. (Constraint 5) `ResourceId.leafName()` extracted from `UploadFileTool`'s private static helper — single-class home for hierarchical-name leaf extraction; `UploadFileTool` migrated. SIMPLIFY pass folded 3 fixes: leafName helper consolidation, preflight WHY-comment added (spec/Core duplication is intentional for clearer error), `publishMissingSourceReturnsUpstreamError` strengthened to assert the `HTTP `-prefixed envelope shape. REVIEW pass closures (test-coverage gaps): added `publishMultiLevelNamePlacesLeafAtTargetRoot` (locks the leaf-extraction contract for nested names) and `publishWithExplicitBucketSkipsPrivateAlias` (covers the non-`private` bucket short-circuit branch). **Out of M.4.0 scope** (spec §6.1 tool 9 explicit non-asks): `validate_only`, `confirm`, `if_match` (publish is non-destructive PENDING; admin approval is the gate); recovery from Core's 500-on-duplicate-publication-url defect (Core-side bug). Build/test gate: `:mcp:test` 93 (unchanged — no `:mcp` unit tests added; integration coverage lives in `:server`), `:server:test` 1076 → 1086 (+10 net via `McpPublishToolTest`; the 9-tools count flow updates `McpReadToolsTest`, `McpWriteToolsTest`, `McpFileToolsTest`), 0 failures, checkstyle clean. Pre-existing flake observed in `ApplicationDeploymentApiTest.testApplicationRestarted` (1-in-3 isolation reruns; same race documented in M.3.0 retro; no MCP-side touch points). | 3S.4 (files/prompts/conversations write), 1.5S.3 (pub/sub for state observability), M.0-pre | 09 §6.1 (tool 9), §3.2 illustrative composition | ✅ | `12ba6278` |
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
