package com.epam.aidial.core.server;

import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.credentials.service.ResourceAuthorizationClient;
import com.epam.aidial.core.credentials.service.ResourceCredentialsManager;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.credentials.service.TokenService;
import com.epam.aidial.core.credentials.service.metadata.AuthorizationServerMetadataService;
import com.epam.aidial.core.credentials.service.metadata.ProtectedResourceMetadataService;
import com.epam.aidial.core.credentials.service.registration.ResourceRegistrationService;
import com.epam.aidial.core.credentials.validation.AuthorizationServerMetadataValidator;
import com.epam.aidial.core.credentials.validation.ProtectedResourceMetadataValidator;
import com.epam.aidial.core.credentials.validation.ResourceAuthSettingsValidator;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.config.FileConfigStore;
import com.epam.aidial.core.server.config.PathNormalizerSpanProcessor;
import com.epam.aidial.core.server.config.RouteNormalizingMeterFilter;
import com.epam.aidial.core.server.controller.HealthCheckController;
import com.epam.aidial.core.server.controller.WellKnownResourceMetadataController;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.GfLogStore;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.AccessTokenValidator;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationOperatorService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.ConsentService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.HeartbeatService;
import com.epam.aidial.core.server.service.InvitationService;
import com.epam.aidial.core.server.service.NotificationService;
import com.epam.aidial.core.server.service.PublicationService;
import com.epam.aidial.core.server.service.ResourceOperationService;
import com.epam.aidial.core.server.service.RuleService;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.server.service.UpstreamCacheService;
import com.epam.aidial.core.server.service.VertxTimerService;
import com.epam.aidial.core.server.service.WellKnownResourceMetadataService;
import com.epam.aidial.core.server.service.codeinterpreter.CodeInterpreterService;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.tracing.DialTracingFactory;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.cache.CacheClientFactory;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Clock;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.vertx.config.spi.utils.JsonObjectHelper;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Slf4j
@Setter
@Getter
public class AiDial {

    private static final Set<String> NON_PRINTABLE_SETTINGS = Set.of("secret", "password", "key", "credential", "identity");
    private JsonObject settings;
    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;

    private RedissonClient redis;
    private Proxy proxy;

    private AccessTokenValidator accessTokenValidator;

    private BlobStorage storage;
    private ResourceService resourceService;
    private EncryptionService encryptionService;

    private LongSupplier clock = System::currentTimeMillis;
    private Supplier<String> generator = () -> UUID.randomUUID().toString().replace("-", "");

    @VisibleForTesting
    void start() throws Exception {
        System.setProperty("io.opentelemetry.context.contextStorageProvider", "io.vertx.tracing.opentelemetry.VertxContextStorageProvider");
        try {
            settings = (settings == null) ? settings() : settings;
            printSettings(settings);
            VertxOptions vertxOptions = new VertxOptions(settings("vertx"));
            setupMetrics(vertxOptions);
            setupTracing(vertxOptions);

            vertx = Vertx.vertx(vertxOptions);
            HttpClientOptions clientOptions = new HttpClientOptions(settings("client"));
            client = vertx.createHttpClient(clientOptions);

            AsyncTaskExecutor taskExecutor = new AsyncTaskExecutor(vertx, settings("asyncTaskExecutor"));

            LogStore logStore = new GfLogStore();

            if (accessTokenValidator == null) {
                accessTokenValidator = new AccessTokenValidator(settings("identityProviders"), vertx, taskExecutor, client, clientOptions);
            }

            if (storage == null) {
                Storage storageConfig = Json.decodeValue(settings("storage").toBuffer(), Storage.class);
                storage = new BlobStorage(storageConfig);
            }
            encryptionService = new EncryptionService(settings("encryption"));

            redis = CacheClientFactory.create(toJsonNode(settings("redis")));

            LockService lockService = new LockService(redis, storage.getPrefix());
            TimerService timerService = new VertxTimerService(vertx, taskExecutor);
            ResourceService.Settings resourceServiceSettings = Json.decodeValue(settings("resources").toBuffer(), ResourceService.Settings.class);
            resourceService = new ResourceService(timerService, redis, storage, lockService, resourceServiceSettings, storage.getPrefix());
            InvitationService invitationService = new InvitationService(resourceService, encryptionService, settings("invitations"));
            ApiKeyStore apiKeyStore = new ApiKeyStore(taskExecutor, redis, storage.getPrefix(), settings("perRequestApiKey"));
            ConfigStore configStore = new FileConfigStore(vertx, settings("config"), apiKeyStore);
            ApplicationOperatorService operatorService = new ApplicationOperatorService(client, settings("applications"));
            ApplicationSchemaService applicationSchemaService = new ApplicationSchemaService(resourceService, configStore, encryptionService);
            ApplicationService applicationService = new ApplicationService(vertx, taskExecutor, redis, apiKeyStore, encryptionService,
                    resourceService, lockService, operatorService, applicationSchemaService, generator, settings("applications"));
            ShareService shareService = new ShareService(resourceService, invitationService, encryptionService, applicationService, lockService, applicationSchemaService);
            RuleService ruleService = new RuleService(resourceService);
            AccessService accessService = new AccessService(encryptionService, shareService, ruleService, applicationSchemaService, settings("access"));
            NotificationService notificationService = new NotificationService(resourceService, encryptionService);
            ResourceOperationService resourceOperationService = new ResourceOperationService(applicationService,
                    resourceService, invitationService, shareService, lockService);
            PublicationService publicationService = new PublicationService(encryptionService, resourceService, accessService,
                    ruleService, notificationService, applicationService, resourceOperationService, generator, clock);
            RateLimiter rateLimiter = new RateLimiter(taskExecutor, resourceService);
            CodeInterpreterService codeInterpreterService = new CodeInterpreterService(vertx, taskExecutor, redis, resourceService,
                    accessService, encryptionService, operatorService, generator, settings("codeInterpreter"));

            TokenStatsTracker tokenStatsTracker = new TokenStatsTracker(taskExecutor, resourceService);

            HeartbeatService heartbeatService = new HeartbeatService(
                    vertx, taskExecutor, settings("resources").getLong("heartbeatPeriod"));

            UpstreamCacheService upstreamCacheService = new UpstreamCacheService(redis, lockService, clock, storage.getPrefix());
            UpstreamRouteProvider upstreamRouteProvider = new UpstreamRouteProvider(vertx, taskExecutor, Random::new, upstreamCacheService);

            ResourceAuthorizationClient resourceAuthorizationClient = new ResourceAuthorizationClient();
            TokenService tokenService = new TokenService(resourceAuthorizationClient);
            ResourceRegistrationService resourceRegistrationService = getResourceRegistrationService(resourceAuthorizationClient);
            ResourceAuthSettingsValidator resourceAuthSettingsValidator = new ResourceAuthSettingsValidator();
            ResourceCredentialsService resourceCredentialsService = new ResourceCredentialsService();
            ResourceCredentialsManager resourceCredentialsManager = new ResourceCredentialsManager(resourceCredentialsService, tokenService);
            ResourceAuthSettingsService resourceAuthSettingsService = new ResourceAuthSettingsService(resourceRegistrationService,
                    resourceAuthSettingsValidator, resourceCredentialsManager);

            ToolSetService toolSetService = new ToolSetService(resourceService, resourceAuthSettingsService);

            DeploymentService deploymentService = new DeploymentService(encryptionService, applicationService, accessService,
                    toolSetService, resourceService);

            ConsentService consentService = new ConsentService(deploymentService, resourceService);

            HealthCheckController healthCheckController = new HealthCheckController(redis, taskExecutor);

            WellKnownResourceMetadataService wellKnownResourceMetadataService = new WellKnownResourceMetadataService(settings("toolsets"));
            WellKnownResourceMetadataController resourceMetadataController = new WellKnownResourceMetadataController(wellKnownResourceMetadataService);

            proxy = new Proxy(vertx, clientOptions, client, configStore, logStore,
                    rateLimiter, upstreamRouteProvider, accessTokenValidator,
                    storage, encryptionService, apiKeyStore, tokenStatsTracker, resourceService, invitationService,
                    shareService, publicationService, accessService, lockService, resourceOperationService, ruleService,
                    notificationService, applicationService, codeInterpreterService, heartbeatService, upstreamCacheService,
                    consentService, deploymentService, healthCheckController, wellKnownResourceMetadataService, resourceMetadataController,
                    toolSetService, applicationSchemaService, resourceCredentialsManager, resourceAuthSettingsService, taskExecutor, version());

            server = vertx.createHttpServer(new HttpServerOptions(settings("server"))).requestHandler(proxy);
            open(server, HttpServer::listen);
            log.info("Proxy started on {}", server.actualPort());
        } catch (Throwable e) {
            log.error("Proxy failed to start:", e);
            stop();
            throw e;
        }
    }

    private static ResourceRegistrationService getResourceRegistrationService(ResourceAuthorizationClient resourceAuthorizationClient) {
        ProtectedResourceMetadataValidator protectedResourceMetadataValidator = new ProtectedResourceMetadataValidator();
        ProtectedResourceMetadataService protectedResourceMetadataService = new ProtectedResourceMetadataService(
                resourceAuthorizationClient, protectedResourceMetadataValidator);

        AuthorizationServerMetadataValidator authorizationServerMetadataValidator = new AuthorizationServerMetadataValidator();
        AuthorizationServerMetadataService authorizationServerMetadataService = new AuthorizationServerMetadataService(
                resourceAuthorizationClient, protectedResourceMetadataService, authorizationServerMetadataValidator);
        return new ResourceRegistrationService(authorizationServerMetadataService, resourceAuthorizationClient);
    }

    @VisibleForTesting
    void stop() throws Exception {
        try {
            close(server, HttpServer::close);
            close(client, HttpClient::close);
            close(resourceService);
            close(vertx, Vertx::close);
            close(storage);
            close(redis);
            log.info("Proxy stopped");
        } catch (Throwable e) {
            log.warn("Proxy failed to stop:", e);
            throw e;
        }
    }

    @SneakyThrows
    private static JsonNode toJsonNode(JsonObject jsonObject) {
        return ProxyUtil.MAPPER.readTree(jsonObject.encode());
    }

    public static JsonObject settings() throws Exception {
        return defaultSettings()
                .mergeIn(fileSettings(), true)
                .mergeIn(envSettings(), true);
    }

    private JsonObject settings(String key) {
        return settings.getJsonObject(key, new JsonObject());
    }

    private static JsonObject defaultSettings() throws IOException {
        String file = "aidial.settings.json";

        try (InputStream stream = AiDial.class.getClassLoader().getResourceAsStream(file)) {
            Objects.requireNonNull(stream, "Default resource file with settings is not found");
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new JsonObject(json);
        }
    }

    private static String version() {
        String filename = "version";
        String version = "undefined";

        try (InputStream stream = AiDial.class.getClassLoader().getResourceAsStream(filename)) {
            Objects.requireNonNull(stream, "Version file not found");
            version = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to load version", e);
        }
        return version;
    }

    public static String getVersion() {
        return version();
    }

    private static JsonObject fileSettings() throws IOException {
        String file = System.getenv().get("AIDIAL_SETTINGS");
        if (file == null) {
            return new JsonObject();
        }

        try (InputStream stream = new FileInputStream(file)) {
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new JsonObject(json);
        }
    }

    private static JsonObject envSettings() {
        String[] prefixes = {"aidial.", "proxy."}; // "proxy." is deprecated
        Properties properties = new Properties();

        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            for (String prefix : prefixes) {
                if (key.startsWith(prefix)) {
                    String suffix = key.substring(prefix.length());
                    properties.put(suffix, value);
                    break;
                }
            }
        }

        return JsonObjectHelper.from(properties, false, true);
    }

    private static <R> void open(R resource, AsyncOpener<R> opener) throws Exception {
        CompletableFuture<R> startup = new CompletableFuture<>();
        opener.open(resource).onSuccess(startup::complete).onFailure(startup::completeExceptionally);
        startup.get(15, TimeUnit.SECONDS);
    }

    private static <R> void close(R resource, AsyncCloser<R> closer) throws Exception {
        if (resource != null) {
            CompletableFuture<Void> shutdown = new CompletableFuture<>();
            closer.close(resource).onSuccess(shutdown::complete).onFailure(shutdown::completeExceptionally);
            shutdown.get(15, TimeUnit.SECONDS);
        }
    }

    private static void close(AutoCloseable resource) throws Exception {
        if (resource != null) {
            resource.close();
        }
    }

    private static void close(RedissonClient resource) {
        if (resource != null) {
            resource.shutdown();
        }
    }

    private interface AsyncOpener<R> {
        Future<R> open(R resource);
    }

    private interface AsyncCloser<R> {
        Future<Void> close(R resource);
    }

    public static void main(String[] args) {
        AiDial dial = new AiDial();
        try {
            dial.start();
        } catch (Throwable e) {
            System.exit(-1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                dial.stop();
            } catch (Throwable e) {
                System.exit(-1);
            }
        }, "shutdown-hook"));
    }

    private static void setupMetrics(VertxOptions options) {
        MetricsOptions metrics = options.getMetricsOptions();
        if (metrics == null || !metrics.isEnabled()) {
            return;
        }

        MicrometerMetricsOptions micrometer = new MicrometerMetricsOptions(metrics.toJson());

        JsonObject prometheus = metrics.toJson().getJsonObject("prometheusOptions", new JsonObject());
        if (prometheus != null && prometheus.getBoolean("enabled", false)) {
            var prometheusReg = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            prometheusReg.config().meterFilter(new RouteNormalizingMeterFilter());
            micrometer.setMicrometerRegistry(prometheusReg);
        }

        JsonObject oltp = metrics.toJson().getJsonObject("oltpOptions", new JsonObject());
        if (oltp != null && oltp.getBoolean("enabled", false)) {
            var otlpReg = new OtlpMeterRegistry(oltp::getString, Clock.SYSTEM);
            otlpReg.config().meterFilter(new RouteNormalizingMeterFilter());
            micrometer.setMicrometerRegistry(otlpReg);
        }

        options.setMetricsOptions(micrometer);
    }

    private static void setupTracing(VertxOptions vertxOptions) {
        String otlMetricExporter = getOtlSetting("OTEL_METRICS_EXPORTER", "otel.metrics.exporter");
        if (otlMetricExporter == null) {
            System.setProperty("otel.metrics.exporter", "none");
        }
        String otlLogsExporter = getOtlSetting("OTEL_LOGS_EXPORTER", "otel.logs.exporter");
        if (otlLogsExporter == null) {
            System.setProperty("otel.logs.exporter", "none");
        }
        // disable trace exporter if the endpoint is not provided explicitly
        String otlExporterEndpoint = getOtlSetting("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "otel.exporter.otlp.traces.endpoint");
        if (otlExporterEndpoint == null) {
            System.setProperty("otel.traces.exporter", "none");
        }

        OpenTelemetry openTelemetry = AutoConfiguredOpenTelemetrySdk.builder()
                .addSpanProcessorCustomizer(((spanProcessor, configProperties) ->
                        SpanProcessor.composite(new PathNormalizerSpanProcessor(), spanProcessor)))
                .build()
                .getOpenTelemetrySdk();

        OpenTelemetryOptions otelOpts = new OpenTelemetryOptions(openTelemetry);
        otelOpts.setFactory(new DialTracingFactory(otelOpts.getFactory()));
        vertxOptions.setTracingOptions(otelOpts);
    }

    private static String getOtlSetting(String envVar, String systemProperty) {
        String val = System.getenv(envVar);
        if (val != null) {
            return val;
        }
        return System.getProperty(systemProperty);
    }

    private static void printSettings(Object settings) {
        log.debug("AI DIAL Core settings");
        log.debug("--------------- start --------------- ");
        printSettings(settings, new StringBuilder());
        log.debug("--------------- end --------------- ");
    }

    private static void printSettings(Object settings, StringBuilder path) {
        if (settings instanceof JsonObject jsonObject) {
            for (var entry : jsonObject.getMap().entrySet()) {
                printSettings(entry.getKey(), entry.getValue(), path);
            }
        } else if (settings instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                printSettings((String) entry.getKey(), entry.getValue(), path);
            }
        } else if (settings instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) {
                printSettings("[" + i + "]", array.getValue(i), path);
            }
        } else if (settings instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                printSettings("[" + i + "]", list.get(i), path);
            }
        } else {
            log.debug("Core setting: key -> {}, value -> {}", path, settings);
        }
    }

    private static void printSettings(String key, Object value, StringBuilder path) {
        if (NON_PRINTABLE_SETTINGS.contains(key)) {
            return;
        }
        int len = path.length();
        if (!path.isEmpty()) {
            path.append('.');
        }
        path.append(key);
        printSettings(value, path);
        path.setLength(len);
    }
}
