---
description: Resume the dial-unified-config MVP — pick or continue a slice from IMPLEMENTATION.md and run the agent loop.
argument-hint: [<slice-id> | status]
allowed-tools: Read, Edit, Write, Glob, Grep, Agent, Skill, LSP, TaskCreate, TaskUpdate, TaskList, Bash(./gradlew:*), Bash(git:*), Bash(gh:*), Bash(ls:*), Bash(find:*), Bash(cat:*)
---

# Dial-Unified-Config MVP Orchestrator

You are the orchestrator for the dial-unified-config MVP implementation. The execution playbook is at `docs/sandbox/dial-unified-config/IMPLEMENTATION.md`. Apply its rules — especially principles §2, the agent loop §4, and the halt conditions §4.1 — to every step.

Argument: `$ARGUMENTS`

## Prerequisites

- The repo is on or near `feature/unified-config` (or you can switch to it).
- `IMPLEMENTATION.md` exists at `docs/sandbox/dial-unified-config/IMPLEMENTATION.md`.

## Step 1 — Load context

Read in parallel:

- `docs/sandbox/dial-unified-config/IMPLEMENTATION.md`
- The project memory entries: search the user's memory directory for `project_unified_config_review.md` and `project_unified_config_implementation.md`. Both are required context.

Do not proceed past this step until both files are loaded.

## Step 2 — Determine the slice

Interpret `$ARGUMENTS`:

- **Empty**: list every slice in §5 with status `📋` or `🚧` or `🔍`. Recommend the first `📋` slice whose dependencies are all `✅`. Ask the user to confirm the recommendation or pick a different slice ID. Stop and wait.
- **`status`**: print a short status report — counts by status, currently in-flight slices, blocked slices (whose deps aren't merged) — and stop. Do not start any work.
- **A slice ID** (e.g. `1S.0`, `2S.11`, `1C.2`): jump to Step 3 with that slice. If its dependencies aren't all `✅`, halt per §4.1 and surface the dependency gap before proceeding.

## Step 3 — Run the agent loop (§4 of IMPLEMENTATION.md)

For the chosen slice:

1. **Branch**: ensure you're on a sub-branch named `feature/unified-config-<slice-id>-<short-title>` (hyphen separator — slash is rejected by Git because `feature/unified-config` is itself a branch ref) cut from the latest `feature/unified-config`. Create it if missing. The current `git status` should be clean.

2. **EXPLORE** *(skip if the code area is already known this session)* — dispatch `feature-dev:code-explorer` to trace existing patterns in the touched area. Use LSP `documentSymbol` / `workspaceSymbol` to map class shapes; reach for grep only when LSP can't resolve (jclouds-dependent files in `:credentials` — see IMPLEMENTATION.md §7.5). Output: file paths + 5-line summary per layer.

3. **ARCHITECT** — dispatch `feature-dev:code-architect` with the design anchors from the slice's row, the principles in §2, and the Explore output. **Verify each design-doc anchor with LSP before producing the plan** — does the cited class/method exist with the expected signature on the expected line? Stale anchors trigger halt condition §4.1 #2. Produce a file-level plan citing the design anchor for each design decision. **Halt and present the plan to the user for approval before any code changes.**

4. **IMPLEMENT** — execute the approved plan as a fork or `general-purpose` agent (use `isolation: "worktree"` if the slice is independent of in-flight work). Before changing a method signature or extending a class, use LSP `findReferences` / `incomingCalls` to bound the blast radius — if it exceeds the slice plan, halt per §4.1 #3 (scope drift). TDD: write the integration test first using the `ResourceApiTest` pattern, then make it pass. Run `./gradlew checkstyleMain :server:test` (and the relevant module-specific tests) before reporting done.

5. **SIMPLIFY** — invoke the `simplify` skill on the changed files. Apply principles §2.1 / §2.2 (Simplicity First, Surgical Changes). Fix issues found; do not touch unrelated code.

6. **REVIEW** — dispatch `feature-dev:code-reviewer` for a final pre-PR pass. Focus areas: bugs, Vert.x event-loop violations (§7.3), security, naming alignment with the locked vocabulary (§2.3), test coverage. Use LSP `findReferences` on every method the slice modified to confirm surgical-cleanup orphaned nothing (§2.2). Ignore LSP diagnostics on files the slice didn't touch — they aren't introduced by your work.

7. **MERGE LOCALLY** — once SIMPLIFY + REVIEW pass cleanly:

   a. Verify `git status` is clean on the slice sub-branch.
   b. Present the slice diff (`git diff feature/unified-config..HEAD`) and a draft commit message in IMPLEMENTATION.md §3.5 format. **Halt for the user's approval** before merging.
   c. Switch to integration: `git checkout feature/unified-config`.
   d. Squash-merge: `git merge --squash <sub-branch>` (stages all sub-branch changes without committing).
   e. Commit with the approved message via HEREDOC; include the `Co-Authored-By` trailer.
   f. Delete the sub-branch: `git branch -D <sub-branch>`.
   g. Update IMPLEMENTATION.md §5: slice `Status` `🚧` → `✅`, fill the `Commit` column with the squash commit's short SHA.
   h. Stop and hand off to the user. The next slice begins on the user's signal in a fresh session.

## Step 4 — Update the slice register

After each transition, edit `docs/sandbox/dial-unified-config/IMPLEMENTATION.md` §5: update the slice's `Status` column (`📋` → `🚧` → `🔍` → `✅`) and `PR` column. Never re-number slice IDs — they are stable references.

## Halt conditions (§4.1 — non-negotiable)

Stop the loop and ask the user when:

- Discovered constraint contradicts the plan (e.g. CI mismatch, missing dependency).
- Existing code differs materially from the design-doc anchors (renamed class, moved file, changed signature).
- Slice scope would need to grow beyond its register row.
- Tests fail in ways the architect plan didn't predict.
- A cross-slice contract change is implied by the current work.
- A locked decision in §9 or memory needs amending to make progress.
- Implementation would require violating a §2 principle.
- Two or more readings of the design are equally valid.

**Halt format**: (1) what was discovered, (2) why it blocks the current path, (3) two-or-three options with trade-offs, (4) your recommendation, (5) wait. Do not proceed until the user responds. Do not start parallel "just-in-case" work.

## Important

- The user is the only approver of architect plans, slice diffs, and halt-decisions. There is no per-slice formal code-owner review — that happens once at MVP-complete via the big PR `feature/unified-config` → `development`.
- Update slice statuses as you go; don't batch the edits to the end.
- If a review round amends a design doc, also add a one-line entry to the project memory `project_unified_config_review.md` per IMPLEMENTATION.md §8.
- After the slice is squash-merged into `feature/unified-config`, stop and hand off to the user. The next slice begins on the user's signal in a fresh session.
