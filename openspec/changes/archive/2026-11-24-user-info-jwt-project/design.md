# Design

## Context

Core resolves the caller once per request. For a JWT the identity provider's `projectPath` gives the token's project
(a string claim only), and Core already uses it for the plugin caller, analytics, the budget cost object and logs. The
user info handler writes `project` only in its API-key branch. A per-request key carries either the starting caller's
key or its claims, never both, so the handler's two branches already answer as that caller.

## Effect per request

None: one more field, already resolved for the request; no extra pass, parse or Redis call.

## Code map

- `UserInfoController.java:handle` - the change: the JWT branch adds `project` from `ProxyContext.java:getUserProject`
  when it is not null; the API-key branch is unchanged.
- `IdentityProvider.java:toExtractedClaims`, `IdentityProvider.java:extractStringClaim` - the project from
  `projectPath`, a string or null.
- `ProxyContext.java:initExtractedClaims` - `userProject` from the claims, the starting caller's for a per-request key.
- `EmbeddedProxyRequest.java` (`new Caller`) - the plugin's caller takes `ProxyContext.java:getProject`, the JWT
  project first, then the key's; the deployment-origin delta states it, no code change.
- `ApiKeyData.java:initFromContext` - a per-request key copies the starting caller's key and claims, one of them null.
- `Proxy.java:authorizeRequest` - a plain key with a different `Authorization` is `400`; a per-request key wins over
  the header.
- Tests: `UserInfoApiTest`; `ResourceBaseTest.java:createClaims(String, String)` behind `Authorization: user@<project>`;
  a per-request key sent as `api-key` after `apiKeyStore.assignPerRequestApiKey`, as `OrgUnitUserOrgUnitsApiTest` does.
