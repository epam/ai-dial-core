package com.epam.aidial.core.config.databind;

import com.epam.aidial.core.config.LocalizedValue;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * Serializes a {@link LocalizedValue} as a plain string when it holds a single value (either a
 * plain string, or a map already collapsed to one entry - see {@link LocalizedValue#normalize}),
 * and as a {@code locale -> value} object when two or more locales are present. This serializer
 * is locale-agnostic: any default-locale collapsing must happen before the value reaches it.
 */
public class LocalizedValueSerializer extends JsonSerializer<LocalizedValue> {

    @Override
    public void serialize(LocalizedValue value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (!value.isMap()) {
            gen.writeString(value.getPlainValue());
            return;
        }

        if (value.getLocaleMap().size() == 1) {
            gen.writeString(value.getLocaleMap().values().iterator().next());
            return;
        }

        gen.writeStartObject();
        for (var entry : value.getLocaleMap().entrySet()) {
            gen.writeStringField(entry.getKey(), entry.getValue());
        }
        gen.writeEndObject();
    }
}
