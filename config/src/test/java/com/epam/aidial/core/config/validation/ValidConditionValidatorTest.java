package com.epam.aidial.core.config.validation;

import com.epam.aidial.core.config.Condition;
import com.epam.aidial.core.config.Operator;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

class ValidConditionValidatorTest {

    private ValidConditionValidator validator;

    @Mock
    private ConstraintValidatorContext context;
    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder constraintViolationBuilder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doNothing().when(context).disableDefaultConstraintViolation();
        when(context.buildConstraintViolationWithTemplate(any())).thenReturn(constraintViolationBuilder);
        validator = new ValidConditionValidator();
    }

    private static Condition condition(String field, Operator operator, Object value) {
        Condition condition = new Condition();
        condition.setField(field);
        condition.setOperator(operator);
        condition.setValue(value);
        return condition;
    }

    @Test
    void isValidReturnsTrueWhenConditionIsNull() {
        assertTrue(validator.isValid(null, context));
    }

    @Test
    void rejectsOrderingOperatorAgainstServiceTier() {
        assertFalse(validator.isValid(condition("serviceTier", Operator.GT, "flex"), context));
    }

    @Test
    void rejectsOrderingOperatorAgainstTtl() {
        assertFalse(validator.isValid(condition("ttl", Operator.LE, "5m"), context));
    }

    @Test
    void allowsOrderingOperatorAgainstNumericStandardField() {
        assertTrue(validator.isValid(condition("promptTokens", Operator.GT, 200000), context));
        assertTrue(validator.isValid(condition("cachedReadTokens", Operator.GE, 0), context));
        assertTrue(validator.isValid(condition("cachedWriteTokens", Operator.LT, 100), context));
    }

    @Test
    void allowsEqualityOperatorAgainstStringStandardField() {
        assertTrue(validator.isValid(condition("serviceTier", Operator.EQ, "flex"), context));
        assertTrue(validator.isValid(condition("ttl", Operator.NE, "1h"), context));
    }

    @Test
    void allowsOrderingOperatorAgainstJsonPathField() {
        assertTrue(validator.isValid(condition("$.usage.server_tool_use.web_search_requests", Operator.GT, 0), context));
    }

    @Test
    void rejectsNonStringNonNumberValue() {
        assertFalse(validator.isValid(condition("promptTokens", Operator.EQ, List.of("bad")), context));
    }
}
