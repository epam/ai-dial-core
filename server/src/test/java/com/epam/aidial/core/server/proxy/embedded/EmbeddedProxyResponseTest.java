package com.epam.aidial.core.server.proxy.embedded;

import com.epam.aidial.core.plugin.Response;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddedProxyResponseTest {

    @Test
    @SuppressWarnings("unchecked")
    void testOriginLoaderScopesAccessorsAndStreamMethods() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader origin = new ClassLoader(previous) { };
        ReadStream<Buffer> stream = mock(ReadStream.class, invocation -> {
            assertSame(origin, Thread.currentThread().getContextClassLoader());
            return invocation.getMock();
        });
        AtomicInteger cancellations = new AtomicInteger();
        Response answer = new Response() {
            @Override
            public int statusCode() {
                assertSame(origin, Thread.currentThread().getContextClassLoader());
                return 201;
            }

            @Override
            public MultiMap headers() {
                assertSame(origin, Thread.currentThread().getContextClassLoader());
                return MultiMap.caseInsensitiveMultiMap().set("x-test", "yes");
            }

            @Override
            public ReadStream<Buffer> body() {
                assertSame(origin, Thread.currentThread().getContextClassLoader());
                return stream;
            }

            @Override
            public void cancel() {
                assertSame(origin, Thread.currentThread().getContextClassLoader());
                cancellations.incrementAndGet();
            }
        };
        EmbeddedProxyResponse response = new EmbeddedProxyResponse(answer,
                () -> assertSame(previous, Thread.currentThread().getContextClassLoader()), () -> { }, origin);
        assertSame(previous, Thread.currentThread().getContextClassLoader());
        assertEquals(201, response.statusCode());
        assertEquals("yes", response.getHeader("x-test"));
        response.exceptionHandler(null).handler(null).pause().resume().fetch(1).endHandler(null);
        assertSame(previous, Thread.currentThread().getContextClassLoader());
        response.body();
        assertSame(previous, Thread.currentThread().getContextClassLoader());
        response.discard();
        response.discard();
        assertEquals(1, cancellations.get());
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPluginHeadersMapAccessUsesOriginLoader() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader origin = new ClassLoader(previous) { };
        MultiMap headers = mock(MultiMap.class);
        when(headers.get((CharSequence) "x-test")).thenAnswer(invocation -> {
            assertSame(origin, Thread.currentThread().getContextClassLoader());
            return "yes";
        });
        Response answer = mock(Response.class);
        when(answer.headers()).thenReturn(headers);
        when(answer.body()).thenReturn(mock(ReadStream.class));
        EmbeddedProxyResponse response = new EmbeddedProxyResponse(answer, () -> { }, () -> { }, origin);
        assertEquals("yes", response.getHeader("x-test"));
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testThrowingStreamAndAccessorRestoreLoader() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader origin = new ClassLoader(previous) { };
        ReadStream<Buffer> stream = mock(ReadStream.class);
        Response answer = mock(Response.class);
        when(answer.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(answer.body()).thenReturn(stream);
        EmbeddedProxyResponse response = new EmbeddedProxyResponse(answer, () -> { }, () -> { }, origin);
        doAnswer(invocation -> {
            assertSame(origin, Thread.currentThread().getContextClassLoader());
            throw new NoClassDefFoundError("private dependency");
        }).when(stream).fetch(anyLong());
        assertThrows(NoClassDefFoundError.class, () -> response.fetch(1));
        assertSame(previous, Thread.currentThread().getContextClassLoader());
        doAnswer(invocation -> {
            assertSame(origin, Thread.currentThread().getContextClassLoader());
            throw new IllegalStateException("broken accessor");
        }).when(answer).statusCode();
        assertThrows(IllegalStateException.class, response::statusCode);
        assertSame(previous, Thread.currentThread().getContextClassLoader());
        verify(stream).fetch(1);
    }
}
