package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.metaschemas.CatalogMetaSchemaHolder;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.CatalogSchemaProcessingException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.CatalogFileKeyword;
import com.epam.aidial.core.server.validation.CatalogSchemaValidationException;
import com.epam.aidial.core.server.validation.ListCollector;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.CollectorContext;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Resolves/validates catalog deployment metadata ({@code catalog_schema_id} / {@code catalog_properties})
 * against the catalog schemas registered in {@link com.epam.aidial.core.config.Config}. Deliberately
 * simpler than {@link ApplicationSchemaService}: catalog schemas are always fully self-contained in
 * config (no runner-hosted schema-endpoint download/merge), and there is no {@code dial:propertyKind}
 * (server/client) split since every catalog field is publicly readable display data.
 */
@Slf4j
public class CatalogSchemaService {

    private static final JsonMetaSchema CATALOG_DIAL_META_SCHEMA = CatalogMetaSchemaHolder.getMetaschemaBuilder()
            .keyword(new CatalogFileKeyword())
            .build();

    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.builder()
            .metaSchema(CATALOG_DIAL_META_SCHEMA)
            .defaultMetaSchemaIri(CATALOG_DIAL_META_SCHEMA.getIri())
            .build();

    private final ResourceService resourceService;
    private final ConfigStore configStore;
    private final EncryptionService encryptionService;

    public CatalogSchemaService(ResourceService resourceService, ConfigStore configStore, EncryptionService encryptionService) {
        this.resourceService = resourceService;
        this.configStore = configStore;
        this.encryptionService = encryptionService;
    }

    @Nullable
    public String getSchema(URI schemaId) {
        return configStore.get().getCatalogSchema(schemaId);
    }

    /**
     * Validates {@code deployment.getCatalogProperties()} against the schema referenced by
     * {@code deployment.getCatalogSchemaId()}, when both are set. Validation is lenient
     * (additionalProperties allowed). Also enforces that every {@code dial:localized} property
     * whose value is a locale map contains the schema's default locale key.
     */
    @SneakyThrows
    public void validate(Deployment deployment) {
        URI schemaId = deployment.getCatalogSchemaId();
        if (schemaId == null || deployment.getCatalogProperties() == null) {
            return;
        }

        String schemaText = getSchema(schemaId);
        if (schemaText == null) {
            throw new CatalogSchemaValidationException("Catalog schema not found: " + schemaId);
        }

        JsonSchema schema = SCHEMA_FACTORY.getSchema(schemaText);
        JsonNode propertiesNode = ProxyUtil.MAPPER.valueToTree(deployment.getCatalogProperties());
        Set<ValidationMessage> validationResult = schema.validate(propertiesNode);
        if (!validationResult.isEmpty()) {
            throw new CatalogSchemaValidationException("Failed to validate catalog_properties against schema " + schemaId, validationResult);
        }

        JsonNode schemaNode = ProxyUtil.MAPPER.readTree(schemaText);
        String defaultLocale = schemaNode.path(CatalogMetaSchemaHolder.CATALOG_DEFAULT_LOCALE).asText("en");
        for (String localizedField : findLocalizedFieldNames(schemaNode)) {
            JsonNode fieldValue = propertiesNode.get(localizedField);
            if (fieldValue != null && fieldValue.isObject() && !fieldValue.has(defaultLocale)) {
                throw new CatalogSchemaValidationException(
                        "catalog_properties." + localizedField + " should contain the default locale \"" + defaultLocale + "\"");
            }
        }
    }

    private static List<String> findLocalizedFieldNames(JsonNode schemaNode) {
        List<String> result = new ArrayList<>();
        JsonNode properties = schemaNode.path("properties");
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            boolean localized = field.getValue()
                    .path(CatalogMetaSchemaHolder.DIAL_META)
                    .path(CatalogMetaSchemaHolder.META_LOCALIZED)
                    .asBoolean(false);
            if (localized) {
                result.add(field.getKey());
            }
        }
        return result;
    }

    /**
     * Collects {@code dial:file}-tagged catalog property values (e.g. Badge image) as
     * {@link ResourceDescriptor}s, for copy-on-publish/share.
     */
    public List<ResourceDescriptor> getFiles(Deployment deployment) {
        URI schemaId = deployment.getCatalogSchemaId();
        if (schemaId == null || deployment.getCatalogProperties() == null) {
            return Collections.emptyList();
        }

        String schemaText = getSchema(schemaId);
        if (schemaText == null) {
            throw new CatalogSchemaValidationException("Catalog schema not found: " + schemaId);
        }

        try {
            JsonSchema schema = SCHEMA_FACTORY.getSchema(schemaText);
            CollectorContext collectorContext = new CollectorContext();
            String propertiesJson = ProxyUtil.MAPPER.writeValueAsString(deployment.getCatalogProperties());
            Set<ValidationMessage> validationResult = schema.validate(propertiesJson, InputFormat.JSON,
                    e -> e.setCollectorContext(collectorContext));
            if (!validationResult.isEmpty()) {
                throw new CatalogSchemaValidationException("Failed to validate catalog_properties against schema " + schemaId, validationResult);
            }

            @SuppressWarnings("unchecked")
            ListCollector<String> fileCollector = (ListCollector<String>) collectorContext.getCollectorMap()
                    .get(ListCollector.ResourceCollectorType.ALL_RESOURCES.getValue());
            if (fileCollector == null) {
                return Collections.emptyList();
            }

            List<ResourceDescriptor> result = new ArrayList<>();
            for (String item : fileCollector.collect()) {
                try {
                    ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(item, encryptionService);
                    ResourceType type = descriptor.getType();
                    if (type == ResourceTypes.FILE) {
                        if (!descriptor.isFolder() && !resourceService.hasResource(descriptor)) {
                            throw new CatalogSchemaValidationException("Resource listed in catalog_properties is not found: " + item);
                        }
                        result.add(descriptor);
                    }
                } catch (IllegalArgumentException e) {
                    // ignore value that is not a DIAL resource url
                }
            }
            return result;
        } catch (CatalogSchemaValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new CatalogSchemaProcessingException("Failed to obtain list of files attached to the catalog properties", e);
        }
    }
}
