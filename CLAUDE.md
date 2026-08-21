# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

GitHub credentials are required for the private JClouds Maven package (hosted on GitHub Packages):

```bash
# Set credentials via environment variables
export GPR_USERNAME=<github_user>
export GPR_PASSWORD=<github_token>

# Build (skip tests)
./gradlew build -x test

# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :server:test

# Run a single test class
./gradlew :server:test --tests "com.epam.aidial.core.server.ResourceApiTest"

# Run a single test method
./gradlew :server:test --tests "com.epam.aidial.core.server.ResourceApiTest.testWorkflow"

# Checkstyle
./gradlew checkstyleMain checkstyleTest

# Run locally
./gradlew :server:run
```

At runtime, set `AIDIAL_SETTINGS` env var pointing to a JSON settings file (see `sample/aidial.settings.json`).

## Architecture

AI DIAL Core is an **HTTP reverse proxy / API gateway** for LLMs built on **Java 21** + **Eclipse Vert.x** (reactive, non-blocking). It exposes an OpenAI-compatible API and routes requests to backend AI providers (Azure OpenAI, AWS, GCP, etc.).

### Gradle Modules

| Module                 | Root Package                                | Responsibility                                                                                                                                                                                              |
|------------------------|---------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `config`               | `com.epam.aidial.core.config`               | POJOs for the dynamic config (`Config`, `Model`, `Application`, `Key`, `Role`, `Route`, `Interceptor`, `ToolSet`, `Upstream`, `Limit`, …) + JSON validation                                                 |
| `storage`              | `com.epam.aidial.core.storage`              | Blob storage abstraction via Apache JClouds (S3, GCS, Azure Blob, filesystem), Redis-backed resource service, resource descriptors, locking                                                                 |
| `credentials`          | `com.epam.aidial.core.credentials`          | OAuth2 token management, AES credential encryption, KMS integration (AWS/Azure/GCP)                                                                                                                         |
| `server`               | `com.epam.aidial.core.server`               | Entry point (`AiDial.java`), HTTP proxy engine (`Proxy.java`), all controllers, services, security, rate limiting, upstream load balancing, telemetry                                                       |
| `openapi-annotations`  | `com.epam.aidial.core.openapi.annotations`  | Custom annotations for describing OpenAPI operations, schemas, parameters, responses, headers, extensions, and reusable response profiles.                                                                  |
| `openapi-generator`    | `com.epam.aidial.core.openapi`              | Generates and validates the OpenAPI 3.0 specification from controller annotations, builds schemas and responses, assembles the specification, and merges it with the manually maintained OpenAPI document.  |


### Key Server Sub-packages

- **`controller/`** — HTTP request handlers (chat completions, embeddings, file upload, resource CRUD, sharing, publications, toolsets, health check)
- **`service/`** — Business logic (ApplicationService, PublicationService, ShareService, RuleService, ToolSetService, CodeInterpreterService, …)
- **`security/`** — JWT validation (JWKS), API key store, AES encryption service, access control
- **`upstream/`** — Load balancing: tiered + weighted random over upstream endpoints
- **`limiter/`** — Token-based and request-based rate limiting (per-minute/per-day per role)
- **`token/`** — Token usage stats tracking and per-provider response parsing
- **`function/`** — Request/response transformation middleware pipeline
- **`tracing/`** — OpenTelemetry span processing
- **`config/`** — Dynamic config hot-reload (`FileConfigStore`)

### Configuration

Two config files govern the system:

- **Static settings** (`aidial.settings.json`): Vert.x server/client options, blob storage provider, Redis connection, identity providers, encryption keys, metrics endpoints. Not hot-reloaded.
- **Dynamic config** (`aidial.config.json`): models, applications, API keys, roles, interceptors, routes, toolsets, rate-limit pricing. Hot-reloaded at runtime.

See `sample/` for examples of both files; `server/src/main/resources/aidial.settings.json` contains the built-in defaults.

### Tech Stack

- **Java 21**, **Gradle 8**, **Lombok** (via `io.freefair.lombok` plugin)
- **Eclipse Vert.x 4.5** — async HTTP server/client (event-loop model)
- **Apache JClouds** — multi-cloud blob storage
- **Redisson** — Redis client (caching, distributed locks)
- **Jackson 2.18** — JSON serialization
- **Auth0 java-jwt + jwks-rsa** — JWT/JWKS authentication
- **Micrometer** (Prometheus + OTLP) + **OpenTelemetry SDK** — metrics & tracing
- **JUnit 5 + Mockito 5 + Vert.x JUnit 5** — testing; integration tests start the full stack with embedded Redis and OkHttp `MockWebServer` for upstream simulation
- **Checkstyle** — Google Java Style, 180-char line limit (`checkstyle/checkstyle.xml`)

## Code Style

When two rules here conflict, the narrower one wins, and *Scope discipline* beats everything
except correctness. If a rule would push you to change code this issue did not ask about,
don't — mention it in your summary instead.

### Naming

- **Methods are verb phrases; classes and records are nouns.** `resolveServingEndpoint()`
  (`server/.../util/DeploymentEndpointUtil.java`), not `servingEndpoint()`.
- **A `getX()` that returns a different value on each call, or mutates state, is misnamed.**
  Use `resolve*`, `load*`, or `build*`. `get*` on a method that derives or does I/O is
  common here and is not worth renaming on its own.
- **Boolean methods start with `is` or `has`.**
- **Prefer the `Base` prefix for new abstract classes** (`BaseInterceptorController`,
  `BaseFunction`, `BaseAuthSettingsValidator`). Do not rename existing ones — `Deployment`,
  `MetadataBase`, and `MessagesBaseController` are all established.
- **A map variable names its contents,** never `map`, `result`, `data`, or `tmp`. Both house
  idioms are fine: `apiKeysByCanonicalId` / `statsByDeployment`, or `prefixToHash` /
  `userIdToDisplayName`. Do not rename existing maps to fit one form.
- **Never rename a Jackson-bound field or getter in `config`** — the name is the public key
  in `aidial.config.json`. New members follow these conventions; existing ones stay.

### Vert.x

- **Never block an event-loop thread.** Blob storage, JClouds, Redisson, and filesystem
  calls go through `AsyncTaskExecutor.submit(...)`
  (`server/.../vertx/AsyncTaskExecutor.java`) or an existing async service API. One blocking
  call in a controller stalls every in-flight request on that loop.
- **Never hold a lock or enter a `synchronized` block on the event loop.**
- **Propagate failures through the `Future` chain.** Compose with `map`/`compose`/`recover`
  and end every chain in a response or a logged failure handler. Never read `.result()` or
  `.cause()` on a future you have not checked.
- **Treat the hot-reloaded `Config` as immutable and shared** — it is read concurrently from
  every event loop. Never mutate a published `Config`, `Model`, or `Application`.
- **Log with SLF4J placeholders** (`log.info("deployment={}", name)`), and never log an API
  key, JWT, upstream credential, or prompt body.

### Scope discipline

- **Change only what the issue requires.** Reformatting, annotation sweeps, and
  "while I'm here" refactors get reverted in review.
- **Completeness of your own change is in scope; unrelated cleanup is not.** Updating every
  site that switches on an enum you extended is part of the change. Deleting an unrelated
  dormant method is not.
- **Report, don't widen.** If the real fix belongs a layer up, or you find pre-existing dead
  code, say so in your summary. Do not open a follow-up PR unbidden.
- **Do not touch bulk or hot paths opportunistically** — `ResourceService` bulk loads,
  listing, and sync paths change only with a stated performance reason.
- **Moving code changes behaviour.** Relocating a filter, check, or guard past a cache or
  lookup can change what the caller sees, not just where the code sits. Say what it costs.

### Design

- **Search for an existing helper before writing one.** Reuse `ResourceService.listResources`
  and `ResourceAuthSettings.withoutSecrets()` rather than hand-rolling pagination or a second
  redaction path.
- **No new indirection for a single call site.** A utility class or callback parameter used
  exactly once should be inlined.
- **Default to the narrowest visibility,** except where Jackson or Lombok requires access.

### Error handling

- **Do not add speculative error handling to new code.** No `catch` for an exception the code
  cannot throw, no null-check for a value that cannot be null, no recovery branch for an
  impossible state.
- **Do not delete existing guards to satisfy the rule above.** A check you cannot justify from
  the diff may be protecting an input you cannot see.
- **Invalid input on a request path throws** — never silently skip a malformed record. Config
  load and background reconciliation are the exception: one bad record must not fail the whole
  reload.
- **Match the documented status codes** in the OpenAPI spec, and pass an upstream status
  through unchanged rather than remapping it to `502`.

### Completing a cross-cutting change

- **Adding an enum constant means sweeping every site.** `AuthenticationType` is
  `OAUTH, API_KEY, NONE, DIAL_NATIVE` — grep every constant across all modules and decide
  explicitly for each `switch`/`if`. The recurring bug is handling a new constant in nine
  places and missing the tenth.
- **A new controller mirrors its siblings' cross-cutting steps** — `rateLimiter.limit(context,
  deployment)`, the MCP `initialize()` handshake before any call, auth injection, audit
  logging. Read the closest existing controller and match it step for step.
- **Run cheap guards before expensive work.** Rate-limit and permission checks come before key
  assignment, auth injection, or upstream I/O.
- **Validate identically on every write path.** A `PUT` that accepts a name the config rebuild
  later rejects creates resources that vanish on restart — reuse the same pattern constant on
  both sides.
- **Use `tryLockResource` in sweep, GC, and background reconciliation loops;** `lockResource`
  is for request paths. A blocking lock in a sweep stalls the loop, and a skipped resource is
  picked up next run.

### Comments and Javadoc

Comment sparingly — a better name beats a comment. Document only what the signature cannot
carry: a contract the caller cannot infer, or a map whose key and value its name does not
already give. Never restate the signature, and never write a doc longer than the method.

### Tests

- **Every new class with behaviour gets a unit test** covering each branch, not just the happy
  path. Records, DTOs, and config POJOs do not need one.
- **Assert against the real code path.** A test that asserts on a string literal instead of
  calling the production method proves nothing.
- **Test cross-replica behaviour with a foreign `senderPodId`,** not a real multi-pod setup.
  `MergedConfigStore.onResourceEvent` drops self-events, so build a `ResourceEvent` with
  another pod's id and drive `applyReplicaEvent` directly — see
  `MergedConfigStoreReplicaUpdateTest`.

### Documentation

- **`docs/open_api_core.yaml` is generated and auto-committed by CI** (`./gradlew replaceSpec`
  in `.github/workflows/pr.yml`). Never hand-edit it — change the controller annotations.
- **A new header, endpoint, or config flag is not done until it is documented** in the
  relevant `docs/*.md`.
