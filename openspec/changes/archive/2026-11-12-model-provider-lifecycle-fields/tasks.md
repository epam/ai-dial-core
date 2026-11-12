# Tasks

## 1. Model fields

- [x] 1.1 Add `provider`, `deprecationDate` and `endOfService` to `Model` (snake_case aliases for the dates) and to
  `ModelData`; fill them in `ModelController` and `AliasPasses.modelView`.
- [x] 1.2 Tests: `ListingTest` (`testProvider`, `testDeprecationDate`, `testEndOfService`: scenario "Listing of a model
  with the fields"), `ModelWriteApiTest#testPutKeepsProviderAndLifecycleDates` ("Written through the Configuration
  API"), `AliasApiTest#aliasWithoutPresentAsTakesTheTargetTypeAndDisplayFields` ("Provider and lifecycle dates of the
  target"). "Listing of a model without the fields" holds for every other model in `ListingTest`'s config.
- [x] 1.3 Docs: `docs/dynamic-settings/models.md`, `docs/open_api_core.yaml`, `sample/aidial.config.json`.
- [ ] 1.4 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew :server:test --tests
  "com.epam.aidial.core.server.ListingTest" --tests "com.epam.aidial.core.server.ModelWriteApiTest" --tests
  "com.epam.aidial.core.server.AliasApiTest"` (deferred: needs a JDK 21 and GitHub Packages credentials, run in CI).

## 2. Integration checks

- [x] 2.1 `openspec validate model-provider-lifecycle-fields --strict` passes.

## Workflow follow-up

- Write `review.md`, archive the change, then `openspec validate --specs --strict`.
