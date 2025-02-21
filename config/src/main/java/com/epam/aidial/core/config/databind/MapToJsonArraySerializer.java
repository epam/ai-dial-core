package com.epam.aidial.core.config.databind;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

public class MapToJsonArraySerializer extends JsonSerializer<Map<URI, String>> {

    @Override
    public void serialize(Map<URI, String> uriStringMap, JsonGenerator jsonGenerator,
                          SerializerProvider serializerProvider) throws IOException {
        jsonGenerator.writeStartArray();
        boolean firstEntry = true;
        for (Map.Entry<URI, String> entry : uriStringMap.entrySet()) {
            if (firstEntry) {
                firstEntry = false;
            } else {
                jsonGenerator.writeRaw(",");
            }
            jsonGenerator.writeRaw(entry.getValue());
        }
        jsonGenerator.writeEndArray();
    }
}
