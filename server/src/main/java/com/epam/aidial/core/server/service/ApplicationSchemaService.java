package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.metaschemas.CopyAppBucketOptions;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ApplicationTypeSchemaProcessingException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.ApplicationTypeResourceException;
import com.epam.aidial.core.server.validation.ApplicationTypeSchemaValidationException;
import com.epam.aidial.core.server.validation.DialFileKeyword;
import com.epam.aidial.core.server.validation.DialMetaKeyword;
import com.epam.aidial.core.server.validation.DialResourceKeyKeyword;
import com.epam.aidial.core.server.validation.ListCollector;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.CollectorContext;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_COMPLETION_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_CONFIGURATION_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_RATE_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_ROUTES;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_TOKENIZE_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_TRUNCATE_PROMPT_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.DIAL_APPLICATION_TYPE_ASSISTANT_ATTACHMENTS_IN_REQUEST;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.DIAL_APPLICATION_TYPE_BUCKET_COPY;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.DIAL_APPLICATION_TYPE_INTERCEPTORS;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.getMetaschemaBuilder;

@Slf4j
@AllArgsConstructor
public class ApplicationSchemaService {

    private static final JsonMetaSchema DIAL_META_SCHEMA = getMetaschemaBuilder()
            .keyword(new DialMetaKeyword())
            .keyword(new DialFileKeyword())
            .keyword(new DialResourceKeyKeyword())
            .build();

    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.builder()
            .metaSchema(DIAL_META_SCHEMA)
            .defaultMetaSchemaIri(DIAL_META_SCHEMA.getIri())
            .build();

    private static final TypeReference<Map<String, Route>> APP_ROUTE_TYPE_REF = new TypeReference<>() {
        @Override
        public Type getType() {
            return super.getType();
        }
    };

    private static final TypeReference<CopyAppBucketOptions> COPY_APP_BUCKET_OPTIONS_TYPE_REF = new TypeReference<>() {
        @Override
        public Type getType() {
            return super.getType();
        }
    };

    private static final TypeReference<List<String>> APP_INTERCEPTOR_LIST_REF = new TypeReference<>() {
        @Override
        public Type getType() {
            return super.getType();
        }
    };

    private final ResourceService resourceService;
    private final ConfigStore configStore;

    private final EncryptionService encryptionService;

    String getCustomApplicationSchemaOrThrow(Application application) {
        URI schemaId = application.getApplicationTypeSchemaId();
        if (schemaId == null) {
            return null;
        }
        String customApplicationSchema = configStore.get().getCustomApplicationSchema(schemaId);
        if (customApplicationSchema == null) {
            throw new ApplicationTypeSchemaValidationException("Custom application schema not found: " + schemaId);
        }
        return customApplicationSchema;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filterProperties(Map<String, Object> applicationProperties, String schema, ListCollector.MetaDataCollectorType collector) {
        try {
            JsonSchema appSchema = SCHEMA_FACTORY.getSchema(schema);
            CollectorContext collectorContext = new CollectorContext();
            String applicationPropertiesJson = ProxyUtil.MAPPER.writeValueAsString(applicationProperties);
            Set<ValidationMessage> validationResult = appSchema.validate(applicationPropertiesJson, InputFormat.JSON,
                    e -> e.setCollectorContext(collectorContext));
            if (!validationResult.isEmpty()) {
                throw new ApplicationTypeSchemaValidationException("Failed to validate custom app against the schema", validationResult);
            }
            ListCollector<String> propsCollector = (ListCollector<String>) collectorContext.getCollectorMap().get(collector.getValue());
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
    public interface MetadataPropertiesConsumer {
        void accept(Map<String, Object> properties, boolean appendApplicationPropertiesHeader) throws JsonProcessingException;
    }

    public void consumeMetadataProperties(Application application, MetadataPropertiesConsumer consumer) {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application);
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
            Map<String, Object> serverProperties = filterProperties(application.getApplicationProperties(),
                    customApplicationSchema, ListCollector.MetaDataCollectorType.ALL);
            consumer.accept(serverProperties, appendApplicationPropertiesHeader);
        } catch (JsonProcessingException e) {
            throw new ApplicationTypeSchemaProcessingException("Failed to parse custom application schema", e);
        }
    }

    @FunctionalInterface
    private interface EndpointConsumer {
        void accept(String completion, String configuration, String rate, String tokenize, String truncatePrompt);
    }

    private void consumeCustomApplicationEndpoints(Application application, EndpointConsumer consumer) {
        try {
            String schema = getCustomApplicationSchemaOrThrow(application);
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

    public Application modifyEndpointsForCustomApplication(Application application) {
        if (application.getApplicationTypeSchemaId() == null) {
            return application;
        }

        Application copy = new Application(application);

        consumeCustomApplicationEndpoints(application, (completionEndpoint, configurationEndpoint, rateEndpoint, tokenizeEndpoint, truncatePromptEndpoint) -> {
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

    public Application filterCustomClientProperties(Application application) {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application);
        if (customApplicationSchema == null) {
            return application;
        }
        if (application.getApplicationProperties() == null) {
            return application;
        }
        Application copy = new Application(application);
        Map<String, Object> appWithClientOptionsOnly = filterProperties(application.getApplicationProperties(),
                customApplicationSchema, ListCollector.MetaDataCollectorType.CLIENT);
        copy.setApplicationProperties(appWithClientOptionsOnly);
        return copy;
    }

    public List<ResourceDescriptor> getServerFiles(Application application) {
        return getFiles(application, ListCollector.ResourceCollectorType.ONLY_SERVER_RESOURCES);
    }

    public List<ResourceDescriptor> getFiles(Application application) {
        return getFiles(application, ListCollector.ResourceCollectorType.ALL_RESOURCES);
    }

    @SuppressWarnings("unchecked")
    private List<ResourceDescriptor> getFiles(Application application, ListCollector.ResourceCollectorType collectorName) {
        try {
            ListCollector<String> propsCollector = (ListCollector<String>) getCollector(application, collectorName.getValue());
            if (propsCollector == null) {
                return Collections.emptyList();
            }
            List<ResourceDescriptor> result = new ArrayList<>();
            for (String item : propsCollector.collect()) {
                try {
                    ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(item, encryptionService);
                    if (descriptor.getType() != ResourceTypes.FILE) {
                        continue;
                    }
                    if (!descriptor.isFolder() && !resourceService.hasResource(descriptor)) {
                        throw new ApplicationTypeResourceException("Resource listed as dependent to the application not found or inaccessible", item);
                    }
                    result.add(descriptor);
                } catch (IllegalArgumentException e) {
                    // ignore resource to be defined in DIAL config
                }
            }
            return result;
        } catch (ApplicationTypeSchemaValidationException | ApplicationTypeResourceException e) {
            throw e;
        } catch (Exception e) {
            throw new ApplicationTypeSchemaProcessingException("Failed to obtain list of files attached to the custom app", e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<ResourceDescriptor> getDeployments(Application application) {
        try {
            ListCollector<String> propsCollector = (ListCollector<String>) getCollector(application,
                    ListCollector.ResourceCollectorType.ALL_RESOURCES.getValue());
            if (propsCollector == null) {
                return Collections.emptyList();
            }
            List<ResourceDescriptor> result = new ArrayList<>();
            for (String item : propsCollector.collect()) {
                try {
                    ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(item, encryptionService);
                    ResourceType type = descriptor.getType();
                    if (type == ResourceTypes.TOOL_SET || type == ResourceTypes.APPLICATION) {
                        if (descriptor.isFolder() || !resourceService.hasResource(descriptor)) {
                            throw new ApplicationTypeResourceException("Deployment listed as dependent to the application is not found or inaccessible", item);
                        }
                        result.add(descriptor);
                    }
                } catch (IllegalArgumentException e) {
                    // ignore resource to be defined in DIAL config
                }
            }
            return result;
        } catch (ApplicationTypeSchemaValidationException | ApplicationTypeResourceException e) {
            throw e;
        } catch (Exception e) {
            throw new ApplicationTypeSchemaProcessingException("Failed to obtain list of toolsets attached to the custom app", e);
        }
    }

    @Nullable
    private Object getCollector(Application application, String collectorName) throws JsonProcessingException {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application);
        if (customApplicationSchema == null) {
            return null;
        }
        JsonSchema appSchema = SCHEMA_FACTORY.getSchema(customApplicationSchema);
        CollectorContext collectorContext = new CollectorContext();
        String customPropsJson = ProxyUtil.MAPPER.writeValueAsString(application.getApplicationProperties());
        Set<ValidationMessage> validationResult = appSchema.validate(customPropsJson, InputFormat.JSON,
                e -> e.setCollectorContext(collectorContext));
        if (!validationResult.isEmpty()) {
            throw new ApplicationTypeSchemaValidationException("Failed to validate custom app against the schema", validationResult);
        }
        return collectorContext.getCollectorMap().get(collectorName);
    }

    public Application modifySchemaRichApplication(Application application, boolean propertyFilteringRequired) {
        try {
            if (propertyFilteringRequired) {
                application = filterCustomClientProperties(application);
            }
            application = modifyEndpointsForCustomApplication(application);
            boolean assistantAttachmentsInRequest = getBooleanProperty(application, DIAL_APPLICATION_TYPE_ASSISTANT_ATTACHMENTS_IN_REQUEST);
            if (assistantAttachmentsInRequest) {
                Features features = application.getFeatures();
                if (features == null) {
                    features = new Features();
                    application.setFeatures(features);
                }
                features.setAssistantAttachmentsInRequestSupported(assistantAttachmentsInRequest);
            }
        } catch (ApplicationTypeSchemaProcessingException | ApplicationTypeResourceException | ApplicationTypeSchemaValidationException ex) {
            log.warn("Failed to modify application to fulfill schema's restrictions %s".formatted(application.getName()), ex);
            application.setApplicationProperties(null);
            application.setInvalid(true);
        }
        return application;
    }

    @Nullable
    @SneakyThrows
    public Map<String, Route> getRoutes(Application application) {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application);
        if (customApplicationSchema == null) {
            return null;
        }
        JsonNode schemaNode = ProxyUtil.MAPPER.readTree(customApplicationSchema);
        JsonNode appRoutes = schemaNode.get(APPLICATION_TYPE_ROUTES);
        if (appRoutes == null) {
            return null;
        }
        return ProxyUtil.MAPPER.treeToValue(appRoutes, APP_ROUTE_TYPE_REF);
    }

    @SneakyThrows
    public CopyAppBucketOptions getCopyAppBucketOptions(Application application) {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application);
        if (customApplicationSchema == null) {
            return CopyAppBucketOptions.DISABLED;
        }
        JsonNode schemaNode = ProxyUtil.MAPPER.readTree(customApplicationSchema);
        JsonNode options = schemaNode.get(DIAL_APPLICATION_TYPE_BUCKET_COPY);
        if (options == null) {
            return CopyAppBucketOptions.DISABLED;
        }
        return ProxyUtil.MAPPER.treeToValue(options, COPY_APP_BUCKET_OPTIONS_TYPE_REF);
    }

    @SneakyThrows
    public List<String> getInterceptors(Application application) {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application);
        if (customApplicationSchema == null) {
            return List.of();
        }
        JsonNode schemaNode = ProxyUtil.MAPPER.readTree(customApplicationSchema);
        JsonNode interceptors = schemaNode.get(DIAL_APPLICATION_TYPE_INTERCEPTORS);
        if (interceptors == null) {
            return List.of();
        }
        return ProxyUtil.MAPPER.treeToValue(interceptors, APP_INTERCEPTOR_LIST_REF);
    }

    @SneakyThrows
    private boolean getBooleanProperty(Application application, String propName) {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application);
        if (customApplicationSchema == null) {
            return false;
        }
        JsonNode schemaNode = ProxyUtil.MAPPER.readTree(customApplicationSchema);
        return Optional.ofNullable(schemaNode.get(propName))
                .map(JsonNode::asBoolean).orElse(false);
    }

}