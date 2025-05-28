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
import java.util.Set;

import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.DIAL_FILE_KEYWORD;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.DIAL_META_KEYWORD;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.PROPERTY_KIND;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.PROPERTY_KIND_SERVER;

public class DialFileKeyword implements Keyword {
    @Override
    public String getValue() {
        return DIAL_FILE_KEYWORD;
    }

    @Override
    public JsonValidator newValidator(SchemaLocation schemaLocation, JsonNodePath evaluationPath,
                                      JsonNode schemaNode, JsonSchema parentSchema, ValidationContext validationContext) {
        return new DialFileCollectorValidator(schemaLocation, evaluationPath, schemaNode, parentSchema, this, validationContext, false);
    }

    private static class DialFileCollectorValidator extends BaseJsonValidator {
        private static final ErrorMessageType ERROR_MESSAGE_TYPE = () -> DIAL_FILE_KEYWORD;

        private final Boolean value;
        private final Boolean isServerProp;

        private static JsonNode findMetaNode(JsonSchema schema) {
            JsonNode metaNode = schema.getSchemaNode().get(DIAL_META_KEYWORD);
            if (metaNode != null) {
                return metaNode;
            }
            JsonSchema parentSchema = schema.getParentSchema();
            if (parentSchema != null) {
                return findMetaNode(parentSchema);
            }
            return null;
        }

        public DialFileCollectorValidator(SchemaLocation schemaLocation, JsonNodePath evaluationPath, JsonNode schemaNode,
                                          JsonSchema parentSchema, Keyword keyword,
                                          ValidationContext validationContext, boolean suppressSubSchemaRetrieval) {
            super(schemaLocation, evaluationPath, schemaNode, parentSchema, ERROR_MESSAGE_TYPE, keyword, validationContext, suppressSubSchemaRetrieval);
            this.value = schemaNode.booleanValue();
            JsonNode metaNode = findMetaNode(parentSchema);
            JsonNode propertyKindNode = (metaNode != null) ? metaNode.get(PROPERTY_KIND) : null;
            this.isServerProp = (propertyKindNode != null) && propertyKindNode.asText().equalsIgnoreCase(PROPERTY_KIND_SERVER);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<ValidationMessage> validate(ExecutionContext executionContext, JsonNode jsonNode, JsonNode jsonNode1, JsonNodePath jsonNodePath) {
            if (Boolean.TRUE.equals(value)) {
                CollectorContext collectorContext = executionContext.getCollectorContext();
                ListCollector<String> fileCollector = (ListCollector<String>) collectorContext.getCollectorMap()
                        .computeIfAbsent(ListCollector.FileCollectorType.ALL_FILES.getValue(), k -> new ListCollector<String>());
                String nodeValue = jsonNode.asText();
                if (nodeValue == null || nodeValue.isEmpty()) {
                    return Set.of();
                }
                fileCollector.combine(List.of(nodeValue));
                if (Boolean.TRUE.equals(isServerProp)) {
                    ListCollector<String> serverFileCollector = (ListCollector<String>) collectorContext.getCollectorMap()
                            .computeIfAbsent(ListCollector.FileCollectorType.ONLY_SERVER_FILES.getValue(), k -> new ListCollector<String>());
                    serverFileCollector.combine(List.of(nodeValue));
                }
            }
            return Set.of();
        }
    }
}
