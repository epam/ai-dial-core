# OpenAPI Generator

Generates OpenAPI 3.0 specification from annotated controller methods in AI DIAL Core.

## Why custom annotations?

The generator uses custom annotations instead of the standard OpenAPI/Swagger annotations because AI DIAL Core does not follow the conventional **one controller method = one OpenAPI operation** model.

A single controller method may describe multiple endpoints via `@ApiOperation`, each with its own HTTP method, path, parameters, responses, and extensions. Standard OpenAPI annotations are designed for a one-to-one mapping and cannot represent this structure without duplicating controller methods or introducing additional abstraction.


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
    }
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

1. Annotate controller method.
2. Run `./gradlew lintMergedOpenApi`.
3. Review `build/generated/openapi-merged.yaml`.
4. Run `./gradlew replaceSpec`.
5. Commit `docs/open_api_core.yaml`.

`build/generated/openapi-merged.yaml` is a temporary build artifact generated during the build.

`docs/open_api_core.yaml` is also generated. It is committed to the repository because it serves as the project's OpenAPI contract, allowing API changes to be reviewed in pull requests and consumed by downstream tools.

## Validation

Before committing changes, validate the generated specification:

```bash
./gradlew lintMergedOpenApi
```

The generator validates that:

- required `@ApiOperation` fields (`method`, `path`, `operationId`) are present;
- each `(HTTP method, path)` combination is unique;
- path parameters match `@ApiParameter(in = PATH)` declarations;
- response codes are valid HTTP status codes and `(code, contentType)` combinations are unique;
- `@ApiSchema` uses a single schema definition strategy;
- every `@ApiOperation(method, path)` matches a registered route in `ControllerSelector`;
- every `operationId` is unique.
- the path field must follow the standard OpenAPI path template syntax. Only path parameters enclosed in curly braces ({}) are supported. Regular expressions and other custom path patterns are rejected during validation.

Path values must follow the standard OpenAPI path template syntax (for example, `/v1/users/{id}`). Regular expressions and custom path patterns are not supported.

If validation fails:

1. Read the reported validation error.
2. Update the corresponding annotation or schema definition.
3. Regenerate the specification:
   ```bash
   ./gradlew lintMergedOpenApi
   ```
4. Run validation again.

## Documentation

- [Annotation Guide](ANNOTATIONS.md) - Detailed annotation reference
- [Schema Guide](SCHEMAS.md) - Schema modeling strategies

## Troubleshooting

**Schema not generated**: Check controller is in `AnnotationEndpointCollector.CONTROLLER_CLASSES`

**Validation errors**: Run `./gradlew lintMergedOpenApi` for details

**Missing imports**: Import from `com.epam.aidial.core.openapi.annotations`
