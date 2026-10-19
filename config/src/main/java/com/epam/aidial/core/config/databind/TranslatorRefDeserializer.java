package com.epam.aidial.core.config.databind;

import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.config.TranslatorRef;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * Reads {@code interfaces.<type>.translator} in either shape it is written: a string naming a
 * {@code translators} entry, or an object defining one inline.
 */
public class TranslatorRefDeserializer extends JsonDeserializer<TranslatorRef> {

    @Override
    public TranslatorRef deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        if (p.getCurrentToken() == JsonToken.VALUE_STRING) {
            return TranslatorRef.named(p.getValueAsString());
        }

        return TranslatorRef.inline(p.readValueAs(Translator.class));
    }
}
