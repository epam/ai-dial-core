# Annotation Reference

Detailed reference for OpenAPI annotations.

## @ApiOperation

Declares an API endpoint on a controller method.

### Required Fields

```java
@ApiOperation(
    method = "POST",                    // HTTP method: GET, POST, PUT, DELETE, PATCH
    path = "/v1/resource/{id}",         // Path with {param} placeholders
    operationId = "updateResource",     // Unique identifier (used by client generators)
    tags = {"Resources"},               // Documentation category
    responses = {...},                  // Response definitions (at least one required)
    responseProfile = ResponseProfile.AUTHENTICATED_WRITE  // Standard error preset
)
```

### Optional Fields

```java
@ApiOperation(
    ...,
    requestBody = @ApiSchema(implementation = UpdateRequest.class),  // Request schema
    contentType = "application/json",   // Request content type (default: application/json)
    parameters = {...},                 // Query/path/header parameters
    extensions = {...}                  // OpenAPI vendor extensions (x-*)
)
```

### When to Use

Use `@ApiOperation` on every controller method that should appear in the OpenAPI spec.

### Example

```java
@ApiOperation(
    method = "POST",
    path = "/v1/users",
    operationId = "createUser",
    tags = {"Users"},
    requestBody = @ApiSchema(implementation = CreateUserRequest.class),
    responses = {
        @ApiResponse(code = 201, body = @ApiSchema(implementation = User.class))
    },
    responseProfile = ResponseProfile.AUTHENTICATED_WRITE
)
public Future<?> createUser() { }
```

### ResponseProfile

Pre-defined error response sets to avoid repetition:

| Profile | Status Codes | Use For |
|---|---|---|
| `AUTHENTICATED_READ` | 401, 403 | Protected read endpoints |
| `AUTHENTICATED_READ_EXTENDED` | 401, 403, 404 | Protected read with not-found |
| `AUTHENTICATED_WRITE` | 400, 401, 403, 409, 413, 422, 500 | Protected write operations |
| `CONDITIONAL_WRITE` | 400, 401, 403, 404, 409, 412, 413, 422, 500 | Write with preconditions |
| `PUBLIC_WRITE` | 400, 404, 409, 422, 429, 500, 503 | Public-facing write operations |
| `OPS_WITH_BAD_REQUEST` | 400, 401, 403, 500 | Admin operations |
| `APPLICATION_OPS` | 400, 401, 403, 404, 409, 422, 500, 503 | Application management |
| `ADMIN_READ_ONLY` | 401, 403, 405, 500 | Admin read-only endpoints |

See `ResponseProfile.java` for all available profiles.

---

## @ApiSchema

Universal schema definition for request/response bodies.

### Purpose

Defines the structure of request and response payloads using one of five strategies.

### Strategies

Use **exactly one** strategy per `@ApiSchema`:

1. **Java class**: `implementation`
2. **Generic type**: `implementation` + `typeArguments`
3. **External schema**: `schemaRef`
4. **Union**: `oneOf` / `oneOfSchemaRefs`
5. **Intersection**: `allOf` / `allOfSchemaRefs`

See [Schema Guide](SCHEMAS.md) for detailed modeling strategies.

### Fields

```java
@ApiSchema(
    implementation = User.class,        // Java class (DTO, primitive, array, collection)
    typeArguments = {ModelData.class},  // Generic type parameters
    schemaRef = "ExternalSchema",       // External/project schema reference
    oneOf = {User.class, Guest.class},  // Union type (classes)
    oneOfSchemaRefs = {"Schema1"},      // Union type (schema refs)
    allOf = {Model.class, Metadata.class},  // Intersection type (classes)
    allOfSchemaRefs = {"BaseSchema"},   // Intersection type (schema refs)
    description = "User object",        // Schema description
    nullable = false                    // Allow null (default: false)
)
```

### Examples

```java
// DTO
@ApiSchema(implementation = User.class)

// List
@ApiSchema(implementation = List.class, typeArguments = {User.class})

// External schema
@ApiSchema(schemaRef = "CreateChatCompletionRequest")

// Union
@ApiSchema(oneOf = {RpcRequest.class, RpcRequest[].class})

// Intersection
@ApiSchema(allOf = {Model.class, EntityMetadata.class})
```

---

## @ApiResponse

Defines a response for a specific HTTP status code.

### Fields

```java
@ApiResponse(
    code = 200,                         // Required: HTTP status code
    description = "Success",            // Response description
    body = @ApiSchema(...),             // Response schema
    contentTypes = {"application/json", "text/event-stream"},  // Media types
    headers = {                         // Response headers
        @ApiHeader(name = "X-Total-Count", schema = "integer")
    }
)
```

### When to Use

Define at least one success response (2xx). Error responses are handled by `responseProfile`.

### Examples

**Single content type**:
```java
@ApiResponse(
    code = 200,
    description = "Success",
    body = @ApiSchema(implementation = User.class)
)
```

**Multiple content types** (streaming + non-streaming):
```java
responses = {
    @ApiResponse(
        code = 200,
        description = "Non-streaming",
        body = @ApiSchema(schemaRef = "ChatCompletionResponse"),
        contentTypes = {"application/json"}
    ),
    @ApiResponse(
        code = 200,
        description = "Streaming",
        body = @ApiSchema(schemaRef = "ChatCompletionStreamResponse"),
        contentTypes = {"text/event-stream"}
    )
}
```

---

## @ApiParameter

Documents query, path, or header parameters.

### Fields

```java
@ApiParameter(
    name = "user_id",                   // Required: Parameter name
    in = ParameterIn.QUERY,             // Required: PATH, QUERY, or HEADER
    required = true,                    // Is parameter required (default: false)
    description = "User identifier",    // Parameter description
    schema = String.class,              // Java type (String, Integer, List, etc.)
    format = "uuid",                    // Format hint (uuid, int32, int64, date-time)
    example = "usr_123",                // Example value
    allowableValues = {"active", "inactive"}  // Enum values
)
```

### When to Use

Document all path parameters (always required) and important query/header parameters.

### Examples

**Path parameter**:
```java
@ApiParameter(name = "id", in = ParameterIn.PATH, required = true)
```

**Query parameter with enum**:
```java
@ApiParameter(
    name = "status",
    in = ParameterIn.QUERY,
    schema = String.class,
    allowableValues = {"active", "inactive", "suspended"}
)
```

**Array query parameter**:
```java
@ApiParameter(
    name = "tags",
    in = ParameterIn.QUERY,
    schema = List.class,
    allowableValues = {"important", "urgent", "low-priority"}
)
```

**Header parameter**:
```java
@ApiParameter(
    name = "X-Request-ID",
    in = ParameterIn.HEADER,
    schema = UUID.class
)
```

---

## @ApiHeader

Documents response headers.

### Fields

```java
@ApiHeader(
    name = "X-Total-Count",             // Required: Header name
    description = "Total item count",   // Header description
    required = false,                   // Is header required (default: false)
    schema = "integer"                  // Type: string, integer, boolean
)
```

### When to Use

Document custom response headers that clients should be aware of.

### Example

```java
@ApiResponse(
    code = 200,
    body = @ApiSchema(implementation = ListData.class, typeArguments = {User.class}),
    headers = {
        @ApiHeader(
            name = "X-Total-Count",
            description = "Total number of users",
            schema = "integer"
        ),
        @ApiHeader(
            name = "X-Page",
            description = "Current page number",
            schema = "integer"
        )
    }
)
```

---

## @ApiExtension

Adds OpenAPI vendor extensions (custom metadata).

### Fields

```java
@ApiExtension(
    name = "x-preview",                 // Required: Extension name (must start with x-)
    value = "true"                      // Required: Extension value (auto-typed)
)
```

### Value Types

Values are automatically converted to appropriate types:
- `"true"` / `"false"` → Boolean
- `"123"` → Integer
- `"2.5"` → Double
- `"text"` → String

### When to Use

Add custom metadata for API consumers, tooling, or documentation systems.

### Example

```java
@ApiOperation(
    ...,
    extensions = {
        @ApiExtension(name = "x-preview", value = "true"),
        @ApiExtension(name = "x-version", value = "2.0"),
        @ApiExtension(name = "x-rate-limit", value = "1000"),
        @ApiExtension(name = "x-stability", value = "alpha")
    }
)
```

Generates:
```yaml
/v1/experimental/feature:
  post:
    operationId: experimentalFeature
    x-preview: true
    x-version: 2.0
    x-rate-limit: 1000
    x-stability: alpha
```

---

## Best Practices

### DO

- Use clear, descriptive `operationId` values (`getUser`, not `get1`)
- Always set `responseProfile` (never use `NONE` unless truly necessary)
- Document all parameters with `description` fields
- Use `required = true` for mandatory parameters
- Import annotations from `com.epam.aidial.core.openapi.annotations`

### DON'T

- Mix multiple strategies in `@ApiSchema` (use exactly one)
- Skip error responses (always use appropriate `responseProfile`)
- Use generic tag names (be specific: `Users` not `API`)
- Forget to mark path parameters as `required = true`
- Invent custom extension names without team consensus
