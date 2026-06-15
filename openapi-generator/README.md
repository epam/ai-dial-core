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
# Generate skeleton from annotations
./gradlew generateOpenApiSkeleton

# Merge skeleton with manual spec
./gradlew mergeOpenApiSpec

# Lint merged spec
./gradlew lintMergedOpenApi

# Check spec is up-to-date
./gradlew checkOpenApiDiff

# Validate spec with etalon
./gradlew publishOpenApiSpec

```

## Annotation Reference

### @ApiOperation

| Parameter | Type | Description |
|---|---|---|
| `method` | String | HTTP method: GET, POST, PUT, DELETE, etc. |
| `path` | String | Endpoint path with parameters: `/v1/example/{id}` |
| `operationId` | String | Unique operation identifier |
| `tags` | String[] | API documentation tags |
| `requestBody` | Class | Request DTO class for schema generation |
| `requestBodySchemaRef` | String | External schema reference (name or path) |
| `contentType` | String | Request content type (default: application/json) |
| `responses` | @ApiResponse[] | Response definitions |
| `parameters` | @ApiParameter[] | Query/path/header parameters |
| `responseProfile` | ResponseProfile | Standard error responses preset |

### @ApiParameter

| Parameter | Type | Description |
|---|---|---|
| `name` | String | Parameter name |
| `in` | ParameterIn | Location: PATH, QUERY, or HEADER |
| `required` | boolean | Is parameter required |
| `description` | String | Parameter description |
| `schema` | String | Type: string, integer, boolean, number, array |

### @ApiResponse

| Parameter | Type | Description |
|---|---|---|
| `code` | int | HTTP status code |
| `description` | String | Response description |
| `body` | Class | Response DTO class |
| `schemaRef` | String | External schema reference |
| `contentType` | String | Response content type |

### ResponseProfile Enum

- `AUTHENTICATED_READ` — 401, 403
- `AUTHENTICATED_READ_EXTENDED` — 401, 403, 404
- `AUTHENTICATED_OPS` — 401, 403, 404, 409, 422, 500
- `PUBLIC_WRITE` — 400, 404, 409, 422, 429, 500, 503
- `RESPONSES_API` — 200, 500

### Complete Example

```java
@ApiOperation(
    method = "GET",
    path = "/openai/deployments/{deployment_name}",
    operationId = "getDeployment",
    tags = {"Deployment listing"},
    parameters = {
        @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                description = "Deployment identifier")
    },
    responses = {
        @ApiResponse(code = 200, description = "Success", body = DeploymentData.class)
    },
    responseProfile = ResponseProfile.AUTHENTICATED_READ_EXTENDED
)
public Future<?> getDeployment(String deploymentId) {
    // implementation
}
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

## External Schemas

External schemas allow referencing pre-defined YAML schema files instead of generating schemas from Java DTOs.

### How It Works

When `requestBodySchemaRef` or `@ApiResponse.schemaRef` is used, `ExternalSchemaRegistry` determines if it's a project schema (name only) or external path (starts with `../`, `/`, or `http://`):

**Project schemas**: 
- Loads from `src/main/resources/schemas/{schemaName}.yaml`
- Recursively resolves `$ref` dependencies to other schemas
- Registers in OpenAPI spec as `#/components/schemas/{schemaName}`
- Schema name = filename without extension

**External schemas**: 
- Path preserved as-is in final spec (not loaded at build time)
- Consumer resolves the reference

### Project Schemas (by name)

Reference schemas from the resources directory by name only:

```java
@ApiOperation(
    method = "POST",
    path = "/v1/chat/completions",
    requestBodySchemaRef = "CreateChatCompletionRequest"  // references schemas/CreateChatCompletionRequest.yaml
)
```

Generated as: `#/components/schemas/CreateChatCompletionRequest`

### Truly External Schemas (by path)

Schemas outside the project use explicit paths (preserved as-is in final spec, not resolved at build time):

```java
@ApiResponse(
    code = 200,
    schemaRef = "../external-schemas/SharedType.yaml"  // path preserved as-is
)
```

**Important:** Use schema names for project schemas, not paths like `./schemas/MySchema.yaml`.

## Schema Generation

### Polymorphic Types

Use `@ApiSubTypes` on parent DTO:

```java
@ApiSubTypes({
    @ApiSubTypes.Type(value = TypeA.class, name = "type_a"),
    @ApiSubTypes.Type(value = TypeB.class, name = "type_b")
})
public interface BaseType { }
```

Generates `oneOf` schema with discriminator.

### Schema Naming

- Simple classes: `ClassName`
- Nested classes: `OuterInner`
- Maps: `MapStringObject`

### Handling Maps and Collections

Maps generate schemas with `additionalProperties`. Collections use array `items`.

## Merge Behavior

When merging skeleton (generated) and manual (hand-written) specs:

- **Manual wins**: descriptions, summaries, examples, `x-` extensions
- **Skeleton wins**: types, properties, parameters, schemas, requestBody
- **New endpoints** from skeleton are added with `x-generated: true`
- **Removed endpoints** still in manual are preserved with `x-orphaned: true`

## Developer Workflows

### Adding a New Endpoint

1. Annotate controller method with `@ApiOperation`
2. Add controller to `AnnotationEndpointCollector` scan list (if new controller)
3. Run `./gradlew generateOpenApiSkeleton mergeOpenApiSpec`
4. Add descriptions to manual spec if needed
5. Run `./gradlew replaceSpec` to update `docs/open_api_core.yaml`
6. Commit the updated spec: `git add docs/open_api_core.yaml && git commit -m "Update OpenAPI spec"`
7. Push to git to publish the new version

### Modifying a DTO

1. Change Java class (add/remove/rename fields)
2. Run `./gradlew generateOpenApiSkeleton mergeOpenApiSpec`
3. Run `./gradlew :openapi-generator:test` to verify
4. Run `./gradlew replaceSpec` to update `docs/open_api_core.yaml`
5. Commit and push: `git add docs/open_api_core.yaml && git commit -m "Update OpenAPI spec" && git push`

**Note**: The `replaceSpec` task copies the merged spec to `docs/open_api_core.yaml` (committed file). Use `publishOpenApiSpec` to run the full validation pipeline before replacing. **The spec must be committed and pushed to git to be published.**

### Troubleshooting

- **Schema not found**: Check controller class is in `AnnotationEndpointCollector` scan list
- **Merge conflict**: Manual spec structure differs from code - skeleton structure wins
- **Test failures**: Run `./gradlew checkOpenApiDiff` to see differences
- **Validation errors**: Run `./gradlew lintMergedOpenApi` for details