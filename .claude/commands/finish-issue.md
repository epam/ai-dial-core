---
description: Run pre-commit checks and commit the issue work on the current branch.
allowed-tools: Bash(./gradlew:*), Bash(git add:*), Bash(git status:*), Bash(git commit:*)
---

# Finish a GitHub issue (checks + commit)

Run on the issue branch created by `/start-issue`. Derive the issue number and commit type from the branch name (e.g., `fix/issue-1234` → type `fix`, issue `1234`).

## Steps

1. **Run checkstyle and the relevant tests**:
   ```
   ./gradlew checkstyleMain checkstyleTest
   ./gradlew :MODULE:test --tests "TestClass"
   ```
   Pick the module and test class(es) actually touched by this change.

2. **Stage only the files related to this issue.** Do not commit unrelated files (local configs, IDE files, etc.). Inspect `git status` before staging.

3. **Commit** using conventional commit format:
   ```
   {type}: {Short description} #{issue_number}
   ```
   Where `{type}` matches the branch prefix (`fix` / `feat` / `chore`).

After the commit, stop and wait for the user. Use `/open-pr` to push and open a PR when ready.