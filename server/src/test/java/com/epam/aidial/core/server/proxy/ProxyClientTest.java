package com.epam.aidial.core.server.proxy;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.plugin.OriginPluginManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProxyClientTest {

    private static Vertx vertx;

    private ProxyClient client;
    private ProxyContext context;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        vertx.close();
    }

    @BeforeEach
    void createClient() {
        client = new ProxyClient(mock(HttpClient.class), vertx, mock(OriginPluginManager.class));
        // at a redecide or a notice the pass's deployment is the candidate that failed, here a user's custom application
        Application custom = new Application();
        custom.setName("applications/bucket-of-a-user/custom");
        context = mock(ProxyContext.class);
        when(context.getConfig()).thenReturn(new Config());
        when(context.getDeployment()).thenReturn(custom);
    }

    @Test
    void testOriginEntryRefusesCustomApplication() {
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> await(client.embedded(context, HttpMethod.POST, "plugin://p1/openai/v1/chat/completions", "p1")));

        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test
    void testRouterEntryDoesNotCheckTheDeployment() throws Exception {
        await(client.embeddedForRouter(context, HttpMethod.POST, "plugin://p1/decide", "p1", new ApiKeyData(), null));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
