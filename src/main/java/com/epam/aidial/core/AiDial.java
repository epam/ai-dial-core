package com.epam.aidial.core;

import com.auth0.jwk.UrlJwkProvider;
import com.epam.aidial.core.config.ConfigStore;
import com.epam.aidial.core.config.FileConfigStore;
import com.epam.aidial.core.config.Storage;
import com.epam.aidial.core.limiter.RateLimiter;
import com.epam.aidial.core.log.GfLogStore;
import com.epam.aidial.core.log.LogStore;
import com.epam.aidial.core.security.AccessTokenValidator;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.upstream.UpstreamBalancer;
import com.epam.deltix.gflog.core.LogConfigurator;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
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
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
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
public class AiDial {

    private JsonObject settings;
    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;

    private BlobStorage storage;

    @VisibleForTesting
    void start() throws Exception {
        try {
            settings = settings();

            VertxOptions vertxOptions = new VertxOptions(settings("vertx"));
            setupMetrics(vertxOptions);

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
            Proxy proxy = new Proxy(vertx, client, configStore, logStore, rateLimiter, upstreamBalancer, accessTokenValidator, storage);

            server = vertx.createHttpServer(new HttpServerOptions(settings("server"))).requestHandler(proxy);
            open(server, HttpServer::listen);

            log.info("Proxy started on {}", server.actualPort());
        } catch (Throwable e) {
            log.warn("Proxy failed to start:", e);
            stop();
            throw e;
        }
    }

    @VisibleForTesting
    void stop() {
        try {
            close(server, HttpServer::close);
            close(client, HttpClient::close);
            close(vertx, Vertx::close);
            close(storage);
            log.info("Proxy stopped");
            LogConfigurator.unconfigure();
        } catch (Throwable e) {
            log.warn("Proxy failed to stop:", e);
            LogConfigurator.unconfigure();
            System.exit(-1);
        }
    }

    @VisibleForTesting
    HttpServer getServer() {
        return server;
    }

    @VisibleForTesting
    void setStorage(BlobStorage storage) {
        this.storage = storage;
    }

    private JsonObject settings(String key) {
        return settings.getJsonObject(key, new JsonObject());
    }

    private static JsonObject settings() throws Exception {
        return defaultSettings()
                .mergeIn(fileSettings(), true)
                .mergeIn(envSettings(), true);
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

    private static void close(Closeable resource) throws IOException {
        if (resource != null) {
            resource.close();
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
}
