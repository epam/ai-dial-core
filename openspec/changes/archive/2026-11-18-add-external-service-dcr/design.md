# Design

## Context

See proposal.md "Why". The parts of Core this change touches, on `development`:

- Toolsets already do everything this change needs, in
  `ResourceAuthSettingsService.java:processResourceAuthSettings`: choose static or dynamic registration
  (`requiresClientRegistration`, `requiresDynamicClientRegistration`), validate for that mode, call
  `ResourceRegistrationService.java:register`, generate PKCE (`setCodeChallengeProperties`) and, on an update that
  stays OAUTH, keep the omitted fields (`resolveCodeChallengeSettings` and the block around it). It takes a
  `SecuredResource` (a `Deployment` subclass) only to read `getName()`, `getEndpoint()`, `isForwardPerRequestKey()`
  and `getAuthSettings()`.
- `ExternalService` is not a `SecuredResource`: it has `display_name`, `description` and `auth_settings` only, so no
  endpoint to discover from.
- External services are written in three places, each calling `ExternalServiceValidation.java:validate`, which
  always validates a create as `CREATE_STATIC_CLIENT`:
  - `ExternalServiceService.java:processOnWrite`, reached from `ApplicationService.java:writeApplication` (Resource
    API, `ConfigResourceController` PUT, `ConfigApplyService` apply). It keeps only `client_secret`
    (`preserveOmittedSecrets`).
  - `ExternalServiceService.java:putExternalService` (`PUT /v1/applications/{appId}/external-services/{id}`), same.
  - `UserExternalServiceService.java:put` (user-authored definitions), `preserveOmittedSecret`, same.
- Since #2281 (merged after this change was planned) every write decrypts the stored side with
  `ResourceAuthSettingsEncryptionService.tryDecryptOrDropSecrets`: an unreadable `client_secret` is dropped with its
  verifier, and an update that would keep it is refused with `400`; `ResourceAuthSettingsService` takes the
  `existingSecretUnreadable` flag and regenerates the PKCE pair then. The external-service paths pass that flag into
  the shared entry point (D1), which replaces their own `preserveOmittedSecret`; `putExternalService` still decrypts
  and encrypts only the service it writes. #2294 (an unreadable verifier without a secret is kept) now applies to
  external-service public clients too and is left to that issue.
- `ExternalServiceService.java:hasSecret` gates encryption and decryption of the whole `auth_settings` on
  `client_secret != null`, although `ResourceAuthSettingsEncryptionService.java:processFields` encrypts
  `code_verifier` too. A public client (no secret) with a verifier would store it in plaintext and never decrypt it.
  `UserExternalServiceService` has its own `encryptSecret` / `decryptSecret` to check for the same gate.
- `DynamicResourceRegistrationStrategy.java:resolveResource` falls back to the endpoint as the RFC 8707 resource
  when the server publishes no protected resource metadata, on the grounds that a DCR server follows the MCP spec.
  `StaticResourceRegistrationStrategy` never guesses it.
- Config-file external services are checked in `ConfigPostProcessor.java:validateExternalServices` with
  `NO_CLIENT_CHANGES`, which requires `client_id` and both endpoints, and dropped with an error log otherwise.
- Sign-in already sends the stored `code_verifier` (`TokenService.java:getToken`, `.codeVerifier(...)`)
  and checks `redirect_uri` against the same `allowedRedirectUris` list the registration uses (`AiDial.java`, one
  list passed to `TokenService` and `ResourceRegistrationService`).

## Goals / Non-Goals

**Goals:**
- One code path for the OAuth client setup of toolsets and external services, so the next fix lands in both.
- A static external-service client behaves like a toolset static client once it has an `discovery_url`; without one, its
  write makes no network call, as today.

**Non-Goals:**
- Changing toolset behaviour. Toolset tests must pass unchanged.
- Re-registration of an existing client, config-file services (proposal "Out of scope").

## Decisions

### D1. A second entry point on `ResourceAuthSettingsService`, not a `SecuredResource` for external services
Add `processResourceAuthSettings(String resourceId, String endpoint, ResourceAuthSettings updated,
ResourceAuthSettings existing, boolean endpointAsResource)` and make the existing `SecuredResource` method delegate to
it (`forwardPerRequestKey` check stays in the toolset method). External-service writes call the new one with
`resourceId = <application url>/external_services/<id>` (the AAD the secret is already bound to; also sent as the
registration's `client_name`; a user-authored service uses its resource URL instead, so the raw user id in its
storage path never reaches the provider). The flag is D3. (Drift found in apply: the plan had a `RegistrationOptions`
object; with D2 reduced to "no endpoint, no lookup" only one flag was left, so it is a parameter. A null `existing`
means a create; the toolset method passes the stored resource's settings.)
Rejected: making `ExternalService` extend `SecuredResource`. It would inherit every `Deployment` field (limits,
features, interceptors) into the external-service JSON and OpenAPI schema.

### D2. Static clients use the toolset static strategy when there is an `discovery_url`
A static external-service create (or switch to OAUTH) with an `discovery_url` runs `StaticResourceRegistrationStrategy`
exactly as for toolsets: when `code_challenge_method` or `scopes_supported` is missing it reads the protected resource
and authorization server metadata of `discovery_url` and fills `code_challenge_method` (so PKCE follows when the server
advertises `S256`), `scopes_supported` and `resource` (from protected resource metadata only, never the endpoint).
Both endpoints are already required by the static validation, so the strategy never fills them. As for toolsets,
a metadata URL that fails is skipped (`FallbackHandler` swallows the HTTP error) and leaves the fields unset, while
metadata that fails validation (a protected resource whose `resource` is not `discovery_url`, no supported PKCE method)
fails the write with `400`. (Drift found in apply: the plan first said every metadata error fails the write.)
Without an `discovery_url` there is nothing to discover from: the shared entry point skips `register` when the endpoint is
null, and PKCE comes only from a `code_challenge_method` in the body (`setCodeChallengeProperties` on create,
`resolveCodeChallengeSettings` on update). Toolsets always have an endpoint and no `discovery_url`, so the skip never applies to them.
This keeps every static external service that works today offline (none has an `discovery_url`), and a body that adds
`discovery_url` opts in to the same discovery and the same failure modes as a toolset.
Rejected: never discovering for static external services. It would be a third behaviour next to toolset static and
dynamic, need its own option, and leave a static client with an `discovery_url` without the automatic PKCE toolsets get.

### D3. Resource indicator from metadata only for external services
`endpointAsResource = false` for external services: `resolveResource` returns the protected
resource metadata's `resource` or null. Toolsets keep `true`. An external service's `discovery_url` may be an
authorization server's issuer URL, and a non-MCP server can reject an unknown `resource` with `invalid_target`.
Rejected: a separate `issuer` field next to `discovery_url`. The metadata fallback already tries the authorization server
well-known paths on `discovery_url`, so one field covers both (see D9).

### D8. A registered external-service client gets only operator-approved redirect URIs
Toolset registration also registers the body's `redirect_uri`, and sign-in always accepts the resource's own
`redirect_uri`. A writer of the resource can therefore get any URL registered under Core's client and later redeem a
code sent there through Core's sign-in (the shared PKCE verifier does not stop it). This change ships alone as a patch
to 0.50, without the general redirect fix (`fix/redirect-uri-allowlist`, development only), so it must not open that
hole for external services by itself: `RegistrationPolicy.EXTERNAL_SERVICE` rejects a body `redirect_uri` outside
`toolsets.security.allowedRedirectUris`, and a registration with an empty list, before any provider request.
`RegistrationPolicy` replaces the `endpointAsResource` flag (D3), since there are two rules now. Static clients are
untouched: their owner registered the redirect with the provider. Toolsets keep today's behaviour here.
When `fix/redirect-uri-allowlist` lands it makes the list the only source for every resource; whichever of the two
merges second drops `anyResourceRedirectUri` (the other branch registers only the list for everyone) and keeps the
empty-list refusal for external services.

### D9. `auth_settings.discovery_url`, not a service-level `endpoint`
A toolset's `endpoint` is where Core sends traffic; the external-service URL is only an input for the OAuth client
setup, and for a REST API it is usually the identity provider's issuer, not the API: OAuth does not require an API to
publish protected resource metadata (RFC 9728 is optional; the MCP authorization spec makes it mandatory for MCP
servers), while issuers commonly publish RFC 8414 / OIDC metadata. So the field is named for its purpose and lives in
`auth_settings` with the other OAuth fields: the validators forbid it for `API_KEY`, `NONE` and `DIAL_NATIVE`, a type
change drops it, an omitted value is kept on update and a blank one clears it, like `resource`. Toolsets reject it
(`400`): they discover from their own `endpoint`.
Discovery stays the toolset discovery, MCP challenge probe included (#1981: a hand-built probe was refused by strict
MCP servers, and the well-known fallback then picked another product's authorization server on a shared host), so MCP
external services work exactly like toolsets. A REST API or an issuer gets one `initialize` POST at setup time, answers
`404`/`405`, and discovery falls back to the well-known URLs; an unreachable or silent `discovery_url` fails the write.
Rejected: a plain GET probe for external services (regresses MCP servers, #1981); a protocol hint field (one setup-time
request does not justify a setting and a UI choice; it can be added later without breaking anything).
(Decided in apply, 2026-11-18, replacing the service-level `endpoint` of the first plan.)

### D4. Encrypt whenever `client_secret` or `code_verifier` is set
`ExternalServiceService.hasSecret` (and the `UserExternalServiceService` equivalent) becomes "client secret or code
verifier is non-null". `processFields` already handles each field independently, and its decrypt has a legacy
plaintext fallback for `code_verifier`, so blobs written before the change still read.
Response paths keep redacting with `withoutSecretsKeepingHint` / `redactSecrets`, which already clear `code_verifier`.

### D5. Registration runs inside the blob write, as for toolsets
The discovery and registration calls run inside `computeResource`, where the existing value is known, like
`ToolSetService.java:putToolSet`. They run on the `AsyncTaskExecutor` thread the write already uses, never on the event
loop. A write that registers holds the application blob's lock for the duration of the calls (bounded by the
`ResourceAuthorizationClient` timeouts).
Rejected: register before `computeResource`. The create-vs-update decision needs the stored service, so we would read
it twice and race a concurrent write.

### D6. `/v1/admin/validate` does not register
The precheck validates the body only; it does not call the authorization server. A dynamic external service passes
validate when its fields are well-formed; a server that refuses registration fails at apply with `400`. Registering in
validate would leave orphan clients at the provider for every dry run.

### D7. Config-file services unchanged
`ConfigPostProcessor.validateExternalServices` keeps `NO_CLIENT_CHANGES`. A config-file service without a client is
already dropped with an error log; this change only adds the scenario to the spec. Core has no write path to the
config files, so a registered client could not be kept across a reload.

## Effect per request

- Proxied requests (`/openai/deployments/...`), sign-in and credential retrieval: no change — no new Core pass, body
  copy, parse, Redis or blob round trip.
- External-service writes: unchanged for static clients without `discovery_url` (D2). A static create with `discovery_url` and
  without `code_challenge_method` or `scopes_supported` adds the metadata GETs, as for toolsets. A write that
  registers adds the metadata GETs (at most
  the protected-resource header probe, two protected-resource well-known URLs and five authorization-server well-known
  URLs, stopping at the first hit) and one registration POST, inside the existing single blob write.
- Measured by the integration tests: the MockWebServer request count per write (static create without `discovery_url`: 0;
  static create with `discovery_url`: the metadata requests, no registration; dynamic create: the metadata requests plus 1
  registration; update: 0).

## Risks / Trade-offs

- [A writer changes a registered service's `redirect_uri` on update; sign-in accepts the service's own value] → no
  leak: the provider only redirects codes to the URIs registered under the client, which D8 limits to the
  operator's list, and it refuses a token request whose `redirect_uri` does not match the one the code was issued to.

- [A slow authorization server holds the application blob lock] → the same as toolsets; bounded by the client
  timeouts; only on a registering write.
- [Orphan clients at the provider when the blob write fails after registration] → the same as toolsets; DCR clients
  are cheap and unused clients are harmless; no cleanup in this change.
- [Registering a service on a shared application lets any writer of the application make Core call an arbitrary URL]
  → no new reach: the same writers can already create toolsets with any endpoint, and outbound calls go through the
  shared `client` (proxy settings apply).
- [`discovery_url` pointing at an issuer of a provider without DCR] → `400` with the "does not support dynamic client
  registration" message from `AuthorizationServerMetadataService`; the owner registers a static client instead.

## Migration Plan

None for the upgrade: `discovery_url` is a new optional field, and stored services without it read and behave as before.

Rollback is not transparent. `ProxyUtil.MAPPER` keeps Jackson's default `FAIL_ON_UNKNOWN_PROPERTIES`, so an older
Core cannot parse an application blob whose external service has `discovery_url` (`ProxyUtil.java:convertToObject`), the
same as for any field added to a stored entity. Before rolling back, remove `discovery_url` from those services with the
current version; the registered client then reads on the older version as a static one. The PKCE fields and a
`client_secret`-less client already parse on `development`.
