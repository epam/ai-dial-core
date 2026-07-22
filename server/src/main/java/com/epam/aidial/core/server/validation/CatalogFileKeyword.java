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
 * Collects {@code dial:file}-tagged property values in a catalog schema instance so they can be
 * copied/rewritten on publish/share. Unlike {@link DialResourceKeyKeyword}, catalog properties
 * have no {@code dial:propertyKind} (server/client) split - every catalog field is client-visible -
 * so this keyword only ever populates {@link ListCollector.ResourceCollectorType#ALL_RESOURCES}.
 */
public class CatalogFileKeyword implements Keyword {

    @Override
    public String getValue() {
        return "dial:file";
    }

    @Override
    public JsonValidator newValidator(SchemaLocation schemaLocation, JsonNodePath evaluationPath,
                                      JsonNode schemaNode, JsonSchema parentSchema, ValidationContext validationContext) {
        return new CatalogFileCollectorValidator(schemaLocation, evaluationPath, schemaNode, parentSchema, this, validationContext, false);
    }

    private static class CatalogFileCollectorValidator extends BaseJsonValidator {
        private static final ErrorMessageType ERROR_MESSAGE_TYPE = () -> "dial:file";

        private final boolean value;

        public CatalogFileCollectorValidator(SchemaLocation schemaLocation, JsonNodePath evaluationPath, JsonNode schemaNode,
                                             JsonSchema parentSchema, Keyword keyword,
                                             ValidationContext validationContext, boolean suppressSubSchemaRetrieval) {
            super(schemaLocation, evaluationPath, schemaNode, parentSchema, ERROR_MESSAGE_TYPE, keyword, validationContext, suppressSubSchemaRetrieval);
            this.value = schemaNode.booleanValue();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Set<ValidationMessage> validate(ExecutionContext executionContext, JsonNode jsonNode, JsonNode jsonNode1, JsonNodePath jsonNodePath) {
            if (value) {
                String nodeValue = jsonNode.asText();
                if (nodeValue == null || nodeValue.isEmpty()) {
                    return Set.of();
                }
                CollectorContext collectorContext = executionContext.getCollectorContext();
                ListCollector<String> fileCollector = (ListCollector<String>) collectorContext.getCollectorMap()
                        .computeIfAbsent(ListCollector.ResourceCollectorType.ALL_RESOURCES.getValue(), k -> new ListCollector<String>());
                fileCollector.combine(List.of(nodeValue));
            }
            return Set.of();
        }
    }
}
