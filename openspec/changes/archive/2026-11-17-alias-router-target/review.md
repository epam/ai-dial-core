# Review

## Scope and findings

Two reviews by a reviewer without the session's context: one of the proposal before any code (ten findings, eight
fixed in the artifacts, one declined by the author, one nit), and one of the implementation against the spec
deltas, the design, `docs/CODE_STYLE.md` and the `deployment-aliases` and `router-deployment` baselines.

Implementation review, nine findings:

- Fixed: the lend of the router passed on to the deployment the router chose when a global interceptor runs in
  front of the router (the decided pass then has the router as `initialDeployment`). `ApiKeyData.initFromContext`
  no longer passes a lend on from a decided pass (design decision 9). Before this change no lent pass was a router
  pass, so other flows are unchanged. Test: `aliasOverRouterAfterGlobalInterceptor`.
- Fixed: `GET /v1/aliases/public/<name>` answered `status: valid` for a hand-written blob with a router target that
  calls ignore. The public rule is now one method, `StoredAliases.validatePublicFields`, used by the public `PUT`,
  the read for calls and listings and the `GET`.
- Fixed: no test covered the alias name taken behind an interceptor (path `[strong, i1]`). Same test as above.
- Fixed: the spec delta said "stored alias" where only the public bucket is meant; a platform alias joins the
  config and may target a router.
- Fixed in the docs: a stored or platform alias takes no `defaultHeaders`, so aliases that tell one router apart by
  a header are config-file aliases. Adding the field to stored aliases changes their API and is left to a follow-up.
- Fixed in the docs: `aliases.md` claimed every decided answer names the alias; embeddings and the other cases
  `routers.md` lists keep the upstream's `model`.
- Not changed: a candidate's `defaultHeaders` stay on the request when that candidate fails and the next one is
  tried. This already holds for every model and application candidate; this change extends it to aliases that
  declare `defaultHeaders`. The fix belongs in the re-entry of the candidate walk, a separate change.
- Not changed (nits): carrying the lender's name in the per-request key instead of reading it from the execution
  path (a new persisted field); renaming the persisted `ResponseMapping.router`, which now holds the answer name.

Found while implementing: `StoredAliases.validateFields` gates platform aliases as well as public ones, so the
router refusal the proposal put there would have refused router aliases managed through the admin API. Recorded as
drift in design decision 1. The baseline scenario "Not directly in the public bucket on write" still says
`PUT /v1/aliases/platform/<name>` answers `404`; platform aliases (#2258) are served and have no spec yet. That
predates this change and is left to a spec of platform aliases.

Request cost: no new Core pass, Redis or blob call, body parse or copy. The alias pass copies the alias's
`defaultHeaders` reference; a decided pass walks the incoming execution path once (a few entries) to find the
alias. Not measured.

## Verification

- `npx -y @fission-ai/openspec@1.14.3 validate alias-router-target --strict`: valid.
- After the archive, `validate --specs --strict` flagged two merged requirements over 500 characters ("Alias type",
  "Router name in the answer"). The router sentences moved, with their scenarios, into "Router target needs a type"
  and "Alias name in the answer", edited in the main specs. All specs pass except `resource-subscription`, whose
  placeholder Purpose predates this change.
- `./gradlew checkstyleMain checkstyleTest`: passed.
- Before the implementation-review fixes, `./gradlew :server:test` passed for `AliasTargetsTest` (7),
  `ConfigPostProcessorAliasTest` (13), `AliasApiTest` (25), `AliasPublicApiTest` (20), `RouterDecisionApiTest` (81),
  `RouterDecisionTest` (6), `AliasPlatformApiTest` (12), `AliasReloadApiTest` (1), `GfLogStoreTest` (53) and
  `BackgroundJobServiceTest` (24).
- After the review fixes, checkstyle passed again; the local re-run of the touched classes was stopped by the host
  for low memory. The fixes (`ApiKeyData`, `StoredAliases.parse` and its callers) and their tests
  (`aliasOverRouterAfterGlobalInterceptor`, `StoredAliasesTest.onlyPublicAliasRefusesRouterTarget`, the `GET`
  checks in `publicAliasCannotPointToRouter`) run first in CI, with the full suite, after the push.
