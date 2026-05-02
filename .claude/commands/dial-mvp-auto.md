---
description: Run multiple dial-mvp slices sequentially in auto-approve mode — halt only on concerns, ambiguity, test failures, or §4.1 conditions.
argument-hint: [<count> | until-blocked | until-phase-end]
allowed-tools: Read, Edit, Write, Glob, Grep, Agent, Skill, LSP, TaskCreate, TaskUpdate, TaskList, Bash(./gradlew:*), Bash(git:*), Bash(gh:*), Bash(ls:*), Bash(find:*), Bash(cat:*)
---

# Dial-MVP Auto-Mode (multi-slice batch)

Run multiple `dial-mvp` slices sequentially. Apply IMPLEMENTATION.md §2 (principles), §4 (agent loop), and §4.1 (halt conditions) exactly as `/dial-mvp` does — but the two routine halts (architect-plan approval, merge-diff approval) become **conditional**, gated by the self-tests in IMPLEMENTATION.md §4.2.

Auto-approval is **earned, not assumed**. Default to halting on uncertainty.

Argument: `$ARGUMENTS`

## Prerequisites

Same as `/dial-mvp`:

- Working tree on `feature/unified-config` (or a slice sub-branch).
- `git status` is clean.
- IMPLEMENTATION.md + project memory accessible.
- `./gradlew build -x test` runs cleanly with current credentials.

## Step 1 — Load context

Read in parallel:

- `docs/sandbox/dial-unified-config/IMPLEMENTATION.md`
- Project memory `project_unified_config_review.md`
- Project memory `project_unified_config_implementation.md`

Required before any planning.

## Step 2 — Plan the batch

Determine the slices to run from §5:

- **Filter**: status `📋` + every `Depends on` slice in status `✅`.
- **Sort**: by phase, then by slice ID.
- **Cap by `$ARGUMENTS`**:
  - empty or `until-blocked` → all runnable slices until any halt.
  - integer (`3`) → exactly that many slices.
  - `until-phase-end` → run until the next phase boundary in §5.

Print the batch list to the user: slice IDs, titles, deps satisfied, est. track. **Halt for the user's confirmation that the batch is correct before starting.** This is the only mandatory halt at the start of the batch.

## Step 3 — Per-slice loop with conditional halts

For each slice in the confirmed batch:

1. **Branch**: cut `feature/unified-config-<slice-id>-<short-title>` from the latest `feature/unified-config`. Hyphen separator (§3.2).

2. **EXPLORE** *(skippable per `/dial-mvp` rules)* — feature-dev:code-explorer; LSP `documentSymbol` / `workspaceSymbol`.

3. **ARCHITECT** — feature-dev:code-architect produces the file-level plan. Run the **§A self-test** (IMPLEMENTATION.md §4.2):
   - If **every item passes** → proceed without asking. Print: `[<slice-id>] ARCHITECT auto-proceed (N/N self-test items passed)`.
   - If **any item is uncertain or false** → halt per §4.1 format. Wait.

4. **IMPLEMENT** — execute the approved plan; TDD; run `./gradlew checkstyleMain :server:test` and module-specific tests.

5. **SIMPLIFY** — invoke the `simplify` skill on changed files.

6. **REVIEW** — feature-dev:code-reviewer; LSP `findReferences` on touched methods.

7. **MERGE LOCALLY** — run the **§B self-test** (IMPLEMENTATION.md §4.2):
   - If **every item passes** → squash-merge per `/dial-mvp` step 7 procedure (a–h). Print: `[<slice-id>] MERGED auto (N/N self-test items passed) → <short-sha>`.
   - If **any item is uncertain or false** → halt per §4.1 format. Wait.

8. **Inter-slice progress**: print one-line summary `[N/M slices done, next: <slice-id>]`.

## Stop conditions

- Batch completed → print summary (slices merged, short-SHAs, brief titles) and stop.
- Any §4.1 halt condition triggered → halt as documented; do not continue.
- Any §4.2 self-test fails → halt as documented; do not continue.
- `$ARGUMENTS` count reached → print summary, stop.

## Important

- **Use only for mechanical or semi-mechanical slices** — Phase-3 entity-type sweep (3S.2 after the first type validates the pattern), Phase-3 CLI extension (3C.0), Phase-2 prereqs that are isolated refactors (2S.0-pre, 2S.1-pre, 2S.2-pre).
- **Don't use for high-uncertainty slices** — 1S.0 bootstrap, 2S.8 `MergedConfigStore`, 2S.10 `SecretFieldProcessor`, 4S.0 apply endpoint. Use plain `/dial-mvp <id>` for those — every halt becomes a real halt and the user reviews the architect plan and the diff.
- The user pre-approves the **batch** at Step 2, not the individual slices. Each slice's self-tests still gate auto-proceed at the architect and merge halts.
- Self-test items are **halt triggers, not pass-fail booleans the orchestrator gets to game**. When in doubt, halt.
- All other rules from `/dial-mvp` apply: §4.1 halt conditions, §3.2 branching, §3.5 commit format, §8 doc-amendment lifecycle.
- After the batch completes or halts, stop and hand off to the user. The next batch begins on the user's signal in a fresh session.
