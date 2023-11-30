package com.epam.aidial.core.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;

public class DoubleStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        if (p.getCurrentToken() != JsonToken.VALUE_STRING) {
            throw InvalidFormatException.from(p, "Expected a JSON string", p.getText(), String.class);
        }

        String value = p.getValueAsString();
        try {
            Double.parseDouble(value);
            return value;
        } catch (NumberFormatException e) {
            throw InvalidFormatException.from(p, "Expected a JSON string with a valid double", value, String.class);
        }
    }
}
