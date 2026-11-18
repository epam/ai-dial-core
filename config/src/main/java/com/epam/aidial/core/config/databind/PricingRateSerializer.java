package com.epam.aidial.core.config.databind;

import com.epam.aidial.core.config.PricingRate;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

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

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("test", value.getTest());
        if (value.getIfTrue() != null) {
            node.put("ifTrue", value.getIfTrue());
        }
        if (value.getIfFalse() != null) {
            node.put("ifFalse", value.getIfFalse());
        }
        serializers.defaultSerializeValue(node, gen);
    }
}
