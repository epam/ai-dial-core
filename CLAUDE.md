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

Use comments sparingly. Only comment complex code.
