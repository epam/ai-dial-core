package com.epam.aidial.core;

import com.epam.aidial.core.config.ConfigStore;
import com.epam.aidial.core.config.Encryption;
import com.epam.aidial.core.config.FileConfigStore;
import com.epam.aidial.core.config.Storage;
import com.epam.aidial.core.limiter.RateLimiter;
import com.epam.aidial.core.log.GfLogStore;
import com.epam.aidial.core.log.LogStore;
import com.epam.aidial.core.security.AccessTokenValidator;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.upstream.UpstreamBalancer;
import com.epam.deltix.gflog.core.LogConfigurator;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.vertx.config.spi.utils.JsonObjectHelper;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import lombok.Getter;
import lombok.Setter;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ConfigSupport;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Setter
@Getter
public class AiDial {

    private JsonObject settings;
    private JsonObject extraSettings = new JsonObject();
    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;

    private RedissonClient redisCache;
    private RedissonClient redisStore;
    private BlobStorage storage;
    private ResourceService resourceService;

    @VisibleForTesting
    void start() throws Exception {
        System.setProperty("io.opentelemetry.context.contextStorageProvider", "io.vertx.tracing.opentelemetry.VertxContextStorageProvider");
        try {
            settings = settings(extraSettings);
            VertxOptions vertxOptions = new VertxOptions(settings("vertx"));
            setupMetrics(vertxOptions);
            setupTracing(vertxOptions);

            vertx = Vertx.vertx(vertxOptions);
            client = vertx.createHttpClient(new HttpClientOptions(settings("client")));

            ConfigStore configStore = new FileConfigStore(vertx, settings("config"));
            LogStore logStore = new GfLogStore(vertx);
            RateLimiter rateLimiter = new RateLimiter();
            UpstreamBalancer upstreamBalancer = new UpstreamBalancer();
            AccessTokenValidator accessTokenValidator = new AccessTokenValidator(settings("identityProviders"), vertx);
            if (storage == null) {
                Storage storageConfig = Json.decodeValue(settings("storage").toBuffer(), Storage.class);
                storage = new BlobStorage(storageConfig);
            }
            EncryptionService encryptionService = new EncryptionService(Json.decodeValue(settings("encryption").toBuffer(), Encryption.class));
            openRedis();

            resourceService = new ResourceService(vertx, redisCache, redisStore, storage, settings("resources"));
            Proxy proxy = new Proxy(vertx, client, configStore, logStore,
                    rateLimiter, upstreamBalancer, accessTokenValidator,
                    storage, encryptionService, resourceService);

            server = vertx.createHttpServer(new HttpServerOptions(settings("server"))).requestHandler(proxy);
            open(server, HttpServer::listen);

            log.info("Proxy started on {}", server.actualPort());
        } catch (Throwable e) {
            log.warn("Proxy failed to start:", e);
            stop();
            throw e;
        }
    }

    private void openRedis() throws IOException {
        if (redisStore == null) {
            redisStore = createRedis("store");
        }

        if (redisCache == null) {
            redisCache = createRedis("cache");
        }

        if (redisStore == null) {
            redisStore = redisCache;
        }

        if (redisCache == null) {
            redisCache = redisStore;
        }
    }

    private RedissonClient createRedis(String key) throws IOException {
        JsonObject conf = settings("redis");
        if (conf.isEmpty()) {
            return null;
        }

        conf = conf.getJsonObject(key, new JsonObject());
        if (conf.isEmpty()) {
            return null;
        }

        ConfigSupport support = new ConfigSupport();
        Config config = support.fromJSON(conf.toString(), Config.class);

        return Redisson.create(config);
    }

    @VisibleForTesting
    void stop() {
        try {
            close(server, HttpServer::close);
            close(client, HttpClient::close);
            close(resourceService);
            close(vertx, Vertx::close);
            close(storage);
            close(redisCache);
            close(redisStore);
            log.info("Proxy stopped");
            LogConfigurator.unconfigure();
        } catch (Throwable e) {
            log.warn("Proxy failed to stop:", e);
            LogConfigurator.unconfigure();
            System.exit(-1);
        }
    }

    private JsonObject settings(String key) {
        return settings.getJsonObject(key, new JsonObject());
    }

    private static JsonObject settings(JsonObject extraSettings) throws Exception {
        return defaultSettings()
                .mergeIn(fileSettings(), true)
                .mergeIn(envSettings(), true)
                .mergeIn(extraSettings, true);
    }

    private static JsonObject defaultSettings() throws IOException {
        String file = "aidial.settings.json";

        try (InputStream stream = AiDial.class.getClassLoader().getResourceAsStream(file)) {
            Objects.requireNonNull(stream, "Default resource file with settings is not found");
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new JsonObject(json);
        }
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

    public static void main(String[] args) throws Exception {
        AiDial dial = new AiDial();
        dial.start();
        Runtime.getRuntime().addShutdownHook(new Thread(dial::stop, "shutdown-hook"));
    }

    private static void setupMetrics(VertxOptions options) {
        MetricsOptions metrics = options.getMetricsOptions();
        if (metrics == null || !metrics.isEnabled()) {
            return;
        }

        JsonObject oltp = metrics.toJson().getJsonObject("oltpOptions", new JsonObject());
        if (oltp == null || !oltp.getBoolean("enabled", false)) {
            return;
        }

        MicrometerMetricsOptions micrometer = new MicrometerMetricsOptions(metrics.toJson());
        micrometer.setMicrometerRegistry(new OtlpMeterRegistry());

        options.setMetricsOptions(micrometer);
    }

    private static void setupTracing(VertxOptions vertxOptions) {
        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        vertxOptions.setTracingOptions(new OpenTelemetryOptions(openTelemetry));
    }
}
