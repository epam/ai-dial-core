# Review

Record of the reviews done before archiving. No human pull-request review had happened when this was written.

## What was reviewed

Commit `8949522c1` on `fix/mcp-execution-path-in-analytics-log`, merged with `origin/development` in `34d4009fa`.

## Reviews done

1. **Test run that found a regression.** The first version called `ApiKeyData.initFromContext` for every MCP call. 7
   tests in `ToolSetApiTest` then failed with a 500: a caller's per-request key with no execution path made
   `new ArrayList<>(null)` throw. Fixed by starting a new path and logging a warning (design decision 4);
   `ToolSetApiTest` passes 77/77 after the fix.
2. **Self-review of the diff** after the fix. Checked: nothing else reads the key data between its creation and use; the
   key is minted once across redirects and retries; `ApiKeyStore.invalidatePerRequestApiKey` returns early on a `null`
   key; `McpResourceController` and `ToolSetToolsController` are unchanged because they write no analytics record. No
   blocking problem found.
3. **`/code-review` run.** The first attempt died on a connection error. The second returned six findings. All were
   triaged against the code:
   - Not real: the other MCP controllers lack the path (they write no analytics record); a key could be overwritten by
     `initProxyApiKeyData()` (it runs once per request, before anything else sets the key data).
   - Fixed in the commit: the duplicated "create and fill key data" steps (now `ApiKeyData.createFromContext`) and the
     silent null-path tolerance (now a warning log).
   - Left as is, with reasons in design.md: fixed port 9876 and a shared log file in the test (the same port is used by
     every MCP test, and Gradle runs test classes one after another in one JVM); the file appender in `gflog-test.xml`
     is redundant and was kept on purpose.

## Checks

- On the merged commit `34d4009fa`: `McpAnalyticsLogApiTest` 7/7, `ToolSetApiTest` 77/77, `McpUpstreamAuthInjectorTest`
  15/15, `ApiKeyDataTest` 4/4, `ToolSetMcpProxyControllerTest` 2/2, `GfLogStoreTest` 43/43. `checkstyleMain` and
  `checkstyleTest` report nothing.
- `validate mcp-analytics-execution-path --strict` passes.
- `CustomApplicationApiTest.testApplicationListing` fails the same way on the base commit `1cec39093`; it is unrelated.

## Not checked

- The full test suite; it runs in CI after the push.
- The per-request cost in design.md follows from reading the code and was not measured.
- `CustomApplicationApiTest` was not re-run on the merged commit.
