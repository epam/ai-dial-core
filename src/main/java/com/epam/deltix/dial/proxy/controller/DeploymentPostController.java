package com.epam.deltix.dial.proxy.controller;

import com.epam.deltix.dial.proxy.Proxy;
import com.epam.deltix.dial.proxy.ProxyContext;
import com.epam.deltix.dial.proxy.config.*;
import com.epam.deltix.dial.proxy.endpoint.DeploymentEndpointProvider;
import com.epam.deltix.dial.proxy.endpoint.EndpointProvider;
import com.epam.deltix.dial.proxy.endpoint.EndpointRoute;
import com.epam.deltix.dial.proxy.token.TokenUsage;
import com.epam.deltix.dial.proxy.token.TokenUsageParser;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

        EndpointProvider endpointProvider = new DeploymentEndpointProvider(deployment);
        EndpointRoute endpointRoute = proxy.getEndpointBalancer().balance(endpointProvider);

        context.setEndpointProvider(endpointProvider);
        context.setEndpointRoute(endpointRoute);

        if (!endpointRoute.hasNext()) {
            return context.respond(HttpStatus.BAD_GATEWAY, "No route");
        }

        return context.getRequest().body()
                .onSuccess(this::handleRequestBody)
                .onFailure(this::handleRequestBodyError);
    }

    @SneakyThrows
    private Future<?> sendRequest() {
        EndpointRoute route = context.getEndpointRoute();
        HttpServerRequest request = context.getRequest();

        if (!route.hasNext()) {
            return context.respond(HttpStatus.BAD_GATEWAY, "No route");
        }

        String upstream = route.next();
        String uri = buildUri(context);
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(uri)
                .setMethod(request.method());

        return proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    private void handleRequestBody(Buffer requestBody) {
        context.setProxyRequestBody(requestBody);

        if (context.getDeployment() instanceof Assistant) {
            try {
                Buffer enhancedRequestBody = enhanceAssistantRequest(context);
                context.setProxyRequestBody(enhancedRequestBody);
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
        log.info("Connected to origin: {}", proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);

        EndpointProvider endpointProvider = context.getEndpointProvider();
        EndpointRoute endpointRoute = context.getEndpointRoute();

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers());

        if (context.getDeployment() instanceof Model model && !model.getUpstreams().isEmpty()) {
            String endpoint = endpointRoute.get();
            String endpointKey = endpointProvider.getEndpoints().get(endpoint);

            proxyRequest.headers().set(Proxy.HEADER_UPSTREAM_ENDPOINT, endpoint);
            proxyRequest.headers().set(Proxy.HEADER_UPSTREAM_KEY, endpointKey);
        }

        Buffer proxyRequestBody = context.getProxyRequestBody();
        proxyRequest.headers().set(HttpHeaders.CONTENT_LENGTH, Integer.toString(proxyRequestBody.length()));

        proxyRequest.send(proxyRequestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyRequestError);
    }

    /**
     * Called when proxy received the response headers from the origin.
     */
    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        log.info("Received response header from origin: status={}, headers={}", proxyResponse.statusCode(),
                proxyResponse.headers().size());

        if ((proxyResponse.statusCode() == HttpStatus.TOO_MANY_REQUESTS.getCode() || proxyResponse.statusCode() == HttpStatus.BAD_GATEWAY.getCode())
                && context.getEndpointRoute().hasNext()) {
            sendRequest(); // try next
            return;
        }

        BufferingReadStream proxyResponseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024));

        context.setProxyResponse(proxyResponse);
        context.setProxyResponseStream(proxyResponseStream);

        HttpServerResponse response = context.getResponse();
        response.setChunked(true);
        response.setStatusCode(proxyResponse.statusCode());
        ProxyUtil.copyHeaders(proxyResponse.headers(), response.headers());

        proxyResponseStream.pipe()
                .endOnFailure(false)
                .to(response)
                .onSuccess(ignored -> handleResponse())
                .onFailure(this::handleResponseError);
    }

    /**
     * Called when proxy sent response from the origin to the client.
     */
    private void handleResponse() {
        Buffer proxyResponseBody = context.getProxyResponseStream().getContent();
        context.setProxyResponseBody(proxyResponseBody);
        proxy.getLogStore().save(context);

        if (context.getDeployment() instanceof Model && context.getResponse().getStatusCode() == HttpStatus.OK.getCode()) {
            TokenUsage tokenUsage = TokenUsageParser.parse(proxyResponseBody);
            context.setTokenUsage(tokenUsage);
            proxy.getRateLimiter().increase(context);
        }

        log.info("Deployment workflow completed. Key: {}. Deployment: {}. Tokens: {}",
                context.getKey().getProject(), context.getDeployment().getName(),
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
            return endpoint + request.uri();
        }

        String query = request.query();
        return endpoint + (query == null ? "" : "?" + query);
    }

    private static Buffer enhanceAssistantRequest(ProxyContext context) throws Exception {
        Config config = context.getConfig();
        Assistant assistant = (Assistant) context.getDeployment();
        Buffer requestBody = context.getProxyRequestBody();

        try (InputStream stream = new ByteBufInputStream(requestBody.getByteBuf())) {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);

            ArrayNode messages = (ArrayNode) tree.get("messages");
            if (assistant.getPrompt() != null && !hasPrompt(messages)) {
                messages.insertObject(0)
                        .put("role", "system")
                        .put("content", assistant.getPrompt());
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
            }

            String name = tree.get("model").asText(null);
            Model model = config.getModels().get(name);

            if (model == null) {
                throw new HttpException(HttpStatus.NOT_FOUND, "No model: " + name);
            }

            if (!DeploymentController.hasAccess(context, model)) {
                throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden model: " + name);
            }

            return Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree));
        }
    }

    private static boolean hasPrompt(ArrayNode messages) {
        for (JsonNode message : messages) {
            String role = message.get("role").asText("");
            if ("system".equals(role)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isAssistant(Deployment deployment) {
        return deployment.getName().equals(ASSISTANT);
    }
}