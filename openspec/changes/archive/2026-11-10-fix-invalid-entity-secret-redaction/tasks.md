# Tasks

## 1. Redact auth-settings secrets in the invalid-entity GET

- [x] 1.1 In `InvalidEntityApiTest`, add `testInvalidToolSetOmitsAuthSettingsSecrets` (scenario "Toolset secret that does
  not decrypt") and `testInvalidApplicationOmitsExternalServiceSecrets` (scenario "Application external service secret
  that does not decrypt"). Each puts a platform blob with a Base64 `client_secret` (and `code_verifier`) long enough to
  pass the length check but not the AES-GCM check, reloads, sends the admin GET, and asserts `status: "invalid"`, a
  surviving sibling field (`client_id`, `display_name`), no secret or hint key, and that the body does not contain the
  stored ciphertext. Verify they fail on the unchanged `ConfigResourceController`.
- [x] 1.2 In `ConfigResourceController.projectInvalidItem`, after the payload copy is built, call
  `redactAuthSettingsSecrets(node, false)` for `TOOL_SET` and `redactExternalServiceSecrets(node, false)` for
  `APPLICATION`, on the copy only and never on `record.getPayload()`. The helpers and `ResourceAuthSettings` stay as
  they are. Verify the two tests of 1.1 pass.
- [x] 1.3 Check `docs/` and `docs/open_api_core.yaml` for text that says what an invalid toolset or application GET
  returns (`grep -rn "invalid" docs --include=*.md --include=*.yaml`); none is expected to change. Verify by listing
  the hits and what each says in the commit message or review.md.
- [x] 1.4 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.InvalidEntityApiTest" --tests "com.epam.aidial.core.server.ToolSetApiTest" --tests
  "com.epam.aidial.core.server.PlatformAppToolsetApiTest" --tests
  "com.epam.aidial.core.server.ExternalServiceCredentialsApiTest" --tests
  "com.epam.aidial.core.server.EncryptedBlobRebuildTest"`. `ToolSetApiTest`, `PlatformAppToolsetApiTest` and
  `ExternalServiceCredentialsApiTest` assert the valid-branch redaction and hint, so they pass unchanged.

## 2. Integration checks

- [x] 2.1 Run `npx -y @fission-ai/openspec@1.14.3 validate fix-invalid-entity-secret-redaction --strict`; it passes.

## Workflow follow-up

- Write `review.md` in the change folder, archive with `npx -y @fission-ai/openspec@1.14.3 archive
  fix-invalid-entity-secret-redaction -y`, then `validate --specs --strict`; commit with explicit paths.
