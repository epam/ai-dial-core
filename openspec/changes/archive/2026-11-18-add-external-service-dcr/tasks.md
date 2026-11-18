# Tasks

Checks at the end of every group: `./gradlew checkstyleMain checkstyleTest` and the test classes named in the group
with `--tests`, under a JDK 21 `JAVA_HOME`.

## 1. Shared OAuth setup entry point (credentials module)

- [x] 1.1 Add the
      `processResourceAuthSettings(resourceId, endpoint, updated, existing, endpointAsResource)` overload on
      `ResourceAuthSettingsService`; make the `SecuredResource` method delegate with toolset options (D1, D2). Verify:
      `ToolSetApiTest`, `ToolSetRepairApiTest` and the existing `ResourceAuthSettingsService` unit tests pass unchanged.
- [x] 1.2 Pass `endpointAsResource` through `ResourceRegistrationService.register` to
      `DynamicResourceRegistrationStrategy.resolveResource` (D3). Verify: a unit test of the strategy — with `false`
      and no protected resource metadata the registration has no `resource`; with `true` it is the endpoint.
- [x] 1.3 A null endpoint skips `register`; the static client gets PKCE only from its own `code_challenge_method`.
      With an endpoint, the toolset static strategy runs unchanged. Verify: unit test — null endpoint: no metadata
      call, `code_challenge` set only when the method is given; endpoint: `code_challenge_method`, scopes and
      `resource` filled from metadata.

## 2. External service model and validation

- [x] 2.1 Add `discoveryUrl` to `ResourceAuthSettings` (D9; forbidden for non-OAUTH types, rejected for toolsets, kept
      when omitted on update); validate an absolute `http(s)` URL in `ExternalServiceValidation`. Verify: "Discovery URL
      is stored and returned", "Relative discovery URL rejected", "Discovery URL on another type or on a toolset"
      in `ExternalServiceManagementApiTest` (new or existing management test class).
- [x] 2.2 Replace the hardcoded `CREATE_STATIC_CLIENT` with the shared mode decision; a create in dynamic mode without
      `endpoint` is `400`. Verify: scenario "Neither client nor endpoint", and a DCR body with `token_endpoint` is
      `400`.

## 3. Write paths

- [x] 3.1 `ExternalServiceService.processOnWrite` and `putExternalService` call the new entry point per service
      (resource id `<app url>/external_services/<id>`), replacing `preserveOmittedSecrets` (D1, D5). Verify:
      "Create registers a client", "Registration through the application write", "Authorization server without
      registration", "Update does not register again", "Static client without discovery URL makes no network call", "Static
      client with discovery URL reads metadata", "Static client with failing metadata URLs", "Static client with invalid
      metadata" — MockWebServer request counts asserted.
- [x] 3.2 Same for `UserExternalServiceService.put`, replacing `preserveOmittedSecret`. Verify: one user-authored
      create-registers test and one round-trip test.
- [x] 3.3 `hasSecret` in both services covers `code_verifier` (D4). Verify: "Public client verifier stays secret" —
      raw blob read shows the verifier encrypted; application `GET`, deployment `GET` and external-service `GET` omit
      it; a blob written before the change (plaintext verifier) still reads.
      Done in `ExternalServiceDcrApiTest.testPublicClientVerifierEncryptedAtRestAndNeverReturned`. The legacy case
      cannot occur for external services: before this change none had a `code_verifier` (the static validation forbids
      it in a body and nothing generated one), and `ResourceAuthSettingsEncryptionService` keeps its plaintext fallback.
- [x] 3.4 Update merge. Verify: "GET then PUT round trip", "Blank resource clears it", "Type change keeps nothing";
      plus the resource-dependencies approval stays after a round trip on an approved application (existing approval
      test pattern).

- [x] 3.5 Redirect URIs of a registered client (D8): `RegistrationPolicy.EXTERNAL_SERVICE`. Verify: unit tests in
      `DynamicResourceRegistrationStrategyTest` (unlisted body redirect and empty list rejected before any call);
      `ExternalServiceDcrApiTest` "Unlisted redirect URI not registered", listed redirect registers, "Sign-in with an
      allowed redirect URI", "Sign-in with an unlisted redirect URI".
- [x] 3.6 A blank `code_challenge_method` means "no PKCE" in the shared code (found while reading the admin panel,
      which sends `""` for "None"; without this the new PKCE path threw on such a body). Verify:
      `ResourceAuthSettingsServiceTest` blank-method tests; `ExternalServiceDcrApiTest.testAdminPanelStaticBodyWithBlankFieldsAccepted`.

## 4. PKCE end to end

- [x] 4.1 Verify: "Registered client gets PKCE" and "Static client asks for PKCE" — sign-in through
      `/v1/ops/external-service/signin` sends `code_verifier` in the token request (MockWebServer body assertion) in
      `ExternalServiceCredentialsApiTest`.

## 5. Config files and admin validate

- [x] 5.1 Verify: "Config-file service without a client" — config-loaded application drops the service, no request
      reaches the MockWebServer (`ConfigPostProcessor` test or an `*ApiTest` with a config file).
- [x] 5.2 Verify D6: `POST /v1/admin/validate` with a dynamic external service sends no registration request.

## 6. Docs and API

- [x] 6.1 OpenAPI: `discovery_url` on `ResourceAuthSettings`, described as in D9, create
      without a client documented; regenerate and validate (`openapi-syncer`). Verify: the OpenAPI generator check passes.
      The generator ignores field-level `@ApiSchema(description)`, so the `discovery_url` property and `putExternalService`
      descriptions are written in `docs/open_api_core.yaml`, which the merge keeps; `validateOpenApiSpec` passes.
- [x] 6.2 README: `toolsets.security.allowedRedirectUris` also applies to external-service registrations; the
      external-service docs section (or `docs/dynamic-settings/` page that covers it) describes `discovery_url` with the same
      definition as 6.1, DCR and PKCE. Verify: links resolve, text matches the spec.
- [x] 6.3 `npx -y @fission-ai/openspec@1.14.3 validate add-external-service-dcr --strict` passes.

## Workflow follow-up

- `/review-issue`, then `review.md` in this folder.
- Archive (`openspec archive add-external-service-dcr -y`, `validate --specs --strict`), one commit with explicit paths.
