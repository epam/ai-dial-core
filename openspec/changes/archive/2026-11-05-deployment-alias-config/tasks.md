## 1. Config model and validation

- [x] 1.1 `ResourceTypes.ALIAS`; `Alias` (with `getEffectivePresentation`), `AliasPresentation`, `AliasTarget` in `config`; `Config.aliases` and `selectDeployment`
- [x] 1.2 `ConfigPostProcessor.processAliases` (namespace, target shape, reserved fields, chaining, type pair, missing-target warning, limit-under-alias warning); `isDeploymentIdTakenByAnotherDeploymentType`
- [x] 1.3 `MergedConfigStore.shallowClone` and `rebuildMergedConfig` carry `aliases`
- [x] 1.4 Tests: `ConfigPostProcessorAliasTest` (scenarios "Alias loaded", "Bad target refused", "Reserved target fields refused", "Name clash", "Alias to an alias", "Toolset shown as a model", "Target removed" load part; effective type and resource target at load)
- [x] 1.5 `./gradlew checkstyleMain checkstyleTest` and the touched test classes with `--tests`

## 2. Calling an alias

- [x] 2.1 `AliasPass`, `AliasApplicationPass`, `AliasToolSetPass`, `AliasPasses.build`; `AliasService` (config and resource targets); `DeploymentService.findCallableDeployment`, `isLent`, local base url, no interceptors for a pass
- [x] 2.2 `ApiKeyData.lentDeployment` and the attached resource target set in `initFromContext`; `findDeployment` grant; the four `accessibleByPerRequestKey` checks
- [x] 2.3 Call sites: `DeploymentPostController`, `ChatCompletionsController`, `ResponsesController`, `MessagesBaseController`, `McpProxyController`
- [x] 2.4 Tests: `AliasApiTest` ("Caller without rights on the target", "Resource target", "Responses and Messages", "Toolset alias over an MCP application", "No limit under the alias's name", "Target removed" call part, "Execution path", "Interceptor in front of the target", "Limit under the target's name", "Error of one caller", "Key handed on by the target", "Alias call accepted"); `AliasReloadApiTest` ("Target changed"); `AliasServiceTest` (incl. a schema-based target), `AliasPassesTest`, `AliasTargetsTest` (incl. incompatible targets) and `DeploymentServiceLentTest` for target resolution, the builders, target parsing and the grant
- [x] 2.5 `./gradlew checkstyleMain checkstyleTest` and the touched test classes with `--tests`

## 3. Listing and operations

- [x] 3.1 `AliasPasses.view` with the display fallbacks; aliases in `ModelController`, `ApplicationController`, `ToolSetController`, `DeploymentController`, `AnthropicModelController`
- [x] 3.2 `AdminHealthConfigController` reports aliases with a missing config target; `aliases` on `/v1/admin/config/file/...`
- [x] 3.3 Tests: `AliasApiTest` ("Model entry", "Display fields of the target", "Type of the target by default", "Application shown as a model", "Toolset entry", "Alias to a resource application", "Broken alias not named", "Broken alias in the health report", "File aliases listed")
- [x] 3.4 `./gradlew checkstyleMain checkstyleTest` and the touched test classes with `--tests`

## 4. Docs and checks

- [x] 4.1 `docs/dynamic-settings/aliases.md` (config section, calling, listing, limits, health, `backgroundJob.localBaseUrl`), README links, sample config
- [x] 4.2 `npx -y @fission-ai/openspec@1.14.3 validate deployment-alias-config --strict`
