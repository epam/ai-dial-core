# Spec Delta

## Purpose
How an application's external service is defined: its OAuth client setup (a client registered by hand or
registered by Core), PKCE, what an update keeps, and which OAuth fields a read returns.

## ADDED Requirements

### Requirement: External service discovery URL
An OAUTH external service SHALL accept an optional `auth_settings.discovery_url`: the URL Core reads OAuth metadata
from (an MCP server's URL, an API's URL that publishes protected resource metadata, or the authorization server's
issuer URL), an absolute `http` or `https` URL, returned on every read. Core SHALL never send application traffic
there. Any other value, the field on another authentication type, or the field on a toolset SHALL be rejected with
`400` and nothing stored.

#### Scenario: Discovery URL is stored and returned
- **WHEN** an application owner sends `PUT /v1/applications/{appId}/external-services/crm` with
  `auth_settings.discovery_url: "https://crm.example.com/api"` and a static OAUTH client
- **THEN** a `GET` of the service, the application `GET` and the deployment `GET` return that `discovery_url`

#### Scenario: Relative discovery URL rejected
- **WHEN** the `PUT` carries `discovery_url: "/api"`
- **THEN** Core answers `400` and the service is not stored

#### Scenario: Discovery URL on another type or on a toolset
- **WHEN** an `API_KEY` external service, or a toolset, carries `discovery_url`
- **THEN** Core answers `400`

### Requirement: Dynamic client registration
When a write creates an OAUTH external service, or changes one to OAUTH, with a `discovery_url` and neither `client_id`
nor `client_secret`, Core SHALL register a client before storing it, on every external-service write path (the
application write, `PUT /v1/applications/{appId}/external-services/{id}`, a user-authored definition). A failed
discovery or registration SHALL answer `400` and store nothing.

#### Scenario: Create registers a client
- **WHEN** an application owner `PUT`s an external service with `authentication_type: OAUTH`, `discovery_url` pointing at a
  server whose authorization server offers a `registration_endpoint`, and no `client_id` or `client_secret`
- **THEN** Core sends one registration request, answers `200`, and a `GET` of the service returns the registered
  `client_id`, the server's `authorization_endpoint` and `token_endpoint`, and no `client_secret`

#### Scenario: Registration through the application write
- **WHEN** an application owner `PUT`s the application with `external_services.crm` set the same way
- **THEN** the stored service carries the registered `client_id`

#### Scenario: Authorization server without registration
- **WHEN** the authorization server metadata has no `registration_endpoint`
- **THEN** Core answers `400` and the service is not stored

#### Scenario: Neither client nor discovery URL
- **WHEN** an OAUTH external service is created with no `client_id`, no `client_secret` and no `discovery_url`
- **THEN** Core answers `400` and the service is not stored

#### Scenario: Update does not register again
- **WHEN** a dynamically registered service is updated and stays OAUTH
- **THEN** Core sends no registration request and the stored `client_id` is unchanged

### Requirement: Registration request and stored client
Core SHALL find the authorization server from the protected resource metadata of `discovery_url`, else from the
authorization server metadata at `discovery_url`. The request SHALL list `toolsets.security.allowedRedirectUris` and the
body's `redirect_uri`. Core SHALL store the returned `client_id`, `client_secret` (if issued) and `redirect_uri`, the
server's `authorization_endpoint` and `token_endpoint`, and as `resource` only the one the metadata names.

#### Scenario: Redirect URIs registered
- **WHEN** `toolsets.security.allowedRedirectUris` is `["https://chat.example.com/callback"]` and the body has no
  `redirect_uri`
- **THEN** the registration request lists `https://chat.example.com/callback`

#### Scenario: Resource indicator only from metadata
- **WHEN** the registration succeeds and `discovery_url` publishes no protected resource metadata
- **THEN** the stored service has no `resource`, and the token request at sign-in sends no `resource` parameter

### Requirement: Redirect URIs of a registered client
Core SHALL register a client for an external service only with redirect URIs from
`toolsets.security.allowedRedirectUris`. A body whose `redirect_uri` is not in that list, or a registration when the
list is empty, SHALL be rejected with `400` before any request to the provider. Sign-in SHALL accept a
`redirect_uri` from the request only when it is in the list or equals the service's own; a blank one uses the stored.

#### Scenario: Unlisted redirect URI not registered
- **WHEN** a body that asks for dynamic registration carries `redirect_uri: "https://attacker.example/cb"`, not in
  `toolsets.security.allowedRedirectUris`
- **THEN** Core answers `400`, sends no request to the provider and stores nothing

#### Scenario: Sign-in with an allowed redirect URI
- **WHEN** a user signs in to a registered service with `redirect_uri: "http://admin/callback"`, which is in the list
- **THEN** the token request carries that `redirect_uri`

#### Scenario: Sign-in with an unlisted redirect URI
- **WHEN** a user signs in with a `redirect_uri` that is neither in the list nor the service's own
- **THEN** Core answers `400` and sends no token request

### Requirement: Client fields Core registers
A body that asks for dynamic registration SHALL NOT set `authorization_endpoint`, `token_endpoint`, `resource`,
`code_challenge`, `code_verifier` or `code_challenge_method`; Core SHALL answer `400` and store nothing.

#### Scenario: Endpoint fields without a client
- **WHEN** an OAUTH external service is created with a `discovery_url`, no client and a `token_endpoint`
- **THEN** Core answers `400` and sends no registration request

### Requirement: Static client setup
An OAUTH external service created with a client and no `discovery_url` SHALL be stored as sent, with no outbound
request. With a `discovery_url`, Core SHALL fill a missing `code_challenge_method`, `scopes_supported` and `resource`
from its metadata, as for a toolset static client, without registering. A metadata URL answering with an error leaves
them unset; an unreachable `discovery_url` or invalid metadata fails the write.

#### Scenario: Switch to OAUTH needs a client secret
- **WHEN** an `API_KEY` service is updated to OAUTH with `client_id` and both endpoints but no `client_secret`
- **THEN** Core answers `400`, as for a create

#### Scenario: Static client without discovery URL makes no network call
- **WHEN** an OAUTH external service is created with `client_id`, `client_secret`, `authorization_endpoint` and
  `token_endpoint` and no `discovery_url`
- **THEN** Core sends no outbound request and stores the client as sent

#### Scenario: Static client with discovery URL reads metadata
- **WHEN** the same body carries a `discovery_url` whose protected resource metadata names a `resource` and whose
  authorization server lists `S256` and `scopes_supported`
- **THEN** Core sends no registration request, and a `GET` returns that `resource`, the `scopes_supported`,
  `code_challenge_method: "S256"` and a `code_challenge`

#### Scenario: Static client with failing metadata URLs
- **WHEN** every metadata URL of `discovery_url` answers `500`
- **THEN** Core answers `200` and stores the client as sent, without PKCE

#### Scenario: Static client with invalid metadata
- **WHEN** the protected resource metadata of `discovery_url` names a different `resource`
- **THEN** Core answers `400` and the service is not stored

### Requirement: External service PKCE
Core SHALL generate a `code_verifier` and its `code_challenge` for an OAUTH external service when it registers a
client, or reads metadata for a static client, and the authorization server advertises `S256`, or when a write of a
static client names a `code_challenge_method` it did not have before. The `code_verifier` SHALL be stored encrypted and SHALL be sent in the
sign-in token request; no read SHALL return it. Reads SHALL return `code_challenge` and `code_challenge_method`.

#### Scenario: Registered client gets PKCE
- **WHEN** a client is registered with an authorization server whose metadata lists `S256` in
  `code_challenge_methods_supported`
- **THEN** a `GET` of the service returns `code_challenge` and `code_challenge_method: "S256"` and no `code_verifier`

#### Scenario: Static client asks for PKCE
- **WHEN** a static OAUTH client without `discovery_url` is created with `code_challenge_method: "S256"`
- **THEN** a `GET` returns a `code_challenge`, and the token request at sign-in carries the matching `code_verifier`

#### Scenario: Public client verifier stays secret
- **WHEN** the registered client has no `client_secret` and the service has a `code_verifier`
- **THEN** the stored blob holds the `code_verifier` encrypted, and the application `GET`, the deployment `GET` and
  the external-service `GET` return no `code_verifier`

### Requirement: External service update keeps OAuth fields
An update that keeps an external service OAUTH SHALL keep each of these stored fields the body omits:
`client_secret`, `token_endpoint_auth_method`, `resource`, `discovery_url`, `code_challenge`, `code_verifier`,
`code_challenge_method` and whether the client was registered dynamically.

#### Scenario: GET then PUT round trip
- **WHEN** an owner `GET`s a service with `token_endpoint_auth_method: "client_secret_post"`, `resource` and a PKCE
  pair, and `PUT`s the returned body back with a new `display_name`
- **THEN** the stored service keeps the auth method, the resource, the same `code_challenge` and the `client_secret`

### Requirement: External service update replaces OAuth fields
On an update, a blank `resource` or `discovery_url` SHALL clear it, a new `code_challenge_method` SHALL replace the PKCE pair and a blank
one SHALL remove it (on a hand-registered create it means no PKCE), and a body
with both `client_id` and `client_secret` on a dynamically registered service SHALL turn it into a static client. A
change of `authentication_type` SHALL keep none of the stored OAuth fields.

#### Scenario: Blank resource clears it
- **WHEN** the `PUT` carries `resource: ""`
- **THEN** the stored service has no `resource`

#### Scenario: Blank PKCE method removes PKCE
- **WHEN** the `PUT` carries `code_challenge_method: ""`, as the admin panel sends for "None"
- **THEN** Core answers `200` and the stored service has no `code_challenge_method`, `code_challenge` or `code_verifier`

#### Scenario: Type change keeps nothing
- **WHEN** an OAUTH service is updated to `API_KEY`
- **THEN** no stored OAuth field is carried over

### Requirement: Config-file external services stay static
An external service declared in a config file SHALL be loaded only with a complete static client, as today; Core
SHALL NOT discover or register a client for it. One without a client SHALL be dropped from the loaded application
with an error log, the application itself still loading.

#### Scenario: Config-file service without a client
- **WHEN** a config file declares `external_services.crm` with `authentication_type: OAUTH`, a `discovery_url` and no
  `client_id`
- **THEN** the loaded application has no `crm` service, and Core sent no metadata or registration request
