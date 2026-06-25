package com.epam.aidial.core.openapi.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Repeatable(ApiResponses.class)
public @interface ApiResponse {

    int code();

    String description();

    Class<?> body() default Void.class;

    Class<?>[] responseOneOf() default {};

    Class<?>[] responseAllOf() default {};

    Class<?> wrapper() default Void.class;

    String schemaRef() default "";

    String[] contentTypes() default {"application/json"};

    ApiHeader[] headers() default {};
}