# Proposal

## Why

The analytics record of an MCP call (`POST /v1/toolset/{name}/mcp`, `POST /v1/deployments/{name}/mcp`) was missing
`execution_path`, and `parent_deployment` too when the caller's key carried interceptors, unless the toolset or
application forwarded a per-request key to its MCP server (`forwardPerRequestKey: true`). The path was only set as a side
effect of minting that key. So an operator could not tell from the analytics log which application called an MCP tool,
for the common case where no key is forwarded.

## What Changes

- Core now builds the request's key data (the source of `execution_path`) for every MCP proxy call, once the deployment
  is resolved and the limits have passed. It no longer depends on a per-request key being minted.
- A per-request key is still minted only when `forwardPerRequestKey` is `true`. A same-origin 307/308 redirect or a
  429/502 retry now reuses the key minted for the call instead of minting one per attempt.
- A per-request key presented by the caller that has no execution path no longer fails the MCP call with a 500. The
  path restarts at the called deployment and Core logs a warning.
- Unused `assignPerRequestKey()` in the MCP proxy controller is removed.

No new setting, header or endpoint. No change to which MCP requests write an analytics record.

## Capabilities

### New Capabilities
- `analytics-log`: the analytics log record Core writes for a request. This change adds the requirements for the
  execution path of MCP proxy calls. Other record fields stay described by the code until a later change baselines them.

### Modified Capabilities

None. `deployment-origin` covers the deployments-POST family and says nothing about MCP calls or about the analytics
record's fields, so no existing requirement changes.

## Impact

- Code: `McpProxyController`, `McpUpstreamAuthInjector`, `ApiKeyData` (all in `server`).
- Records: analytics log rows of MCP calls gain `execution_path` and, for nested calls, `parent_deployment`. Consumers
  that read the log must accept the field on MCP rows; it is already present on chat, embeddings and Responses rows.
- Redis: no extra round trip; fewer per-request keys for MCP calls that follow a redirect or a retry.
- Motivation: the missing `execution_path` on MCP calls came up while tracing how the execution path reaches the
  analytics log. No issue or document number was supplied. This change covers the MCP proxy path only.
- Out of scope, each its own change if wanted:
  - MCP error responses (rate limit, 403, tool not allowed, upstream failure) still write no analytics record.
  - `McpResourceController` and `ToolSetToolsController` write no analytics record at all, so they have no path to lose.
  - Other controllers that repeat "create key data, fill it from the context" are not refactored to the new helper.
  - The route fields (`route_name`, `route_type`) and session id on analytics rows.
