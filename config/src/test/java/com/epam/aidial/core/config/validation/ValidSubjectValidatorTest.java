package com.epam.aidial.core.config.validation;

import com.epam.aidial.core.config.policy.PolicySubjectRule;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ValidSubjectValidatorTest {

    private ValidSubjectValidator validator;

    @Mock
    private ConstraintValidatorContext context;
    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder constraintViolationBuilder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(context.buildConstraintViolationWithTemplate(any())).thenReturn(constraintViolationBuilder);
        validator = new ValidSubjectValidator();
    }

    private static PolicySubjectRule rule(String source, PolicySubjectRule.Function function, String... targets) {
        PolicySubjectRule rule = new PolicySubjectRule();
        rule.setSource(source);
        rule.setFunction(function);
        rule.setTargets(List.of(targets));
        return rule;
    }

    @Test
    void acceptsNull() {
        assertTrue(validator.isValid(null, context));
    }

    @Test
    void acceptsEmptyList() {
        assertTrue(validator.isValid(List.of(), context));
    }

    @Test
    void acceptsValidRules() {
        assertTrue(validator.isValid(List.of(rule("roles", PolicySubjectRule.Function.EQUAL, "legal"),
                rule("user_name", PolicySubjectRule.Function.REGEX, "test-user.*")), context));
        verify(context, never()).buildConstraintViolationWithTemplate(any());
    }

    @Test
    void rejectsRuleWithoutSource() {
        assertFalse(validator.isValid(List.of(rule(null, PolicySubjectRule.Function.EQUAL, "test-user")), context));
        verify(context).buildConstraintViolationWithTemplate("every subject rule needs a source");
    }

    @Test
    void escapesTheAdminTextInTheMessage() {
        assertFalse(validator.isValid(List.of(rule("${x}", PolicySubjectRule.Function.REGEX, "(")), context));
        verify(context).buildConstraintViolationWithTemplate("subject rule REGEX on '\\$\\{x\\}' has an invalid pattern '('");
    }
}
