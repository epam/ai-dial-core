package com.epam.aidial.core.config.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ReportAsSingleViolation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;

/**
 * A list of subject rules, checked as a policy assignment's subject is. An empty list is valid and matches nobody.
 */
@Documented
@Constraint(validatedBy = { ValidSubjectValidator.class })
@Target({ FIELD })
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
public @interface ValidSubject {
    String message() default "Invalid subject rules";
    Class<?>[] groups() default {};
    Class<? extends jakarta.validation.Payload>[] payload() default {};
}
