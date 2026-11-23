# Tasks

## 1. No limit check and relayed headers on an alias pass

- [x] 1.1 Compare `design.md` with the code on `development`; correct a decision or a Code map entry in place on a
      drift
- [x] 1.2 Add to `dial-config/budget-spend-caps.json` (or a copy of it) a model `m1` that serves Messages and
      Responses, an alias `alias-m1` of it, a router `r1` in decision mode that serves both, and an alias `strong` of
      `r1`; the router's decide endpoint and the upstream of `m1` on the test server
- [x] 1.3 Tests in `BudgetSpendCapsApiTest`, written first and failing: "Messages through an alias"
      (`testMessagesThroughAnAlias`), "Responses through an alias and a router" (`testResponsesThroughAliasAndRouter`),
      "Upstream headers removed behind the alias" (`testUpstreamHeadersRemovedBehindTheAlias`) (requirement "Spend
      headers through an alias"); "Budget used up, through an alias and a router"
      (`testBudgetUsedUpThroughAliasAndRouter`), "Role cost limit reached, budget not"
      (`testRoleCostLimitReachedBudgetNotThroughAnAlias`), "Request limit under the alias's name"
      (`testAliasRequestLimitDoesNotApply`) (requirement "No limit check on an alias pass")
- [x] 1.4 `SpendCapCheck.verify` returns no caps and checks nothing when the pass's deployment is an `AliasPass`;
      `MessagesController.putLimitHeaders` and `ResponsesController.putLimitHeaders` leave the headers as they are for
      an `AliasPass`
- [x] 1.5 Existing scenarios still pass: "Upstream sends its own limits", "Upstream headers replaced", the
      translated-deployment scenarios, and the router decision tests with an alias candidate
- [x] 1.6 `./gradlew checkstyleMain checkstyleTest` and
      `./gradlew :server:test --tests "*BudgetSpendCapsApiTest" --tests "*MessagesSpendLimitApiTest" --tests "*ResponsesSpendLimitApiTest" --tests "*RouterDecisionApiTest" --tests "*AliasInProcessApiTest"`

## 2. Docs

- [x] 2.1 `docs/anthropic-spend-limits.md` and `docs/codex-spend-limits.md`: a call through an alias, also an alias
      a decision-mode router chooses, reports the caps of the deployment that serves it, and the alias makes no check
      of its own
- [x] 2.2 `docs/dynamic-settings/aliases.md`: on Messages and Responses the alias pass makes no limit check and relays
      the target's spend headers
- [ ] 2.3 Claude Code and Codex CLI calling an alias over a decision-mode router that chooses an alias of a model show
      the budget's usage (deferred: needs a dev environment with this build and a budget in enforce mode)
- [x] 2.4 `./gradlew checkstyleMain checkstyleTest`, the test classes of 1.6, and
      `npx -y @fission-ai/openspec@1.14.3 validate relay-spend-headers --strict`
