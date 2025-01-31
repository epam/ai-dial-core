package com.epam.aidial.core.metaschemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.Format;
import com.networknt.schema.JsonType;
import com.networknt.schema.TypeFactory;
import com.networknt.schema.ValidationContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DialFileFormat implements Format {

    private static final Pattern PATTERN = Pattern.compile("^files/([a-zA-Z0-9]+)/((?:(?:[a-zA-Z0-9()_\\-.~]|%[a-zA-Z0-9]{2})+/?)+)$");

    @Override
    public boolean matches(ExecutionContext executionContext, ValidationContext validationContext, JsonNode value) {
        JsonType nodeType = TypeFactory.getValueNodeType(value, validationContext.getConfig());
        if (nodeType != JsonType.STRING) {
            return false;
        }
        String nodeValue = value.textValue();
        Matcher matcher = PATTERN.matcher(nodeValue);
        return matcher.matches();
    }

    @Override
    public String getName() {
        return "dial-file-encoded";
    }
}
