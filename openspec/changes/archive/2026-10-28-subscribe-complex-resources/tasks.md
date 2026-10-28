## 1. Events and subscription

- [x] 1.1 Add SKILL and PROJECT to `ResourceTypes.EVENT_TYPES`; drop internal complex events in `ResourceService`
- [x] 1.2 Publish logical resource and file events from `ComplexResourceService`
- [x] 1.3 Fan file events out to resource subscribers in `ResourceTopic`
- [x] 1.4 Accept SKILL and PROJECT in `ResourceOperationController`
- [x] 1.5 Tests: `ResourceTopicSubscribeAllTest`, `SkillResourceApiTest` (one per scenario)
- [x] 1.6 `./gradlew checkstyleMain checkstyleTest` and the touched test classes with `--tests`

## 2. Docs

- [x] 2.1 Update the subscribe description in `docs/open_api_core.yaml`
- [x] 2.2 `npx -y @fission-ai/openspec@1.14.3 validate subscribe-complex-resources --strict`
