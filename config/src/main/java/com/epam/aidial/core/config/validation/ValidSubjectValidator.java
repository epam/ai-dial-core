package com.epam.aidial.core.config.validation;

import com.epam.aidial.core.config.policy.PolicyAssignmentValidator;
import com.epam.aidial.core.config.policy.PolicySubjectRule;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;

public class ValidSubjectValidator implements ConstraintValidator<ValidSubject, List<PolicySubjectRule>> {

    @Override
    public boolean isValid(List<PolicySubjectRule> value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        String error = PolicyAssignmentValidator.validateSubject(value);
        if (error == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        // the message quotes the admin's source and pattern: escape it so it is not read as a message template
        context.buildConstraintViolationWithTemplate(error.replaceAll("([\\\\{}$])", "\\\\$1")).addConstraintViolation();
        return false;
    }
}
