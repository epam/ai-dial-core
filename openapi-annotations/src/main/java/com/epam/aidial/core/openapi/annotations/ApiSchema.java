package com.epam.aidial.core.openapi.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Universal schema definition for OpenAPI - single source of truth.
 * Pure Java-centric design: use Java types for all schemas, external files for complex edge cases.
 *
 * <p><b>Primary strategies (mutually exclusive at top level):</b>
 * <ol>
 *   <li>implementation - Java class (DTO, primitive, collection)
 *   <li>schemaRef - External/project schema reference
 *   <li>oneOf/allOf - Composition of schemas
 * </ol>
 *
 * <p><b>Arrays via Java Collections:</b>
 * <pre>
 * // Array of DTOs
 * {@literal @}ApiSchema(implementation = List.class, typeArguments = {User.class})
 * → type: array, items: {$ref: User}
 *
 * // Array of primitives
 * {@literal @}ApiSchema(implementation = List.class, typeArguments = {String.class})
 * → type: array, items: {type: string}
 * </pre>
 *
 * <p><b>Primitives with Automatic Format Generation:</b>
 * <pre>
 * // Binary content (replaces OpenApiBinary.class)
 * {@literal @}ApiSchema(implementation = byte[].class)
 * → type: string, format: binary
 *
 * // UUID
 * {@literal @}ApiSchema(implementation = UUID.class)
 * → type: string, format: uuid
 *
 * // Timestamps
 * {@literal @}ApiSchema(implementation = Instant.class)
 * → type: string, format: date-time
 *
 * // Integers with formats
 * {@literal @}ApiSchema(implementation = Long.class)
 * → type: integer, format: int64
 * </pre>
 *
 * <p><b>Other Examples:</b>
 * <pre>
 * // Java DTO
 * {@literal @}ApiSchema(implementation = ChatRequest.class)
 *
 * // Generic wrapper (generates object schema)
 * {@literal @}ApiSchema(implementation = ListData.class, typeArguments = {ModelData.class})
 *
 * // External schema
 * {@literal @}ApiSchema(schemaRef = "CreateChatCompletionResponse")
 *
 * // Composition
 * {@literal @}ApiSchema(allOfSchemaRefs = {"BaseSchema"}, allOf = {Extension.class})
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.ANNOTATION_TYPE, ElementType.TYPE})
public @interface ApiSchema {

    // ========== Primary Schema Definition ==========

    /**
     * Java class for schema generation.
     * Handles DTOs, primitives, and collections automatically.
     *
     * <p><b>DTOs:</b> Delegates to DtoSchemaGenerator to produce component schema.
     *
     * <p><b>Primitives with formats:</b> Generator automatically adds OpenAPI formats:
     * <ul>
     *   <li>byte[].class → type: string, format: binary
     *   <li>UUID.class → type: string, format: uuid
     *   <li>Instant.class → type: string, format: date-time
     *   <li>LocalDate.class → type: string, format: date
     *   <li>Long.class/long → type: integer, format: int64
     *   <li>Integer.class/int → type: integer, format: int32
     * </ul>
     *
     * <p><b>Arrays:</b> Use List.class, Set.class, or Collection.class with typeArguments.
     */
    Class<?> implementation() default Void.class;

    /**
     * Type arguments for generic implementation class.
     *
     * <p>Examples:
     * <ul>
     *   <li>implementation = List.class, typeArguments = {User.class} → array of User
     *   <li>implementation = ListData.class, typeArguments = {ModelData.class} → generic object wrapper
     *   <li>implementation = Map.class, typeArguments = {String.class, Integer.class} → Map&lt;String, Integer&gt;
     * </ul>
     */
    Class<?>[] typeArguments() default {};

    /**
     * External or project schema reference.
     * Project schemas (no path separators) loaded from resources/schemas/
     * External schemas (../, /, http://) preserved as-is.
     *
     * <p>For arrays with complex constraints (e.g., array of external schema),
     * define external schema file and reference it here.
     */
    String schemaRef() default "";

    // ========== Composition ==========

    /**
     * oneOf composition - Class<?> alternatives (union type).
     * Each class generates a schema reference in oneOf array.
     */
    Class<?>[] oneOf() default {};

    /**
     * oneOf composition - schemaRef alternatives.
     * Combined with oneOf classes into oneOf schema.
     */
    String[] oneOfSchemaRefs() default {};

    /**
     * oneOf composition - entries that need generic type arguments (e.g. Map&lt;String, String&gt;),
     * which a bare {@code Class<?>} in {@link #oneOf()} cannot express.
     */
    ApiSchemaType[] oneOfTypes() default {};

    /**
     * allOf composition - Class<?> elements (intersection/extension).
     * Each class generates a schema reference in allOf array.
     */
    Class<?>[] allOf() default {};

    /**
     * allOf composition - schemaRef elements.
     * Combined with allOf classes into allOf schema.
     */
    String[] allOfSchemaRefs() default {};

    // ========== Metadata ==========

    /**
     * Schema description (optional).
     */
    String description() default "";

    /**
     * Whether schema allows null values (optional).
     */
    boolean nullable() default false;
}
