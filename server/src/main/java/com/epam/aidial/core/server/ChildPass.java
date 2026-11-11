package com.epam.aidial.core.server;

import com.epam.aidial.core.plugin.OriginCall;
import com.epam.aidial.core.plugin.OriginHost;
import com.epam.aidial.core.plugin.OriginResponse;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.RouteTemplate;
import com.epam.aidial.core.server.upstream.ChildServerRequest;
import com.epam.aidial.core.server.upstream.ChildServerResponse;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.impl.ContextInternal;
import io.vertx.tracing.opentelemetry.VertxContextStorageProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Serves the in-process calls an origin plugin makes while it answers one attempt of an embedded pass. Each call is a
 * child pass: an in-memory request routed by Core's own controller selection, authenticated by the per-request key
 * record of the plugin's pass, so it is checked, limited, charged and logged as a request with that key, with no HTTP
 * request into Core and no key lookup.
 *
 * <p>Calls run on the plugin's context, the one {@code OriginPlugin.handle} was called on. Each child pass runs on a
 * duplicate of it, with its own locals but the plugin's OTel context, so the child's HTTP origin calls are traced
 * under the plugin's server span. {@link #close} ends the attempt: open answers are cancelled and later calls get
 * {@code 401}, as an HTTP child gets once the key is deleted.
 */
@Slf4j
public final class ChildPass implements OriginHost {

    private static final List<RouteTemplate> CREATE_ROUTES = List.of(RouteTemplate.POST_DEPLOYMENT,
            RouteTemplate.LLM_CHAT_COMPLETIONS_API, RouteTemplate.LLM_RESPONSES_API, RouteTemplate.LLM_RESPONSES_API_UNVERSIONED,
            RouteTemplate.LLM_MESSAGES_API);

    // a plugin whose calls come back to itself is stopped here: an in-process call has no connection pool or timeout
    // to slow it down, as a deployment calling itself over HTTP has
    static final int MAX_CALL_DEPTH = 32;

    private final Proxy proxy;
    private final ProxyContext parent;
    private final ContextInternal pluginContext;
    // the key record the calls carry; null for the parent pass's proxy key, read at each call
    private final ApiKeyData callKey;
    private final Promise<Void> cancelled;
    // touched on the plugin's context only
    private final List<ChildServerResponse> responses = new ArrayList<>();
    private boolean closed;
    private Runnable onClose;

    /**
     * @param parent        the pass whose plugin makes the calls, with its proxy key
     * @param pluginContext the context the plugin was called on
     */
    public ChildPass(Proxy proxy, ProxyContext parent, ContextInternal pluginContext) {
        this(proxy, parent, pluginContext, null, false);
    }

    private ChildPass(Proxy proxy, ProxyContext parent, ContextInternal pluginContext, ApiKeyData callKey, boolean closed) {
        this.proxy = proxy;
        this.parent = parent;
        this.pluginContext = pluginContext;
        this.callKey = callKey;
        this.closed = closed;
        this.cancelled = pluginContext.promise();
    }

    /**
     * The host of a router's decide or notice request: its calls carry {@code callKey}, the decide request's key. A
     * notice carries no key, so with none the host is closed from the start and every call is answered {@code 401}.
     */
    public static ChildPass forRouter(Proxy proxy, ProxyContext parent, ContextInternal pluginContext, @Nullable ApiKeyData callKey) {
        return new ChildPass(proxy, parent, pluginContext, callKey, callKey == null);
    }

    @Override
    public Future<Void> cancelled() {
        return cancelled.future();
    }

    @Override
    public Future<OriginResponse> call(OriginCall call) {
        if (call == null || call.uri() == null || call.body() == null) {
            return pluginContext.failedFuture(new IllegalArgumentException("A call needs a uri and a body"));
        }
        ContextInternal childContext = pluginContext.duplicate();
        Object otelContext = pluginContext.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT);
        if (otelContext != null) {
            childContext.putLocal(VertxContextStorageProvider.ACTIVE_CONTEXT, otelContext);
        }
        ChildServerResponse response = new ChildServerResponse(childContext, pluginContext);
        if (closed) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED.getCode()).end("Unknown api key");
            return response.answer();
        }
        String path = pathOf(call.uri());
        if (CREATE_ROUTES.stream().noneMatch(route -> route.matches(path))) {
            return pluginContext.failedFuture(new IllegalArgumentException("A call reaches the create operations only, not " + path));
        }
        if (isResponses(path) && call.body().path("background").asBoolean(false)) {
            return pluginContext.failedFuture(new IllegalArgumentException("A call cannot create a background response"));
        }

        ApiKeyData key = callKey != null ? callKey : parent.getProxyApiKeyData();
        List<String> executionPath = key.getExecutionPath();
        if (executionPath != null && executionPath.size() >= MAX_CALL_DEPTH) {
            response.setStatusCode(HttpStatus.BAD_REQUEST.getCode()).end("Call depth limit reached: " + MAX_CALL_DEPTH);
            return response.answer();
        }

        ChildServerRequest request = new ChildServerRequest(call.uri(), call.headers(), response);
        ProxyContext child = new ProxyContext(proxy, request, key, null, null,
                IdGenerator.random().generateSpanId(), parent.getTraceFlags());
        child.setCallTree(call.body());
        responses.add(response);
        response.answer().onSuccess(answer -> {
            if (closed) {
                answer.cancel();
            }
        });
        childContext.runOnContext(ignored -> {
            Future<?> result;
            try {
                result = proxy.dispatch(child);
            } catch (Throwable e) {
                result = Future.failedFuture(e);
            }
            result.onFailure(error -> proxy.handleError(error, request));
        });
        return response.answer();
    }

    /**
     * Ends the attempt because Core stopped waiting for its answer: {@link #cancelled()} completes, and the attempt
     * closes. Safe to call from any thread and more than once.
     */
    public void cancel() {
        pluginContext.emit(null, ignored -> cancelled.tryComplete());
        close();
    }

    /**
     * Ends the attempt the calls belong to. Safe to call from any thread and more than once.
     */
    public void close() {
        pluginContext.emit(null, ignored -> {
            if (closed) {
                return;
            }
            closed = true;
            responses.forEach(ChildServerResponse::cancel);
            responses.clear();
            if (onClose != null) {
                onClose.run();
            }
        });
    }

    /**
     * Runs {@code action} once the attempt closes, or at once when it has closed already. Takes one action. Safe to
     * call from any thread.
     */
    public void whenClosed(Runnable action) {
        pluginContext.emit(null, ignored -> {
            if (closed) {
                action.run();
            } else {
                onClose = action;
            }
        });
    }

    private static String pathOf(String uri) {
        int question = uri.indexOf('?');
        return question < 0 ? uri : uri.substring(0, question);
    }

    private static boolean isResponses(String path) {
        return RouteTemplate.LLM_RESPONSES_API.matches(path) || RouteTemplate.LLM_RESPONSES_API_UNVERSIONED.matches(path);
    }
}
