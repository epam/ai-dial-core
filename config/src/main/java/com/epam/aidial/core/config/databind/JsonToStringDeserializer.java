package com.epam.aidial.core.config.databind;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

public class JsonToStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        if (p.getCurrentToken() == JsonToken.VALUE_STRING) {
            return p.getValueAsString();
        }

        return p.readValueAsTree().toString();
    }
}
