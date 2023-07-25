package com.epam.deltix.dial.proxy.controller;

import com.epam.deltix.dial.proxy.Proxy;
import com.epam.deltix.dial.proxy.ProxyContext;
import com.epam.deltix.dial.proxy.config.*;
import com.epam.deltix.dial.proxy.token.TokenUsage;
import com.epam.deltix.dial.proxy.token.TokenUsageParser;
import com.epam.deltix.dial.proxy.upstream.DeploymentUpstreamProvider;
import com.epam.deltix.dial.proxy.upstream.UpstreamProvider;
import com.epam.deltix.dial.proxy.upstream.UpstreamRoute;
import com.epam.deltix.dial.proxy.util.BufferingReadStream;
import com.epam.deltix.dial.proxy.util.HttpException;
import com.epam.deltix.dial.proxy.util.HttpStatus;
import com.epam.deltix.dial.proxy.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class DeploymentPostController {

    private static final String ASSISTANT = "assistant";

    private final Proxy proxy;
    private final ProxyContext context;

    public Future<?> handle(String deploymentId, String deploymentApi) {
        if (!Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON.equalsIgnoreCase(context.getRequest().getHeader(HttpHeaders.CONTENT_TYPE))) {
            return context.respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only application/json is supported");
        }

        Deployment deployment = select(deploymentId, deploymentApi);
        context.setDeployment(deployment);

        if (deployment == null || (!isAssistant(deployment) && !DeploymentController.hasAccess(context, deployment))) {
            return context.respond(HttpStatus.FORBIDDEN, "Forbidden deployment");
        }

        if (deployment instanceof Model && proxy.getRateLimiter().limit(context)) {
            return context.respond(HttpStatus.TOO_MANY_REQUESTS, "Hit token rate limit");
        }

        log.info("Received request from client. Key: {}. Deployment: {}. Headers: {}", context.getKey().getProject(),
                context.getDeployment().getName(), context.getRequest().headers().size());

        UpstreamProvider endpointProvider = new DeploymentUpstreamProvider(deployment);
        UpstreamRoute endpointRoute = proxy.getUpstreamBalancer().balance(endpointProvider);
        context.setUpstreamRoute(endpointRoute);

        if (!endpointRoute.hasNext()) {
            return context.respond(HttpStatus.BAD_GATEWAY, "No route");
        }

        return context.getRequest().body()
                .onSuccess(this::handleRequestBody)
                .onFailure(this::handleRequestBodyError);
    }

    @SneakyThrows
    private Future<?> sendRequest() {
        UpstreamRoute route = context.getUpstreamRoute();
        HttpServerRequest request = context.getRequest();

        if (!route.hasNext()) {
            return context.respond(HttpStatus.BAD_GATEWAY, "No route");
        }

        Upstream upstream = route.next();
        Objects.requireNonNull(upstream);

        String uri = buildUri(context);
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(uri)
                .setMethod(request.method());

        return proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    private void handleRequestBody(Buffer requestBody) {
        log.info("Received body from client. Key: {}. Deployment: {}. Length: {}", context.getKey().getProject(),
                context.getDeployment().getName(), requestBody.length());

        context.setRequestBody(requestBody);
        context.setRequestBodyTimestamp(System.currentTimeMillis());

        if (context.getDeployment() instanceof Assistant) {
            try {
                Map.Entry<Buffer, Map<String, String>> enhancedRequest = enhanceAssistantRequest(context);
                context.setRequestBody(enhancedRequest.getKey());
                context.setRequestHeaders(enhancedRequest.getValue());
            } catch (HttpException e) {
                context.respond(e.getStatus(), e.getMessage());
                log.warn("Can't enhance assistant request: {}", e.getMessage());
                return;
            } catch (Throwable e) {
                context.respond(HttpStatus.BAD_REQUEST);
                log.warn("Can't enhance assistant request: {}", e.getMessage());
                return;
            }
        }

        sendRequest();
    }

    /**
     * Called when proxy connected to the origin.
     */
    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to origin. Key: {}. Deployment: {}. Address: {}", context.getKey().getProject(),
                context.getDeployment().getName(), proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers());

        if (context.getDeployment() instanceof Model model && !model.getUpstreams().isEmpty()) {
            Upstream upstream = context.getUpstreamRoute().get();
            proxyRequest.putHeader(Proxy.HEADER_UPSTREAM_ENDPOINT, upstream.getEndpoint());
            proxyRequest.putHeader(Proxy.HEADER_UPSTREAM_KEY, upstream.getKey());
        }

        Buffer requestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(requestBody.length()));
        context.getRequestHeaders().forEach(proxyRequest::putHeader);

        proxyRequest.send(requestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyRequestError);
    }

    /**
     * Called when proxy received the response headers from the origin.
     */
    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        log.info("Received header from origin. Key: {}. Deployment: {}. Endpoint: {}. Upstream: {}. Status: {}. Headers: {}",
                context.getKey().getProject(), context.getDeployment().getName(),
                context.getDeployment().getEndpoint(), context.getUpstreamRoute().get().getEndpoint(),
                proxyResponse.statusCode(), proxyResponse.headers().size());

        if ((proxyResponse.statusCode() == HttpStatus.TOO_MANY_REQUESTS.getCode() || proxyResponse.statusCode() == HttpStatus.BAD_GATEWAY.getCode())
                && context.getUpstreamRoute().hasNext()) {
            sendRequest(); // try next
            return;
        }

        BufferingReadStream responseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024));

        context.setProxyResponse(proxyResponse);
        context.setProxyResponseTimestamp(System.currentTimeMillis());
        context.setResponseStream(responseStream);

        HttpServerResponse response = context.getResponse();

        response.setChunked(true);
        response.setStatusCode(proxyResponse.statusCode());

        ProxyUtil.copyHeaders(proxyResponse.headers(), response.headers());
        response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(context.getUpstreamRoute().attempts()));

        responseStream.pipe()
                .endOnFailure(false)
                .to(response)
                .onSuccess(ignored -> handleResponse())
                .onFailure(this::handleResponseError);
    }

    /**
     * Called when proxy sent response from the origin to the client.
     */
    private void handleResponse() {
        Buffer responseBody = context.getResponseStream().getContent();
        context.setResponseBody(responseBody);
        context.setResponseBodyTimestamp(System.currentTimeMillis());
        proxy.getLogStore().save(context);

        if (context.getDeployment() instanceof Model && context.getResponse().getStatusCode() == HttpStatus.OK.getCode()) {
            TokenUsage tokenUsage = TokenUsageParser.parse(responseBody);
            context.setTokenUsage(tokenUsage);
            proxy.getRateLimiter().increase(context);

            if (tokenUsage == null) {
                log.warn("Can't find token usage. Key: {}. Deployment: {}. Endpoint: {}. Upstream: {}. Status: {}. Length: {}",
                        context.getKey().getProject(), context.getDeployment().getName(),
                        context.getDeployment().getEndpoint(),
                        context.getUpstreamRoute().get().getEndpoint(),
                        context.getResponse().getStatusCode(),
                        context.getResponseBody().length());
            }
        }

        log.info("Sent response to client. Key: {}. Deployment: {}. Endpoint: {}. Upstream: {}. Status: {}. Length: {}. Timing: {} (body={}, connect={}, header={}, body={}). Tokens: {}",
                context.getKey().getProject(), context.getDeployment().getName(),
                context.getDeployment().getEndpoint(),
                context.getUpstreamRoute().get().getEndpoint(),
                context.getResponse().getStatusCode(),
                context.getResponseBody().length(),
                context.getResponseBodyTimestamp() - context.getRequestTimestamp(),
                context.getRequestBodyTimestamp() - context.getRequestTimestamp(),
                context.getProxyConnectTimestamp() - context.getRequestBodyTimestamp(),
                context.getProxyResponseTimestamp() - context.getProxyConnectTimestamp(),
                context.getResponseBodyTimestamp() - context.getProxyResponseTimestamp(),
                context.getTokenUsage() == null ? "n/a" : context.getTokenUsage());
    }

    /**
     * Called when proxy failed to receive request body from the client.
     */
    private void handleRequestBodyError(Throwable error) {
        log.warn("Failed to receive client body: {}", error.getMessage());
        context.respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
    }

    /**
     * Called when proxy failed to connect to the origin.
     */
    private void handleProxyConnectionError(Throwable error) {
        log.warn("Can't connect to origin: {}", error.getMessage());
        context.respond(HttpStatus.BAD_GATEWAY, "Failed to connect to origin");
    }

    /**
     * Called when proxy failed to send request to the origin.
     */
    private void handleProxyRequestError(Throwable error) {
        log.warn("Can't send request to origin: {}", error.getMessage());
        context.respond(HttpStatus.BAD_GATEWAY, "Failed to send request to origin");
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    private void handleResponseError(Throwable error) {
        log.warn("Can't send response to client: {}", error.getMessage());
        context.getProxyRequest().reset(); // drop connection to stop origin response
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
    }

    private Deployment select(String deploymentId, String deploymentApi) {
        Config config = context.getConfig();
        ModelType type = switch (deploymentApi) {
            case "completions" -> ModelType.COMPLETION;
            case "chat/completions" -> ModelType.CHAT;
            case "embeddings" -> ModelType.EMBEDDING;
            default -> null;
        };

        if (type == null) {
            return null;
        }

        Model model = config.getModels().get(deploymentId);
        if (model != null) {
            return (type == model.getType()) ? model : null;
        }

        if (type != ModelType.CHAT) {
            return null;
        }

        Assistants assistants = config.getAssistant();

        if (assistants.getEndpoint() != null && ASSISTANT.equals(deploymentId)) {
            Assistant assistant = new Assistant();
            assistant.setName(ASSISTANT);
            assistant.setEndpoint(assistants.getEndpoint());
            return assistant;
        }

        Assistant assistant = assistants.getAssistants().get(deploymentId);
        if (assistant != null) {
            return assistant;
        }

        return config.getApplications().get(deploymentId);
    }

    private static String buildUri(ProxyContext context) {
        HttpServerRequest request = context.getRequest();
        Deployment deployment = context.getDeployment();
        String endpoint = deployment.getEndpoint();

        if (deployment instanceof Model) {
            return endpoint + ProxyUtil.stripExtraLeadingSlashes(request.uri());
        }

        String query = request.query();
        return endpoint + (query == null ? "" : "?" + query);
    }

    private static Map.Entry<Buffer, Map<String, String>> enhanceAssistantRequest(ProxyContext context)
            throws Exception {
        Config config = context.getConfig();
        Assistant assistant = (Assistant) context.getDeployment();
        Buffer requestBody = context.getRequestBody();

        try (InputStream stream = new ByteBufInputStream(requestBody.getByteBuf())) {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);

            ArrayNode messages = (ArrayNode) tree.get("messages");
            if (assistant.getPrompt() != null) {
                deletePrompt(messages);
                insertPrompt(messages, assistant.getPrompt());
            }

            Set<String> names = new LinkedHashSet<>(assistant.getAddons());
            ArrayNode addons = (ArrayNode) tree.get("addons");

            if (addons == null) {
                addons = tree.putArray("addons");
            }

            for (JsonNode addon : addons) {
                String name = addon.get("name").asText("");
                names.add(name);
            }

            addons.removeAll();
            Map<String, String> headers = new HashMap<>();
            int addonIndex = 0;
            for (String name : names) {
                Addon addon = config.getAddons().get(name);
                if (addon == null) {
                    throw new HttpException(HttpStatus.NOT_FOUND, "No addon: " + name);
                }

                if (!DeploymentController.hasAccess(context, addon)) {
                    throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden addon: " + name);
                }

                String url = addon.getEndpoint();
                addons.addObject().put("url", url);
                if (addon.getToken() != null && !addon.getToken().isBlank()) {
                    headers.put("x-addon-token-" + addonIndex, addon.getToken());
                }
                ++addonIndex;
            }

            String name = tree.get("model").asText(null);
            Model model = config.getModels().get(name);

            if (model == null) {
                throw new HttpException(HttpStatus.NOT_FOUND, "No model: " + name);
            }

            if (!DeploymentController.hasAccess(context, model)) {
                throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden model: " + name);
            }

            Buffer updatedBody = Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree));
            return Map.entry(updatedBody, headers);
        }
    }

    private static ObjectNode insertPrompt(ArrayNode messages, String prompt) {
        return messages.insertObject(0)
                .put("role", "system")
                .put("content", prompt);
    }

    private static void deletePrompt(ArrayNode messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            String role = message.get("role").asText("");

            if ("system".equals(role)) {
                messages.remove(i);
            }
        }
    }

    private static boolean isAssistant(Deployment deployment) {
        return deployment.getName().equals(ASSISTANT);
    }
}