package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.tracing.BlockingCallTracer;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Callable;

import static com.epam.aidial.core.server.Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ChatCompletionsControllerTest {

    private static final Buffer BODY = Buffer.buffer("{\"model\":\"test-model\",\"messages\":[]}");

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @Mock
    private HttpServerRequest request;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    private ChatCompletionsController controller;

    @BeforeEach
    void setUp() {
        lenient().when(context.getConfig()).thenReturn(new Config());
        lenient().when(proxy.getBlockingCallTracer()).thenReturn(BlockingCallTracer.NOOP);
        controller = new ChatCompletionsController(proxy, context);
    }

    @Test
    public void testUnsupportedContentType() {
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn("text/plain");

        controller.handle();

        verify(context).respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only application/json is supported");
        verify(request, never()).body();
    }

    @Test
    public void testBodyReadFailureRespondsUnprocessableEntity() {
        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.failedFuture(new RuntimeException("connection reset")));

        controller.handle();

        verify(context).respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
        verify(context, never()).setRequestBody(any());
        verify(request, times(1)).body();
    }

    @Test
    public void testInterceptorReentryReadsBodyOnceAndDispatchesToNextInterceptor() {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("per-request-key");
        apiKeyData.setInterceptors(List.of("interceptor1", "interceptor2"));
        apiKeyData.setInterceptorIndex(0);
        apiKeyData.setInitialDeployment("test-model");

        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(BODY));
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getInterceptors()).thenReturn(apiKeyData.getInterceptors());

        controller.handle();

        verify(request, times(1)).body();
        verify(context).setRequestBodyWithTimestamp(BODY);
        verify(context).setInitialDeployment("test-model");
        verify(context).setInterceptors(apiKeyData.getInterceptors());
        // interceptor2 is not declared in the config, so reaching this response proves it was dispatched to
        verify(context).respond(HttpStatus.NOT_FOUND, "Interceptor is not found");
        verify(proxy, never()).getUpstreamRouteProvider();
    }

    @Test
    public void testDeploymentInterceptorReadsBodyOnceAndDispatchesToFirstInterceptor() throws Exception {
        Model model = new Model();
        model.setName("test-model");
        model.setEndpoint("http://model/openai/deployments/test-model/chat/completions");

        when(context.getRequest()).thenReturn(request);
        when(request.getHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(HEADER_CONTENT_TYPE_APPLICATION_JSON);
        when(request.body()).thenReturn(Future.succeededFuture(BODY));
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(proxy.getTaskExecutor()).thenReturn(taskExecutor);
        when(taskExecutor.submit(any(Callable.class)))
                .thenAnswer(invocation -> Future.succeededFuture(((Callable<?>) invocation.getArgument(0)).call()));
        when(proxy.getDeploymentService().findDeployment(context, "test-model")).thenReturn(model);
        when(proxy.getDeploymentService().getInterceptors(context, model)).thenReturn(List.of("interceptor1"));
        when(context.hasNextInterceptor()).thenReturn(true);
        when(context.getDeployment()).thenReturn(model);
        when(context.getInterceptors()).thenReturn(List.of("interceptor1"));

        controller.handle();

        verify(request, times(1)).body();
        verify(context).setRequestBodyWithTimestamp(BODY);
        verify(context).setInitialDeployment("test-model");
        verify(context).respond(eq(HttpStatus.NOT_FOUND), anyString());
        verify(proxy, never()).getUpstreamRouteProvider();
    }
}
