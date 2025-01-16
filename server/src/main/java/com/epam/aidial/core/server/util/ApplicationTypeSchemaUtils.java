package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.validation.ApplicationTypeSchemaValidationException;
import com.epam.aidial.core.server.validation.DialFileKeyword;
import com.epam.aidial.core.server.validation.DialMetaKeyword;
import com.epam.aidial.core.server.validation.ListCollector;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.CollectorContext;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import lombok.experimental.UtilityClass;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.getMetaschemaBuilder;


@UtilityClass
public class ApplicationTypeSchemaUtils {

    private static final JsonMetaSchema DIAL_META_SCHEMA = getMetaschemaBuilder()
            .keyword(new DialMetaKeyword())
            .keyword(new DialFileKeyword())
            .build();

    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.builder()
            .metaSchema(DIAL_META_SCHEMA)
            .defaultMetaSchemaIri(DIAL_META_SCHEMA.getIri())
            .build();

    static String getCustomApplicationSchemaOrThrow(Config config, Application application) {
        URI schemaId = application.getApplicationTypeSchemaId();
        if (schemaId == null) {
            return null;
        }
        String customApplicationSchema = config.getCustomApplicationSchema(schemaId);
        if (customApplicationSchema == null) {
            throw new ApplicationTypeSchemaValidationException("Custom application schema not found: " + schemaId);
        }
        return customApplicationSchema;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filterProperties(Map<String, Object> applicationProperties, String schema, String collectorName) {
        try {
            JsonSchema appSchema = SCHEMA_FACTORY.getSchema(schema);
            CollectorContext collectorContext = new CollectorContext();
            String applicationPropertiesJson = ProxyUtil.MAPPER.writeValueAsString(applicationProperties);
            Set<ValidationMessage> validationResult = appSchema.validate(applicationPropertiesJson, InputFormat.JSON,
                    e -> e.setCollectorContext(collectorContext));
            if (!validationResult.isEmpty()) {
                throw new ApplicationTypeSchemaValidationException("Failed to validate custom app against the schema", validationResult);
            }
            ListCollector<String> propsCollector = (ListCollector<String>) collectorContext.getCollectorMap().get(collectorName);
            if (propsCollector == null) {
                return Collections.emptyMap();
            }
            Map<String, Object> result = new HashMap<>();
            for (String propertyName : propsCollector.collect()) {
                result.put(propertyName, applicationProperties.get(propertyName));
            }
            return result;
        } catch (ApplicationTypeSchemaValidationException e) {
            throw e;
        } catch (Throwable e) {
            throw new ApplicationTypeSchemaProcessingException("Failed to filter custom properties", e);
        }
    }

    public static Map<String, Object> getCustomServerProperties(Config config, Application application) {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(config, application);
        if (customApplicationSchema == null) {
            return Collections.emptyMap();
        }
        if (application.getApplicationProperties() == null) {
            throw new ApplicationTypeSchemaValidationException("Typed application's properties not set");
        }
        return filterProperties(application.getApplicationProperties(), customApplicationSchema, "server");
    }

    public static String getCustomApplicationEndpoint(Config config, Application application) {
        try {
            String schema = getCustomApplicationSchemaOrThrow(config, application);
            JsonNode schemaNode = ProxyUtil.MAPPER.readTree(schema);
            JsonNode endpointNode = schemaNode.get("dial:applicationTypeCompletionEndpoint");
            if (endpointNode == null) {
                throw new ApplicationTypeSchemaProcessingException("Custom application schema does not contain completion endpoint");
            }
            return endpointNode.asText();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ApplicationTypeSchemaProcessingException("Failed to get custom application endpoint", e);
        }
    }

    public static Application modifyEndpointForCustomApplication(Config config, Application application) {
        String customEndpoint = getCustomApplicationEndpoint(config, application);
        if (customEndpoint == null) {
            return application;
        }
        Application copy = new Application(application);
        copy.setEndpoint(customEndpoint);
        return copy;
    }

    public static Application filterCustomClientProperties(Config config, Application application) {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(config, application);
        if (customApplicationSchema == null) {
            return application;
        }
        if (application.getApplicationProperties() == null) {
            return application;
        }
        Application copy = new Application(application);
        Map<String, Object> appWithClientOptionsOnly = filterProperties(application.getApplicationProperties(), customApplicationSchema, "client");
        copy.setApplicationProperties(appWithClientOptionsOnly);
        return copy;
    }

    public static Application filterCustomClientPropertiesWhenNoWriteAccess(ProxyContext ctx, ResourceDescriptor resource, Application application) {
        if (!ctx.getProxy().getAccessService().hasWriteAccess(resource, ctx)) {
            application = filterCustomClientProperties(ctx.getConfig(), application);
        }
        return application;
    }

    public static void replaceCustomAppFiles(Application application, Map<String, String> replacementLinks) {
        if (application.getApplicationTypeSchemaId() == null) {
            return;
        }
        JsonNode customProperties = ProxyUtil.MAPPER.convertValue(application.getApplicationProperties(), JsonNode.class);
        replaceLinksInJsonNode(customProperties, replacementLinks, null, null);
        Map<String, Object> customPropertiesMap = ProxyUtil.MAPPER.convertValue(customProperties, new TypeReference<>() {
        });

        application.setApplicationProperties(customPropertiesMap);
    }

    @SuppressWarnings("unchecked")
    public static List<ResourceDescriptor> getFiles(Config config, Application application, EncryptionService encryptionService, ResourceService resourceService) {
        try {
            String customApplicationSchema = getCustomApplicationSchemaOrThrow(config, application);
            if (customApplicationSchema == null) {
                return Collections.emptyList();
            }
            JsonSchema appSchema = SCHEMA_FACTORY.getSchema(customApplicationSchema);
            CollectorContext collectorContext = new CollectorContext();
            String customPropsJson = ProxyUtil.MAPPER.writeValueAsString(application.getApplicationProperties());
            Set<ValidationMessage> validationResult = appSchema.validate(customPropsJson, InputFormat.JSON,
                    e -> e.setCollectorContext(collectorContext));
            if (!validationResult.isEmpty()) {
                throw new ApplicationTypeSchemaValidationException("Failed to validate custom app against the schema", validationResult);
            }
            ListCollector<String> propsCollector = (ListCollector<String>) collectorContext.getCollectorMap().get("file");
            if (propsCollector == null) {
                return Collections.emptyList();
            }
            List<ResourceDescriptor> result = new ArrayList<>();
            for (String item : propsCollector.collect()) {
                try {
                    ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(item, encryptionService);
                    if (!descriptor.isFolder() && !resourceService.hasResource(descriptor)) {
                        throw new ApplicationTypeSchemaValidationException("Resource listed as dependent to the application not found or inaccessible: " + item);
                    }
                    result.add(descriptor);
                } catch (IllegalArgumentException e) {
                    throw new ApplicationTypeSchemaValidationException("Failed to get resource descriptor for url: " + item, e);
                }
            }
            return result;
        } catch (ApplicationTypeSchemaValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ApplicationTypeSchemaProcessingException("Failed to obtain list of files attached to the custom app", e);
        }
    }

    public static void replaceLinksInJsonNode(JsonNode node, Map<String, String> replacementLinks, JsonNode parent, String fieldName) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> replaceLinksInJsonNode(entry.getValue(), replacementLinks, node, entry.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode childNode = node.get(i);
                if (childNode.isTextual()) {
                    String replacement = replacementLinks.get(childNode.textValue());
                    if (replacement != null) {
                        ((ArrayNode) node).set(i, replacement);
                    }
                } else {
                    replaceLinksInJsonNode(childNode, replacementLinks, node, String.valueOf(i));
                }
            }
        } else if (node.isTextual()) {
            String replacement = replacementLinks.get(node.textValue());
            if (replacement != null && parent.isObject()) {
                ((ObjectNode) parent).put(fieldName, replacement);
            }
        }
    }
}