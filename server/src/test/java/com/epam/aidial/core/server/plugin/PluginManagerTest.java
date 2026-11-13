package com.epam.aidial.core.server.plugin;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Plugin;
import com.epam.aidial.core.server.config.TestOriginPluginProvider;
import com.epam.aidial.core.server.config.TestOriginPluginProvider.Closed;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManagerTest {

    private static final PluginRegistry REGISTRY = PluginRegistry.load(Path.of("no-plugins-dir"));

    // a real one: the executor that closes plugins needs a context
    private final Vertx vertx = Vertx.vertx();
    private final PluginManager origins = new PluginManager(REGISTRY, vertx, new AsyncTaskExecutor(vertx, new JsonObject()));

    @BeforeEach
    void clearRecords() {
        TestOriginPluginProvider.reset();
    }

    @AfterEach
    void stopVertx() {
        vertx.close();
    }

    @Test
    void testPluginCreatedWithCoresVertx() {
        origins.resolve(configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("hello"))), "p1");

        assertSame(vertx, TestOriginPluginProvider.CREATED_WITH.get());
    }

    @Test
    void testSamePluginForTheSameAndForAnEqualDeclaration() {
        Config config = configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("hello")));
        com.epam.aidial.core.plugin.Plugin first = origins.resolve(config, "p1");

        assertSame(first, origins.resolve(config, "p1"));
        // a rebuild makes new objects for the same declaration
        assertSame(first, origins.resolve(configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("hello"))), "p1"));
    }

    @Test
    void testProviderChangingItsSettingsKeepsThePlugin() {
        com.epam.aidial.core.plugin.Plugin first = origins.resolve(configWith(new Plugin(TestOriginPluginProvider.TYPE,
                greeting("hello").put("mutateSettings", true))), "p1");

        // the rebuild's declaration equals the one the plugin was created from
        assertSame(first, origins.resolve(configWith(new Plugin(TestOriginPluginProvider.TYPE,
                greeting("hello").put("mutateSettings", true))), "p1"));
    }

    @Test
    void testPluginCreatedOutsideTheRequestsContext() throws Exception {
        ContextInternal request = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        CompletableFuture<com.epam.aidial.core.plugin.Plugin> created = new CompletableFuture<>();

        request.runOnContext(ignored -> {
            try {
                created.complete(origins.resolve(configWith(origin("hello")), "p1"));
            } catch (RuntimeException e) {
                created.completeExceptionally(e);
            }
        });

        assertNotNull(created.get(5, TimeUnit.SECONDS));
        assertFalse(TestOriginPluginProvider.CREATED_ON_DUPLICATE.get());
    }

    @Test
    void testNewPluginAfterSettingsChange() {
        com.epam.aidial.core.plugin.Plugin hello = origins.resolve(configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("hello"))), "p1");

        assertNotSame(hello, origins.resolve(configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("hi"))), "p1"));
    }

    @Test
    void testReplacedPluginClosedAtOnceOffTheEventLoop() throws Exception {
        origins.resolve(configWith(origin("hello")), "p1");

        // as a request does, on the event loop
        vertx.runOnContext(ignored -> origins.resolve(configWith(origin("hi")), "p1"));

        awaitClosed(1);
        assertEquals(new Closed("hello", false), TestOriginPluginProvider.CLOSED.get(0));
    }

    @Test
    void testRacingRequestsShareOnePlugin() throws Exception {
        Config config = configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("hello").put("createMillis", 200)));

        CompletableFuture<com.epam.aidial.core.plugin.Plugin> first = CompletableFuture.supplyAsync(() -> origins.resolve(config, "p1"));
        CompletableFuture<com.epam.aidial.core.plugin.Plugin> second = CompletableFuture.supplyAsync(() -> origins.resolve(config, "p1"));

        assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(List.of(), TestOriginPluginProvider.CLOSED);
    }

    @Test
    void testPluginCreatedDuringShutdownIsClosed() throws Exception {
        Config config = configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("late").put("createMillis", 300)));
        var resolving = CompletableFuture.supplyAsync(() -> origins.resolve(config, "p1"));
        Thread.sleep(100);

        await(origins.close());

        ExecutionException error = assertThrows(ExecutionException.class, () -> resolving.get(5, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("shutting down"), error.getCause().getMessage());
        awaitClosed(1);
    }

    @Test
    void testRemovedEntryClosedByTheRequestThatNamesIt() throws Exception {
        origins.resolve(configWith(origin("hello")), "p1");

        assertFailure(new Config(), "No plugins entry is named 'p1'");
        awaitClosed(1);
        assertEquals("hello", TestOriginPluginProvider.CLOSED.get(0).greeting());
    }

    @Test
    void testCloseThatThrowsChangesNothingElse() throws Exception {
        ObjectNode failing = greeting("hello").put("closeFails", true);
        origins.resolve(configWith(new Plugin(TestOriginPluginProvider.TYPE, failing)), "p1");
        Config hi = configWith(origin("hi"));
        com.epam.aidial.core.plugin.Plugin current = origins.resolve(hi, "p1");
        awaitClosed(1);

        assertSame(current, origins.resolve(hi, "p1"));
    }

    @Test
    void testCloseClosesEveryPluginOnce() throws Exception {
        origins.resolve(configWith(origin("replaced")), "p1");
        Config current = new Config();
        // a close that throws fails neither the others nor the future
        current.setPlugins(Map.of("p1", new Plugin(TestOriginPluginProvider.TYPE, greeting("current").put("closeFails", true)),
                "p2", origin("other")));
        origins.resolve(current, "p1");
        origins.resolve(current, "p2");
        awaitClosed(1);

        await(origins.close());
        Thread.sleep(100);

        assertEquals(List.of("current", "other", "replaced"),
                TestOriginPluginProvider.CLOSED.stream().map(Closed::greeting).sorted().toList());
        assertThrows(IllegalStateException.class, () -> origins.resolve(current, "p1"));
    }

    @Test
    void testMissingEntry() {
        assertFailure(new Config(), "No plugins entry is named 'p1'");
    }

    @Test
    void testProviderNotLoaded() {
        assertFailure(configWith(new Plugin("missing", null)), "No plugin provider of type 'missing' is loaded");
    }

    @Test
    void testPluginThatCannotBeCreated() {
        ObjectNode settings = JsonNodeFactory.instance.objectNode().put("failCreate", true);

        assertFailure(configWith(new Plugin(TestOriginPluginProvider.TYPE, settings)), "Plugin 'p1' cannot be created");
    }

    @Test
    void testProviderReturningNullCannotCreate() {
        ObjectNode settings = JsonNodeFactory.instance.objectNode().put("createNull", true);

        assertFailure(configWith(new Plugin(TestOriginPluginProvider.TYPE, settings)), "its provider returned null");
    }

    private void assertFailure(Config config, String message) {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> origins.resolve(config, "p1"));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static void awaitClosed(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (TestOriginPluginProvider.CLOSED.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(50);
        assertEquals(count, TestOriginPluginProvider.CLOSED.size(), TestOriginPluginProvider.CLOSED::toString);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static Plugin origin(String greeting) {
        return new Plugin(TestOriginPluginProvider.TYPE, greeting(greeting));
    }

    private static Config configWith(Plugin plugin) {
        Config config = new Config();
        config.setPlugins(Map.of("p1", plugin));
        return config;
    }

    private static ObjectNode greeting(String text) {
        return JsonNodeFactory.instance.objectNode().put("greeting", text);
    }
}
