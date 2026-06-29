# OpenAPI Generator

Generates OpenAPI 3.0 specification from annotated controller methods in AI DIAL Core.

## Quick Start

Annotate a controller method:

```java
@ApiOperation(
    method = "GET",
    path = "/v1/users/{id}",
    operationId = "getUser",
    tags = {"Users"},
    parameters = {
        @ApiParameter(name = "id", in = ParameterIn.PATH, required = true)
    },
    responses = {
        @ApiResponse(code = 200, body = @ApiSchema(implementation = User.class))
    },
    responseProfile = ResponseProfile.AUTHENTICATED_READ_EXTENDED
)
public Future<?> getUser(String id) { }
```

Generate OpenAPI spec:

```bash
./gradlew mergeOpenApiSpec
```

## Annotations

| Annotation | Purpose |
|---|---|
| `@ApiOperation` | Declares an API endpoint |
| `@ApiSchema` | Defines request/response schema |
| `@ApiParameter` | Documents query/path/header parameters |
| `@ApiResponse` | Defines response for a status code |
| `@ApiExtension` | Adds OpenAPI vendor extensions (`x-*`) |
| `@ApiHeader` | Documents response headers |

See [Annotation Guide](ANNOTATIONS.md) for detailed reference.

## Schema Strategies

| Strategy | Example |
|---|---|
| Java class | `@ApiSchema(implementation = User.class)` |
| Generic type | `@ApiSchema(implementation = List.class, typeArguments = {User.class})` |
| External schema | `@ApiSchema(schemaRef = "CreateChatCompletionRequest")` |
| Union (oneOf) | `@ApiSchema(oneOf = {User.class, Guest.class})` |
| Intersection (allOf) | `@ApiSchema(allOf = {Model.class, EntityMetadata.class})` |

See [Schema Guide](SCHEMAS.md) for modeling strategies.

## Gradle Tasks

```bash
./gradlew mergeOpenApiSpec           # Generate spec
./gradlew lintMergedOpenApi          # Lint spec
./gradlew replaceSpec                # Update committed spec
./gradlew :openapi-generator:test    # Run tests
```

## Workflow

1. Annotate controller method
2. Run `./gradlew mergeOpenApiSpec`
3. Review `build/generated/openapi-merged.yaml`
4. Run `./gradlew replaceSpec`
5. Commit `docs/open_api_core.yaml`

## Documentation

- [Annotation Guide](ANNOTATIONS.md) - Detailed annotation reference
- [Schema Guide](SCHEMAS.md) - Schema modeling strategies

## Troubleshooting

**Schema not generated**: Check controller is in `AnnotationEndpointCollector.CONTROLLER_CLASSES`

**Validation errors**: Run `./gradlew lintMergedOpenApi` for details

**Missing imports**: Import from `com.epam.aidial.core.openapi.annotations`
