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

import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.DIAL_META_KEYWORD;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.PROPERTY_KIND;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.PROPERTY_KIND_CLIENT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.PROPERTY_KIND_SERVER;

public class DialMetaKeyword implements Keyword {
    @Override
    public String getValue() {
        return DIAL_META_KEYWORD;
    }

    @Override
    public JsonValidator newValidator(SchemaLocation schemaLocation, JsonNodePath evaluationPath,
                                      JsonNode schemaNode, JsonSchema parentSchema, ValidationContext validationContext) {
        return new DialMetaCollectorValidator(schemaLocation, evaluationPath, schemaNode, parentSchema, this, validationContext, false);
    }

    private static class DialMetaCollectorValidator extends BaseJsonValidator {
        private static final ErrorMessageType ERROR_MESSAGE_TYPE = () -> DIAL_META_KEYWORD;

        String propertyKindString;

        public DialMetaCollectorValidator(SchemaLocation schemaLocation, JsonNodePath evaluationPath, JsonNode schemaNode,
                                          JsonSchema parentSchema, Keyword keyword,
                                          ValidationContext validationContext, boolean suppressSubSchemaRetrieval) {
            super(schemaLocation, evaluationPath, schemaNode, parentSchema, ERROR_MESSAGE_TYPE, keyword, validationContext, suppressSubSchemaRetrieval);
            propertyKindString = schemaNode.get(PROPERTY_KIND).asText();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<ValidationMessage> validate(ExecutionContext executionContext, JsonNode jsonNode, JsonNode jsonNode1, JsonNodePath jsonNodePath) {

            CollectorContext collectorContext = executionContext.getCollectorContext();
            ListCollector<String> serverPropsCollector = (ListCollector<String>) collectorContext.getCollectorMap()
                    .computeIfAbsent(PROPERTY_KIND_SERVER, k -> new ListCollector<String>());
            ListCollector<String> clientPropsCollector = (ListCollector<String>) collectorContext
                    .getCollectorMap().computeIfAbsent(PROPERTY_KIND_CLIENT, k -> new ListCollector<String>());
            String propertyName = jsonNodePath.getName(-1);
            if (Objects.equals(propertyKindString, PROPERTY_KIND_SERVER)) {
                serverPropsCollector.combine(List.of(propertyName));
            } else {
                clientPropsCollector.combine(List.of(propertyName));
            }
            return Set.of();
        }
    }
}