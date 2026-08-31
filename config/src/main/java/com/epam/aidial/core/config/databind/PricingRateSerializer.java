package com.epam.aidial.core.config.databind;

import com.epam.aidial.core.config.PricingRate;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * Serializes a {@link PricingRate} as a plain rate string when it's the flat leaf shape, and as a
 * {@code {test, ifTrue, ifFalse}} decision-tree node object otherwise.
 */
public class PricingRateSerializer extends JsonSerializer<PricingRate> {

    @Override
    public void serialize(PricingRate value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value.isLeaf()) {
            gen.writeString(value.getRate());
            return;
        }

        gen.writeStartObject();
        gen.writeObjectField("test", value.getTest());
        writeBranch(gen, serializers, "ifTrue", value.getIfTrue());
        writeBranch(gen, serializers, "ifFalse", value.getIfFalse());
        gen.writeEndObject();
    }

    private static void writeBranch(JsonGenerator gen, SerializerProvider serializers, String name, PricingRate branch)
            throws IOException {
        if (branch != null) {
            gen.writeFieldName(name);
            serializers.defaultSerializeValue(branch, gen);
        }
    }
}
