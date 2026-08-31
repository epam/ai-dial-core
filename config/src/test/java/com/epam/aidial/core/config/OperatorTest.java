package com.epam.aidial.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorTest {

    @ParameterizedTest
    @EnumSource(value = Operator.class, names = {"GT", "LT", "GE", "LE"})
    void orderingOperatorsAreOrdering(Operator operator) {
        assertTrue(operator.isOrdering());
    }

    @ParameterizedTest
    @EnumSource(value = Operator.class, names = {"EQ", "NE"})
    void equalityOperatorsAreNotOrdering(Operator operator) {
        assertFalse(operator.isOrdering());
    }

    @Test
    void symbolsMatchWireFormat() {
        assertEquals("==", Operator.EQ.getSymbol());
        assertEquals("!=", Operator.NE.getSymbol());
        assertEquals(">", Operator.GT.getSymbol());
        assertEquals("<", Operator.LT.getSymbol());
        assertEquals(">=", Operator.GE.getSymbol());
        assertEquals("<=", Operator.LE.getSymbol());
    }
}
