# Proposal

## Why

Core sends `X-DIAL-DEPLOYMENT-ID` to an origin on only some of the requests it forwards to a deployment:

- interfaces in `translator` mode;
- Anthropic Messages (`/anthropic/v1/messages` and `/count_tokens`), with the body's `model` as the value;
- interceptor calls, with the initial deployment as the value.

A pass-through interface (`openaiChatCompletions`, `openaiEmbeddings`, `openaiResponses`), the deployment feature
endpoints and Responses item operations get no such header from Core. When the deployment sets `overrideName`, the
request path and `X-DIAL-OVERRIDE-NAME` both carry the upstream name, so the origin cannot tell which DIAL deployment
the client called. On top of that, pass-through interfaces and feature endpoints copy a client-sent
`X-DIAL-DEPLOYMENT-ID` to the origin unchanged.

## What Changes

- Core sets `X-DIAL-DEPLOYMENT-ID` to the deployment name on every request it forwards to a deployment's origin
  (models and applications):
  - every interface in any mode: chat completions, embeddings, Responses create and Anthropic Messages;
  - the deployment feature endpoints: `tokenize`, `truncate_prompt`, `rate` and `configuration`;
  - Responses item operations: retrieve, cancel and delete, including background-job polling, which goes back through
    Core's own item route.
- The value replaces any value the client sent.
- Anthropic Messages now sends the deployment name instead of the body's `model`. The two name the same deployment:
  Core looks the deployment up by an exact match on `model`. They can differ only in the spelling of a resource url,
  where the deployment name is the canonical encoded url.
- Interceptor calls are unchanged: they still carry the initial deployment.

Out of scope:

- Toolset and MCP calls (`McpProxyController`): a toolset is not a model or an application.
- Routes (`BaseRouteController`): a route is not a deployment.
- What an alias pass sends. It forwards to Core itself, and the target pass sets the header to the target's name, as
  translator mode already did.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `deployment-origin`: renames "Identity headers for applications and translated interfaces" to "Identity headers for
  applications and deployments" and widens its `X-DIAL-DEPLOYMENT-ID` clause to every forwarded request.
- `responses-api`: "Item request to the origin" adds `X-DIAL-DEPLOYMENT-ID` to the headers an item request sends.

## Impact

- Issue: [#2243](https://github.com/epam/ai-dial-core/issues/2243) (Pass X-DIAL-DEPLOYMENT-ID upstream for all
  deployments, not only translated interfaces). This change covers all of it: the header on every pass-through
  interface, `putHeader` so a client value is replaced, and the other forwarding paths the issue asks to check
  (Responses item operations, and the feature endpoints).
- Code: `BaseDeploymentPostController`, `MessagesBaseController`, `DeploymentFeatureController`, `ResponsesApiClient`,
  `ResponseItemController`.
- API: `docs/open_api_core.yaml` gets a short "Deployment id" section next to "Deployment features".
- Compatibility: an origin that read the client's own `X-DIAL-DEPLOYMENT-ID` through a pass-through interface or a
  feature endpoint now sees the deployment name instead.
