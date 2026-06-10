package com.epam.aidial.cli.service.template;

import com.epam.aidial.cli.exception.TemplateException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionEvaluatorTest {

    private ExpressionEvaluator evaluator(Map<String, Object> ctx) {
        return new ExpressionEvaluator(ctx);
    }

    @Test
    void andHasHigherPrecedenceThanOr() {
        ExpressionEvaluator e = evaluator(Map.of());
        assertFalse(e.evaluate("false || true && false"));
        assertTrue(e.evaluate("true || true && false"));
        assertTrue(e.evaluate("true && true || false"));
    }

    @Test
    void shortCircuitAndDoesNotThrowOnRhs() {
        ExpressionEvaluator e = evaluator(Map.of());
        // Right-hand side references a missing namespace; if &&'s short-circuit works, we don't throw.
        assertFalse(e.evaluate("false && ${vars.MISSING} == 'x'"));
    }

    @Test
    void shortCircuitOrDoesNotThrowOnRhs() {
        ExpressionEvaluator e = evaluator(Map.of());
        // OR short-circuit: if LHS is true the RHS isn't evaluated.
        assertTrue(e.evaluate("true || ${vars.MISSING} == 'x'"));
    }

    @Test
    void stringEqualityAndInequality() {
        ExpressionEvaluator e = evaluator(Map.of("vars", Map.of("x", "abc")));
        assertTrue(e.evaluate("${vars.x} == 'abc'"));
        assertFalse(e.evaluate("${vars.x} != 'abc'"));
        assertTrue(e.evaluate("${vars.x} != 'xyz'"));
    }

    @Test
    void notNegation() {
        ExpressionEvaluator e = evaluator(Map.of());
        assertFalse(e.evaluate("!true"));
        assertTrue(e.evaluate("!false"));
        assertTrue(e.evaluate("!(false || false)"));
    }

    @Test
    void compoundOrAndPrecedence() {
        ExpressionEvaluator e = evaluator(Map.of());
        assertEquals(true, e.evaluate("true || false && false"));
        assertEquals(false, e.evaluate("false || false && true"));
        assertEquals(true, e.evaluate("(false || true) && true"));
    }

    @Test
    void malformedExpressionThrows() {
        ExpressionEvaluator e = evaluator(Map.of());
        assertThrows(TemplateException.class, () -> e.evaluate("(("));
        assertThrows(TemplateException.class, () -> e.evaluate("true &&"));
    }
}
