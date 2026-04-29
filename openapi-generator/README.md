# OpenAPI Generator

Generates an OpenAPI 3.0 skeleton spec from annotated controller methods in `ai-dial-core`, then merges it with a hand-maintained spec to produce the final API documentation.

Merged and manually enriched OpenAPI 3.0 spec is used then on the public API docs website, which is powered by [Redocly](https://redocly.com/).

## How It Works

1. **Annotation Scanning** — `AnnotationEndpointCollector` scans `@ApiOperation` annotations on controller methods to discover all API endpoints (method, path, request/response types, tags).
2. **Schema Generation** — `DtoSchemaGenerator` uses [victools/jsonschema-generator](https://github.com/victools/jsonschema-generator) to produce JSON Schema definitions from Java DTOs, then converts them to OAS 3.0 component schemas.
3. **Spec Assembly** — `SpecAssembler` combines endpoints and schemas into a complete OpenAPI 3.0 YAML skeleton.
4. **Spec Merging** — `SpecMerger` merges the generated skeleton with a manually maintained spec, preserving hand-written descriptions, examples, and extensions while updating structural elements from code.

## Gradle Tasks

```bash
# Generate the skeleton spec from code
./gradlew generateOpenApiSkeleton

# Merge skeleton with the manual spec
./gradlew mergeOpenApiSpec
```

## Adding a New Endpoint

Annotate the controller method with `@ApiOperation`:

```java
@ApiOperation(
    method = "GET",
    path = "/v1/example/{id}",
    operationId = "getExample",
    responseBody = Example.class,
    tags = {"Examples"}
)
```

For methods handling multiple endpoints, use `@ApiOperations` (the `@Repeatable` container).

Then add the controller class to the scan list in `AnnotationEndpointCollector` if it's not already there.

## Key Components

| Class | Role |
|---|---|
| `@ApiOperation` | Annotation on controller methods declaring endpoint metadata |
| `AnnotationEndpointCollector` | Scans annotations and produces endpoint descriptors |
| `DtoSchemaGenerator` | Converts Java DTOs to OAS 3.0 schemas |
| `SpecAssembler` | Builds the full OpenAPI skeleton from endpoints + schemas |
| `SpecMerger` | Merges generated skeleton with hand-maintained spec |
| `OpenApiSkeletonGenerator` | CLI entry point for skeleton generation |

## Merge Behavior

When merging skeleton (generated) and manual (hand-written) specs:

- **Manual wins**: descriptions, summaries, examples, `x-` extensions
- **Skeleton wins**: types, properties, parameters, schemas, requestBody
- **New endpoints** from skeleton are added with `x-generated: true`
- **Removed endpoints** still in manual are preserved with `x-orphaned: true`
