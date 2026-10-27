# analytics-log Specification

## Purpose
The analytics log record Core writes for a request: which deployment served it, through which chain of callers, and what
it used. This delta covers the execution path of MCP proxy calls.

## Requirements

### Requirement: MCP calls record their execution path
Core SHALL write `deployment` and `execution_path` in the analytics record of every MCP proxy call that ends with an
origin response, whether or not the deployment forwards a per-request key to the MCP server. For a call that no other
deployment made, `execution_path` is the called deployment alone and `parent_deployment` is absent.

#### Scenario: Toolset that does not forward a per-request key
- **WHEN** a client with a project key sends `POST /v1/toolset/tool-no-forward/mcp` with a `tools/call` body and the
  toolset has `forwardPerRequestKey: false`
- **THEN** the MCP server receives no `API-KEY` header, and the analytics record has `deployment: "tool-no-forward"`,
  `execution_path: ["tool-no-forward"]` and no `parent_deployment`
- Test: `McpAnalyticsLogApiTest.testMcpCallWritesExecutionPathToAnalyticsLog` (case 1)

#### Scenario: Toolset that forwards a per-request key
- **WHEN** the same call goes to `tool-forward`, which has `forwardPerRequestKey: true`
- **THEN** the MCP server receives an `API-KEY` header, and the record has `execution_path: ["tool-forward"]`
- Test: `McpAnalyticsLogApiTest.testMcpCallWritesExecutionPathToAnalyticsLog` (case 2)

#### Scenario: Application, with and without forwarding
- **WHEN** a client sends `POST /v1/deployments/app-no-forward/mcp` (`mcp.forwardPerRequestKey: false`) and
  `POST /v1/deployments/app-forward/mcp` (`mcp.forwardPerRequestKey: true`)
- **THEN** each record has `execution_path` equal to the application's own name, and only the second call carries an
  `API-KEY` header to the MCP server
- Test: `McpAnalyticsLogApiTest.testMcpCallWritesExecutionPathToAnalyticsLog` (cases 3 and 4)

#### Scenario: Streaming answer
- **WHEN** the MCP server answers with `Content-Type: text/event-stream`
- **THEN** the record has the same `deployment` and `execution_path` as for a JSON answer, and `response.status` 200
- Test: `McpAnalyticsLogApiTest.testMcpCallWithStreamingResponseWritesExecutionPathToAnalyticsLog`

### Requirement: Nested MCP calls extend the caller's path
When the MCP call is made with a per-request key, Core SHALL set `execution_path` to the caller's execution path
followed by the called deployment, and `parent_deployment` to the caller's source deployment. If that key carries no
execution path, Core SHALL still serve the call, start the path at the called deployment, and log a warning.

#### Scenario: Called by an application
- **WHEN** a per-request key with execution path `["orchestrator"]` and source deployment `orchestrator` calls
  `POST /v1/toolset/tool-no-forward/mcp`
- **THEN** the record has `execution_path: ["orchestrator", "tool-no-forward"]` and `parent_deployment: "orchestrator"`
- Test: `McpAnalyticsLogApiTest.testMcpCallWithPerRequestKeyAppendsToCallerExecutionPath`

#### Scenario: Caller key without an execution path
- **WHEN** a per-request key with no execution path calls an MCP deployment `tool`
- **THEN** Core does not answer 500, and the execution path of the call is `["tool"]`
- Test: `ApiKeyDataTest.initFromContextStartsExecutionPathWhenCallerKeyHasNone`

### Requirement: One per-request key per forwarding MCP call
Core SHALL mint a per-request key for an MCP call only when the deployment forwards one, and at most one key per client
call. A same-origin redirect or a retry over another upstream SHALL send the key already minted.

#### Scenario: Same-origin redirect
- **WHEN** a call to `tool-forward` gets a `307` from the MCP server and Core follows it
- **THEN** both requests to the MCP server carry the same `API-KEY` value, and that key is no longer valid once the call
  is answered
- Test: `McpAnalyticsLogApiTest.testMcpCallFollowingRedirectReusesSinglePerRequestKey`

#### Scenario: No forwarding mints no key
- **WHEN** a call goes to a deployment with forwarding off
- **THEN** no per-request key is minted and no `API-KEY` header reaches the MCP server
- Test: `McpUpstreamAuthInjectorTest.application_noForwardPerRequestKey_leavesProxyApiKeyDataUntouched`
