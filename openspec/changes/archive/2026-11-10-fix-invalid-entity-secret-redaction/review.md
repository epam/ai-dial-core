# Review: fix-invalid-entity-secret-redaction

A record of what was done and checked. It is not an independent or three-lens review. Issue #2253.

## Shipped

- `ConfigResourceController.projectInvalidItem` redacts the copied payload of an invalid toolset
  (`auth_settings`) and application (`external_services.*.auth_settings`) with `redactAuthSettingsSecrets` and
  `redactExternalServiceSecrets`, always with `revealHint` `false`: no `client_secret`, `code_verifier` or
  `client_secret_hint` in the response. It never touches `record.getPayload()`, which `resurrectInvalidModels` reads.
- Those two helpers take a parameter named `revealHint` instead of `admin`; the valid-branch call sites still pass
  `admin`.
- `InvalidEntityApiTest` has two new tests and a small assertion helper.

Dropped during the change: matching the camelCase aliases (`authSettings`, `clientSecret`, and the rest). No Core write
path stores camelCase, so only a hand-written blob would carry it. The proposal, spec delta and design say so.

## Checked

Run with JDK 21 from the Gradle cache.

| Check | Result |
|---|---|
| `checkstyleMain`, `checkstyleTest` | pass (last run after the final code change) |
| `InvalidEntityApiTest` (2 new tests) | 9/9, run after the final code change |
| `ToolSetApiTest` | 77/77 |
| `PlatformAppToolsetApiTest` | 19/19 |
| `ExternalServiceCredentialsApiTest` | 69/69 |
| `EncryptedBlobRebuildTest` | 3/3 |
| `openspec validate fix-invalid-entity-secret-redaction --strict` | valid |

The last four classes ran after the camelCase removal and before the `admin` to `revealHint` rename. That rename changes
a parameter name only, and those classes were not rerun after it. The full `./gradlew test` was not run; it runs in CI.

Red check: before the fix, the new tests were seen failing, with the stored `clientSecret` ciphertext in the response
(3 failed, 7 passed in `InvalidEntityApiTest`; that run still had the camelCase test, later removed).

Scenario tests: Toolset secret that does not decrypt
(`InvalidEntityApiTest#testInvalidToolSetOmitsAuthSettingsSecrets`), Application external service secret that does
not decrypt (`InvalidEntityApiTest#testInvalidApplicationOmitsExternalServiceSecrets`).

Docs: `grep -rn "invalid" docs --include=*.md --include=*.yaml` found nothing that describes an invalid toolset or
application GET, so no doc changed. The hits: `docs/dynamic-settings/aliases.md:174` and `docs/open_api_core.yaml:3377`
describe an invalid alias, which has no secrets; `docs/open_api_core.yaml:3461` is the alias write error, and
`docs/open_api_core.yaml:4353` is the application creation note on invalid `application_properties`.

## Not covered

- A hand-written blob with camelCase keys whose secret does not decrypt still returns its ciphertext in the invalid
  GET. Accepted, see design.md "Snake_case keys only".
- The application scenario uses `client_secret` snake_case only, as the spec says; the `external_services` path is read
  by `redactExternalServiceSecrets` in the same way for any valid snake_case blob.
- Only an admin GET is tested. A non-admin GET of the config entity URL is not exercised here; the redaction does not
  depend on the caller, since `revealHint` is `false`.
- Secrets in free-form fields, such as an application's `application_properties`, are not redacted. Out of scope.
- Reaching the invalid state through a real system import of an archive without `encryption-keys/` was not run. The
  tests put a blob whose secret does not decrypt directly into storage.
