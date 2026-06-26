package com.epam.aidial.core.openapi.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ApiOperations.class)
public @interface ApiOperation {
    String method();
    String path();
    String operationId();
    ApiSchema requestBody() default @ApiSchema;
    String[] tags() default {};
    String contentType() default "application/json";
    ApiParameter[] parameters() default {};
    ApiResponse[] responses() default {};
    ResponseProfile responseProfile() default ResponseProfile.NONE;
}
