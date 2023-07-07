package com.epam.deltix.dial.proxy;

import com.epam.deltix.dial.proxy.config.ConfigStore;
import com.epam.deltix.dial.proxy.config.FileConfigStore;
import com.epam.deltix.dial.proxy.endpoint.EndpointBalancer;
import com.epam.deltix.dial.proxy.limiter.RateLimiter;
import com.epam.deltix.dial.proxy.log.GFLogStore;
import com.epam.deltix.dial.proxy.log.LogStore;
import com.epam.deltix.gflog.core.LogConfigFactory;
import com.epam.deltix.gflog.core.LogConfigurator;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ProxyApp {

    private JsonObject settings;
    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;

    public void start() throws Exception {
        LogConfigurator.configure(LogConfigFactory.loadDefault());

        try {
            settings = settings();
            vertx = Vertx.vertx(new VertxOptions(settings("vertx")));
            client = vertx.createHttpClient(new HttpClientOptions(settings("client")));

            ConfigStore configStore = new FileConfigStore(vertx, settings("config"));
            LogStore logStore = new GFLogStore(vertx);
            RateLimiter rateLimiter = new RateLimiter();
            EndpointBalancer endpointBalancer = new EndpointBalancer();

            RSAPublicKey publicKey = decodePublicKey(settings("identityProvider").getString("publicKey"));
            Proxy proxy = new Proxy(client, configStore, logStore, rateLimiter, endpointBalancer, publicKey);

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

    public void stop() {
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
        String file = System.getenv().getOrDefault("PROXY_SETTINGS", "proxy.settings.json");
        InputStream stream;

        try {
            stream = new FileInputStream(file);
        } catch (FileNotFoundException e) {
            stream = ProxyApp.class.getClassLoader().getResourceAsStream(file);
        }

        if (stream == null) {
            throw new FileNotFoundException("Proxy settings file is not found: " + file);
        }

        try (InputStream resource = stream) {
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new JsonObject(json);
        }
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

    private static RSAPublicKey decodePublicKey(String encodedKey) throws Exception {
        byte[] publicKeyByteServer = Base64.getDecoder().decode(encodedKey);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyByteServer));
    }

    public static void main(String[] args) throws Exception {
        new ProxyApp().start();
    }
}
