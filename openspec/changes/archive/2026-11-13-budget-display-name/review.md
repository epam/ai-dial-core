# Review

## Scope and findings

Reviewed the implementation against the config-store and user-limits deltas and the development baseline.
No remaining code findings from this review. The touched server test classes pass as detailed below; the full
latest CI suite is still running and must be green before merge.

- Correctness: `Policy` retains the configured string through the existing JSON mapping. Both stats endpoints
  use `BudgetEnforcer.collectStats`, which applies the same full-path fallback after selecting matching budgets.
  Neither policy paths nor `BudgetCounterStore.describe` changed. The rename test seeds real spend and compares
  the complete response entry after changing only the label.
- Compatibility: existing unnamed policies remain valid. A non-blank name is returned unchanged; absent, null,
  empty and whitespace-only names use the full policy path. Policy-only assignment validation names the field.
  Older strict parsers require the documented rollout and rollback order.
- Authorization: the change adds no route or lookup and keeps the existing subject, expiration and API-key
  filtering. It exposes the label only for an already-visible budget. It does not grant policy-edit permission.
- Request cost: inspection of the projection and its call sites confirms only a field read and blank check per
  returned budget. There is no added Redis/blob call or parse/copy of a forwarded request body. No performance
  improvement is claimed.
- Documentation: configuration and response examples match the implemented fields and test setup. Generated
  OpenAPI remains owned by the existing CI `replaceSpec` job.

Review and CI corrected two test assumptions: policies are API-managed and are not loaded from dynamic
configuration files (`Config` ignores these maps), so the self-applying policy test now uses PUT and GET of the
policy around a reload. Specs and operator docs were corrected to match this existing API surface. The full-path
fallback test uses `projA/engineering` because project policies can only be assigned within their own cost object.

## Verification

- `openspec validate budget-display-name --strict`: passed.
- Post-archive main-spec validation: the touched `config-store` and `user-limits` specs pass. The pre-existing
  placeholder Purpose in the touched user-limits spec was replaced with its actual scope. Repository-wide strict
  validation still reports the unchanged placeholder Purpose in `resource-subscription`, also present at the
  baseline commit; that unrelated spec is outside this change.
- `git diff --check`: passed.
- `:config:test --tests '*PolicyAssignmentValidatorTest'`: passed, 13 tests, including the added display-name
  assignment rejection assertion. The config module and its tests compiled on Java 21.
- All modules' `checkstyleMain` and `checkstyleTest`: passed with a temporary, untracked Gradle init script that
  sets each Checkstyle task's `classpath` to `files()`. This runs the repository's source rules without compiling
  private storage dependencies; it is not a successful standard build.
- Standard Checkstyle plus the four targeted server test classes: blocked at `:storage:compileJava` by HTTP 401
  for `org.apache.jclouds.api:filesystem:2.7.3` and `org.apache.jclouds:jclouds-allblobstore:2.7.3` from GitHub Packages.
  Server compilation and tests cannot be verified locally with the available credentials.

### CI evidence

- [First workflow](https://github.com/epam/ai-dial-core/actions/runs/43403567200), source commit `872b66cb`:
  server/test compilation, standard Checkstyle, Docker build and OpenAPI generation passed. The server suite
  reported 3843 tests, one failure and two skipped. The sole failure was the incorrect file-policy test described
  above. All `UserBudgetsApiTest` (6), `PolicyApiTest` (38) and `PolicyRecordCodecTest` (12) cases passed.
- [Corrected API test run](https://github.com/epam/ai-dial-core/actions/runs/43404911091/job/131816784526),
  source commit `f5a773a0`: all nine `BudgetDisplayNameApiTest` cases passed, including the self-applying policy,
  rename-after-charge and missing/null/blank fallback cases. Standard Checkstyle and OpenAPI generation passed
  again. Production code and the three regression/codec classes are unchanged from the first run.

This verifies the touched classes across those two runs. The latest full suite was still running when this
record was written; no successful full-suite result is claimed. The final follow-up changes only verification
notes and task status.
