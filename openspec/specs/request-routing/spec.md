# request-routing Specification

## Purpose
Which handler answers a request before any handler logic runs: the requests Core answers before authentication, the
table of HTTP method and URL patterns Core maps to its built-in APIs, the path variables it extracts and how it decodes
them, the order in which overlapping patterns are tried, and what happens on a miss. A request that matches no built-in
pattern falls through to the config `routes`, and `/v1/deployments/{id}/route/...` reaches an application's own
`routes`. What each handler then does belongs to its owning capability, named where one exists.

## Requirements

### Requirement: HTTP version
Core SHALL answer `505` with an empty body to any request whose HTTP version is not HTTP/1.1, before any other check.

#### Scenario: HTTP/1.0 request
- **WHEN** a client sends `GET /openai/models` over HTTP/1.0
- **THEN** Core answers `505` and routes the request nowhere

### Requirement: CORS preflight
Core SHALL answer every `OPTIONS` request on any path with `200`, an empty body and `Access-Control-Max-Age: 86400`,
without credentials and without routing it. On every response, preflight or not, Core SHALL send
`Access-Control-Allow-Origin: *` and echo the request's `Access-Control-Request-Method` and
`Access-Control-Request-Headers` as `Access-Control-Allow-Methods` and `Access-Control-Allow-Headers` when present.

#### Scenario: Preflight on an API path
- **WHEN** a client sends `OPTIONS /openai/deployments/llm/chat/completions` with `Access-Control-Request-Method: GET`
  and `Access-Control-Request-Headers: Api-Key` and no credentials
- **THEN** Core answers `200` with `Access-Control-Max-Age: 86400`, `Access-Control-Allow-Origin: *`,
  `Access-Control-Allow-Methods: GET` and `Access-Control-Allow-Headers: Api-Key`

### Requirement: Accepted methods
Core SHALL answer `405` with an empty body and no `Allow` header to a request whose method is not one of `GET`,
`POST`, `PUT`, `DELETE`, `HEAD`, `PATCH` or `OPTIONS`, on any path, without credentials.

#### Scenario: Unsupported method
- **WHEN** a client sends `MERGE /openai/models`
- **THEN** Core answers `405` with an empty body and no `Allow` header

### Requirement: Request body size limit
Core SHALL answer `413` with body `Request body is too large`, before the health, version and metadata checks and
without credentials, to a request whose `Content-Length` exceeds `storage.maxUploadedFileSize` (default 536870912)
when its `Content-Type` starts with `multipart/form-data`, or exceeds `resources.maxSize` (default 67108864)
otherwise. A request without a parsable `Content-Length` SHALL be checked as 1024 bytes long.

#### Scenario: Oversized JSON body
- **WHEN** `resources.maxSize` is `1024` and a client sends `PUT /v1/conversations/<bucket>/c` with
  `Content-Length: 2048` and `Content-Type: application/json`
- **THEN** Core answers `413` with body `Request body is too large`

### Requirement: Health check
Core SHALL answer `GET /health` without credentials: `200` with an empty body when the Redis ping call returns,
whether or not every node answered, and `500` with an empty body when the call throws. The path is compared after
percent-decoding.

#### Scenario: Healthy
- **WHEN** a client sends `GET /health` with no `API-KEY` or `Authorization` header and Redis is up
- **THEN** Core answers `200` with an empty body

### Requirement: Version
Core SHALL answer `GET /version` without credentials with `200` and the Core version string as the body. The path is
compared after percent-decoding.

#### Scenario: Version without credentials
- **WHEN** a client sends `GET /version` with no credentials
- **THEN** Core answers `200` with the version string as the body

### Requirement: Well-known MCP resource metadata
Core SHALL answer `GET /.well-known/oauth-protected-resource/v1/toolset/{id}/mcp` and
`GET /.well-known/oauth-protected-resource/v1/deployments/{id}/mcp` before authentication. When
`toolsets.security.authorizationServers` is set (the metadata is enabled) the answer SHALL be `200` with JSON
`resource` (`<resourceSchema>://<resourceHost><path after the prefix>`, defaults `https` and the request authority)
and `authorization_servers`; otherwise `404` with an empty body.

#### Scenario: Toolset metadata without credentials
- **WHEN** `toolsets.security.authorizationServers` is `["https://idp"]`, `resourceHost` is `core.example` and a client
  sends `GET /.well-known/oauth-protected-resource/v1/toolset/my-toolset/mcp` with no credentials
- **THEN** Core answers `200` with `resource: "https://core.example/v1/toolset/my-toolset/mcp"` and
  `authorization_servers: ["https://idp"]`

#### Scenario: Metadata disabled
- **WHEN** `toolsets.security.authorizationServers` is not set and a client sends the same request
- **THEN** Core answers `404` with an empty body, not `401`

### Requirement: Authentication before routing
Core SHALL authenticate every request not answered above before selecting a handler, so a request with none of
`API-KEY`, `Authorization` or `x-api-key` gets `401` with body
`At least API-KEY or Authorization header must be provided` whatever its path, known or not.

#### Scenario: Unknown path without credentials
- **WHEN** a client sends `GET /unexpected` with no credentials
- **THEN** Core answers `401` with body `At least API-KEY or Authorization header must be provided`

### Requirement: MCP challenge without credentials
When a request without credentials is a `GET` or `POST` on `/v1/toolset/{id}/mcp` or `/v1/deployments/{id}/mcp` and
the protected resource metadata is enabled, the `401` SHALL carry
`WWW-Authenticate: Bearer resource_metadata="<scheme>://<host>/.well-known/oauth-protected-resource<request path>"`.

#### Scenario: MCP path without credentials
- **WHEN** the protected resource metadata is enabled and a client sends `POST /v1/toolset/my-toolset/mcp` with no
  credentials
- **THEN** Core answers `401` with a `WWW-Authenticate` header whose `resource_metadata` ends with
  `/.well-known/oauth-protected-resource/v1/toolset/my-toolset/mcp`

### Requirement: Project key as a bearer token
When a request carries only `Authorization: Bearer <token>`, `<token>` does not decode as a JWT, and no identity
provider declares a user-info endpoint, Core SHALL authenticate `<token>` as a project API key (OpenAI clients send
their key this way); a token that is no project key either SHALL get `401`. When a provider declares one, its
user-info answer decides and a token it rejects SHALL get `401` without the project key lookup.

#### Scenario: OpenAI client sends the project key
- **WHEN** no identity provider declares a user-info endpoint and a client sends `GET /openai/models` with
  `Authorization: Bearer proxyKey1` and no `API-KEY`
- **THEN** Core authenticates the request as project key `proxyKey1` and answers `200`

### Requirement: Matching rule
Core SHALL select the handler by trying the route table in its fixed order and taking the first entry whose method
equals the request method and whose pattern matches the raw (still percent-encoded) request path without the query
string. Patterns are case-sensitive.

#### Scenario: Query string ignored
- **WHEN** an authenticated client sends `GET /openai/models?limit=1`
- **THEN** Core selects the models listing, as for `GET /openai/models`

#### Scenario: Prefixed path is not a built-in route
- **WHEN** an authenticated client sends `GET /prefix/openai/deployments/deployment`
- **THEN** no built-in route matches and the request goes to the config routes

### Requirement: Leading slashes
Core SHALL accept one or more leading slashes on the `/openai/...`, `/anthropic/...`, `/v1/{id}/rate`,
`/v1/deployments`, `/v1/deployment-names`, `/v1/deployments/{id}`, `/v1/deployments/{id}/(tokenize|truncate_prompt|configuration)`
and `/v1/deployments/{id}/route/...` patterns, and exactly one on every other built-in pattern.

#### Scenario: Doubled slash on an OpenAI path
- **WHEN** an authenticated client sends `GET //openai/models`
- **THEN** Core selects the models listing

#### Scenario: Doubled slash on a v1 path
- **WHEN** an authenticated client sends `GET //v1/bucket`
- **THEN** no built-in route matches and the request goes to the config routes

### Requirement: Path variable decoding
Core SHALL percent-decode (`+` stays `+`) the identifier variables it extracts: deployment, model, application,
toolset, response, consent, invitation and external-service ids, config entity `{path}`, admin file `{name}` and the
v2 `{filePath}`. File, resource and v2 resource paths and the app-route `{routePath}` SHALL be passed on still encoded.

#### Scenario: Encoded deployment id
- **WHEN** an authenticated client sends `GET /openai/deployments/deployment_x-y%2B%2F`
- **THEN** the deployment lookup receives id `deployment_x-y+/`

#### Scenario: Encoded file path kept encoded
- **WHEN** an authenticated client sends `GET /v1/files/bucket/fol%2Fder%201/file1%23.txt`
- **THEN** the file download handler receives `files/bucket/fol%2Fder%201/file1%23.txt` (see resource-access-control)

### Requirement: Multi-segment ids
Core SHALL let a deployment, model, application, toolset or consent `{id}` span `/`, so a custom application addressed
by its url (`applications/{bucket}/{name}`) is a valid id; the id is the shortest match before the pattern's fixed suffix.

#### Scenario: Custom application in a chat completions path
- **WHEN** an authenticated client sends `POST /openai/deployments/applications/bucket/my-application/chat/completions`
- **THEN** the deployment request handler receives deployment id `applications/bucket/my-application`

### Requirement: Deployment interfaces
Core SHALL route `POST /openai/deployments/{id}/(completions|chat/completions|embeddings)` to the deployment request
handler (deployment-origin), `POST /openai/v1/chat/completions` to the OpenAI chat completions API and
`POST /anthropic/v1/messages` and `POST /anthropic/v1/messages/count_tokens` to the Anthropic Messages API.

#### Scenario: Deployment chat completions
- **WHEN** an authenticated client sends `POST /openai/deployments/app1/completions`
- **THEN** the deployment request handler receives deployment id `app1`

#### Scenario: count_tokens is not a messages call
- **WHEN** an authenticated client sends `POST /anthropic/v1/messages/count_tokens`
- **THEN** Core selects the count-tokens handler, not the messages handler

### Requirement: Responses routes
Core SHALL route `POST /openai/v1/responses` and `POST /openai/responses` to Responses create,
`GET` and `DELETE /openai/v1/responses/{id}` to retrieve and delete, and `POST /openai/v1/responses/{id}/cancel` to
cancel, where `{id}` is one segment (see responses-api).

#### Scenario: Cancel by id
- **WHEN** an authenticated client sends `POST /openai/v1/responses/dial_abc/cancel`
- **THEN** the Responses cancel operation receives id `dial_abc`

### Requirement: OpenAI listings
Core SHALL route `GET /openai/deployments`, `/openai/models`, `/openai/applications`, `/openai/toolsets` to their
listings, `GET` of each with `/{id}` to the single entity, and `GET /anthropic/v1/models` and
`GET /anthropic/v1/models/{id}` to the Anthropic model listing and lookup, and `GET /anthropic/api/oauth/usage` to the
Anthropic usage handler.

#### Scenario: Single model
- **WHEN** an authenticated client sends `GET /openai/models/model1`
- **THEN** the model lookup receives id `model1`

### Requirement: Deployment features
Core SHALL route `POST /v1/{id}/rate`, `POST /v1/deployments/{id}/tokenize`, `POST /v1/deployments/{id}/truncate_prompt`
and `GET /v1/deployments/{id}/configuration` to the deployment feature handler, and `GET /v1/deployments/{id}/limits`
and `GET /v1/deployments/{id}/usage` to the deployment limit and usage handler.

#### Scenario: Rate
- **WHEN** an authenticated client sends `POST /v1/app/rate`
- **THEN** the rate feature handler receives deployment id `app`

#### Scenario: Tokenize
- **WHEN** an authenticated client sends `POST /v1/deployments/app/tokenize`
- **THEN** the tokenize feature handler receives deployment id `app`

### Requirement: Deployment info and listing
Core SHALL route `GET /v1/deployments` to the deployment listing, `GET /v1/deployment-names` to the deployment name
listing and `GET /v1/deployments/{id}` to deployment info, where `{id}` may span `/`.

#### Scenario: Deployment info for a custom application
- **WHEN** an authenticated client sends `GET /v1/deployments/applications/bucket/my-application`
- **THEN** deployment info receives id `applications/bucket/my-application`

### Requirement: Deployment info is tried last
Core SHALL try `GET /v1/deployments/{id}` after every other route, so `/limits`, `/usage`, `/configuration`, `/mcp`,
`/mcp/resources` and `/route/...` suffixes reach their own handlers and not deployment info.

#### Scenario: Sub-resource paths
- **WHEN** an authenticated client sends `GET /v1/deployments/name/limits`, then `GET /v1/deployments/name/configuration`,
  then `GET /v1/deployments/name/mcp`, then `GET /v1/deployments/name/route/v1/search`
- **THEN** they reach the limits handler, the configuration feature, the application MCP proxy and the application
  routes respectively, each with deployment id `name`

### Requirement: MCP routes
Core SHALL route `GET`, `POST` and `DELETE /v1/deployments/{id}/mcp` to the application MCP proxy,
`GET /v1/deployments/{id}/mcp/resources` to the MCP resource handler, and `GET`, `POST` and `DELETE /v1/toolset/{id}/mcp`
to the toolset MCP proxy.

#### Scenario: Toolset MCP proxy
- **WHEN** an authenticated client sends `POST /v1/toolset/toolsets/bucket/my-toolset/mcp`
- **THEN** the toolset MCP proxy receives toolset id `toolsets/bucket/my-toolset`

### Requirement: Toolset routes
Core SHALL route `GET /v1/toolset/{id}/tools` and `GET /v1/toolset/{id}/allowed-tools` to the toolset tool listings,
`POST /v1/ops/toolset/(signin|signout)` to toolset credentials and `POST /v1/ops/toolset/{bucket}/{path}/repair` to
toolset repair for resource `toolsets/{bucket}/{path}`.

#### Scenario: Allowed tools
- **WHEN** an authenticated client sends `GET /v1/toolset/my-toolset/allowed-tools`
- **THEN** the toolset tool listing receives toolset id `my-toolset` in allowed-tools mode

### Requirement: File routes
Core SHALL route `GET`, `PUT` and `DELETE /v1/files/{bucket}/{path}` to file download, upload and delete, and
`GET /v1/metadata/files/{bucket}/{path}` to file metadata, where `{bucket}` is `[a-zA-Z0-9]+` and `{path}` may be
empty (see resource-access-control).

#### Scenario: Upload
- **WHEN** an authenticated client sends `PUT /v1/files/bucket/folder1/file1.txt`
- **THEN** the file upload handler receives `files/bucket/folder1/file1.txt`

### Requirement: Resource routes
Core SHALL route `GET`, `PUT` and `DELETE /v1/{type}/{bucket}/{path}` and `GET /v1/metadata/{type}/{bucket}/{path}`,
`{type}` one of `conversations`, `prompts`, `applications`, `toolsets` and `{bucket}` `[a-zA-Z0-9]+`, to the resource
handler with the path after `/v1/` or `/v1/metadata/` (see resource-access-control).

#### Scenario: Resource metadata
- **WHEN** an authenticated client sends `GET /v1/metadata/conversations/bucket/path`
- **THEN** the resource handler receives `conversations/bucket/path` in metadata mode

### Requirement: Platform applications and toolsets
Core SHALL route `GET`, `POST`, `PUT` and `DELETE` on `/v1/(applications|toolsets)/platform/{path}` and
`/v1/metadata/(applications|toolsets)/platform/{path}` to the config entity API (see config-store), trying them before
the resource routes.

#### Scenario: Platform application
- **WHEN** an authenticated client sends `GET /v1/applications/platform/my-app`
- **THEN** the config entity API answers, not the resource handler

#### Scenario: Other bucket stays a resource
- **WHEN** an authenticated client sends `GET /v1/applications/public/my-app`
- **THEN** the resource handler receives `applications/public/my-app`

### Requirement: Config entity routes
Core SHALL route `GET`, `POST`, `PUT` and `DELETE` on `/v1/{type}/{bucket}/{path}` and `/v1/metadata/{type}/{bucket}/{path}`,
`{type}` one of `models`, `interceptors`, `translators`, `roles`, `keys`, `routes`, `schemas`, `catalog_schemas`,
`settings`, `policies`, `policy_assignments` and `{bucket}` `[a-zA-Z0-9_-]+`, to the config entity API, methods it does
not serve included, so these four never fall through to the config routes; the `405` answers are config-store's.

#### Scenario: Model entity
- **WHEN** an authenticated client sends `PUT /v1/models/platform/new%20model`
- **THEN** the config entity API receives type `models`, bucket `platform`, path `new model`

### Requirement: HEAD and PATCH on config entity URLs
Core SHALL NOT route `HEAD` or `PATCH` on the config entity URLs to the config entity API: no built-in route serves
these methods, so the request falls through to the config routes.

#### Scenario: PATCH on a model entity
- **WHEN** an authenticated client sends `PATCH /v1/models/platform/m` and no config route matches it
- **THEN** Core answers `404` with body `Route is not found`

### Requirement: V2 resource routes
Core SHALL route, for `{group}` `skills` or `projects`, `GET`/`PUT`/`DELETE /v2/{group}/{bucket}/{path}` (no empty
segment, no trailing `/`, no `/files/`), `.../{path}/files/{filePath}` and folder `.../{path}/` to the v2 resource
handler, and `GET /v2/metadata/{group}/{bucket}/{path}[/files[/{filePath}]]` to v2 metadata, the `/files` form first.

#### Scenario: Single file inside a skill
- **WHEN** an authenticated client sends `GET /v2/skills/bucket/group/name/files/docs/readme.md`
- **THEN** the v2 resource handler receives resource `skills/bucket/group/name` and file path `docs/readme.md`

#### Scenario: Folder
- **WHEN** an authenticated client sends `PUT /v2/projects/bucket/group/`
- **THEN** the v2 resource handler receives folder `projects/bucket/group/`

### Requirement: Admin and config routes
Core SHALL route `GET /v1/admin/health/config`, `POST /v1/admin/validate`, `POST /v1/admin/apply`,
`POST /v1/admin/config/file/migrate`, `POST /v1/ops/config/reload` and `GET`, `POST`, `PUT` and `DELETE` on
`/v1/admin/config/file/{type}[/{name}]` (`{type}` a config entity type other than `policies` and
`policy_assignments`, or `applications`, `toolsets`) to the admin config API (config-store), and
`POST /v1/admin/export` to the admin export handler.

#### Scenario: File config entry
- **WHEN** an admin sends `GET /v1/admin/config/file/models/test-model-v1`
- **THEN** the admin file config API receives type `models` and name `test-model-v1`

### Requirement: Bucket, user and schema routes
Core SHALL route `GET /v1/bucket`, `GET /v1/user/info`, `GET /v1/user/limits`, `GET /v1/user/usage`,
`GET /v1/user/offline-credentials`, `POST /v1/user/offline-credentials/(signin|signout)`, `GET` and
`POST /v1/consent/{id}`, and `GET /v1/application_type_schemas/(schemas|schema|meta_schema)` and
`GET /v1/catalog_schemas/(schemas|schema|meta_schema)` to their handlers.

#### Scenario: Bucket
- **WHEN** an authenticated client sends `GET /v1/bucket`
- **THEN** the bucket handler answers

### Requirement: Share and invitation routes
Core SHALL route `POST /v1/ops/resource/share/(create|list|discard|revoke|copy)` to sharing, `GET /v1/invitations` to
the invitation listing and `GET` and `DELETE /v1/invitations/{id}`, `{id}` `[a-zA-Z0-9]+`, to the invitation.

#### Scenario: Share list
- **WHEN** an authenticated client sends `POST /v1/ops/resource/share/list`
- **THEN** sharing receives operation `list`

#### Scenario: Delete invitation
- **WHEN** an authenticated client sends `DELETE /v1/invitations/id`
- **THEN** the invitation handler deletes invitation `id`

### Requirement: Publication routes
Core SHALL route `POST /v1/ops/publication/(list|get|create|delete|approve|reject|update)`,
`POST /v1/ops/publication/resource/list` and `POST /v1/ops/publication/rule/list` to publications.

#### Scenario: Rule list
- **WHEN** an authenticated client sends `POST /v1/ops/publication/rule/list`
- **THEN** publications lists the rules

### Requirement: Operation routes
Core SHALL route `POST` on `/v1/ops/resource/(move|copy|subscribe)`, `/v1/ops/notification/(list|delete)`,
`/v1/ops/application/(deploy|undeploy|logs|redeploy)`, `/v1/ops/code_interpreter/{op}` (nine session and file
operations) and `/v1/ops/external-service/(signin|signout|credentials|obo-credentials)` to their handlers.

#### Scenario: Move
- **WHEN** an authenticated client sends `POST /v1/ops/resource/move`
- **THEN** the resource operation handler runs `move`

### Requirement: Unanchored operation routes
Core SHALL match `POST /v1/ops/toolset/(signin|signout)`, `/v1/ops/resource/per-request-permissions/(grant|revoke|list)`
and `/v1/ops/client-channel/(subscribe|report|unsubscribe|interact)` on the path prefix, so trailing characters after
the operation are ignored.

#### Scenario: Trailing segment
- **WHEN** an authenticated client sends `POST /v1/ops/client-channel/subscribe/extra`
- **THEN** the client channel handler runs `subscribe`

### Requirement: External service management
Core SHALL route `GET /v1/applications/{appId}/external-services`, `GET`, `PUT` and
`DELETE /v1/applications/{appId}/external-services/{id}` and `POST` and `DELETE .../{id}/consent` to external service
management, trying them before the resource routes; `{appId}` may span `/`, `{id}` is one segment.

#### Scenario: Dynamic application id
- **WHEN** an authenticated client sends `PUT /v1/applications/bucket/my-app/external-services/svc1`
- **THEN** external service management receives app id `bucket/my-app` and service id `svc1`, not the resource handler

### Requirement: Application routes
Core SHALL route every accepted method on `/v1/deployments/{id}/route{routePath}` to the application's own `routes`,
where `{id}` runs up to the last `/route/` in the path and `{routePath}` is the rest starting with `/`, unless an
earlier route matches the same path.

#### Scenario: Application route
- **WHEN** an authenticated client sends `POST /v1/deployments/app-route/route/v1/index/search`
- **THEN** Core matches `/v1/index/search` against the `routes` of application `app-route`

#### Scenario: Earlier route wins
- **WHEN** an authenticated client sends `POST /v1/deployments/app/route/v1/rate`
- **THEN** the rate feature handler receives deployment id `deployments/app/route/v1`, not the application routes

### Requirement: Application route selection
Core SHALL try the application's `routes` in ascending `order` (default `2147483647`) and take the first whose
`methods` is empty or holds the request method and whose `paths` is empty or has a pattern fully matching `{routePath}`;
with no match it SHALL answer `404` with body `Route is not found`.

#### Scenario: No application route matches
- **WHEN** an authenticated client sends `GET /v1/deployments/app-route/route/v1/unknown` and no route of `app-route`
  matches
- **THEN** Core answers `404` with body `Route is not found`

### Requirement: Application routes need an application
Core SHALL answer `404` with body `Application is not found: {id}` when `{id}` in `/v1/deployments/{id}/route/...`
names a deployment that is not an application.

#### Scenario: Model id
- **WHEN** an authenticated client sends `POST /v1/deployments/chat-gpt-35-turbo/route/v1/search`
- **THEN** Core answers `404` with body `Application is not found: chat-gpt-35-turbo`

### Requirement: Fallback to config routes
Core SHALL hand every authenticated request that matches no built-in route to the config `routes`, tried in ascending
`order` (default `2147483647`, ties in config order), taking the first whose `methods` is empty or holds the method and
whose `paths` is empty or has a pattern fully matching the raw path without the query string.

#### Scenario: Config route matched
- **WHEN** an authenticated client sends `GET /v1/vector_store/1?q=p` and route `vector_store_query` has
  `"paths": ["/v1/vector_store(/[^/]+)*$"]` and `"methods": ["GET", "HEAD"]`
- **THEN** Core serves the request through `vector_store_query`

### Requirement: Unknown path
Core SHALL answer `404` with body `Route is not found` and no `Allow` header to an authenticated request that matches
no built-in route and no config route.

#### Scenario: Unknown path
- **WHEN** an authenticated client sends `GET /unexpected` and no config route matches it
- **THEN** Core answers `404` with body `Route is not found`

### Requirement: Wrong method on a built-in path
Core SHALL NOT answer `405` for a method a built-in path does not serve, outside `POST`, `PUT` and `DELETE` on the
config entity and admin file config URLs (config-store): the request falls to the next matching route or to the config routes, and with no match gets `404` with
body `Route is not found`.

#### Scenario: DELETE on a listing
- **WHEN** an authenticated client sends `DELETE /openai/models` and no config route matches it
- **THEN** Core answers `404` with body `Route is not found`

#### Scenario: GET on a chat completions path
- **WHEN** an authenticated client sends `GET /openai/deployments/gpt-4/chat/completions`
- **THEN** the single-deployment lookup receives id `gpt-4/chat/completions` and Core answers `404` with an empty body

### Requirement: First matching config route decides
Core SHALL stop at the first config route matching method and path: when the caller's roles are not in its
`userRoles`, Core SHALL answer `403` with body `Forbidden route` and SHALL NOT try later routes.

#### Scenario: Role not allowed
- **WHEN** route `forbidden` has `"paths": ["/v1/forbidden"]`, `"methods": ["GET"]`, `"userRoles": []` and an
  authenticated client sends `GET /v1/forbidden`
- **THEN** Core answers `403` with body `Forbidden route`
