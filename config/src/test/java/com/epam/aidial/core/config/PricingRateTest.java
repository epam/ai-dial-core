package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingRateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void deserializesFlatRateString() throws Exception {
        PricingRate rate = MAPPER.readValue("\"0.0000003\"", PricingRate.class);
        assertTrue(rate.isLeaf());
        assertEquals("0.0000003", rate.getRate());
    }

    @Test
    void deserializesDecisionTreeNode() throws Exception {
        String json = """
                {
                  "test": { "field": "promptTokens", "operator": ">", "value": 200000 },
                  "ifTrue": "0.0000006",
                  "ifFalse": "0.0000003"
                }
                """;
        PricingRate rate = MAPPER.readValue(json, PricingRate.class);
        assertFalse(rate.isLeaf());
        assertEquals("promptTokens", rate.getTest().getField());
        assertEquals(Operator.GT, rate.getTest().getOperator());
        assertEquals(200000, rate.getTest().getValue());
        assertTrue(rate.getIfTrue().isLeaf());
        assertEquals("0.0000006", rate.getIfTrue().getRate());
        assertTrue(rate.getIfFalse().isLeaf());
        assertEquals("0.0000003", rate.getIfFalse().getRate());
    }

    @Test
    void deserializesNestedDecisionTree() throws Exception {
        String json = """
                {
                  "test": { "field": "promptTokens", "operator": ">", "value": 200000 },
                  "ifTrue": {
                    "test": { "field": "ttl", "operator": "==", "value": "1h" },
                    "ifTrue": "0.000012"
                  }
                }
                """;
        PricingRate rate = MAPPER.readValue(json, PricingRate.class);
        assertFalse(rate.isLeaf());
        PricingRate ifTrue = rate.getIfTrue();
        assertFalse(ifTrue.isLeaf());
        assertEquals("ttl", ifTrue.getTest().getField());
        assertEquals("0.000012", ifTrue.getIfTrue().getRate());
        assertNull(ifTrue.getIfFalse());
        assertNull(rate.getIfFalse());
    }

    @Test
    void rejectsNonDoubleLeafString() {
        assertThrows(InvalidFormatException.class, () -> MAPPER.readValue("\"not-a-number\"", PricingRate.class));
    }

    @Test
    void rejectsNodeMissingTest() {
        assertThrows(Exception.class, () -> MAPPER.readValue("{\"ifTrue\": \"0.1\"}", PricingRate.class));
    }

    @Test
    void serializesLeafAsPlainString() throws Exception {
        PricingRate rate = new PricingRate();
        rate.setRate("0.0000003");
        assertEquals("\"0.0000003\"", MAPPER.writeValueAsString(rate));
    }

    @Test
    void roundTripsNestedTree() throws Exception {
        String json = """
                {"test":{"field":"promptTokens","operator":">","value":200000},"ifTrue":"0.000012","ifFalse":"0.0000075"}""";
        PricingRate rate = MAPPER.readValue(json, PricingRate.class);
        String written = MAPPER.writeValueAsString(rate);
        PricingRate roundTripped = MAPPER.readValue(written, PricingRate.class);
        assertEquals(rate.getTest().getField(), roundTripped.getTest().getField());
        assertEquals(rate.getIfTrue().getRate(), roundTripped.getIfTrue().getRate());
        assertEquals(rate.getIfFalse().getRate(), roundTripped.getIfFalse().getRate());
    }
}
