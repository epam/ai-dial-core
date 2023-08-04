package com.epam.deltix.dial.proxy;

import com.epam.deltix.dial.proxy.config.ConfigStore;
import com.epam.deltix.dial.proxy.config.FileConfigStore;
import com.epam.deltix.dial.proxy.limiter.RateLimiter;
import com.epam.deltix.dial.proxy.log.GFLogStore;
import com.epam.deltix.dial.proxy.log.LogStore;
import com.epam.deltix.dial.proxy.security.IdentityProvider;
import com.epam.deltix.dial.proxy.upstream.UpstreamBalancer;
import com.epam.deltix.gflog.core.LogConfigurator;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.vertx.config.spi.utils.JsonObjectHelper;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.metrics.MetricsOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import lombok.extern.slf4j.Slf4j;

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
public class ProxyApp {

    private JsonObject settings;
    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;

    private void start() throws Exception {
        try {
            settings = settings();

            VertxOptions vertxOptions = new VertxOptions(settings("vertx"));
            setupMetrics(vertxOptions);

            vertx = Vertx.vertx(vertxOptions);
            client = vertx.createHttpClient(new HttpClientOptions(settings("client")));

            ConfigStore configStore = new FileConfigStore(vertx, settings("config"));
            LogStore logStore = new GFLogStore(vertx);
            RateLimiter rateLimiter = new RateLimiter();
            UpstreamBalancer upstreamBalancer = new UpstreamBalancer();

            IdentityProvider identityProvider = new IdentityProvider(settings("identityProvider"));
            Proxy proxy = new Proxy(client, configStore, logStore, rateLimiter, upstreamBalancer, identityProvider);

            server = vertx.createHttpServer(new HttpServerOptions(settings("server"))).requestHandler(proxy);
            open(server, HttpServer::listen);

            log.info("Proxy started on {}", server.actualPort());
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "shutdown-hook"));
        } catch (Throwable e) {
            log.warn("Proxy failed to start:", e);
            stop();
            throw e;
        }
    }

    private void stop() {
        try {
            close(server, HttpServer::close);
            close(client, HttpClient::close);
            close(vertx, Vertx::close);
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

    private static JsonObject settings() throws Exception {
        return defaultSettings()
                .mergeIn(fileSettings(), true)
                .mergeIn(envSettings(), true);
    }

    private static JsonObject defaultSettings() throws IOException {
        String file = "proxy.settings.json";

        try (InputStream stream = ProxyApp.class.getClassLoader().getResourceAsStream(file)) {
            Objects.requireNonNull(stream, "Default resource file with settings is not found");
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new JsonObject(json);
        }
    }

    private static JsonObject fileSettings() throws IOException {
        String file = System.getenv().get("PROXY_SETTINGS");
        if (file == null) {
            return new JsonObject();
        }

        try (InputStream stream = new FileInputStream(file)) {
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new JsonObject(json);
        }
    }

    private static JsonObject envSettings() {
        String prefix = "proxy.";
        Properties properties = new Properties();
        System.getenv().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(entry -> Map.entry(entry.getKey().substring(prefix.length()), entry.getValue()))
                .forEach(entry -> properties.put(entry.getKey(), entry.getValue()));

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

    private interface AsyncOpener<R> {
        Future<R> open(R resource);
    }

    private interface AsyncCloser<R> {
        Future<Void> close(R resource);
    }

    public static void main(String[] args) throws Exception {
        new ProxyApp().start();
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
