# Plugin classloader POC

This branch implements the proposed sibling-loader runtime for review-environment testing. It is not the final
OpenSpec implementation. Proposal/design are in `openspec/changes/plugin-shared-classloaders/`; spec deltas and
implementation tasks remain intentionally undrafted until deployment evidence is available.

## Runtime

- JVM application classpath: bootstrap launcher only.
- Shared: approved API packages plus logging/telemetry implementations and their runtime dependency closure.
- Core: Core classes and remaining private dependencies, parented by Shared.
- Each enabled plugin directory: its own loader, with a filtered view of Shared and no Core-private fallback.
- Plugin service descriptors stay directory-private. Core scopes TCCL at plugin invocation boundaries and restores
  it on synchronous return; plugins still own asynchronous propagation where needed.
- Bundled shared classes are ignored with one bounded startup warning. Missing shared classes never fall back to
  plugin copies.
- The #2383 rule adding `kotlin-reflect` beside Core's stdlib is removed. Plugins bundle their own complete Kotlin
  runtime. Shared still has stdlib through its exporter dependencies, but that copy is hidden from plugins.

Installed layout:

```text
bootstrap/     launcher jar
shared/        shared runtime jars
core/          Core code and private dependencies
bin/server     unchanged operator command, now launches bootstrap
plugins/       optional <group>/<plugin>/ directories
```

`runtime-partition.txt` in the distribution lists each resolved artifact and its partition. The build checks that
Shared/Core form a complete, disjoint jar partition and both generated launch scripts use bootstrap only.

## Build and deploy

Use JDK 25 and the repository's existing package credentials:

```bash
./gradlew :server:verifyRuntimeDistribution :server:distTar
```

Local distribution: `server/build/install/server/`.
Archive: `server/build/distributions/server-0.0.0.tar`.
Local launch: `AIDIAL_SETTINGS=<settings.json> ./server/build/install/server/bin/server`.
`:server:run` also uses the installed bootstrap runtime.

Build the branch through the existing review-environment functionality. The repository Dockerfile extracts the
split distribution without a new deployment setting. No image was published from the local POC session.

Mount/copy the real model-provider plugin jar into `/app/plugins/custom/model-provider/`, one jar copy only. Do not
mount over all of `/app`, `plugins/`, or the Shared/Core directories. The plugin API must match the target Core
release. Existing plugin directory include/exclude settings remain unchanged.

## Review-environment exercise

Keep the environment's existing authentication, Redis, storage and telemetry settings. Add provider and translator
entries and models using the actual review upstream/credential references. The following fragment illustrates the
shape; replace placeholders before use:

```json
{
  "plugins": {
    "provider-poc": {"type": "model-provider", "settings": {"adapter": "openai"}},
    "translator-poc": {
      "type": "translator",
      "settings": {"direction": "anthropicMessagesToOpenaiResponses"}
    }
  },
  "translators": {
    "translation-poc": {
      "in": "anthropicMessages",
      "out": "openaiResponses",
      "baseUrl": "dial-plugin://translator-poc"
    }
  },
  "models": {
    "responses-poc": {
      "type": "chat",
      "overrideName": "<upstream-model>",
      "interfaces": {
        "openaiResponses": {"base_url": "dial-plugin://provider-poc"},
        "anthropicMessages": {"base_url": "dial-plugin://provider-poc"}
      },
      "upstreams": [{
        "id": "review-responses-poc",
        "endpoint": "<provider-responses-url>",
        "responsesEndpoint": "<provider-responses-url>",
        "key": "<existing-credential-reference>"
      }]
    }
  }
}
```

Here `endpoint` serves the plugin's Messages-to-Responses translation, while `responsesEndpoint` supplies the
Responses interface's upstream header. An upstream `id` is required. Typed upstream interfaces can be used instead
of these legacy endpoint fields. Grant the testing role access/limits through the environment's normal config.
For the Core translator path, change that model's `anthropicMessages` entry to
`{"mode":"translator","translator":"translation-poc"}`.

Exercise:

1. Startup and `/health`; logs name both real plugin providers and `custom/model-provider`.
2. Native Messages passthrough against a Messages endpoint.
3. Responses passthrough against a Responses endpoint.
4. Messages-to-Responses translation within the provider plugin.
5. The Core translator contract calling the Responses interface in process.
6. Streaming, tool calls, cancellation and actual cloud-auth/native paths used by the environment.
7. Structured JSON logging, shared trace continuity and export to the configured collector.
8. Restart/config changes, plugin creation/closing, missing dependencies and bundled-shared warnings.

Translation must use a response shape accepted by the current provider SDKs. For a fake Responses origin,
`usage.input_tokens_details` includes both `cached_tokens` and `cache_write_tokens`; omission caused the local
fixture's initial translation failure. This is the inspected provider's requirement, not a new Core validation.

## Local evidence and limits

- A real installed-runtime smoke passed Core health, Messages passthrough, Responses passthrough, both translation
  paths, and receipt of OTLP trace payloads by a local HTTP collector before removing #2383's metadata rule.
- After removing the rule: 100 targeted server tests and 4 bootstrap tests passed; repository Checkstyle passed;
  distribution verification/build passed. The installed manifest and jar inventory contain no `kotlin-reflect`.
- The real-plugin smoke also passed against the rebuilt distribution without `kotlin-reflect`: Core health,
  Messages and Responses passthrough, provider-owned translation, Core translator calls, and receipt of OTLP trace
  payloads. This closes the local workaround-removal check; review-environment coverage remains separate.
- The broader `ConfigPluginApiTest` attempt failed during fixture initialization because the bundled macOS Redis
  executable requires an unavailable OpenSSL 3 library; ten test failures were environment setup failures, not
  classloader assertions. The direct runtime smoke uses an isolated local `redis:7-alpine` container instead.
- Full CI suite, review deployment, real cloud credentials, streaming/native behaviour and Windows execution have
  not been verified locally. Do not infer those from the targeted tests.

Local smoke helper/evidence are scratch files, not production dependencies. The helper starts only a new
localhost-bound Redis container, local fake upstream/OTLP collector and the installed Core process, then stops its
own processes/container. It does not change existing services or disable JWT verification. Requests use a local test
API key; the fixture declares a JWKS-based identity provider with verification left enabled.

## POC restrictions to check before rollout

- Split startup supplies the default Core JSON layout and appenders programmatically. Custom
  `logback.configurationFile` is explicitly rejected; arbitrary custom XML configurations are not supported here.
- The split SDK is process-owned and registered with shared `GlobalOpenTelemetry`. Already-registered global SDKs
  or Java-agent overrides are not supported by this POC; early context initialization cannot be repaired. Telemetry
  setting changes require process restart.
- Shared's dependency closure is physically larger than its plugin-visible API. Plugins must bundle private SDKs,
  Kotlin and Jackson extension modules rather than borrow Shared's hidden libraries.
- A native library can still conflict across loaders; this POC is dependency isolation, not process isolation.
- URLClassLoader can inspect manifest `Class-Path` entries, but the plugin loader rejects definitions/resources from
  unlisted jars. An unlisted manifest class shadowing a later listed jar can fail lookup rather than retry that jar.
- Directory provider fixtures in tests contain actual class bytes; registration-only fixtures cannot rely on
  Core-private provider classes through the strict plugin loader.

Roll back the Core image and plugin artifacts together to the previous compatible pair. Configuration/storage
formats are unchanged, but private dependency selection differs from the old parent-first runtime.
