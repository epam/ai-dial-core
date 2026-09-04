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
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.networknt.schema.CollectorContext;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonMetaSchema;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_COMPLETION_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_CONFIGURATION_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_RATE_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_RESPONSES_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_ROUTES;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_TOKENIZE_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.APPLICATION_TYPE_TRUNCATE_PROMPT_ENDPOINT;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.DIAL_APPLICATION_TYPE_ASSISTANT_ATTACHMENTS_IN_REQUEST;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.DIAL_APPLICATION_TYPE_BUCKET_COPY;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.DIAL_APPLICATION_TYPE_INTERCEPTORS;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.DIAL_APPLICATION_TYPE_MCP;
import static com.epam.aidial.core.metaschemas.MetaSchemaHolder.getMetaschemaBuilder;

@Slf4j
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

    private static final TypeReference<LinkedHashMap<String, Route>> APP_ROUTE_TYPE_REF = new TypeReference<>() {
        @Override
        public Type getType() {
            return super.getType();
        }
    };

    private static final TypeReference<Application.Mcp> APP_MCP_TYPE_REF = new TypeReference<>() {
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

    private final HttpClient httpClient;

    private final ConcurrentHashMap<URI, String> schemaCache = new ConcurrentHashMap<>();

    public ApplicationSchemaService(ResourceService resourceService, ConfigStore configStore,
                                    EncryptionService encryptionService, @Nullable ProxySelector proxySelector) {
        this.resourceService = resourceService;
        this.configStore = configStore;
        this.encryptionService = encryptionService;
        HttpClient.Builder builder = HttpClient.newBuilder();
        builder.connectTimeout(Duration.of(5, ChronoUnit.SECONDS));
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        this.httpClient = builder.build();
    }

    @VisibleForTesting
    ApplicationSchemaService(ResourceService resourceService, ConfigStore configStore,
                             EncryptionService encryptionService, HttpClient httpClient) {
        this.resourceService = resourceService;
        this.configStore = configStore;
        this.encryptionService = encryptionService;
        this.httpClient = httpClient;
    }

    @SneakyThrows
    @Nullable
    public String getSchema(URI schemaId, boolean forceReload) {
        String customApplicationSchema = configStore.get().getCustomApplicationSchema(schemaId);
        if (customApplicationSchema == null) {
            return null;
        }
        JsonNode schema = ProxyUtil.MAPPER.readTree(customApplicationSchema);
        if (schema.has(MetaSchemaHolder.DIAL_APPLICATION_TYPE_SCHEMA_ENDPOINT)) {
            String url = schema.get(MetaSchemaHolder.DIAL_APPLICATION_TYPE_SCHEMA_ENDPOINT).textValue();
            if (forceReload || !schemaCache.containsKey(schemaId)) {
                try {
                    ObjectNode appSchema = downloadAppSchema(url);
                    merge(appSchema, schema);
                    String result = appSchema.toString();
                    schemaCache.put(schemaId, result);
                    return result;
                } catch (Exception e) {
                    log.warn("Failed to download application schema: {}", url, e);
                    throw new ApplicationTypeSchemaProcessingException("Failed to download application schema: " + url, e);
                }
            } else {
                return schemaCache.get(schemaId);
            }
        } else {
            return customApplicationSchema;
        }
    }

    @SneakyThrows
    String getCustomApplicationSchemaOrThrow(Application application, boolean forceReload) {
        URI schemaId = application.getApplicationTypeSchemaId();
        if (schemaId == null) {
            return null;
        }
        String customApplicationSchema = getSchema(schemaId, forceReload);
        if (customApplicationSchema == null) {
            throw new ApplicationTypeSchemaValidationException("Custom application schema not found: " + schemaId);
        }
        return customApplicationSchema;
    }

    private void merge(ObjectNode appSchema, JsonNode schema) {
        Iterator<String> fieldNames = schema.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            appSchema.set(field, schema.get(field));
        }
    }

    @SneakyThrows
    private ObjectNode downloadAppSchema(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        String body = response.body();
        if (status != 200) {
            log.debug("Error of downloading application schema {}: status {}, response {}, headers: {}",
                    request.uri(), response.statusCode(), response.body(), response.headers());
            throw new HttpException(status, "Application runner returned error on downloading application schema");
        }
        JsonNode tree = ProxyUtil.MAPPER.readTree(body);
        if (!tree.isObject()) {
            throw new ApplicationTypeSchemaProcessingException("Application schema is not JSON object");
        }
        return (ObjectNode) tree;
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
        if (application.getApplicationProperties() == null) {
            throw new ApplicationTypeSchemaValidationException("Typed application's properties not set");
        }
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application, false);
        try {
            if (customApplicationSchema == null) {
                consumer.accept(application.getApplicationProperties(), true);
                return;
            }
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
        void accept(
                String completion,
                String responses,
                String configuration,
                String rate,
                String tokenize,
                String truncatePrompt);
    }

    private void consumeCustomApplicationEndpoints(Application application, EndpointConsumer consumer) {
        try {
            String schema = getCustomApplicationSchemaOrThrow(application, false);
            JsonNode schemaNode = ProxyUtil.MAPPER.readTree(schema);

            String completionEndpoint = getEndpoint(schemaNode, APPLICATION_TYPE_COMPLETION_ENDPOINT, false);
            String responsesEndpoint = getEndpoint(schemaNode, APPLICATION_TYPE_RESPONSES_ENDPOINT, false);
            String configurationEndpoint = getEndpoint(schemaNode, APPLICATION_TYPE_CONFIGURATION_ENDPOINT, false);
            String rateEndpoint = getEndpoint(schemaNode, APPLICATION_TYPE_RATE_ENDPOINT, false);
            String tokenizeEndpoint = getEndpoint(schemaNode, APPLICATION_TYPE_TOKENIZE_ENDPOINT, false);
            String truncatePromptEndpoint = getEndpoint(schemaNode, APPLICATION_TYPE_TRUNCATE_PROMPT_ENDPOINT, false);

            consumer.accept(
                    completionEndpoint,
                    responsesEndpoint,
                    configurationEndpoint,
                    rateEndpoint,
                    tokenizeEndpoint,
                    truncatePromptEndpoint);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ApplicationTypeSchemaProcessingException("Failed to get custom application endpoints", e);
        }
    }

    @SneakyThrows
    public String getChatCompletionEndpoint(Application application) {
        String schema = getCustomApplicationSchemaOrThrow(application, false);
        JsonNode schemaNode = ProxyUtil.MAPPER.readTree(schema);
        return getEndpoint(schemaNode, APPLICATION_TYPE_COMPLETION_ENDPOINT, false);
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

        consumeCustomApplicationEndpoints(application, (completionEndpoint, responsesEndpoint, configurationEndpoint, rateEndpoint, tokenizeEndpoint, truncatePromptEndpoint) -> {
            copy.setEndpoint(completionEndpoint);
            if (responsesEndpoint != null) {
                copy.setResponsesEndpoint(responsesEndpoint);
            }

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
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application, false);
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
        return getApplicationResources(application, Set.of(ResourceTypes.FILE),
                ListCollector.ResourceCollectorType.ONLY_SERVER_RESOURCES, false);
    }

    public List<ResourceDescriptor> getFiles(Application application) {
        return getApplicationResources(application, Set.of(ResourceTypes.FILE),
                ListCollector.ResourceCollectorType.ALL_RESOURCES, false);
    }

    public List<ResourceDescriptor> getPrompts(Application application) {
        return getApplicationResources(application, Set.of(ResourceTypes.PROMPT),
                ListCollector.ResourceCollectorType.ALL_RESOURCES, false);
    }

    public List<ResourceDescriptor> getSkills(Application application) {
        return getApplicationResources(application, Set.of(ResourceTypes.SKILL),
                ListCollector.ResourceCollectorType.ALL_RESOURCES, false);
    }

    public List<ResourceDescriptor> getDeployments(Application application) {
        return getApplicationResources(application, Set.of(ResourceTypes.APPLICATION, ResourceTypes.TOOL_SET),
                ListCollector.ResourceCollectorType.ALL_RESOURCES, false);
    }

    @SuppressWarnings("unchecked")
    private List<ResourceDescriptor> getApplicationResources(Application application, Set<ResourceTypes> resourceTypes,
                                                             ListCollector.ResourceCollectorType collectorName, boolean forceReload) {
        try {
            ListCollector<String> propsCollector = (ListCollector<String>) getCollector(application,
                    collectorName.getValue(), forceReload);
            if (propsCollector == null) {
                return Collections.emptyList();
            }
            List<ResourceDescriptor> result = new ArrayList<>();
            for (String item : propsCollector.collect()) {
                try {
                    ResourceDescriptor descriptor = ResourceDescriptorFactory.fromAnyUrl(item, encryptionService);
                    ResourceType type = descriptor.getType();
                    if (resourceTypes.contains(type)) {
                        if (!descriptor.isFolder() && !resourceService.hasResource(descriptor)) {
                            throw new ApplicationTypeResourceException("Resource listed as dependent to the application is not found", item);
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
            throw new ApplicationTypeSchemaProcessingException("Failed to obtain list of resources attached to the custom app", e);
        }
    }

    @Nullable
    private Object getCollector(Application application, String collectorName, boolean forceReload) throws JsonProcessingException {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application, forceReload);
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

            LinkedHashMap<String, Route> routes = getRoutes(application);
            if (routes != null) {
                application.setRoutes(routes);
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
    public LinkedHashMap<String, Route> getRoutes(Application application) {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application, false);
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

    @Nullable
    @SneakyThrows
    public Application.Mcp getMcp(Application application) {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application, false);
        if (customApplicationSchema == null) {
            return null;
        }
        JsonNode schemaNode = ProxyUtil.MAPPER.readTree(customApplicationSchema);
        JsonNode mcpNode = schemaNode.get(DIAL_APPLICATION_TYPE_MCP);
        if (mcpNode == null) {
            return null;
        }
        Application.Mcp mcp = ProxyUtil.MAPPER.treeToValue(mcpNode, APP_MCP_TYPE_REF);

        // an instance-level allowedTools can only narrow the application type's allowedTools, never widen it
        Application.Mcp instanceMcp = application.getMcp();
        if (instanceMcp != null && !instanceMcp.getAllowedTools().isEmpty()) {
            List<String> typeAllowedTools = mcp.getAllowedTools();
            List<String> effectiveAllowedTools = typeAllowedTools.isEmpty()
                    ? instanceMcp.getAllowedTools()
                    : instanceMcp.getAllowedTools().stream()
                            .filter(typeAllowedTools::contains)
                            .toList();
            // an empty list means "unrestricted" downstream, so a no-overlap override must not be applied:
            // keeping the application type's list restricts the app instead of exposing every tool
            if (effectiveAllowedTools.isEmpty()) {
                log.warn("Ignoring mcp.allowedTools of application {}: no overlap with the application type's allowedTools",
                        application.getName());
            } else {
                mcp.setAllowedTools(effectiveAllowedTools);
            }
        }
        return mcp;
    }

    @SneakyThrows
    public CopyAppBucketOptions getCopyAppBucketOptions(Application application) {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application, false);
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
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application, false);
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
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application, false);
        if (customApplicationSchema == null) {
            return false;
        }
        JsonNode schemaNode = ProxyUtil.MAPPER.readTree(customApplicationSchema);
        return Optional.ofNullable(schemaNode.get(propName))
                .map(JsonNode::asBoolean).orElse(false);
    }

    @SneakyThrows
    @Nullable
    public String getStringProperty(Application application, String propName) {
        String customApplicationSchema = getCustomApplicationSchemaOrThrow(application, false);
        if (customApplicationSchema == null) {
            return null;
        }
        JsonNode schemaNode = ProxyUtil.MAPPER.readTree(customApplicationSchema);
        return Optional.ofNullable(schemaNode.get(propName))
                .map(JsonNode::asText).orElse(null);
    }

}