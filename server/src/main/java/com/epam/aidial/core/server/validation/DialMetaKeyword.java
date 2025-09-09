package com.epam.aidial.core.server.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.BaseJsonValidator;
import com.networknt.schema.CollectorContext;
import com.networknt.schema.ErrorMessageType;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonValidator;
import com.networknt.schema.Keyword;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.ValidationContext;
import com.networknt.schema.ValidationMessage;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class DialMetaKeyword implements Keyword {
    @Override
    public String getValue() {
        return "dial:meta";
    }

    @Override
    public JsonValidator newValidator(SchemaLocation schemaLocation, JsonNodePath evaluationPath,
                                      JsonNode schemaNode, JsonSchema parentSchema, ValidationContext validationContext) {
        return new DialMetaCollectorValidator(schemaLocation, evaluationPath, schemaNode, parentSchema, this, validationContext, false);
    }

    private enum PropertyKind {
        CLIENT("client"), SERVER("server");
        private final String value;

        PropertyKind(String value) {
            this.value = value;
        }
    }

    private static class DialMetaCollectorValidator extends BaseJsonValidator {
        private static final ErrorMessageType ERROR_MESSAGE_TYPE = () -> "dial:meta";

        String propertyKindString;

        public DialMetaCollectorValidator(SchemaLocation schemaLocation, JsonNodePath evaluationPath, JsonNode schemaNode,
                                          JsonSchema parentSchema, Keyword keyword,
                                          ValidationContext validationContext, boolean suppressSubSchemaRetrieval) {
            super(schemaLocation, evaluationPath, schemaNode, parentSchema, ERROR_MESSAGE_TYPE, keyword, validationContext, suppressSubSchemaRetrieval);
            propertyKindString = schemaNode.get("dial:propertyKind").asText();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<ValidationMessage> validate(ExecutionContext executionContext, JsonNode jsonNode, JsonNode jsonNode1, JsonNodePath jsonNodePath) {

            CollectorContext collectorContext = executionContext.getCollectorContext();
            ListCollector<String> allPropsCollector = (ListCollector<String>) collectorContext.getCollectorMap()
                    .computeIfAbsent(ListCollector.MetaDataCollectorType.ALL.getValue(), k -> new ListCollector<String>());
            ListCollector<String> clientPropsCollector = (ListCollector<String>) collectorContext
                    .getCollectorMap().computeIfAbsent(ListCollector.MetaDataCollectorType.CLIENT.getValue(), k -> new ListCollector<String>());
            String propertyName = jsonNodePath.getName(-1);
            if (Objects.equals(propertyKindString, PropertyKind.CLIENT.value)) {
                clientPropsCollector.combine(List.of(propertyName));
            }
            allPropsCollector.combine(List.of(propertyName));
            return Set.of();
        }
    }
}