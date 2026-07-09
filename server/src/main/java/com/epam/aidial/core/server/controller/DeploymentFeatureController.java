package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_ID;
import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_PROPERTIES;

@Slf4j
public class DeploymentFeatureController {

    private final Proxy proxy;
    private final ProxyContext context;
    protected final List<BaseRequestFunction<ObjectNode>> enhancementFunctions = new ArrayList<>();

    public DeploymentFeatureController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/deployments/{deployment_name}/configuration",
            operationId = "configurationDeployment",
            tags = {"Deployment Feature"},
            parameters = {
                    @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_NAME)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "ProxyResponse"))
            },
            responseProfile = ResponseProfile.AUTHENTICATED_READ_EXTENDED
    )
    @ApiOperation(
            method = "POST",
            path = "/v1/deployments/{deployment_name}/tokenize",
            operationId = "tokenize",
            tags = {"Deployment Feature"},
            parameters = {
                    @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_NAME)
            },
            requestBody = @ApiSchema(schemaRef = "TokenizeRequest"),
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "TokenizeResponse"))
            },
            responseProfile = ResponseProfile.AUTHENTICATED_READ_EXTENDED
    )
    @ApiOperation(
            method = "POST",
            path = "/v1/deployments/{deployment_name}/truncate_prompt",
            operationId = "truncatePrompt",
            tags = {"Deployment Feature"},
            parameters = {
                    @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_NAME)
            },
            requestBody = @ApiSchema(schemaRef = "TruncatePromptRequest"),
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "TruncatePromptResponse"))
            },
            responseProfile = ResponseProfile.AUTHENTICATED_READ_EXTENDED
    )
    public Future<?> handle(String deploymentId, Function<Deployment, String> endpointGetter, boolean requireEndpoint) {
        // make sure request.body() called before request.resume()
        return proxy.getTaskExecutor().submit(() -> proxy.getDeploymentService().findDeployment(context, deploymentId)).map(dep -> {
            if (dep instanceof Application application) {
                dep = proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(application);
            }
            String endpoint = endpointGetter.apply(dep);
            context.setDeployment(dep);
            context.getRequest().body()
                    .onSuccess(requestBody -> handleRequestBody(endpoint, requireEndpoint, requestBody))
                    .onFailure(this::handleRequestBodyError);
            return dep;
        }).otherwise(error -> {
            handleRequestError(deploymentId, error);
            return null;
        });
    }

    @SneakyThrows
    private void handleRequestBody(String endpoint, boolean requireEndpoint, Buffer requestBody) {
        context.setRequestBody(requestBody);
        if (endpoint == null) {
            if (requireEndpoint) {
                respond(HttpStatus.FORBIDDEN, "Forbidden deployment");
            } else {
                respond(HttpStatus.OK);
                proxy.getLogStore().save(context);
            }
            return;
        }

        ApiKeyData proxyApiKeyData = new ApiKeyData();
        setupProxyApiKeyData(proxyApiKeyData);

        proxy.getTaskExecutor().submit(() -> {
            if (runEnhancementFunctions(requestBody)) {
                proxy.getApiKeyStore().assignPerRequestApiKey(proxyApiKeyData);
                return true;
            }
            return false;
        }).onSuccess(result -> {
            if (result) {
                sendRequest(endpoint);
            }
        }).onFailure(this::handleError);

    }

    private boolean runEnhancementFunctions(Buffer requestBody) {
        if (enhancementFunctions.isEmpty()) {
            return true;
        }
        try (InputStream stream = new ByteBufInputStream(requestBody.getByteBuf())) {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            if (ProxyUtil.processChain(tree, enhancementFunctions)) {
                context.setRequestBody(Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree)));
            }
        } catch (Throwable e) {
            if (e instanceof HttpException httpException) {
                respond(httpException.getStatus(), httpException.getMessage());
            } else {
                respond(HttpStatus.BAD_REQUEST);
            }
            log.warn("Can't process JSON request body. Error:", e);
            return false;
        }
        return true;
    }

    private void handleError(Throwable error) {
        respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
        log.error("Error occurred while processing request", error);
    }

    @SneakyThrows
    private void sendRequest(String endpoint) {
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(new URL(endpoint))
                .setMethod(context.getRequest().method())
                .setConnectTimeout(context.getProxy().getClientOptions().getConnectTimeout())
                .setIdleTimeout(context.getProxy().getClientOptions().getIdleTimeout());

        proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    private void setupProxyApiKeyData(ApiKeyData proxyApiKeyData) {
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);
    }

    private void handleRequestError(String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            respond(HttpStatus.FORBIDDEN, error.getMessage());
            log.warn("Forbidden deployment {}", deploymentId);
        } else if (error instanceof ResourceNotFoundException) {
            respond(HttpStatus.NOT_FOUND, error.getMessage());
            log.warn("Deployment not found {}", deploymentId, error);
        } else {
            respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process deployment: " + deploymentId);
            log.error("Failed to handle deployment {}", deploymentId, error);
        }
    }

    /**
     * Called when proxy connected to the origin.
     */
    void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to origin. Address: {}",
                proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        Deployment deployment = context.getDeployment();
        MultiMap excludeHeaders = MultiMap.caseInsensitiveMultiMap();
        if (!deployment.isForwardAuthToken()) {
            excludeHeaders.add(HttpHeaders.AUTHORIZATION, "whatever");
        }
        excludeHeaders.add(HEADER_APPLICATION_PROPERTIES, "whatever");
        excludeHeaders.add(HEADER_APPLICATION_ID, "whatever");

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers(), excludeHeaders);
        ProxyUtil.setOverrideNameHeader(proxyRequest, deployment);

        if ((deployment instanceof Application application && application.hasApplicationTypeSchemaId())) {
            try {
                proxyRequest.headers().add(HEADER_APPLICATION_ID, deployment.getName());
                proxy.getApplicationSchemaService().consumeMetadataProperties(application, (properties, appendApplicationPropertiesHeader) -> {
                    if (appendApplicationPropertiesHeader) {
                        String propsString = ProxyUtil.MAPPER.writeValueAsString(properties);
                        proxyRequest.headers().add(HEADER_APPLICATION_PROPERTIES, propsString);
                    }
                });
            } catch (Throwable e) {
                throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to enrich request with application properties");
            }
        }

        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        proxyRequest.headers().add(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());

        if (deployment instanceof Model model && !model.getUpstreams().isEmpty()) {
            Upstream upstream = model.getUpstreams().getFirst();
            proxyRequest.putHeader(Proxy.HEADER_UPSTREAM_ENDPOINT, upstream.getEndpoint());
            proxyRequest.putHeader(Proxy.HEADER_UPSTREAM_KEY, upstream.getKey());
        }

        Buffer requestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(requestBody.length()));

        proxyRequest.send(requestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyRequestError);
    }

    /**
     * Called when proxy received the response headers from the origin.
     */
    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        log.info("Received response header from origin: status={}, headers={}", proxyResponse.statusCode(),
                proxyResponse.headers().size());

        BufferingReadStream proxyResponseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024));

        context.setProxyResponse(proxyResponse);

        HttpServerResponse response = context.getResponse();
        response.setChunked(true);
        response.setStatusCode(proxyResponse.statusCode());
        ProxyUtil.copyHeaders(proxyResponse.headers(), response.headers());

        proxyResponseStream.pipe()
                .endOnFailure(false)
                .to(response)
                .onSuccess(ignored -> handleResponse(proxyResponseStream))
                .onFailure(this::handleResponseError);
    }

    /**
     * Called when proxy sent response from the origin to the client.
     */
    private void handleResponse(BufferingReadStream responseStream) {
        Buffer proxyResponseBody = responseStream.getContent();
        context.setResponseBody(proxyResponseBody);
        proxy.getLogStore().save(context);
        finalizeRequest();
    }

    /**
     * Called when proxy failed to receive request body from the client.
     */
    private void handleRequestBodyError(Throwable error) {
        respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
        log.warn("Failed to receive client body: {}", error.getMessage());
    }

    /**
     * Called when proxy failed to connect to the origin.
     */
    private void handleProxyConnectionError(Throwable error) {
        respond(HttpStatus.BAD_GATEWAY, "connection error to origin");
        log.warn("Can't connect to origin: {}", error.getMessage());
    }

    /**
     * Called when proxy failed to send request to the origin.
     */
    private void handleProxyRequestError(Throwable error) {
        respond(HttpStatus.BAD_GATEWAY, "deployment responded with error");
        log.warn("Can't send request to origin: {}", error.getMessage());
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    private void handleResponseError(Throwable error) {
        log.warn("Can't send response to client: {}", error.getMessage());
        context.getProxyRequest().reset(); // drop connection to stop origin response
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
        finalizeRequest();
    }

    private void respond(HttpStatus status, String errorMessage) {
        finalizeRequest();
        context.respond(status, errorMessage);
    }

    private void respond(HttpStatus status) {
        finalizeRequest();
        context.respond(status);
    }

    private void finalizeRequest() {
        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        if (proxyApiKeyData != null) {
            proxy.getApiKeyStore().invalidatePerRequestApiKey(proxyApiKeyData)
                    .onSuccess(invalidated -> {
                        if (!invalidated) {
                            log.warn("Per request is not removed: {}", proxyApiKeyData.getPerRequestKey());
                        }
                    }).onFailure(error -> log.error("error occurred on invalidating per-request key", error));
        }
    }
}