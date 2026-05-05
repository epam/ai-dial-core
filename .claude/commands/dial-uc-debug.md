---
description: Investigate a dial-unified-config issue/idea against design docs + implementation, propose a plan, halt for approval before any edits.
argument-hint: <issue, question, or improvement idea — free text>
allowed-tools: Read, Edit, Write, Glob, Grep, Agent, Skill, LSP, TaskCreate, TaskUpdate, TaskList, ExitPlanMode, AskUserQuestion, Bash(./gradlew:*), Bash(git log:*), Bash(git status:*), Bash(git diff:*), Bash(git branch:*), Bash(ls:*), Bash(find:*), Bash(grep:*), Bash(cat:*)
---

# Dial-Unified-Config Debug / Explore / Improve

You are the orchestrator for a focused investigation into the dial-unified-config design or implementation. The user brings the question; you collect warm-up context, find root cause or identify the change shape, propose a plan, halt for approval, and only then implement — keeping design docs in sync with code.

**User's input (verbatim):** `$ARGUMENTS`

If `$ARGUMENTS` is empty, prompt for one of: a bug/symptom, a design ambiguity, or an improvement idea. Stop until provided.

---

## Step 1 — Warm-up (mandatory; do not skip)

Read these in parallel before forming any hypothesis. Do not narrate the reading; just load.

- `docs/sandbox/dial-unified-config/README.md`
- `docs/sandbox/dial-unified-config/01-problem-and-context.md`
- `docs/sandbox/dial-unified-config/02-architecture.md`
- `docs/sandbox/dial-unified-config/03-api-reference.md`
- `docs/sandbox/dial-unified-config/04-security-and-audit.md`
- `docs/sandbox/dial-unified-config/05-cli-design.md`
- `docs/sandbox/dial-unified-config/06-cli-user-guide.md`
- `docs/sandbox/dial-unified-config/07-migration-and-rollout.md`
- `docs/sandbox/dial-unified-config/08-open-questions-and-references.md`
- `docs/sandbox/dial-unified-config/09-admin-mcp-spec.md`
- `docs/sandbox/dial-unified-config/IMPLEMENTATION.md` — operating principles §2 and slice register §5 are load-bearing.
- The user's auto-memory unified-config entries: search the user's memory directory (`~/.claude/projects/.../memory/`) for `project_unified_config_*.md` and `feedback_*unified_config*.md` / `feedback_dial_mvp*.md`. Load every match.

Also gather lightweight branch state:

- `git log development..HEAD --oneline` — what slices have landed on `feature/unified-config`.
- `git status --short` — uncommitted work that may be relevant.

Do not proceed past this step until warm-up is complete. If any required doc is missing, halt and tell the user.

---

## Step 2 — Classify the request

Decide which of these the user's input is (one is enough; some inputs span more):

- **Bug / unexpected behavior** → root-cause hunt: reproduce mentally from the design + code, then point to the failing component or config divergence.
- **Design ambiguity / spec question** → answer from the docs first; only dig into code if the docs don't resolve it.
- **Improvement / new capability idea** → check it against §2 principles (Simplicity First, Surgical Changes), the slice register §5 (is this already in scope of an unmerged slice?), and the locked decisions in `08-open-questions-and-references.md`. An idea that re-opens a locked OQ is a halt point — surface it, do not silently override.
- **Refactor / cleanup proposal** → check whether the touched code belongs to a merged slice (✅ in §5) or an in-flight one (🚧 / 🔍). Cleanup of in-flight work belongs in that slice, not a separate change.

State the classification in one sentence to the user, then continue.

---

## Step 3 — Explore (read-only; subagent-friendly)

Pick the lightest tool that resolves the question:

- **Spec-only question**: stay in docs. No code reads.
- **Single known file**: read it directly.
- **Cross-cutting investigation**: dispatch `Explore` (or `feature-dev:code-explorer` if architectural depth is needed). Brief it with the user's input verbatim, the warm-up findings, and the specific question to answer. Ask for file paths + line numbers + a short summary. Cap any Explore agent to 500 words of report.
- **Verify design-doc anchors before trusting them.** Stale anchors (renamed class, moved file, changed signature) trigger halt condition `IMPLEMENTATION.md §4.1 #2` — surface and stop.

Output of this step (kept in the conversation, not a separate file):

1. **Findings** — what the code/docs actually say, with file:line citations.
2. **Root cause / change shape** — the precise mechanism behind the bug, or the precise diff shape the improvement implies.
3. **Open questions for the user** — only if there is genuine ambiguity. Use `AskUserQuestion` for these. Don't invent ambiguity to look thorough.

---

## Step 4 — Propose a plan (halt point)

Produce a plan that includes, in this order:

1. **Context** — one paragraph: what the user reported, what you found, why it needs (or doesn't need) a change.
2. **Verdict** — bug in code / bug in spec / config issue / design gap / improvement / no-op. If "no-op", explain and stop here without ExitPlanMode.
3. **Proposed change** — minimum diff that resolves it. Per §2.1 (Simplicity First) and §2.2 (Surgical Changes): smallest viable change, no speculative refactors, no adjacent cleanup.
4. **Files to touch** — explicit list. Split into:
   - **Code** (tests, production)
   - **Design docs** (which `docs/sandbox/dial-unified-config/*.md` files need a sync edit, and what changes — see Step 5 for rules)
   - **Memory** (an entry in `project_unified_config_review.md` if a locked decision is amended, per IMPLEMENTATION.md §8)
5. **Verification** — how we'll know the change works: which `:server:test` classes, which manual curl, what the success criterion is.
6. **Out of scope** — what *not* to touch even though it's tempting.

If the plan touches code or docs, **halt via `ExitPlanMode`** and wait for approval. If the user's question is fully answered without any change ("no-op"), do not call ExitPlanMode — just summarize and stop.

Use `AskUserQuestion` (not ExitPlanMode) for genuine forks — e.g. two equally valid fixes with different trade-offs.

---

## Step 5 — Implement (only after approval)

When the plan is approved:

1. **Branch hygiene**: if the change is non-trivial and you're on `feature/unified-config`, cut a sub-branch `feature/unified-config-<short-slug>` (hyphen separator — `feature/unified-config/x` is rejected because the integration ref already exists). For one-line fixes or test-only changes, working directly on `feature/unified-config` is acceptable — confirm with the user once.

2. **Tests first** when applicable. Match the `ResourceApiTest` / `ConfigBootstrapTest` pattern. Use `@DialConfigLocation` for test-specific dial configs.

3. **Implementation** — execute the approved plan. Follow `IMPLEMENTATION.md §2`:
   - **Simplicity First** (§2.1): minimum code; no speculative abstractions.
   - **Surgical Changes** (§2.2): touch only what the plan lists; remove only imports/symbols your changes orphaned.
   - **Codebase addenda** (§2.3): Vert.x event-loop discipline, volatile-swap idiom, locked vocabulary (`platform/` not `admin/`/`global/`), strict POST/PUT split, Checkstyle 180-char.

4. **Design-doc sync (load-bearing — do NOT skip)**:
   - If the change alters externally-observable behavior (API shape, error code, auth rule, validation, CLI flag, audit field), update the **owning** doc per the doc-intent table in `review-unified-config.md`:
     - HTTP shape → `03-api-reference.md`
     - AuthZ / audit → `04-security-and-audit.md`
     - System behavior, hot-reload, pub/sub → `02-architecture.md`
     - CLI UX → `06-cli-user-guide.md`; CLI internals → `05-cli-design.md`
     - Phase / rollout → `07-migration-and-rollout.md`
     - Open question resolved → close it in `08-open-questions-and-references.md`
   - Do **not** update doc 0X just because you read it — only when the change makes its current text wrong or incomplete.
   - If the change amends a locked decision, add a one-line entry to the user's memory file `project_unified_config_review.md` per IMPLEMENTATION.md §8.

5. **Verification gate** before reporting done:
   - `./gradlew checkstyleMain checkstyleTest`
   - `./gradlew :server:test` — full suite, per the user's feedback memory `feedback_dial_mvp_auto_full_suite.md` ("§B 'tests pass' must mean full `:server:test`, not a curated subset").
   - If the change is CLI-side, run the corresponding CLI module's tests.
   - Manual repro of the original symptom if applicable.

6. **SIMPLIFY pass** — invoke the `simplify` skill on the changed files. Apply principles §2.1 / §2.2.

7. **Commit** — only when the user explicitly says to commit. Draft message in IMPLEMENTATION.md §3.5 format if it's slice-shaped, or a plain `fix:` / `test:` / `docs:` prefix for non-slice changes. Hand the draft to the user; do not commit autonomously.

---

## Halt conditions (IMPLEMENTATION.md §4.1 — non-negotiable)

Stop and ask the user when:

- Discovered constraint contradicts the plan.
- Existing code differs materially from the design-doc anchors.
- Scope would need to grow beyond what was approved.
- Tests fail in ways the plan didn't predict.
- A cross-slice contract change is implied.
- A locked decision in `08-open-questions-and-references.md` or memory needs amending to make progress.
- Implementation would require violating a §2 principle.
- Two or more readings of the design are equally valid.

**Halt format**: (1) what was discovered, (2) why it blocks the current path, (3) two-or-three options with trade-offs, (4) your recommendation, (5) wait. Do not proceed until the user responds. Do not start parallel "just-in-case" work.

---

## Important

- The user is the only approver of the plan, the diff, and any halt-decision.
- Per §2.2: do **not** "improve" adjacent code, comments, or formatting outside the plan. Note observed issues in the report; don't silently fix them.
- Code change without doc sync is a regression in this MVP — the proposal is the contract until the big PR lands. Treat docs as part of the change, not a follow-up.
- After the change is merged (or after the user accepts a no-op verdict), stop and hand off. The next investigation begins on the user's signal in a fresh session.
