package com.epam.aidial.core.server.plugin;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Plugin;
import com.epam.aidial.core.plugin.OriginPlugin;
import com.epam.aidial.core.server.config.TestEchoPluginProvider;
import com.epam.aidial.core.server.config.TestOriginPluginProvider;
import com.epam.aidial.core.server.config.TestOriginPluginProvider.Closed;
import com.epam.aidial.core.server.plugin.OriginPluginManager.Lease;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OriginPluginManagerTest {

    private static final PluginRegistry REGISTRY = PluginRegistry.load(Path.of("no-plugins-dir"));

    // a real one: the executor that closes plugins needs a context
    private final Vertx vertx = Vertx.vertx();
    private final OriginPluginManager origins = new OriginPluginManager(REGISTRY, vertx, new AsyncTaskExecutor(vertx, new JsonObject()));

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
        OriginPlugin first = origins.resolve(config, "p1");

        assertSame(first, origins.resolve(config, "p1"));
        // a rebuild makes new objects for the same declaration
        assertSame(first, origins.resolve(configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("hello"))), "p1"));
    }

    @Test
    void testNewPluginAfterSettingsChange() {
        OriginPlugin hello = origins.resolve(configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("hello"))), "p1");

        assertNotSame(hello, origins.resolve(configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("hi"))), "p1"));
    }

    @Test
    void testReplacedPluginClosedOnceAfterItsLastRelease() throws Exception {
        Lease hello = origins.acquire(configWith(origin("hello")), "p1");
        origins.resolve(configWith(origin("hi")), "p1");
        Thread.sleep(100);

        assertEquals(List.of(), TestOriginPluginProvider.CLOSED);
        hello.onDone().run();
        awaitClosed(1);
        assertEquals("hello", TestOriginPluginProvider.CLOSED.get(0).greeting());
    }

    @Test
    void testCloseRunsOffTheEventLoop() throws Exception {
        Lease hello = origins.acquire(configWith(origin("hello")), "p1");
        origins.resolve(configWith(origin("hi")), "p1");

        vertx.runOnContext(ignored -> hello.onDone().run());
        awaitClosed(1);
        assertFalse(TestOriginPluginProvider.CLOSED.get(0).onEventLoop());
    }

    @Test
    void testAcquireAfterTheResolvedPluginClosedGetsAnOpenOne() throws Exception {
        Config config = configWith(origin("hello"));
        // as ProxyClient.embedded does, before the attempt is sent
        OriginPlugin resolved = origins.resolve(config, "p1");
        origins.resolve(configWith(origin("hi")), "p1");
        awaitClosed(1);

        Lease lease = origins.acquire(config, "p1");
        assertNotSame(resolved, lease.plugin());
        lease.onDone().run();
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
        OriginPlugin current = origins.resolve(hi, "p1");
        awaitClosed(1);

        Lease lease = origins.acquire(hi, "p1");
        assertSame(current, lease.plugin());
        lease.onDone().run();
    }

    @Test
    void testCloseClosesCurrentRetiredAndBusyPluginsOnce() throws Exception {
        origins.resolve(configWith(origin("idle")), "p1");
        Lease busy = origins.acquire(configWith(origin("busy")), "p1");
        awaitClosed(1);
        Config current = new Config();
        // a close that throws fails neither the others nor the future
        current.setPlugins(Map.of("p1", new Plugin(TestOriginPluginProvider.TYPE, greeting("current").put("closeFails", true)),
                "p2", origin("other")));
        origins.resolve(current, "p1");
        origins.resolve(current, "p2");

        await(origins.close());
        busy.onDone().run();
        Thread.sleep(100);

        assertEquals(List.of("busy", "current", "idle", "other"),
                TestOriginPluginProvider.CLOSED.stream().map(Closed::greeting).sorted().toList());
        assertThrows(IllegalStateException.class, () -> origins.resolve(current, "p1"));
        assertThrows(IllegalStateException.class, () -> origins.acquire(current, "p1"));
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
    void testProviderThatServesNoRequests() {
        assertFailure(configWith(new Plugin(TestEchoPluginProvider.TYPE, greeting("hi"))), "serves no requests");
    }

    @Test
    void testPluginThatCannotBeCreated() {
        ObjectNode settings = JsonNodeFactory.instance.objectNode().put("failCreate", true);

        assertFailure(configWith(new Plugin(TestOriginPluginProvider.TYPE, settings)), "Plugin 'p1' cannot be created");
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
