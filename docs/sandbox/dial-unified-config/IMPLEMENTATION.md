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

Two parallel tracks. Server work depends only on prior server slices; CLI work depends on the corresponding server slice's wire contract being stable (PR open or merged — not necessarily landed).

| Track | Owner(s) | Scope | Module(s) |
|---|---|---|---|
| **A — Server** | Implementation lead + core-team contributors | `server/`, `storage/`, `config/`, `credentials/` | `:server`, `:storage`, `:config`, `:credentials` |
| **B — CLI** | Second implementer | New sibling `:cli` Gradle module in **the same repo** (Picocli + Quarkus, JVM-mode for MVP — see §3.4 GraalVM deferral) | `:cli` (new) |

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

- Track B starts as soon as Track A's slice **1S.1** (`GET /v1/models/public/{name}`) PR is open. The CLI doesn't need it merged — only the wire contract stable.
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
| **1S.2** | `GET /v1/models/public/` listing with `?limit&cursor` pagination (default 100, max 500). `hasMore` always present. Trailing-slash optional. | 1S.1 | 03 §1, §4 | ✅ | `395a9360` |
| **1S.3** | Extend reads to remaining MergedConfigStore-managed types (`interceptors`, `roles`, `keys`, `routes`, `schemas`, `settings`). Bucket-aware authz (`platform/` admin-only). 405 for `POST` on `/v1/settings/platform/global` with `Allow: GET, PUT, DELETE` (singleton has no create surface; `PUT` is upsert and `DELETE` clears the API blob — Phase 2 implements `DELETE` alongside `PUT`). Settings GET projection: `"api"` (blob present) | `"file"` (no blob, file defines fields) | `"default"` (no blob, file silent). | 1S.1, 1S.2 | 03 §1; 04 §1.2 | ✅ | `af64319e` |
| **1S.4** | Read paths for `applications`, `toolsets` via existing `ApplicationService` / `ToolSetService` with `ConfigAuthorizationService` preflight. | 1S.1 | 03 §1; 02 §6 | ✅ | `acfe1ace` |
| **1S.5** | Admin authz preflight on existing `FILES` / `RESOURCE` controllers for `public/` admin reads/writes; deny admin reach into user buckets. | 1S.4 | 03 §1; OQ-21, OQ-33 | ✅ | `7be8db9e` |
| **1S.6** | `GET /v1/admin/export` — full snapshot of in-memory `Config`. JSON + YAML output. | 1S.3 | 03 §1; 07 Phase 1 | ✅ | `ec1ac537` |
| **1S.7** | `GET /v1/admin/health/config` returning `{status, skipped[]}` (skipped is `[]` in Phase 1 — invalid-entity store ships in 2S.9). Prometheus metric scaffolds (cardinality-zero in Phase 1). | 1S.0 | 07 Phase 2; 02 §4.1 | ✅ | `2a5a10ac` |

**Track B — CLI**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **1C.0** | New `:cli` Gradle module. Picocli + Quarkus Command Mode skeleton. `~/.dial-cli/config.yaml` profile loader. API-key resolution chain (env var → keystore → `--api-key-file` → no-echo prompt). Direct dependency on `:config` module data classes. | 1S.1 (contract only) | 05 §1, §2, §6 | 📋 | — |
| **1C.1** | `dial-cli env list / current / use / check`. Persist `defaults.env` on `use`. | 1C.0 | 05 §1 | 📋 | — |
| **1C.2** | `dial-cli model get <name>` and `dial-cli get models` (alias). `-o table\|json\|yaml`. | 1C.0, 1S.1 | 05 §1; 06 §2.2 | 📋 | — |
| **1C.3** | Extend `get` / `list` to all entity types. **Mechanical** once 1C.2 lands. | 1C.2, 1S.3, 1S.4 | 05 §1 | 📋 | — |
| **1C.4** | `dial-cli export --env <env>`. Streams `GET /v1/admin/export` to stdout / file. | 1C.0, 1S.6 | 05 §1 | 📋 | — |
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
| **2S.10** | `SecretFieldProcessor` + `@EncryptedField` annotation in `:config`. Dual `ObjectMapper` (blob I/O vs API response). Mask `***` on Public-view; preserve-on-omit (and `***` sentinel) on `PUT`. Reuses `CredentialEncryptionService` primitives. | — | 04 §2.4–2.6 | 📋 | — |
| **2S.11** | `MODEL` `ResourceTypes` entry. `POST /v1/models/public/{name}` (409 on conflict). `PUT /v1/models/public/{name}` (404 on missing, optional `If-Match` → 412). `DELETE`. Strict POST/PUT split. Bucket-aware authz. ETag in response header. | 2S.1-pre, 2S.2-pre, 2S.8, 2S.9, 2S.10 | 03 §1, §3; 07 Phase 2 | 📋 | — |
| **2S.12** | `POST /v1/admin/validate` — model-scoped (Phase 4 extends to other types and bulk). | 2S.11 | 03 §6 | 📋 | — |
| **2S.13** | Cross-reference validation on per-entity write — strict-by-default `422`; `config.write.softValidation` opt-in. | 2S.11 | 03 §6; 02 §9 | 📋 | — |
| **2S.14** | Writer-pod immediate `volatile Config` swap via `rebuildNow()` after write. Keys-controller `DELETE` ordering invariant (delete blob → `removeKey` → `rebuildNow`). | 2S.11 | 02 §4 | 📋 | — |

**Track B — CLI (models-only writes)**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **2C.0** | `dial-cli model add` (POST). `--dry-run`. Exit codes per 06 §2.8 (`0` / `5` / `2` / `3`). No `--template` yet (Phase 4). | 1C.2, 2S.11 | 05 §1; 06 §2.8 | 📋 | — |
| **2C.1** | `dial-cli model update` (PUT) with `--set k=v` (GET → local-merge → PUT). `--if-match`. Exit codes (`0` / `4` / `6` / `2`). | 2C.0 | 05 §1 (Update ergonomics) | 📋 | — |
| **2C.2** | `dial-cli model delete` with `--if-match`. | 2C.0 | 05 §1 | 📋 | — |
| **2C.3** | `dial-cli model validate` against `POST /v1/admin/validate`. | 2S.12, 2C.0 | 05 §1 | 📋 | — |
| **2C.4** | `dial-cli model promote --from --to` (as-is + explicit `--template` only — no `auto` reverse-match in MVP). | 2C.0 | 05 §4 | 📋 | — |
| **2C.5** | `dial-cli model diff --source --target` (single-type). | 2C.0 | 05 §1 | 📋 | — |

### 5.3 Phase 1.5 — Redis pub/sub (concurrent with Phase 2 write path)

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **1.5S.0-pre** | `ResourceTopic` codec: shared `ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES = false` + `JsonInclude.NON_NULL`. New `ResourceTopic(redis, key, mapper)` constructor; legacy delegates with safe defaults. `ResourceService` wires shared mapper. **Standalone PR before any 1.5 traffic.** | — | 07 Phase 1.5 prereqs; 02 §11.1 | 📋 | — |
| **1.5S.1** | `ResourceTopic.subscribeAll(Consumer<ResourceEvent>)`. New `globalSubscribers` `CopyOnWriteArrayList`; second loop in `handle()`. | 1.5S.0-pre | 02 §11.1 | 📋 | — |
| **1.5S.2** | `ResourceEvent.senderPodId` field (`@JsonInclude(NON_NULL)`, `@JsonIgnoreProperties(ignoreUnknown = true)`). Pod-UUID generated at `:server` boot, supplied to `ResourceService` via `Supplier<String>`. | 1.5S.0-pre | 02 §11.1 | 📋 | — |
| **1.5S.3** | `MergedConfigStore` `subscribeAll` listener. Filter by `senderPodId` (skip-self) + resource type. 500ms trailing-edge debounce on `requestRebuild()`. Polling stays at 60s. | 1.5S.1, 1.5S.2, 2S.8 | 02 §11.1; 07 Phase 1.5 | 📋 | — |

### 5.4 Phase 3 — Write API for all entity types (mechanical extension)

**Track A — Server**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **3S.0-pre** | `ResourceAuthSettingsEncryptionService.processFields()` extension for `codeVerifier` with lazy plaintext fallback (catch base64 decode error → return as-is → re-encrypt on next write). | — | 07 Phase 3 prereqs; 04 §2.7 | 📋 | — |
| **3S.1** | `BlobEntityValidator` helper for apps/toolsets — validates against current `Config` (interceptor refs, schema refs, deployment dependencies). Folded into Configuration API listing/get response only; chat-completion hot path unchanged. | 2S.9 | 07 Phase 3; 02 §4.3 | 📋 | — |
| **3S.2** | Write APIs (POST/PUT/DELETE) for `schemas`, `interceptors`, `roles`, `keys` (with dual-format compatibility from 2S.0-pre), `routes`, `settings` (PUT upsert + DELETE clears API override and reverts to file/default; 405 on POST). Start with one type to validate the pattern; subsequent types **Mechanical**. | 2S.11, 2S.13, 3S.0-pre | 03 §1; 07 Phase 3 | 📋 | — |
| **3S.3** | Admin write paths for `applications`, `toolsets` in `public/` via existing `ApplicationService` / `ToolSetService` unified with user-published. Removes `DeploymentService` config-file special-case. | 3S.1 | 07 Phase 3; 02 §6 | 📋 | — |
| **3S.4** | Admin write paths for `files`, `prompts`, `conversations` in `public/` via existing controllers + `ConfigAuthorizationService` preflight. Reuses existing resource types. | 1S.5 | 03 §1; OQ-21 | 📋 | — |

**Track B — CLI**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **3C.0** | Generic Picocli command class parameterized by entity type so `add` / `update` / `delete` / `validate` / `promote` / `diff` ship for all remaining types. (If reviewer prefers per-type symmetry, split — but the principle §2.1 favors one parameterized class.) | 2C.5, 3S.2, 3S.3, 3S.4 | 05 §1 | 📋 | — |

### 5.5 Phase 4 — Declarative apply + diff (NICE TO HAVE)

> **MVP-cut**: deliver **4S.0**, **4S.1**, **4C.0** (apply with fully-resolved manifests). Defer the template DSL, overlays, bundles, and reverse-match `auto` promote.

**Track A — Server**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **4S.0** | `POST /v1/admin/apply` — bulk upsert; dependency-ordered sequential (`globalSettings → schemas → interceptors → roles → keys → routes → models → toolsets → applications`); continues on failure; per-entity status array. `precheck: true\|false` (default `true`); `softValidation` orthogonal; proposed-config validation always-on. | 3S.2, 3S.3 | 03 §7; 07 Phase 4 | 📋 | — |
| **4S.1** | `POST /v1/admin/validate` — multi-entity, batch-aware with `precheck` semantics. | 4S.0 | 03 §6 | 📋 | — |

**Track B — CLI**

| ID | Slice | Depends on | Design anchors | Status | Commit |
|---|---|---|---|---|---|
| **4C.0** | `dial-cli apply -f <path>` — single-doc and multi-doc YAML manifest parsing, validate-first gate (`POST /v1/admin/validate`) then `POST /v1/admin/apply`. `--dry-run`. Exit codes per 06 §2.8. **No template DSL, no overlays, no bundles in MVP — manifests must be fully resolved.** | 4S.0, 4S.1 | 03 §7; 05 §5.1 | 📋 | — |

**Deferred beyond MVP** (if Phase-4 demand emerges post-MVP):

- **4C.1** Template DSL (`extends`, `includes`, `!if`, `!for`, function set) — 05 §3
- **4C.2** Overlays (base + overlay) — 05 §5.2
- **4C.3** Bundles — 05 §5.3
- **4C.4** `${SECRET:*}` resolution — 05 §3.1
- **4C.5** `promote --template auto` reverse-match — 05 §4

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
DEMO
```

~25 PRs, end-to-end across the API + CLI + cross-replica + multiple entity types. Phase-3 entity-sweep can be partial; reviewer feedback determines where to stop.

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
