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

/**
 * The class is deprecated in favor of {@link DialResourceKeyKeyword}.
 */
@Deprecated
public class DialFileKeyword implements Keyword {
    @Override
    public String getValue() {
        return "dial:file";
    }

    @Override
    public JsonValidator newValidator(SchemaLocation schemaLocation, JsonNodePath evaluationPath,
                                      JsonNode schemaNode, JsonSchema parentSchema, ValidationContext validationContext) {
        return new DialFileCollectorValidator(schemaLocation, evaluationPath, schemaNode, parentSchema, this, validationContext, false);
    }

    private static class DialFileCollectorValidator extends BaseJsonValidator {
        private static final ErrorMessageType ERROR_MESSAGE_TYPE = () -> "dial:file";

        private final Boolean value;
        private final Boolean isServerProp;

        private static JsonNode findMetaNode(JsonSchema schema) {
            JsonNode metaNode = schema.getSchemaNode().get("dial:meta");
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
            JsonNode propertyKindNode = (metaNode != null) ? metaNode.get("dial:propertyKind") : null;
            this.isServerProp = (propertyKindNode != null) && propertyKindNode.asText().equalsIgnoreCase("server");
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<ValidationMessage> validate(ExecutionContext executionContext, JsonNode jsonNode, JsonNode jsonNode1, JsonNodePath jsonNodePath) {
            if (value) {
                CollectorContext collectorContext = executionContext.getCollectorContext();
                ListCollector<String> fileCollector = (ListCollector<String>) collectorContext.getCollectorMap()
                        .computeIfAbsent(ListCollector.ResourceCollectorType.ALL_RESOURCES.getValue(), k -> new ListCollector<String>());
                String nodeValue = jsonNode.asText();
                if (nodeValue == null || nodeValue.isEmpty()) {
                    return Set.of();
                }
                fileCollector.combine(List.of(nodeValue));
                if (isServerProp) {
                    ListCollector<String> serverFileCollector = (ListCollector<String>) collectorContext.getCollectorMap()
                            .computeIfAbsent(ListCollector.ResourceCollectorType.ONLY_SERVER_RESOURCES.getValue(), k -> new ListCollector<String>());
                    serverFileCollector.combine(List.of(nodeValue));
                }
            }
            return Set.of();
        }
    }
}
