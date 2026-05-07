package com.epam.aidial.core.config.databind;

import com.epam.aidial.core.config.IpAddressRange;
import com.epam.aidial.core.config.IpAddressRanges;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.util.List;

public class IpAddressRangeDeserializer extends JsonDeserializer<IpAddressRanges> {

    @Override
    public IpAddressRanges deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JacksonException {
        JsonNode root = jsonParser.getCodec().readTree(jsonParser);
        if (!root.isArray()) {
            throw InvalidFormatException.from(jsonParser, "Expected a JSON array of client IP ranges", root.toString(), List.class);
        }
        IpAddressRanges ranges = new IpAddressRanges();
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            if (!node.isTextual()) {
                throw InvalidFormatException.from(jsonParser, "Expected a JSON string of client IP range", node.toString(), String.class);
            }
            ranges.getRanges().add(IpAddressRange.parseCidr(node.textValue()));
        }
        return ranges;
    }
}
