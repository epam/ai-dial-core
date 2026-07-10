package com.epam.aidial.core.openapi.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Repeatable(ApiParameters.class)
public @interface ApiParameter {

    String name();

    ParameterIn in();

    boolean required() default false;

    String description() default "";

    String example() default "";

    String[] allowableValues() default {};

    Class<?> schema() default String.class;
}
