---
description: Push the current issue branch and open a pull request against development.
allowed-tools: Bash(git push:*), Bash(gh pr create:*)
---

# Open a pull request

Run on the issue branch after committing with `/finish-issue`. Derive the issue number and commit type from the branch name (e.g., `fix/issue-1234` → type `fix`, issue `1234`).

## Steps

1. **Push** the current branch:
   ```
   git push -u origin {branch}
   ```

2. **Create the PR** against `development`:
   ```
   gh pr create --base development --title "{type}: {Title from issue} #{issue_number}" --body "$(cat <<'EOF'
   {1-3 sentence summary of changes}

   ### Applicable issues

   - fixes #{issue_number}

   ### Description of changes

   {Bullet list of what changed and why}

   ### Checklist

   - [X] Title of the pull request follows [Conventional Commits specification](https://www.conventionalcommits.org/en/v1.0.0/)

   By submitting this pull request, I confirm that my contribution is made under the terms of the Apache 2.0 license.
   EOF
   )"
   ```

## Important

- Keep the PR title under 70 characters.
- Confirm the branch is up to date with the latest commits before pushing.