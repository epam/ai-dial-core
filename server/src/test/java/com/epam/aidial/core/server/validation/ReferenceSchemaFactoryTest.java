package com.epam.aidial.core.server.validation;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaException;
import com.networknt.schema.JsonSchemaFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReferenceSchemaFactoryTest {

    private static final JsonSchemaFactory FACTORY = ReferenceSchemaFactory.of(JsonMetaSchema.getV7());

    @Test
    void testRefInsideTheDocumentIsEvaluatedAlsoWhenTheSchemaHasAnAbsoluteId() {
        JsonSchema schema = FACTORY.getSchema("""
                {"$id":"https://dial.example/schemas/a","type":"object","properties":{"a":{"$ref":"#/definitions/text"}},
                 "definitions":{"text":{"type":"string"}}}""");

        assertEquals(1, schema.validate("{\"a\":1}", InputFormat.JSON).size());
        assertEquals(0, schema.validate("{\"a\":\"x\"}", InputFormat.JSON).size());
    }

    @Test
    void testRefOutsideTheDocumentIsRefusedWithoutBeingLoaded() {
        assertThrows(JsonSchemaException.class, () -> FACTORY.getSchema("""
                {"type":"object","properties":{"a":{"$ref":"http://refused.invalid/ref.json"}}}""")
                .validate("{\"a\":\"x\"}", InputFormat.JSON));
    }
}
