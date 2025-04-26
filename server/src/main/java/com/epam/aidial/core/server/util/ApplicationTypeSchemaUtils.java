package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.validation.ApplicationTypeResourceException;
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
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_COMPLETION_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_CONFIGURATION_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_RATE_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_TOKENIZE_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_TRUNCATE_PROMPT_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.getMetaschemaBuilder;

@Slf4j
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

    @FunctionalInterface
    public interface ServerPropertiesConsumer {
        void accept(Map<String, Object> properties, boolean appendApplicationPropertiesHeader) throws JsonProcessingException;
    }

    public static void consumeServerProperties(Config config, Application application, ServerPropertiesConsumer consumer) {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(config, application);
        if (customApplicationSchema == null) {
            return;
        }

        if (application.getApplicationProperties() == null) {
            throw new ApplicationTypeSchemaValidationException("Typed application's properties not set");
        }

        try {
            JsonNode schemaNode = ProxyUtil.MAPPER.readTree(customApplicationSchema);
            boolean appendApplicationPropertiesHeader = !schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_APPEND_APPLICATION_PROPERTIES)
                                                        || schemaNode.get(MetaSchemaHolder.APPLICATION_TYPE_APPEND_APPLICATION_PROPERTIES).asBoolean();
            Map<String, Object> serverProperties = filterProperties(application.getApplicationProperties(), customApplicationSchema, "server");
            consumer.accept(serverProperties, appendApplicationPropertiesHeader);
        } catch (JsonProcessingException e) {
            throw new ApplicationTypeSchemaProcessingException("Failed to parse custom application schema", e);
        }
    }

    @FunctionalInterface
    private interface EndpointConsumer {
        void accept(String completion, String configuration, String rate, String tokenize, String truncatePrompt);
    }

    private static void consumeCustomApplicationEndpoints(Config config, Application application, EndpointConsumer consumer) {
        try {
            String schema = getCustomApplicationSchemaOrThrow(config, application);
            JsonNode schemaNode = ProxyUtil.MAPPER.readTree(schema);

            String completionEndpoint = getEndpoint(schemaNode, APPLICATION_TYPE_COMPLETION_ENDPOINT, true);
            String configurationEndpoint = getEndpoint(schemaNode, APPLICATION_TYPE_CONFIGURATION_ENDPOINT, false);
            String rateEndpoint = getEndpoint(schemaNode, APPLICATION_TYPE_RATE_ENDPOINT, false);
            String tokenizeEndpoint = getEndpoint(schemaNode, APPLICATION_TYPE_TOKENIZE_ENDPOINT, false);
            String truncatePromptEndpoint = getEndpoint(schemaNode, APPLICATION_TYPE_TRUNCATE_PROMPT_ENDPOINT, false);

            consumer.accept(completionEndpoint, configurationEndpoint, rateEndpoint, tokenizeEndpoint, truncatePromptEndpoint);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ApplicationTypeSchemaProcessingException("Failed to get custom application endpoints", e);
        }
    }

    private static String getEndpoint(JsonNode schemaNode, String endpointKey, boolean isRequired) {
        JsonNode endpointNode = schemaNode.get(endpointKey);
        if (endpointNode == null) {
            if (isRequired) {
                throw new ApplicationTypeSchemaProcessingException("Custom application schema does not contain " + endpointKey);
            } else {
                return null;
            }
        }
        return endpointNode.asText();
    }

    public static Application modifyEndpointsForCustomApplication(Config config, Application application) {
        if (application.getApplicationTypeSchemaId() == null) {
            return application;
        }

        Application copy = new Application(application);

        consumeCustomApplicationEndpoints(config, application, (completionEndpoint, configurationEndpoint, rateEndpoint, tokenizeEndpoint, truncatePromptEndpoint) -> {
            copy.setEndpoint(completionEndpoint);

            Features features = copy.getFeatures();
            if (features == null) {
                features = new Features();
            }

            if (configurationEndpoint != null) {
                features.setConfigurationEndpoint(configurationEndpoint);
            }
            if (rateEndpoint != null) {
                features.setRateEndpoint(rateEndpoint);
            }
            if (tokenizeEndpoint != null) {
                features.setTokenizeEndpoint(tokenizeEndpoint);
            }
            if (truncatePromptEndpoint != null) {
                features.setTruncatePromptEndpoint(truncatePromptEndpoint);
            }

            copy.setFeatures(features);
        });

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

    public static List<ResourceDescriptor> getServerFiles(Config config, Application application, EncryptionService encryptionService,
                                                          ResourceService resourceService) {
        return getFiles(config, application, encryptionService, resourceService, ListCollector.FileCollectorType.ONLY_SERVER_FILES);
    }

    public static List<ResourceDescriptor> getFiles(Config config, Application application, EncryptionService encryptionService,
                                                    ResourceService resourceService) {
        return getFiles(config, application, encryptionService, resourceService, ListCollector.FileCollectorType.ALL_FILES);
    }

    @SuppressWarnings("unchecked")
    private static List<ResourceDescriptor> getFiles(Config config, Application application, EncryptionService encryptionService,
                                                     ResourceService resourceService, ListCollector.FileCollectorType collectorName) {
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
            ListCollector<String> propsCollector = (ListCollector<String>) collectorContext.getCollectorMap().get(collectorName.getValue());
            if (propsCollector == null) {
                return Collections.emptyList();
            }
            List<ResourceDescriptor> result = new ArrayList<>();
            for (String item : propsCollector.collect()) {
                try {
                    ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(item, encryptionService);
                    if (!descriptor.isFolder() && !resourceService.hasResource(descriptor)) {
                        throw new ApplicationTypeResourceException("Resource listed as dependent to the application not found or inaccessible", item);
                    }
                    result.add(descriptor);
                } catch (IllegalArgumentException e) {
                    throw new ApplicationTypeResourceException("Failed to get resource descriptor for url", item, e);
                }
            }
            return result;
        } catch (ApplicationTypeSchemaValidationException | ApplicationTypeResourceException e) {
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

    public static Application modifySchemaRichApplication(Application application, boolean propertyFilteringRequired, ProxyContext context) {
        try {
            if (propertyFilteringRequired) {
                application = ApplicationTypeSchemaUtils.filterCustomClientProperties(context.getConfig(), application);
            }
            application = ApplicationTypeSchemaUtils.modifyEndpointsForCustomApplication(context.getConfig(), application);
        } catch (ApplicationTypeSchemaProcessingException | ApplicationTypeResourceException | ApplicationTypeSchemaValidationException ex) {
            log.error("Failed to modify application to fulfill schema's restrictions %s".formatted(application.getName()), ex);
            application.setApplicationProperties(null);
            application.setInvalid(true);
        }
        return application;
    }
}