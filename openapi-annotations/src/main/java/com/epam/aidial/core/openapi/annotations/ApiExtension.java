package com.epam.aidial.core.openapi.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * OpenAPI vendor extension (x-*).
 * Allows adding custom metadata to operations via the OpenAPI extensions mechanism.
 *
 * <p>Extension names must start with "x-" per OpenAPI specification.
 * Values are strings but can represent booleans, numbers, or complex JSON via serialization.
 *
 * <p>Example:
 * <pre>
 * &#64;ApiExtension(name = "x-preview", value = "true")
 * &#64;ApiExtension(name = "x-version", value = "2.0")
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiExtension {
    /**
     * Extension name. Must start with "x-".
     */
    String name();

    /**
     * Extension value as string. Can represent boolean, number, or structured data.
     * For structured values, use JSON string representation.
     */
    String value();
}
