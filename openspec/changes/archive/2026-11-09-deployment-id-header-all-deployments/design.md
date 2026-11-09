# Design

## Context

Line numbers are at `development` `3157af667`. See proposal.md for why.

Where Core sets `X-DIAL-DEPLOYMENT-ID` today:

| Path | Code | Value |
|---|---|---|
| Interfaces of a deployment | `BaseDeploymentPostController.sendProxyRequest` (`:407`), only when `resolveMode(...) == InterfaceMode.TRANSLATOR` (`:450`) | `deployment.getName()` |
| Anthropic Messages | `MessagesBaseController.enrichProxyRequestHeaders` (`:212`), overriding the line above; the value is saved in `handle` (`:88`) | the body's `model`, saved before `EnhanceDeploymentRequestFn` rewrites it to `overrideName` |
| Interceptor calls | `BaseInterceptorController.handleProxyRequest` (`:132`) | `context.getInitialDeployment()` |

Where it is missing:

- `DeploymentFeatureController.handleProxyRequest` (`:224`) copies the client headers and sets
  `X-DIAL-OVERRIDE-NAME` (`:241`), but not `X-DIAL-DEPLOYMENT-ID`.
- `ResponsesApiClient.send` (`:27`), called from `ResponseItemController.sendRequest` (`:223`), sends only `API-KEY`
  and the three `X-UPSTREAM-*` headers.
- `BackgroundJobService.poll` (`:198`) calls Core's own item route, so it ends up in `ResponseItemController`.

`ProxyUtil.copyHeaders` (`:86`) filters hop-by-hop and trace headers and the caller's exclusions, not
`X-DIAL-DEPLOYMENT-ID`. Both deployment controllers call it before setting their own headers, so a client value
reaches the origin unless Core then replaces it with `putHeader`.

`MessagesBaseController` looks the deployment up with `DeploymentService.findCallableDeployment`, which calls
`Config.selectDeployment` (`:93`): an exact map lookup on the body's `model`. For a config deployment `model` is
`deployment.getName()`. For a resource application, the name is the canonical url (`ApplicationService:712`), and the
client may have written it unencoded.

## Goals / Non-Goals

**Goals:**
- Every request Core forwards to a model or application origin carries `X-DIAL-DEPLOYMENT-ID` with the deployment
  name, whatever the interface mode.
- A client cannot set the value the origin sees.

**Non-Goals:**
- Interceptor calls: they keep the initial deployment.
- Toolsets, MCP and routes: not deployments in this sense.

## Decisions

**1. Drop the `TRANSLATOR` guard in `sendProxyRequest`.** One `putHeader` after the client headers are copied covers
chat completions, embeddings, Responses create and Anthropic Messages in both modes, and replaces a client value.
Rejected: a per-mode or per-interface switch. Nothing an origin needs differs by mode, and a switch would leave the
next interface to be added without the header.

**2. Drop the Anthropic Messages override.** With decision 1, Messages gets the deployment name from the base
controller. The override sent the body's `model`, which names the same deployment (the lookup is exact) and differs
only in the spelling of a resource url, where it was the non-canonical one. It was added in #1709, when no
pass-through interface sent the header and `model` was the only value at hand. Rejected: keeping it. It is a second
source for one header and suggests, wrongly, that the client picks the value.

**3. Set the header in `DeploymentFeatureController.handleProxyRequest`.** One `putHeader` next to
`setOverrideNameHeader`, after `copyHeaders`. Rejected: moving the feature controller onto
`BaseDeploymentPostController`. That is a refactor of a separate request flow, larger than this fix.

**4. Pass the deployment name into `ResponsesApiClient.send`.** `send` gains a `deploymentId` parameter, and its only
caller, `ResponseItemController.sendRequest`, passes `context.getDeployment().getName()` (the mapping's deployment).
Rejected: reading the deployment inside `ResponsesApiClient`. It is a stateless HTTP client with no context.

Effect per request: one more header on the forwarded request. No body copy or parse, and no Redis or blob round trip,
so no measurement task.

## Risks / Trade-offs

- [An origin relied on a client-sent `X-DIAL-DEPLOYMENT-ID` passing through] → It now sees the deployment name. Core
  already overwrote the header in translator mode and on Messages, so no origin served through those could rely on it.
- [Messages to a resource application called by an unencoded url] → The origin now sees the canonical encoded url
  instead of the client's spelling. It is the same name the other interfaces already sent in translator mode.

## Migration Plan

None. No setting, blob format or API field changes. Rollback is a revert.
