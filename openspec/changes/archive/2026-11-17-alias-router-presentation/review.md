# Review

## Scope and findings

Two reviews by a reviewer without the session's context: one of the proposal before any code, and one of the
implementation against the spec deltas, the design, `docs/CODE_STYLE.md` and the `deployment-aliases` and
`router-deployment` specs.

Proposal review, eleven findings, all taken into the artifacts before the code: the requirements on endpoints,
features and default headers now name the type `router` (the code already served it through the same alias pass);
the router-type check runs on every path, resource paths included, so a router listing never reads storage on the
event loop; the `/v1/routers/{id}` fallback looks up config aliases only, never a stored url; `/v1/deployments`
applies its interface filters to router-type aliases; tests for a missing router target, a model-type alias over a
router, a resource target and a platform alias; the `tier` alias in the test config; the rollback note; an
observable `THEN` for "Router type by default"; `routers.md` and the OpenAPI parameter description.

Implementation review, ten findings, none blocking. The reviewer confirmed that the router listings read no
storage, that the `(Router)` casts cannot fail, that the access checks of the single-item and list paths decide on
the same roles, and that every use of the alias type handles `ROUTER`.

- Fixed: `aliases.md` said every alias listing reads the stored aliases; the router listings do not.
- Fixed: `routers.md` named only an alias without `presentAs` as a router-type alias, not `presentAs: router`.
- Fixed: no test showed a router-type alias with a missing router left out of `/v1/routers`, `/v1/deployments` and
  `/v1/deployment-names`. Added to `routerTypeAliasListedAsRouter`.
- Fixed: no test of a platform alias of type `router`. `platformAliasOfTypeRouter` writes one through
  `PUT /v1/aliases/platform/...` and finds it in `/v1/routers`.
- Fixed: no test of a router-type alias on Messages. `messagesThroughRouterTypeAlias`. The feature endpoints run
  through the same alias pass as for type `model`, which `AliasApiTest` covers.
- Fixed: the migration note now covers platform aliases written during a rolling upgrade.
- Not changed: a test that tells "storage is not read" apart from "read, then filtered" for
  `/v1/routers/aliases%2F...`. A public alias can never have the type `router`, so no answer differs; the rule is
  in the code and the design.
- Not changed (nits): reusing `DeploymentService.findAliasView` in `RouterController.getRouterAlias` (it would read
  storage for an `aliases/...` id), one helper for the router entries of `/v1/routers` and `/v1/deployments`, and
  one config-only listing method in place of `List::of` at three call sites.

Request cost: none per call. A router listing walks the config aliases once more, in memory. Not measured.

## Verification

- `npx -y @fission-ai/openspec@1.14.3 validate alias-router-presentation --strict`: valid.
- `./gradlew checkstyleMain checkstyleTest`: passed.
- `./gradlew :server:test`: `AliasTargetsTest` (8), `StoredAliasesTest` (8), `ConfigPostProcessorAliasTest` (14),
  `AliasPassesTest` (11), `AliasPublicApiTest` (20), `RouterApiTest` (23) and `AliasApiTest` (25) passed; after the
  review fixes, `RouterDecisionApiTest` (91) passed. A first integration run failed to start embedded Redis: a Redis
  left by an earlier, dead test JVM held port 16370. It was stopped and the run repeated.
- The full suite runs in CI after the push.
