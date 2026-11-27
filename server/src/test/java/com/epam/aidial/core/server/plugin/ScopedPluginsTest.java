package com.epam.aidial.core.server.plugin;

import com.epam.aidial.core.plugin.HostDelegate;
import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.plugin.Request;
import com.epam.aidial.core.plugin.Response;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScopedPluginsTest {

    private final ClassLoader previous = Thread.currentThread().getContextClassLoader();
    private final ClassLoader origin = new ClassLoader(previous) { };

    @Test
    void testProviderCallsRunUnderPluginLoaderAndRestore() {
        PluginProvider delegate = mock(PluginProvider.class);
        Plugin plugin = mock(Plugin.class);
        when(delegate.type()).thenAnswer(ignored -> expectOrigin("t"));
        when(delegate.validate(any())).thenAnswer(ignored -> expectOrigin(List.of()));
        when(delegate.create(any(), any())).thenAnswer(ignored -> expectOrigin(plugin));
        doAnswer(ignored -> expectOrigin(null)).when(plugin).close();
        PluginProvider provider = new ScopedPlugins.ScopedProvider(delegate, origin);

        provider.type();
        provider.validate(JsonNodeFactory.instance.objectNode());
        Plugin created = provider.create(JsonNodeFactory.instance.objectNode(), null);
        assertSame(previous, Thread.currentThread().getContextClassLoader());
        assertSame(plugin, assertInstanceOf(ScopedPlugins.ScopedPlugin.class, created).delegate());

        created.close();
        verify(plugin).close();
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    @Test
    void testNullsStayNull() {
        PluginProvider delegate = mock(PluginProvider.class);
        assertNull(new ScopedPlugins.ScopedProvider(delegate, origin).create(JsonNodeFactory.instance.objectNode(), null));
        Plugin plugin = mock(Plugin.class);
        assertNull(new ScopedPlugins.ScopedPlugin(plugin, origin).handle(null, null));
        when(plugin.handle(any(), any())).thenReturn(Future.succeededFuture());
        assertNull(new ScopedPlugins.ScopedPlugin(plugin, origin).handle(null, null).result());
    }

    @Test
    void testExceptionRestoresLoader() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.handle(any(), any())).thenThrow(new NoClassDefFoundError("private dependency"));
        assertThrows(NoClassDefFoundError.class, () -> new ScopedPlugins.ScopedPlugin(plugin, origin).handle(null, null));
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testLoaderRestoredBeforeFutureCompletesAndResponseStaysScoped() {
        Promise<Response> pending = Promise.promise();
        Plugin plugin = mock(Plugin.class);
        when(plugin.handle(any(Request.class), any(HostDelegate.class))).thenAnswer(ignored -> expectOrigin(pending.future()));
        Future<Response> answer = new ScopedPlugins.ScopedPlugin(plugin, origin).handle(mock(Request.class), mock(HostDelegate.class));
        assertFalse(answer.isComplete());
        assertSame(previous, Thread.currentThread().getContextClassLoader());

        Response response = mock(Response.class);
        ReadStream<Buffer> body = mock(ReadStream.class);
        when(response.statusCode()).thenAnswer(ignored -> expectOrigin(200));
        when(response.headers()).thenAnswer(ignored -> expectOrigin(MultiMap.caseInsensitiveMultiMap()));
        when(response.body()).thenAnswer(ignored -> expectOrigin(body));
        doAnswer(ignored -> expectOrigin(null)).when(response).cancel();
        when(body.handler(any())).thenAnswer(ignored -> expectOrigin(body));
        when(body.exceptionHandler(any())).thenAnswer(ignored -> expectOrigin(body));
        when(body.endHandler(any())).thenAnswer(ignored -> expectOrigin(body));
        when(body.pause()).thenAnswer(ignored -> expectOrigin(body));
        when(body.resume()).thenAnswer(ignored -> expectOrigin(body));
        when(body.fetch(anyLong())).thenAnswer(ignored -> expectOrigin(body));
        pending.complete(response);

        Response scoped = answer.result();
        scoped.statusCode();
        scoped.headers();
        scoped.cancel();
        ReadStream<Buffer> stream = scoped.body();
        assertSame(stream, stream.handler(buffer -> { }).exceptionHandler(error -> { }).endHandler(ignored -> { })
                .pause().resume().fetch(1));
        verify(body).fetch(1);
        verify(response).cancel();
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    private <T> T expectOrigin(T result) {
        assertSame(origin, Thread.currentThread().getContextClassLoader());
        return result;
    }
}
