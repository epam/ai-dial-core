## What shipped

`POST /v1/ops/dependency/list` (`x-preview`) lists, for each Application named in `roots`, the flat list of the resources it reaches, for an
explicit admin (`public/`, `platform/` and the file configuration, no role check) and for any other caller (own bucket, shared buckets and `public/`
by the right to read, configuration Models and Applications by `user_roles`, Interceptors and schemas listed). The graph is explored once for all roots, each
resource is read once and only its references are kept, the walk takes no bucket lock, and an element the caller may not see is left out without a
trace. A per-request key is `403`; a root is authorized before it is looked up. Docs: `docs/dependency-list.md` and `docs/error_codes.md`. `docs/open_api_core.yaml` is generated and committed by CI, so this branch does not change it.

## Verification

- `./gradlew checkstyleMain checkstyleTest` passes.
- `./gradlew :server:test` over the classes of groups 1 to 4 passes: `MarkerRecorderTest`, `RequestTimeCollectionCompatibilityTest`,
  `ApplicationSchemaServiceTest`, `CatalogSchemaServiceTest`, `ShortNameResolver*`, `SchemaReferenceResolverTest`, `DeploymentEdgeDiscoveryTest`,
  `DependencyTypePolicyTest`, `DependencyNamesTest`, `DependencyAccessPolicyTest`,
  `DependencyWalkerTest`, `DependencyRequestParserTest`, `DependencyListControllerSelectionTest`, `DependencyListApiTest`,
  `DependencyListAccessApiTest`.
- `./gradlew mergeOpenApiSpec lintMergedOpenApi`: no new lint warning (the generated `docs/open_api_core.yaml` is left to CI).
- `npx -y @fission-ai/openspec@1.14.3 validate --all --strict`: all items pass.
- Measured in `DependencyWalkerTest` (two roots reaching 50 resources through a diamond and a cycle): 34 reads (one per Application), 16 existence checks
  (one per File), 3 policy calls (one per level), and no body kept after its references are found.
- `DependencyListApiTest.testWalkDoesNotWaitForAnAdminWriteThatHoldsTheBucketLocks` runs a walk while the test holds the bucket locks of every admin write;
  it answers `200` without a lock wait.
- `DependencyWalkerTest.testWalkWaitsForConfigurationPublicationThatHoldsTheLock` and `testPublicationLockIsHeldForTheTwoConfigurationReadsOnly`: the
  walk queues on the publication lock while another thread holds it and answers after the release, and it holds the lock for the two configuration
  reads only.
- Task 4.1: an Application in a user bucket is read by URL through `ApplicationService.getApplication` (`ResourceService.getResourceWithMetadata`), the
  same stored body that the walker reads with `ResourceService.getResource`; recorded in the design Code map.

## Drift from design.md

- `DependencyAccessPolicy` has a third method, `isSupported`: reference discovery asks it which locations and types are candidates, because the check was
  fixed to `public/` and `platform/` and a user-bucket URL could never become a candidate. D3 now says so.
- The spec says `url` is null for "a deployment of the configuration" listed for a caller who is not an admin. It is read as a Model or an Application, so
  an Interceptor and a schema of `platform/` keep their URL. Recorded in the Code map.
- A user bucket is followed for an Application, a File, a Prompt and a Skill; the other types are configuration entities.

## Not done

- A run on a real cluster (needs one).
- The full suite runs in CI after the push.
- No test starts an admin write while a walk is in progress; the walk holding no lock is tested, and a write cannot wait for a lock that is never taken.
- The draft PR with `proposal.md` and the spec delta that `CLAUDE.md` asks for before the code is not opened; nothing is committed.
