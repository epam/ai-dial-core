package com.epam.aidial.core.credentials.databind;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * Accepts a JSON string, or, for interoperability with non-compliant servers, a single-element
 * JSON array whose only element is a string, unwrapping it to that scalar value.
 *
 * <p>RFC 9728 defines the {@code resource} field of Protected Resource Metadata as a single JSON
 * string, but some MCP servers (e.g. GitLab) emit it as a one-element array. A genuine
 * multi-element array is still rejected, since the spec permits only one value.
 */
public class SingleElementArrayOrStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.START_ARRAY) {
            String value = null;
            int count = 0;
            while (p.nextToken() != JsonToken.END_ARRAY) {
                count++;
                if (count > 1) {
                    throw ctx.weirdStringException(p.getText(), String.class,
                            "expected a single-element array or a string value");
                }
                value = p.getValueAsString();
            }
            return value;
        }
        return p.getValueAsString();
    }
}