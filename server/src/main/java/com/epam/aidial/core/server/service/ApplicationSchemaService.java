package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.metaschemas.CopyAppBucketOptions;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.resource.ComplexResourceService;
import com.epam.aidial.core.server.util.ApplicationTypeSchemaProcessingException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.validation.ApplicationTypeResourceException;
import com.epam.aidial.core.server.validation.ApplicationTypeSchemaValidationException;
import com.epam.aidial.core.server.validation.CollectionIssueKind;
import com.epam.aidial.core.server.validation.DialFileKeyword;
import com.epam.aidial.core.server.validation.DialMetaKeyword;
import com.epam.aidial.core.server.validation.DialResourceKeyKeyword;
import com.epam.aidial.core.server.validation.ListCollector;
import com.epam.aidial.core.server.validation.MarkerCollection;
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
import com.networknt.schema.JsonSchemaException;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.DisallowSchemaLoader;
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

    /**
     * The factory of the export: the same meta-schema and keywords as {@link #SCHEMA_FACTORY}, but it refuses every
     * {@code $ref} that is not local to the document, so a schema document cannot make Core fetch or read anything.
     */
    private static final JsonSchemaFactory EXPORT_SCHEMA_FACTORY = JsonSchemaFactory.builder()
            .metaSchema(DIAL_META_SCHEMA)
            .defaultMetaSchemaIri(DIAL_META_SCHEMA.getIri())
            .schemaLoaders(loaders -> loaders.add(DisallowSchemaLoader.getInstance()))
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
    private final ComplexResourceService complexResourceService;

    private final EncryptionService encryptionService;

    private final HttpClient httpClient;

    private static final class CachedSchema {
        private final String raw;
        private final JsonNode parsed;
        private volatile JsonSchema compiled;

        CachedSchema(String raw, JsonNode parsed) {
            this.raw = raw;
            this.parsed = parsed;
        }

        JsonSchema compiled() {
            JsonSchema result = compiled;
            if (result == null) {
                result = SCHEMA_FACTORY.getSchema(raw);
                compiled = result;
            }
            return result;
        }
    }

    private final ConcurrentHashMap<URI, CachedSchema> schemaCache = new ConcurrentHashMap<>();

    public ApplicationSchemaService(ResourceService resourceService, ConfigStore configStore,
                                    ComplexResourceService complexResourceService,
                                    EncryptionService encryptionService, @Nullable ProxySelector proxySelector) {
        this.resourceService = resourceService;
        this.configStore = configStore;
        this.complexResourceService = complexResourceService;
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
                             ComplexResourceService complexResourceService,
                             EncryptionService encryptionService, HttpClient httpClient) {
        this.resourceService = resourceService;
        this.configStore = configStore;
        this.complexResourceService = complexResourceService;
        this.encryptionService = encryptionService;
        this.httpClient = httpClient;
    }

    @VisibleForTesting
    int schemaCacheSize() {
        return schemaCache.size();
    }

    @SneakyThrows
    @Nullable
    public String getSchema(URI schemaId, boolean forceReload) {
        CachedSchema cached = resolveSchema(schemaId, forceReload);
        return cached == null ? null : cached.raw;
    }

    @SneakyThrows
    @Nullable
    private CachedSchema resolveSchema(URI schemaId, boolean forceReload) {
        if (!forceReload) {
            CachedSchema cached = schemaCache.get(schemaId);
            if (cached != null) {
                return cached;
            }
        }
        CachedSchema result = buildSchema(configStore.get().getCustomApplicationSchema(schemaId), false);
        if (result == null) {
            schemaCache.remove(schemaId);
            return null;
        }
        schemaCache.put(schemaId, result);
        return result;
    }

    /**
     * Builds the schema from its stored document and, when it declares {@code dial:applicationTypeSchemaEndpoint}, from a
     * download of the runner's document. It neither reads nor writes the cache.
     *
     * @param quiet true for the export: a failed download then writes no endpoint, response or exception to a log, raises an exception that
     *              carries no endpoint and no cause, and an interrupt is restored and rethrown. Request processing passes false and
     *              behaves exactly as before: the endpoint and the cause are logged and wrapped, an interrupt included
     */
    @SneakyThrows
    @Nullable
    private CachedSchema buildSchema(@Nullable String customApplicationSchema, boolean quiet) {
        if (customApplicationSchema == null) {
            return null;
        }
        JsonNode schema = ProxyUtil.MAPPER.readTree(customApplicationSchema);
        if (schema.has(MetaSchemaHolder.DIAL_APPLICATION_TYPE_SCHEMA_ENDPOINT)) {
            String url = schema.get(MetaSchemaHolder.DIAL_APPLICATION_TYPE_SCHEMA_ENDPOINT).textValue();
            try {
                ObjectNode appSchema = downloadAppSchema(url, quiet);
                merge(appSchema, schema);
                return new CachedSchema(appSchema.toString(), appSchema);
            } catch (Exception e) {
                if (quiet) {
                    if (e instanceof InterruptedException) {
                        // an interrupt is neither a failure of the runner nor a reason to go on
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    log.warn("Failed to download application schema");
                    throw new ApplicationTypeSchemaProcessingException("Failed to download application schema");
                }
                log.warn("Failed to download application schema: {}", url, e);
                throw new ApplicationTypeSchemaProcessingException("Failed to download application schema: " + url, e);
            }
        }
        return new CachedSchema(customApplicationSchema, schema);
    }

    @SneakyThrows
    String getCustomApplicationSchemaOrThrow(Application application, boolean forceReload) {
        CachedSchema cached = getCachedSchemaOrThrow(application, forceReload);
        return cached == null ? null : cached.raw;
    }

    @SneakyThrows
    @Nullable
    private CachedSchema getCachedSchemaOrThrow(Application application, boolean forceReload) {
        URI schemaId = application.getApplicationTypeSchemaId();
        if (schemaId == null) {
            return null;
        }
        CachedSchema cached = resolveSchema(schemaId, forceReload);
        if (cached == null) {
            throw new ApplicationTypeSchemaValidationException("Custom application schema not found: " + schemaId);
        }
        return cached;
    }

    private void merge(ObjectNode appSchema, JsonNode schema) {
        Iterator<String> fieldNames = schema.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            appSchema.set(field, schema.get(field));
        }
    }

    @SneakyThrows
    private ObjectNode downloadAppSchema(String url, boolean quiet) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        String body = response.body();
        if (status != 200) {
            if (quiet) {
                log.debug("Error of downloading application schema: status {}", status);
            } else {
                log.debug("Error of downloading application schema {}: status {}, response {}, headers: {}",
                        request.uri(), response.statusCode(), response.body(), response.headers());
            }
            throw new HttpException(status, "Application runner returned error on downloading application schema");
        }
        JsonNode tree = ProxyUtil.MAPPER.readTree(body);
        if (!tree.isObject()) {
            throw new ApplicationTypeSchemaProcessingException("Application schema is not JSON object");
        }
        return (ObjectNode) tree;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filterProperties(Map<String, Object> applicationProperties, JsonSchema appSchema, ListCollector.MetaDataCollectorType collector) {
        try {
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
        CachedSchema cached = getCachedSchemaOrThrow(application, false);
        try {
            if (cached == null) {
                consumer.accept(application.getApplicationProperties(), true);
                return;
            }
            JsonNode schemaNode = cached.parsed;
            boolean appendApplicationPropertiesHeader = !schemaNode.has(MetaSchemaHolder.APPLICATION_TYPE_APPEND_APPLICATION_PROPERTIES)
                                                        || schemaNode.get(MetaSchemaHolder.APPLICATION_TYPE_APPEND_APPLICATION_PROPERTIES).asBoolean();
            Map<String, Object> serverProperties = filterProperties(application.getApplicationProperties(),
                    cached.compiled(), ListCollector.MetaDataCollectorType.ALL);
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
            CachedSchema cached = getCachedSchemaOrThrow(application, false);
            JsonNode schemaNode = cached.parsed;

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
        } catch (IllegalArgumentException e) {
            throw new ApplicationTypeSchemaProcessingException("Failed to get custom application endpoints", e);
        }
    }

    @SneakyThrows
    public String getChatCompletionEndpoint(Application application) {
        CachedSchema cached = getCachedSchemaOrThrow(application, false);
        return getEndpoint(cached.parsed, APPLICATION_TYPE_COMPLETION_ENDPOINT, false);
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
        CachedSchema cached = getCachedSchemaOrThrow(application, false);
        if (cached == null) {
            return application;
        }
        if (application.getApplicationProperties() == null) {
            return application;
        }
        Application copy = new Application(application);
        Map<String, Object> appWithClientOptionsOnly = filterProperties(application.getApplicationProperties(),
                cached.compiled(), ListCollector.MetaDataCollectorType.CLIENT);
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

    /**
     * The schema of an application as the export holds it: evaluable, or the reason why it is not.
     *
     * @param schema the schema, built with the factory that refuses a {@code $ref} outside the document; {@code null} when there is an issue
     * @param issue  {@code SCHEMA_NOT_FOUND} when nothing holds the id, {@code SCHEMA_UNAVAILABLE} when the schema, or the document of its
     *               Runner, cannot be evaluated; {@code null} when there is a schema
     */
    public record ExportSchema(@Nullable JsonSchema schema, @Nullable CollectionIssueKind issue) {
    }

    /**
     * Builds the schema with the given id from the given configuration, so that an export which holds one snapshot looks the schema up in
     * the same generation as everything else. The schema is built as request processing builds it, through the same download of the
     * Runner's document, but live: the cache is neither read nor written, so the export never sees a stale schema and never replaces the
     * one that request processing uses. A missing schema and an unusable remote part are an {@link ExportSchema} with an issue, not an
     * exception; any other failure propagates.
     */
    public ExportSchema buildExportSchema(URI schemaId, Config config) {
        CachedSchema cached;
        try {
            cached = buildSchema(config.getCustomApplicationSchema(schemaId), true);
        } catch (ApplicationTypeSchemaProcessingException e) {
            return new ExportSchema(null, CollectionIssueKind.SCHEMA_UNAVAILABLE);
        }
        if (cached == null) {
            return new ExportSchema(null, CollectionIssueKind.SCHEMA_NOT_FOUND);
        }
        try {
            return new ExportSchema(EXPORT_SCHEMA_FACTORY.getSchema(cached.raw), null);
        } catch (JsonSchemaException e) {
            return new ExportSchema(null, CollectionIssueKind.SCHEMA_UNAVAILABLE);
        }
    }

    /**
     * Collects the marked values of the application's properties against a schema that the caller already holds. The collector parses
     * nothing, checks no existence and authorizes nothing: it returns the original values with their marker kind and RFC 6901 pointer.
     *
     * <p>Issues carry no library message and no value, because a message can hold the instance value or an IRI.
     */
    public MarkerCollection collectMarkerReferences(Application application, JsonSchema schema) {
        String propertiesJson;
        try {
            propertiesJson = ProxyUtil.MAPPER.writeValueAsString(application.getApplicationProperties());
        } catch (JsonProcessingException e) {
            throw new ApplicationTypeSchemaProcessingException("Failed to serialize the properties of the application", e);
        }
        return MarkerCollection.collect(schema, propertiesJson);
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
                        if (!descriptor.isFolder() && !hasResource(descriptor)) {
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

    private boolean hasResource(ResourceDescriptor resource) {
        return resource.getType() == ResourceTypes.SKILL
                ? complexResourceService.hasResource(resource)
                : resourceService.hasResource(resource);
    }

    @Nullable
    private Object getCollector(Application application, String collectorName, boolean forceReload) throws JsonProcessingException {
        CachedSchema cached = getCachedSchemaOrThrow(application, forceReload);
        if (cached == null) {
            return null;
        }
        JsonSchema appSchema = cached.compiled();
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
        CachedSchema cached = getCachedSchemaOrThrow(application, false);
        if (cached == null) {
            return null;
        }
        JsonNode schemaNode = cached.parsed;
        JsonNode appRoutes = schemaNode.get(APPLICATION_TYPE_ROUTES);
        if (appRoutes == null) {
            return null;
        }
        return ProxyUtil.MAPPER.treeToValue(appRoutes, APP_ROUTE_TYPE_REF);
    }

    @Nullable
    @SneakyThrows
    public Application.Mcp getMcp(Application application) {
        CachedSchema cached = getCachedSchemaOrThrow(application, false);
        if (cached == null) {
            return null;
        }
        JsonNode schemaNode = cached.parsed;
        JsonNode mcp = schemaNode.get(DIAL_APPLICATION_TYPE_MCP);
        if (mcp == null) {
            return null;
        }
        return ProxyUtil.MAPPER.treeToValue(mcp, APP_MCP_TYPE_REF);
    }

    @SneakyThrows
    public CopyAppBucketOptions getCopyAppBucketOptions(Application application) {
        CachedSchema cached = getCachedSchemaOrThrow(application, false);
        if (cached == null) {
            return CopyAppBucketOptions.DISABLED;
        }
        JsonNode schemaNode = cached.parsed;
        JsonNode options = schemaNode.get(DIAL_APPLICATION_TYPE_BUCKET_COPY);
        if (options == null) {
            return CopyAppBucketOptions.DISABLED;
        }
        return ProxyUtil.MAPPER.treeToValue(options, COPY_APP_BUCKET_OPTIONS_TYPE_REF);
    }

    @SneakyThrows
    public List<String> getInterceptors(Application application) {
        CachedSchema cached = getCachedSchemaOrThrow(application, false);
        if (cached == null) {
            return List.of();
        }
        JsonNode schemaNode = cached.parsed;
        JsonNode interceptors = schemaNode.get(DIAL_APPLICATION_TYPE_INTERCEPTORS);
        if (interceptors == null) {
            return List.of();
        }
        return ProxyUtil.MAPPER.treeToValue(interceptors, APP_INTERCEPTOR_LIST_REF);
    }

    @SneakyThrows
    private boolean getBooleanProperty(Application application, String propName) {
        CachedSchema cached = getCachedSchemaOrThrow(application, false);
        if (cached == null) {
            return false;
        }
        return Optional.ofNullable(cached.parsed.get(propName))
                .map(JsonNode::asBoolean).orElse(false);
    }

    @SneakyThrows
    @Nullable
    public String getStringProperty(Application application, String propName) {
        CachedSchema cached = getCachedSchemaOrThrow(application, false);
        if (cached == null) {
            return null;
        }
        return Optional.ofNullable(cached.parsed.get(propName))
                .map(JsonNode::asText).orElse(null);
    }

}