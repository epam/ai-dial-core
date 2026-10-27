# Design

## Context

See proposal.md for the problem. How `execution_path` gets into the analytics record today:

1. `ApiKeyData.initFromContext` builds the path: the caller's path (empty for a project key) plus the called deployment
   or route, and sets `sourceDeployment`.
2. A controller stores that key data with `ProxyContext.setProxyApiKeyData`. The chat, embeddings, Responses, Anthropic,
   interceptor, feature and route controllers do it for every request
   (`ChatCompletionsController`, `DeploymentPostController`, `ResponsesController`, `BaseRouteController`, ...).
3. `ProxyContext.getExecutionPath` returns the path of that key data, or `null` when it was never set.
4. `AnalyticsLogContext.from(ProxyContext, ...)` copies it and derives `parentDeployment` with
   `AnalyticsLogContext.getParentDeployment`.
5. `GfLogStore.append` writes `execution_path` only when it is not `null`. `GfLogStore.save` writes POST requests only,
   so a `GET` or `DELETE` MCP call never had a record.

`McpProxyController` never did step 2. Only `McpUpstreamAuthInjector.assignPerRequestKey` did, and it ran from
`injectProxyRequestHeaders` only when `forwardPerRequestKey` was true, creating the key data and the Redis key together.
`McpResourceController` and `ToolSetToolsController` call the same injector but write no analytics record.

## Goals / Non-Goals

**Goals:**
- `execution_path` and `parent_deployment` on MCP proxy rows no matter what `forwardPerRequestKey` says.
- No new per-request key, Redis call, body copy or body parse for a call that forwards no key.

**Non-Goals:**
- Logging MCP error responses, or the two MCP controllers that write no record.
- Moving the other controllers to the new helper, or changing how the per-request key's Redis entry is written or
  expires.

## Decisions

**1. Build the key data in `McpProxyController.handleRateLimitSuccess`, after `setDeployment`.**
`initProxyApiKeyData()` runs after the rate-limit check (`CODE_STYLE.md`: cheap guards before expensive work) and
before the body is read, so every later path (JSON answer, streamed answer, redirect, retry) sees it.
- Rejected: set it in `McpUpstreamAuthInjector` whatever the flag says. The injector is shared with
  `McpResourceController` and `ToolSetToolsController`, which would allocate key data for nothing.
- Rejected: set it in `handle()` before the rate limit. A call that is refused would build it for no reason.

**2. The injector reuses the key data and mints at most once.**
`McpUpstreamAuthInjector.assignPerRequestKey` takes `context.getProxyApiKeyData()` when present, creates it otherwise
(the two callers that do not go through `McpProxyController`), and calls `ApiKeyStore.assignPerRequestApiKey` only when
`perRequestKey` is `null`.
- Today's behaviour differs: `handleProxyRequest` runs again for a 307/308 redirect (`tryFollowRedirect`) and for a
  429/502 retry (`canRetry`), and each run minted a new key. Only the last was invalidated in
  `McpProxyController.finalizeRequest`; the earlier ones lived until their TTL. Reusing one key removes that.
- Rejected: always mint, key data or not. That adds a Redis write for every call that forwards no key.

**3. `ApiKeyData.createFromContext(ProxyContext)`.**
Two places (the controller and the injector's fallback) create key data and fill it from the context, so the three steps
live in one factory. Rejected: a method on `ProxyContext`; tests mock `ProxyContext`, so it would return `null` there.
Rejected: moving the other nine call sites of `initFromContext` to the factory (scope; `CODE_STYLE.md`).

**4. A caller key without an execution path starts a new path and logs a warning.**
`initFromContext` used `new ArrayList<>(caller.getExecutionPath())`, which throws on `null`. Before this change it ran
for a forwarding MCP call only; now it runs for every MCP call, so existing keys created without a path (tests do it)
would turn calls into 500s. `ConsentService.verifyUserConsent` already treats a `null` path as possible.
- Deliberate difference: this also applies to every other controller that calls `initFromContext`. They get a warning
  and a fresh path where they used to fail with an NPE. The callers of `ApiKeyStore.assignPerRequestApiKey` in `server`
  build their key data with `initFromContext` first, so a real per-request key has a path as far as the code shows, and
  the warning marks a key written some other way.
- Rejected: leave the NPE and fix only the test keys. It hides a real hazard behind fixtures.

**5. The integration test reads the real gflog file.**
`McpAnalyticsLogApiTest` points the `aidial.log` logger at a temp file with `LogConfigurator.configure(..., properties)`
and the config `gflog-mcp-analytics-test.xml`, then restores `gflog-test.xml`. The record the test parses is the line
`GfLogStore` wrote.
- Rejected: swap in a capturing `LogStore`. `AiDial.start` builds `GfLogStore` as a local variable, so it would need a
  new production seam, and the test would no longer cover `GfLogStore.append`.
- Known leftover: `gflog-test.xml` also carries a file appender that routes `aidial.log` to
  `build/test-analytics/aidial.log`. The test no longer needs it. It was kept on purpose and writes every test's
  analytics lines to a file under `build/`, which git ignores.

**Per-request cost.** For an MCP call, Core allocates one `ApiKeyData`, one list for the path and copies at most two
request headers (`collectHttpHeaders`: `X-CONVERSATION-ID`, `X-JOB-TITLE`). This is on the event loop and does no
I/O. Body copies and parses: none added. Redis round trips: none added; one fewer per redirect or retry of a forwarding
call. Not benchmarked; the numbers follow from reading the code.

## Risks / Trade-offs

- Analytics consumers that parse MCP rows now see `execution_path` (and `parent_deployment`). → They already receive both
  on the other deployment types; the change note in the proposal's Impact says so.
- The warning in decision 4 could log on every call if some component writes keys without a path. → It names no key and
  no body, and the next step would be to fix that writer; today none is known.
- `gflog-test.xml` and `gflog-mcp-analytics-test.xml` overlap. → Either file alone works; remove the shared appender
  later if nothing else reads it.

## Migration Plan

None. No setting, data format or endpoint changes. Rollback is a revert of the commit.
