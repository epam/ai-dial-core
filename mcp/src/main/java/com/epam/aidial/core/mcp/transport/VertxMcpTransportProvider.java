package com.epam.aidial.core.mcp.transport;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vert.x SPI implementation of {@link McpStreamableServerTransportProvider}. Bridges the SDK's
 * Reactor-based contract to Vert.x {@link HttpServerRequest}/{@link HttpServerResponse} lifecycles.
 *
 * <p>Threading: SDK Mono blocking calls are dispatched via {@link Vertx#executeBlocking(java.util.concurrent.Callable)}
 * so the event loop is never parked. SSE writes triggered from Reactor scheduler threads are
 * marshalled back to the response's owning context via {@link Vertx#runOnContext}.
 */
@Slf4j
public class VertxMcpTransportProvider implements McpStreamableServerTransportProvider {

    private static final String MESSAGE_EVENT_TYPE = "message";
    private static final String JSON = "application/json";
    private static final String SSE = "text/event-stream";

    private final Vertx vertx;
    private final McpJsonMapper jsonMapper;
    private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean closing;
    private volatile McpStreamableServerSession.Factory sessionFactory;

    public VertxMcpTransportProvider(Vertx vertx) {
        this.vertx = vertx;
        this.jsonMapper = McpJsonDefaults.getMapper();
    }

    @Override
    public List<String> protocolVersions() {
        return List.of(ProtocolVersions.MCP_2024_11_05, ProtocolVersions.MCP_2025_03_26,
                ProtocolVersions.MCP_2025_06_18, ProtocolVersions.MCP_2025_11_25);
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory factory) {
        this.sessionFactory = factory;
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return Flux.fromIterable(sessions.values())
                .flatMap(session -> session.sendNotification(method, params)
                        .doOnError(e -> log.error("Failed to notify session {}: {}", session.getId(), e.getMessage()))
                        .onErrorResume(e -> Mono.empty()))
                .then();
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return Mono.defer(() -> {
            McpStreamableServerSession session = sessions.get(sessionId);
            if (session == null) {
                return Mono.empty();
            }
            return session.sendNotification(method, params);
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        closing = true;
        return Flux.fromIterable(sessions.values())
                .flatMap(session -> session.closeGracefully()
                        .doOnError(e -> log.error("Failed to close session {}: {}", session.getId(), e.getMessage()))
                        .onErrorResume(e -> Mono.empty()))
                .then(Mono.fromRunnable(sessions::clear));
    }

    public void handleRequest(HttpServerRequest request) {
        HttpMethod method = request.method();
        if (method == HttpMethod.POST) {
            handlePost(request);
        } else if (method == HttpMethod.GET) {
            handleGet(request);
        } else if (method == HttpMethod.DELETE) {
            handleDelete(request);
        } else {
            request.response().setStatusCode(405).end();
        }
    }

    private void handlePost(HttpServerRequest request) {
        if (closing) {
            request.response().setStatusCode(503).end();
            return;
        }
        // Capture the response's owning event-loop context here (not in VertxSessionTransport's ctor,
        // which runs on the executeBlocking worker thread and would inherit context implicitly).
        Context responseContext = vertx.getOrCreateContext();
        request.bodyHandler(body -> vertx.executeBlocking(() -> {
            dispatchPost(request, body, responseContext);
            return null;
        }, false).onFailure(err -> {
            log.error("MCP POST dispatch failed", err);
            if (!request.response().ended()) {
                request.response().setStatusCode(500).end();
            }
        }));
    }

    private void dispatchPost(HttpServerRequest request, Buffer body, Context responseContext) {
        HttpServerResponse response = request.response();
        McpSchema.JSONRPCMessage message;
        try {
            message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body.toString());
        } catch (Exception e) {
            response.setStatusCode(400).end();
            return;
        }

        if (message instanceof McpSchema.JSONRPCRequest req
                && McpSchema.METHOD_INITIALIZE.equals(req.method())) {
            handleInitialize(request, req);
            return;
        }

        String sessionId = request.getHeader(HttpHeaders.MCP_SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            response.setStatusCode(400).end();
            return;
        }
        McpStreamableServerSession session = sessions.get(sessionId);
        if (session == null) {
            response.setStatusCode(404).end();
            return;
        }

        try {
            if (message instanceof McpSchema.JSONRPCResponse jsonResp) {
                session.accept(jsonResp).block();
                response.setStatusCode(202).end();
            } else if (message instanceof McpSchema.JSONRPCNotification notification) {
                session.accept(notification).block();
                response.setStatusCode(202).end();
            } else if (message instanceof McpSchema.JSONRPCRequest jsonReq) {
                response.setChunked(true);
                response.putHeader("Content-Type", SSE);
                response.putHeader("Cache-Control", "no-cache");
                VertxSessionTransport transport = new VertxSessionTransport(sessionId, response, responseContext);
                session.responseStream(jsonReq, transport).block();
                if (!response.ended()) {
                    response.end();
                }
            } else {
                response.setStatusCode(500).end();
            }
        } catch (Exception e) {
            log.error("Failed to dispatch MCP message for session {}: {}", sessionId, e.getMessage());
            if (!response.ended()) {
                response.setStatusCode(500).end();
            }
        }
    }

    private void handleInitialize(HttpServerRequest request, McpSchema.JSONRPCRequest jsonReq) {
        HttpServerResponse response = request.response();
        if (sessionFactory == null) {
            response.setStatusCode(503).end();
            return;
        }
        try {
            McpSchema.InitializeRequest initRequest = jsonMapper.convertValue(
                    jsonReq.params(), new TypeRef<McpSchema.InitializeRequest>() {
                    });
            McpStreamableServerSession.McpStreamableServerSessionInit init =
                    sessionFactory.startSession(initRequest);
            sessions.put(init.session().getId(), init.session());
            McpSchema.InitializeResult initResult = init.initResult().block();

            McpSchema.JSONRPCResponse rpcResponse = new McpSchema.JSONRPCResponse(
                    McpSchema.JSONRPC_VERSION, jsonReq.id(), initResult, null);
            String json = jsonMapper.writeValueAsString(rpcResponse);

            response.putHeader("Content-Type", JSON);
            response.putHeader(HttpHeaders.MCP_SESSION_ID, init.session().getId());
            response.setStatusCode(200).end(json);
        } catch (Exception e) {
            log.error("MCP initialize failed", e);
            response.setStatusCode(500).end();
        }
    }

    private void handleGet(HttpServerRequest request) {
        if (closing) {
            request.response().setStatusCode(503).end();
            return;
        }
        HttpServerResponse response = request.response();
        String sessionId = request.getHeader(HttpHeaders.MCP_SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            response.setStatusCode(400).end();
            return;
        }
        McpStreamableServerSession session = sessions.get(sessionId);
        if (session == null) {
            response.setStatusCode(404).end();
            return;
        }

        response.setChunked(true);
        response.putHeader("Content-Type", SSE);
        response.putHeader("Cache-Control", "no-cache");

        VertxSessionTransport transport = new VertxSessionTransport(sessionId, response, vertx.getOrCreateContext());
        AtomicBoolean closed = new AtomicBoolean(false);
        McpStreamableServerSession.McpStreamableServerSessionStream listening =
                session.listeningStream(transport);
        response.closeHandler(v -> {
            if (closed.compareAndSet(false, true)) {
                listening.close();
            }
        });
        response.endHandler(v -> {
            if (closed.compareAndSet(false, true)) {
                listening.close();
            }
        });
    }

    private void handleDelete(HttpServerRequest request) {
        HttpServerResponse response = request.response();
        String sessionId = request.getHeader(HttpHeaders.MCP_SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            response.setStatusCode(400).end();
            return;
        }
        McpStreamableServerSession session = sessions.remove(sessionId);
        if (session == null) {
            response.setStatusCode(404).end();
            return;
        }
        vertx.executeBlocking(() -> {
            try {
                session.delete().block();
            } catch (Exception e) {
                log.error("MCP delete failed for session {}", sessionId, e);
            }
            return null;
        }, false).onComplete(ar -> response.setStatusCode(200).end());
    }

    private final class VertxSessionTransport implements McpStreamableServerTransport {

        private final String sessionId;
        private final HttpServerResponse response;
        private final Context responseContext;
        private volatile boolean closed;

        VertxSessionTransport(String sessionId, HttpServerResponse response, Context responseContext) {
            this.sessionId = sessionId;
            this.response = response;
            this.responseContext = responseContext;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return sendMessage(message, null);
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
            return Mono.create(sink -> {
                if (closed) {
                    sink.success();
                    return;
                }
                String json;
                try {
                    json = jsonMapper.writeValueAsString(message);
                } catch (Exception e) {
                    sink.error(e);
                    return;
                }
                String eventId = messageId != null ? messageId : sessionId;
                StringBuilder sb = new StringBuilder();
                sb.append("id: ").append(eventId).append('\n');
                sb.append("event: ").append(MESSAGE_EVENT_TYPE).append('\n');
                sb.append("data: ").append(json).append("\n\n");
                String chunk = sb.toString();

                responseContext.runOnContext(v -> {
                    if (closed || response.ended()) {
                        sink.success();
                        return;
                    }
                    response.write(chunk).onComplete(ar -> {
                        if (ar.succeeded()) {
                            sink.success();
                        } else {
                            sink.error(ar.cause());
                        }
                    });
                });
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return jsonMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.create(sink -> {
                if (closed) {
                    sink.success();
                    return;
                }
                closed = true;
                responseContext.runOnContext(v -> {
                    if (response.ended()) {
                        sink.success();
                    } else {
                        response.end().onComplete(ar -> sink.success());
                    }
                });
            });
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
