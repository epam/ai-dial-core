package com.epam.aidial.core.server.proxy.embedded;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpFrame;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerFileUpload;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.StreamPriority;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.NetSocket;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.security.cert.X509Certificate;

/**
 * The request of a pass Core serves in process, for an origin plugin's call or as the target pass of an alias call: the
 * call's method, path and query, with the call's headers and JSON body. A create operation takes the body as a tree,
 * so its {@link #body()} is empty; any other pass reads the body's bytes from {@link #body()}, serialized on a worker
 * when first read. The body is whole before the pass starts, so nothing is streamed. The members of a file upload, a
 * WebSocket, cookies and the connection throw: a plugin's call has none of them.
 */
public final class EmbeddedServerRequest implements HttpServerRequest {

    /**
     * The headers Core sets for an origin itself. A plugin that forwards its own request's headers would otherwise pass
     * them on to another deployment: the ones Core sets only for some deployments, and the application and feature
     * headers, which an interceptor hop copies on.
     */
    static final List<String> DROPPED_HEADERS = List.of(
            Proxy.HEADER_UPSTREAM_ENDPOINT, Proxy.HEADER_UPSTREAM_KEY, Proxy.HEADER_UPSTREAM_EXTRA_DATA,
            Proxy.HEADER_CACHE_BREAKPOINT_PATH, Proxy.HEADER_CACHE_EXTRA_METADATA,
            Proxy.HEADER_OVERRIDE_NAME, Proxy.HEADER_DEPLOYMENT_ID,
            Proxy.HEADER_APPLICATION_ID, Proxy.HEADER_APPLICATION_PROPERTIES, Proxy.HEADER_DEPLOYMENT_FEATURES);

    private final HttpMethod method;
    private final String uri;
    private final String path;
    private final String query;
    private final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    private final HttpServerResponse response;
    private final ObjectNode body;
    private final AsyncTaskExecutor serializer;
    private Future<Buffer> bytes;
    private MultiMap params;
    private String paramsCharset = StandardCharsets.UTF_8.name();

    /**
     * @param method     the call's method
     * @param uri        the path and query of the call
     * @param headers    the call's headers, copied
     * @param body       the call's body, if any
     * @param serializer serializes the body for {@link #body()}; null when the pass takes the body as a tree
     */
    public EmbeddedServerRequest(HttpMethod method, String uri, MultiMap headers, @Nullable ObjectNode body,
                                 @Nullable AsyncTaskExecutor serializer, HttpServerResponse response) {
        this.method = method;
        this.uri = uri;
        int question = uri.indexOf('?');
        this.path = question < 0 ? uri : uri.substring(0, question);
        this.query = question < 0 ? null : uri.substring(question + 1);
        this.response = response;
        this.body = body;
        this.serializer = serializer;
        if (headers != null) {
            this.headers.addAll(headers);
        }
        DROPPED_HEADERS.forEach(this.headers::remove);
        // the length of the plugin's own request, if passed on: the body is whole, and a controller measures it
        this.headers.remove(HttpHeaders.CONTENT_LENGTH);
        if (body != null) {
            this.headers.set(HttpHeaders.CONTENT_TYPE, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON);
        }
    }

    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    public HttpVersion version() {
        return HttpVersion.HTTP_1_1;
    }

    @Override
    public String scheme() {
        return "http";
    }

    @Override
    public String uri() {
        return uri;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String query() {
        return query;
    }

    @Override
    public String host() {
        return null;
    }

    @Override
    public String absoluteURI() {
        return uri;
    }

    @Override
    public MultiMap headers() {
        return headers;
    }

    @Override
    public MultiMap params() {
        if (params == null) {
            params = MultiMap.caseInsensitiveMultiMap();
            for (Map.Entry<String, List<String>> entry : new QueryStringDecoder(uri).parameters().entrySet()) {
                params.add(entry.getKey(), entry.getValue());
            }
        }
        return params;
    }

    @Override
    public MultiMap params(boolean semicolonIsNormalChar) {
        return params();
    }

    @Override
    public HttpServerRequest setParamsCharset(String charset) {
        this.paramsCharset = charset;
        return this;
    }

    @Override
    public String getParamsCharset() {
        return paramsCharset;
    }

    @Override
    public HttpServerResponse response() {
        return response;
    }

    @Override
    public Future<Buffer> body() {
        if (bytes == null) {
            bytes = body == null || serializer == null
                    ? Future.succeededFuture(Buffer.buffer())
                    : serializer.submit(() -> Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(body)));
        }
        return bytes;
    }

    @Override
    public Future<Void> end() {
        return Future.succeededFuture();
    }

    @Override
    public boolean isEnded() {
        return true;
    }

    @Override
    public long bytesRead() {
        return 0;
    }

    // the body is complete before the pass starts, so there is nothing to stream

    @Override
    public HttpServerRequest exceptionHandler(Handler<Throwable> handler) {
        return this;
    }

    @Override
    public HttpServerRequest handler(Handler<Buffer> handler) {
        return this;
    }

    @Override
    public HttpServerRequest pause() {
        return this;
    }

    @Override
    public HttpServerRequest resume() {
        return this;
    }

    @Override
    public HttpServerRequest fetch(long amount) {
        return this;
    }

    @Override
    public HttpServerRequest endHandler(Handler<Void> endHandler) {
        return this;
    }

    @Override
    public DecoderResult decoderResult() {
        return DecoderResult.SUCCESS;
    }

    @Override
    public boolean isExpectMultipart() {
        return false;
    }

    @Override
    public HostAndPort authority() {
        throw unsupported("authority");
    }

    @Override
    public HostAndPort authority(boolean real) {
        throw unsupported("authority");
    }

    @Override
    @SuppressWarnings("removal")
    public X509Certificate[] peerCertificateChain() {
        throw unsupported("peerCertificateChain");
    }

    @Override
    public Future<NetSocket> toNetSocket() {
        throw unsupported("toNetSocket");
    }

    @Override
    public HttpServerRequest setExpectMultipart(boolean expect) {
        throw unsupported("setExpectMultipart");
    }

    @Override
    public HttpServerRequest uploadHandler(Handler<HttpServerFileUpload> uploadHandler) {
        throw unsupported("uploadHandler");
    }

    @Override
    public MultiMap formAttributes() {
        throw unsupported("formAttributes");
    }

    @Override
    public String getFormAttribute(String attributeName) {
        throw unsupported("getFormAttribute");
    }

    @Override
    public Future<ServerWebSocket> toWebSocket() {
        throw unsupported("toWebSocket");
    }

    @Override
    public HttpServerRequest customFrameHandler(Handler<HttpFrame> handler) {
        throw unsupported("customFrameHandler");
    }

    @Override
    public HttpConnection connection() {
        throw unsupported("connection");
    }

    @Override
    public HttpServerRequest streamPriorityHandler(Handler<StreamPriority> handler) {
        throw unsupported("streamPriorityHandler");
    }

    @Override
    public Cookie getCookie(String name) {
        throw unsupported("getCookie");
    }

    @Override
    public Cookie getCookie(String name, String domain, String path) {
        throw unsupported("getCookie");
    }

    @Override
    public Set<Cookie> cookies(String name) {
        throw unsupported("cookies");
    }

    @Override
    public Set<Cookie> cookies() {
        throw unsupported("cookies");
    }

    static UnsupportedOperationException unsupported(String member) {
        return new UnsupportedOperationException(member + " is not served for an origin plugin's in-process call");
    }
}
