# Tasks

The implementation is already committed on `fix/mcp-execution-path-in-analytics-log` (commit `8949522c1`, merged with
`origin/development` in `34d4009fa`). This list records what was done and how each part was checked.

## 1. Key data for every MCP call

- [x] 1.1 Add `ApiKeyData.createFromContext` and let `initFromContext` start a new path, with a warning, when the caller's key has none; verify with `ApiKeyDataTest` (`createFromContextReturnsKeyDataWithExecutionPath`, `initFromContextStartsExecutionPathWhenCallerKeyHasNone`, `initFromContextAppendsDeploymentToCallerExecutionPath`)
- [x] 1.2 Call `initProxyApiKeyData()` in `McpProxyController.handleRateLimitSuccess` after `setDeployment`, and remove the unused `assignPerRequestKey()`; verify with `McpAnalyticsLogApiTest.testMcpCallWritesExecutionPathToAnalyticsLog` (4 cases: toolset and application, forwarding on and off)
- [x] 1.3 Make `McpUpstreamAuthInjector.assignPerRequestKey` reuse the context's key data and mint only when `perRequestKey` is `null`; verify with `McpUpstreamAuthInjectorTest` (reuse, mint once, create when absent, no forwarding touches nothing)
- [x] 1.4 Check `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests "*ApiKeyDataTest" --tests "*McpUpstreamAuthInjectorTest"` pass

## 2. Integration tests on the real analytics file

- [x] 2.1 Add `McpAnalyticsLogApiTest` with `gflog-mcp-analytics-test.xml` and `dial-config/mcp-analytics-log.json`; verify that all 7 tests read `execution_path` back from the gflog file
- [x] 2.2 Cover a nested call with a caller key (`execution_path: ["orchestrator", "tool-no-forward"]`, `parent_deployment: "orchestrator"`), a 307 redirect that reuses one key, and a streamed (SSE) answer; verify by the three named tests in `McpAnalyticsLogApiTest`
- [x] 2.3 Check the neighbouring MCP suites still pass: `./gradlew :server:test --tests "*ToolSetApiTest" --tests "*ToolSetMcpProxyControllerTest" --tests "*McpAnalyticsLogApiTest"` (`ToolSetApiTest` 77/77)

## 3. Docs

- [x] 3.1 Confirm no operator doc describes `execution_path` per controller: `grep -rn "execution_path" docs README.md CLAUDE.md` finds nothing, and `docs/dynamic-settings/*.md` `forwardPerRequestKey` text stays true; no doc change needed

## 4. Checks after the merge with development

- [x] 4.1 Re-run `./gradlew :server:test --tests "*McpAnalyticsLogApiTest" --tests "*ToolSetApiTest" --tests "*McpUpstreamAuthInjectorTest" --tests "*ApiKeyDataTest" --tests "*ToolSetMcpProxyControllerTest" --tests "*GfLogStoreTest"` and `checkstyleMain checkstyleTest` on the merged commit `34d4009fa`; all pass (7, 77, 15, 4, 2 and 43 tests) and checkstyle reports nothing
- [x] 4.2 Run `npx -y @fission-ai/openspec@1.14.3 validate mcp-analytics-execution-path --strict`; the change is valid

## Workflow follow-up

- Write `review.md` in this folder once the PR review is done.
- Archive with `npx -y @fission-ai/openspec@1.14.3 archive mcp-analytics-execution-path -y`, then run
  `validate --specs --strict`.
- Known failure unrelated to this change: `CustomApplicationApiTest.testApplicationListing` fails the same way on the
  base commit `1cec39093`.
