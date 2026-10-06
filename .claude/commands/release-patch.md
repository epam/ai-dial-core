---
description: Backport merged development PRs to a release branch and open a patch PR.
argument-hint: <release-branch> <pr-number> [<pr-number> ...]
allowed-tools: Bash(git:*), Bash(gh pr view:*), Bash(gh pr create:*), Bash(./gradlew:*)
---

# Create a release patch

Arguments: `$ARGUMENTS` — first the release branch (e.g. `release-0.9`), then one or more merged PR numbers (e.g. `2060 2067`).

`development` uses squash merges (`<title> (#N)`), so one PR is exactly one commit.

## Preconditions

- Abort if fewer than 2 arguments are given.
- Abort if the working tree is dirty (`git status --porcelain --untracked-files=no` is not empty); untracked files are ignored.
- `git fetch origin --tags`, then verify `origin/{release}` exists. If not, list available `origin/release-*` branches and stop.

## Steps

1. **Check out and update the release branch**:
   ```
   git checkout {release}
   git pull origin {release}
   ```

2. **Resolve commits for all PRs up front**. For each PR number `{n}`:
   - `gh pr view {n} --json state,mergeCommit` — use `mergeCommit.oid` when `state` is `MERGED`.
   - If that fails, fall back to `git log origin/development --format=%H --grep='(#{n})'`.
   - If the PR is not merged or no commit is found, stop and report.

   Sort the commits by merge order on `origin/development` (oldest first, e.g. `git rev-list --reverse origin/development` filtered to the resolved SHAs), regardless of argument order. Print the final PR → SHA order before continuing.

3. **Create the patch branch**. Compute `{next_release_version}`: take `git describe --tags --abbrev=0 origin/{release}` (e.g. `0.9.2`) and increment the patch number (`0.9.3`). If no tag is found, ask the human. A previous aborted run or an open patch PR leaves the tag unchanged, so first check that the name is free locally (`git rev-parse --verify --quiet patch-{next_release_version}`) and on origin (`git ls-remote --exit-code --heads origin patch-{next_release_version}`). If either exists, ask the human whether to delete it or use a suffix (`patch-{next_release_version}-2`); never overwrite it. Then:
   ```
   git checkout -b patch-{next_release_version}
   ```
   Never push to the release branch directly.

4. **Cherry-pick each commit in order**:
   ```
   git cherry-pick -x {sha}
   ```
   `-x` appends a `(cherry picked from commit ...)` trailer; keep the original message.

5. **On conflicts**, inspect `git status` and `git diff`:
   - Resolve yourself only when the intent is unambiguous: import or whitespace differences, adjacent non-overlapping hunks, or files that do not exist on the release because the feature was not backported.
   - **Escalate to the human** (stop, show the conflicting files and hunks, ask how to proceed) when both sides changed the same logic, the PR depends on code missing from the release branch, or any resolution would change behaviour.
   - After resolving, show the resolution diff, then `git add` the files and `git cherry-pick --continue`.
   - Remember each conflict and how it was resolved for the PR body.

6. Go to the next commit (step 4) until all are applied.

7. **Verify** once after the last pick:
   ```
   ./gradlew checkstyleMain checkstyleTest compileJava compileTestJava
   ```
   Fix trivial failures caused by conflict resolution; escalate anything else. Full tests are left to CI.

8. **Push and open the PR** against the release branch:
   ```
   git push -u origin patch-{next_release_version}
   gh pr create --base {release} --title "chore: backport #{n1}, #{n2} to {release}" --body "$(cat <<'EOF'
   Backport of merged PRs to `{release}`.

   ### Backported PRs

   - #{n1}
   - #{n2}

   ### Conflicts

   {None, or each conflict and how it was resolved}

   By submitting this pull request, I confirm that my contribution is made under the terms of the Apache 2.0 license.

   🤖 Generated with [Claude Code](https://claude.com/claude-code)
   EOF
   )"
   ```

## Important

- Keep the PR title under 70 characters.
- On any abort mid-run, run `git cherry-pick --abort` if a pick is in progress, leave the patch branch in place for inspection, and report the current state.
- Return the PR URL when done.
