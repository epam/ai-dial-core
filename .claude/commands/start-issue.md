---
description: Fetch a GitHub issue and create a branch from latest development.
argument-hint: <issue-number>
allowed-tools: Bash(gh issue view:*), Bash(git stash:*), Bash(git checkout:*), Bash(git pull:*)
---

# Start a GitHub issue

Issue number: $ARGUMENTS

## Prerequisites

- [GitHub CLI (`gh`)](https://cli.github.com/) must be installed and authenticated (`gh auth login`).

## Steps

1. **Fetch the issue** from GitHub:
   ```
   gh issue view $ARGUMENTS --repo epam/ai-dial-core --json title,body,labels,assignees,comments
   ```
   Read it. Summarize to the user in 2-3 sentences.

2. **Determine the branch prefix** from the issue title/labels:
   - `fix/` — bugs, errors, incorrect behavior
   - `feat/` — new features, enhancements
   - `chore/` — maintenance, CI, deps, docs

3. **Create the branch** from latest development:
   ```
   git stash  # if there are uncommitted changes
   git checkout development
   git pull origin development
   git checkout -b {prefix}/issue-$ARGUMENTS
   ```
   If the branch already exists, ask the user whether to reuse or recreate it.

After the branch is ready, hand off to the user for implementation. Use `/finish-issue` when ready to commit and `/open-pr` when ready to push and open a PR.