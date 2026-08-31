package com.epam.aidial.core.config.validation;

import com.epam.aidial.core.config.Condition;
import com.epam.aidial.core.config.Operator;
import com.epam.aidial.core.config.StandardField;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidConditionValidator implements ConstraintValidator<ValidCondition, Condition> {

    @Override
    public boolean isValid(Condition value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        Object conditionValue = value.getValue();
        if (!(conditionValue instanceof String) && !(conditionValue instanceof Number)) {
            return fail(context, "Condition value must be a string or a number");
        }

        String field = value.getField();
        Operator operator = value.getOperator();
        if (field != null && operator != null && operator.isOrdering() && !field.startsWith("$")) {
            StandardField standardField = StandardField.fromFieldName(field).orElse(null);
            if (standardField != null && standardField.isString()) {
                return fail(context, "Ordering operator '" + operator.getSymbol()
                        + "' is not valid against string-typed standard field '" + field + "'");
            }
        }

        return true;
    }

    private static boolean fail(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
