# Work on a GitHub issue

You are starting work on a GitHub issue. The issue number is: $ARGUMENTS

## Prerequisites

- [GitHub CLI (`gh`)](https://cli.github.com/) must be installed and authenticated (`gh auth login`).

## Steps

1. **Fetch the issue** from GitHub:
   ```
   gh issue view $ARGUMENTS --repo epam/ai-dial-core --json title,body,labels,assignees
   ```
   Read and understand the issue. Summarize it to the user in 2-3 sentences.

2. **Determine branch prefix** from the issue title/labels:
   - `fix/` — for bugs, errors, incorrect behavior
   - `feat/` — for new features, enhancements
   - `chore/` — for maintenance, CI, deps, docs

3. **Create a branch** from latest development:
   ```
   git stash (if needed)
   git checkout development
   git pull origin development
   git checkout -b {prefix}/issue-$ARGUMENTS
   ```
   If the branch already exists, ask the user whether to reuse or recreate it.

4. **Work with the user** on the fix/feature. Follow CLAUDE.md guidelines for build, test, and code style.

5. **Before committing**, run checkstyle and relevant tests:
   ```
   ./gradlew checkstyleMain checkstyleTest
   ./gradlew :MODULE:test --tests "TestClass"
   ```

6. **When the user asks to commit**, stage only the files related to this issue. Use conventional commit format:
   ```
   {type}: {Short description} #{issue_number}
   ```
   Where `{type}` matches the branch prefix (fix/feat/chore).

7. **When the user asks to create a PR**, push the branch and create a PR:
   ```
   git push -u origin {branch}
   gh pr create --title "{type}: {Title from issue} #{issue_number}" --body "$(cat <<'EOF'
   {1-3 sentence summary of changes}

   ### Applicable issues

   - fixes #$ARGUMENTS

   ### Description of changes

   {Bullet list of what changed and why}

   ### Checklist

   - [X] Title of the pull request follows [Conventional Commits specification](https://www.conventionalcommits.org/en/v1.0.0/)

   By submitting this pull request, I confirm that my contribution is made under the terms of the Apache 2.0 license.
   EOF
   )"
   ```

## Important

- Keep the PR title under 70 characters
- Always run checkstyle before committing
- Do not commit unrelated files (local configs, IDE files, etc.)
- Ask the user before any destructive git operations