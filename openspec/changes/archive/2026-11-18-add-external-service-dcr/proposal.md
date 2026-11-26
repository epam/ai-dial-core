# Proposal

## Why

An OAUTH external service today needs a client that an administrator registered with the provider by hand:
`client_id`, `client_secret`, `authorization_endpoint` and `token_endpoint` are all required on create, and Core
never generates PKCE for it. Toolsets need none of that: Core discovers the authorization server and registers a
client itself (dynamic client registration, DCR, RFC 7591) and generates the PKCE pair. So an application cannot use
an external service whose provider offers only DCR, or one that requires PKCE.

An update has a related gap: when the body omits them, an external-service write keeps only the stored
`client_secret`. A client that sends back what a GET returned loses `token_endpoint_auth_method` and `resource`, and
will lose the PKCE pair once there is one.

## What Changes

- An OAUTH external service gets an optional `auth_settings.discovery_url`: the URL Core reads OAuth metadata from
  (an MCP server's URL, an API's URL that publishes protected resource metadata, or the authorization server's issuer
  URL). Core never sends application traffic there; the application keeps calling the service itself. It is required
  only when Core has to register the client.
- An OAUTH external service created (or switched to OAUTH) with an `discovery_url` and without `client_id` and
  `client_secret` is registered dynamically: Core discovers the authorization server from `discovery_url` and registers a
  client, as it does for toolsets. A write without `discovery_url` and without a client is rejected with `400`, as today.
- A static client with an `discovery_url` gets the same metadata lookup as a toolset static client: Core fills the missing
  `code_challenge_method`, `scopes_supported` and `resource` from the provider's metadata.
- Core generates `code_challenge` / `code_verifier` for an OAUTH external service when the authorization server
  advertises `S256` (dynamic registration, or a static client with an `discovery_url`) or the request names a
  `code_challenge_method`. Reads
  return `code_challenge` and `code_challenge_method`, never `code_verifier`.
- An update that keeps the service OAUTH keeps every stored OAuth field the body omits (`client_secret`,
  `token_endpoint_auth_method`, `resource`, the PKCE pair, the registration kind), the same way a toolset update
  does.
- A static client without an `discovery_url` keeps working unchanged: no network call on its write, as today.
- These rules apply to every write path of an external service: the application write (Resource API, Configuration
  API, admin apply), `PUT /v1/applications/{appId}/external-services/{id}`, and the user-authored definition.

Out of scope:

- External services declared in the config files: they stay static (Core never writes to those files, so it has
  nowhere to keep a registered client).
- Re-registering a client the provider forgot (the toolset repair endpoint has no external-service counterpart yet).
- Chat and admin panel changes: building the authorize URL with `code_challenge`, the `discovery_url` field in forms.
- Moving the stored credentials of an already registered client to a new one: re-registration happens only on create
  or a switch to OAUTH, and that switch already removes the old credentials.

## Capabilities

### New Capabilities

- `external-services`: how an application's external service is defined and stored — its OAuth client setup (static
  or dynamic registration, PKCE), what an update keeps, and what reads return. Existing behaviour of external
  services that this change does not touch (sign-in, credential retrieval, consent) is not specified here.

### Modified Capabilities

None. `config-store` "External services on write" (the map as desired state, credential purge) is unchanged.

## Impact

- Issue: [#2287](https://github.com/epam/ai-dial-core/issues/2287) (dynamic client registration and PKCE for
  external services). Covered: DCR on create and on a switch to OAUTH, PKCE, keeping omitted OAuth fields on update.
  Left out: the config-file services, re-registration / repair and the UI, as listed above.
- Code: `ResourceAuthSettings` (new `discovery_url`, rejected for toolsets and non-OAUTH types), `ExternalServiceValidation`,
  `ExternalServiceService` (application write and the management PUT), `UserExternalServiceService`,
  `ResourceAuthSettingsService` (an entry point that takes an id and an endpoint instead of a `SecuredResource`),
  `DynamicResourceRegistrationStrategy` (resource indicator rule).
- API: `auth_settings.discovery_url` on the external-service body and response; `docs/open_api_core.yaml` changes. `auth_settings` on
  create may now omit the client.
- Settings: `toolsets.security.allowedRedirectUris` now also feeds external-service registrations (README).
- Compatibility: existing static services and their stored credentials are untouched. A body that used to fail with
  `400` for a missing `client_id` now registers a client when it has an `discovery_url`.
