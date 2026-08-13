package com.epam.aidial.core.openapi.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A Java type with optional generic type arguments, used as a {@link ApiSchema#oneOfTypes()}
 * entry when a oneOf alternative needs generics (e.g. {@code Map<String, String>}), which a bare
 * {@code Class<?>} in {@link ApiSchema#oneOf()} cannot express.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface ApiSchemaType {
    Class<?> implementation();
    Class<?>[] typeArguments() default {};
}
