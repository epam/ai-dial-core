# Schema Modeling Guide

How to model request/response schemas using `@ApiSchema`.

## Strategy Selection

Use **exactly one** strategy per `@ApiSchema`:

| Strategy | When to Use | Example |
|---|---|---|
| **Java class** | You have a Java DTO | `implementation = User.class` |
| **Generic type** | Working with `List<T>`, `Map<K,V>`, or custom generics | `implementation = List.class, typeArguments = {User.class}` |
| **External schema** | Complex OpenAPI features or external specs | `schemaRef = "CreateChatCompletionRequest"` |
| **Union (oneOf)** | Request/response can be one of several types | `oneOf = {User.class, Guest.class}` |
| **Intersection (allOf)** | Combining multiple schemas | `allOf = {Model.class, EntityMetadata.class}` |

---

## 1. Java Class (implementation)

Use when you have a Java DTO that models the structure.

### Basic DTOs

```java
@ApiSchema(implementation = User.class)
@ApiSchema(implementation = CreateUserRequest.class)
```

The schema is generated from the Java class fields.

### Primitives

Primitives are inlined with correct OpenAPI types and formats:

```java
@ApiSchema(implementation = String.class)        // string
@ApiSchema(implementation = Integer.class)       // integer (format: int32)
@ApiSchema(implementation = Long.class)          // integer (format: int64)
@ApiSchema(implementation = Boolean.class)       // boolean
@ApiSchema(implementation = Double.class)        // number (format: double)
@ApiSchema(implementation = Float.class)         // number (format: float)
```

### Special Types

```java
@ApiSchema(implementation = byte[].class)        // string (format: binary)
@ApiSchema(implementation = UUID.class)          // string (format: uuid)
@ApiSchema(implementation = Instant.class)       // string (format: date-time)
@ApiSchema(implementation = LocalDate.class)     // string (format: date)
```

---

## 2. Generic Types (implementation + typeArguments)

Use when working with parameterized types.

### Collections

```java
// List<User>
@ApiSchema(implementation = List.class, typeArguments = {User.class})

// Set<Publication>
@ApiSchema(implementation = Set.class, typeArguments = {Publication.class})

// Collection<Item>
@ApiSchema(implementation = Collection.class, typeArguments = {Item.class})
```

Generates OpenAPI array schemas:
```yaml
type: array
items:
  $ref: "#/components/schemas/User"
```

### Maps

```java
// Map<String, Object>
@ApiSchema(implementation = Map.class, typeArguments = {String.class, Object.class})

// Map<String, ModelData>
@ApiSchema(implementation = Map.class, typeArguments = {String.class, ModelData.class})
```

Generates OpenAPI object schemas with `additionalProperties`:
```yaml
type: object
additionalProperties:
  $ref: "#/components/schemas/ModelData"
```

### Custom Generic Wrappers

```java
// ListData<ModelData>
@ApiSchema(implementation = ListData.class, typeArguments = {ModelData.class})

// ItemsResponse<Publication>
@ApiSchema(implementation = ItemsResponse.class, typeArguments = {Publication.class})
```

Generates object schemas (not arrays) with generic-specific structure.

---

## 3. External Schema (schemaRef)

Use when schema exists in a separate YAML file or for complex OpenAPI features.

### Project Schemas

Reference schemas from `src/main/resources/schemas/`:

```java
@ApiSchema(schemaRef = "CreateChatCompletionRequest")
@ApiSchema(schemaRef = "CreateEmbeddingResponse")
```

Generates:
```yaml
$ref: "#/components/schemas/CreateChatCompletionRequest"
```

### External Schemas

Reference schemas outside the project:

```java
@ApiSchema(schemaRef = "../external-api/SharedType.yaml")
@ApiSchema(schemaRef = "/absolute/path/Schema.yaml")
@ApiSchema(schemaRef = "https://example.com/schemas/Type.yaml")
```

Path is preserved as-is in the generated spec.

### Creating External Schema Files

1. Create file in `openapi-generator/src/main/resources/schemas/{SchemaName}.yaml`:

```yaml
type: object
properties:
  name:
    type: string
    minLength: 1
    maxLength: 100
  tags:
    type: array
    items:
      type: string
required:
  - name
```

2. Reference in annotation:

```java
@ApiSchema(schemaRef = "SchemaName")
```

### When to Use External Schemas

Use external schemas for:
- Advanced OpenAPI validation (minLength, pattern, additionalProperties)
- Shared schemas across multiple projects
- Complex discriminators with custom mapping
- OpenAPI features not expressible in Java (conditional schemas, etc.)

Use Java DTOs for:
- Standard CRUD operations
- Type-safe schemas that change with code
- Schemas without advanced validation

---



### Proxy Endpoints

For proxy endpoints, prefer referencing the original upstream request and response schemas using `schemaRef` instead of creating duplicate Java DTOs.

```java
@ApiOperation(
    requestBody = @ApiSchema(schemaRef = "CreateEmbeddingRequest"),
    responses = {
        @ApiResponse(
            code = 200,
            body = @ApiSchema(schemaRef = "CreateEmbeddingResponse")
        )
    }
)
```

If the proxied endpoint accepts or returns arbitrary JSON without a documented contract, use the generic proxy schemas.

```java
@ApiOperation(
    requestBody = @ApiSchema(schemaRef = "ProxyRequest"),
    responses = {
        @ApiResponse(
            code = 200,
            body = @ApiSchema(schemaRef = "ProxyResponse")
        )
    }
)
```

This approach is recommended because it preserves the upstream API contract while avoiding duplicate Java DTOs.

> **Note**
>
> For most media types, no additional configuration is required. The referenced schema is emitted unchanged.
>
> The generator has dedicated processing only for:
>
> - `multipart/form-data`
> - `text/event-stream`
>
> If you need to document another media type, first export its request or response schema into a YAML file and reference it using `schemaRef`. Generator changes are only required when the media type needs custom OpenAPI generation behavior similar to `multipart/form-data` or `text/event-stream`.


---

## 4. Union Types (oneOf)

Use when request/response can be **one of** several types.

### Basic Union

```java
@ApiSchema(oneOf = {User.class, Guest.class, ServiceAccount.class})
```

Generates:
```yaml
oneOf:
  - $ref: "#/components/schemas/User"
  - $ref: "#/components/schemas/Guest"
  - $ref: "#/components/schemas/ServiceAccount"
```

### Single or Batch

```java
@ApiSchema(oneOf = {RpcRequest.class, RpcRequest[].class})
```

Generates:
```yaml
oneOf:
  - $ref: "#/components/schemas/RpcRequest"
  - type: array
    items:
      $ref: "#/components/schemas/RpcRequest"
```

### Mixing Classes and Schema References

```java
@ApiSchema(
        oneOf = {User.class, Guest.class},
        oneOfSchemaRefs = {"ExternalUser", "LegacyUser"}
)
```

Generates:
```yaml
oneOf:
  - $ref: "#/components/schemas/User"
  - $ref: "#/components/schemas/Guest"
  - $ref: "#/components/schemas/ExternalUser"
  - $ref: "#/components/schemas/LegacyUser"
```

---

## 5. Intersection Types (allOf)

Use when combining multiple schemas (composition, adding metadata).

### Basic Intersection

```java
@ApiSchema(allOf = {Model.class, EntityMetadata.class})
```

Generates:
```yaml
allOf:
  - $ref: "#/components/schemas/Model"
  - $ref: "#/components/schemas/EntityMetadata"
```

### Mixing Classes and Schema References

```java
@ApiSchema(
        allOfSchemaRefs = {"ProxyResponse"},
        allOf = {EntityMetadata.class, AuditInfo.class}
)
```

Generates:
```yaml
allOf:
  - $ref: "#/components/schemas/ProxyResponse"
  - $ref: "#/components/schemas/EntityMetadata"
  - $ref: "#/components/schemas/AuditInfo"
```

### Use Cases

**Adding metadata to existing schemas**:
```java
@ApiSchema(allOf = {DeploymentData.class, RuntimeMetadata.class})
```

**Extending external schemas**:
```java
@ApiSchema(allOfSchemaRefs = {"BaseResponse"}, allOf = {CustomFields.class})
```

---

## Binary Uploads (Multipart)

Use `byte[].class` with `contentType = "multipart/form-data"` for file uploads:

```java
@ApiOperation(
        method = "PUT",
        path = "/v1/files/{bucket}/{path}",
        contentType = "multipart/form-data",
        requestBody = @ApiSchema(implementation = byte[].class),
        parameters = {
                @ApiParameter(name = "bucket", in = ParameterIn.PATH, required = true),
                @ApiParameter(name = "path", in = ParameterIn.PATH, required = true)
        },
        responses = {
                @ApiResponse(code = 200, body = @ApiSchema(implementation = FileMetadata.class))
        },
)
```

Generates:
```yaml
requestBody:
  required: true
  content:
    multipart/form-data:
      schema:
        type: object
        properties:
          file:
            type: string
            format: binary
        required:
          - file
```

---

## Streaming Responses (SSE)

Use `contentTypes = {"text/event-stream"}` for Server-Sent Events:

```java
@ApiResponse(
        code = 200,
        description = "Streaming response",
        body = @ApiSchema(schemaRef = "CreateChatCompletionStreamResponse"),
        contentTypes = {"text/event-stream"}
)
```

**Behavior**:
- **Schema references** are automatically wrapped in arrays
- **Primitives** (`String.class`, `Boolean.class`) remain unwrapped

Generates:
```yaml
text/event-stream:
  schema:
    type: array
    items:
      $ref: "#/components/schemas/CreateChatCompletionStreamResponse"
```

### Dual-Mode Endpoints (JSON + Streaming)

```java
responses = {
    @ApiResponse(
        code = 200,
        description = "Non-streaming response",
        body = @ApiSchema(schemaRef = "ChatCompletionResponse"),
        contentTypes = {"application/json"}
    ),
    @ApiResponse(
        code = 200,
        description = "Streaming response",
        body = @ApiSchema(schemaRef = "ChatCompletionStreamResponse"),
        contentTypes = {"text/event-stream"}
    )
}
```

---

## Polymorphic DTOs

For DTOs with subtypes, annotate the parent class with `@ApiSubTypes`:

```java
@ApiSubTypes(
        discriminatorProperty = "type",
        value = {
                @ApiSubType(discriminatorValue = "user", type = UserAccount.class),
                @ApiSubType(discriminatorValue = "service", type = ServiceAccount.class)
        }
)
public interface Account { }
```

Use in annotations:
```java
@ApiSchema(implementation = Account.class)
```

Generates:
```yaml
Account:
  oneOf:
    - $ref: "#/components/schemas/UserAccount"
    - $ref: "#/components/schemas/ServiceAccount"
  discriminator:
    propertyName: type
    mapping:
      user: "#/components/schemas/UserAccount"
      service: "#/components/schemas/ServiceAccount"
```

---

## Best Practices

### DO

- **Use Java DTOs** when possible (type-safe, auto-updates)
- **Use `List.class`** for array responses, not custom array wrappers
- **Use external schemas** for advanced OpenAPI validation
- **Document complex unions** with clear descriptions

### DON'T

- **Mix strategies** (only one of: `implementation`, `schemaRef`, or composition)
- **Duplicate DTOs** for minor variations (use `allOf` instead)
- **Create artificial wrappers** (use `List<T>` directly)
- **Nest deeply** (extract complex schemas to external files)

---

## Common Patterns

### List Endpoint

```java
@ApiResponse(
    code = 200,
    body = @ApiSchema(
        implementation = ListData.class,
        typeArguments = {ModelData.class}
    )
)
```

### Paginated Response

```java
@ApiResponse(
        code = 200,
        body = @ApiSchema(
                implementation = ItemsResponse.class,
                typeArguments = {DeploymentData.class}
        ),
        headers = {
                @ApiHeader(name = "X-Total-Count", schema = Integer.class),
                @ApiHeader(name = "X-Page", schema = Integer.class)
        }
)
```

### Union Request (Single or Batch)

```java
@ApiOperation(
        requestBody = @ApiSchema(oneOf = {RpcRequest.class, RpcRequest[].class}),
        ...
)
```

### Composed Response (Base + Metadata)

```java
@ApiResponse(
        code = 200,
        body = @ApiSchema(allOf = {Model.class, EntityMetadata.class})
)
```

### External Schema Reference

```java
@ApiOperation(
        requestBody = @ApiSchema(schemaRef = "CreateChatCompletionRequest"),
        responses = {
                @ApiResponse(
                        code = 200,
                        body = @ApiSchema(schemaRef = "CreateChatCompletionResponse")
                )
        },
        ...
)
```
