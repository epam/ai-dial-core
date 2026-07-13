package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.consent.AcceptConsentRequest;
import com.epam.aidial.core.server.data.consent.ReviewConsentResponse;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class ConsentController {

    private ProxyContext context;
    private Proxy proxy;

    @ApiOperation(
            method = "GET",
            path = "/v1/consent/{deployment_id}",
            operationId = "requestUserConsent",
            tags = {"User Consent"},
            parameters = {
                    @ApiParameter(name = "deployment_id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ReviewConsentResponse.class)),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> requestConsent(String deploymentId) {
        proxy.getTaskExecutor().submit(() -> proxy.getConsentService().buildConsent(context, deploymentId)).onSuccess(consent -> {
            context.respond(HttpStatus.OK, consent);
        }).onFailure(error -> handleRequestError(deploymentId, error));
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/consent/{deployment_id}",
            operationId = "acceptUserConsent",
            requestBody = @ApiSchema(implementation = AcceptConsentRequest.class),
            tags = {"User Consent"},
            parameters = {
                    @ApiParameter(name = "deployment_id", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success"),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> acceptConsent(String deploymentId) {
        context.getRequest()
                .body()
                .compose(buffer -> {
                    AcceptConsentRequest request;
                    try {
                        request = ProxyUtil.convertToObject(buffer, AcceptConsentRequest.class);
                    } catch (Exception e) {
                        log.warn("Invalid request body provided", e);
                        context.respond(HttpStatus.BAD_REQUEST, "Invalid request body provided");
                        return Future.succeededFuture();
                    }

                    if (request == null || request.getConsent() == null) {
                        context.respond(HttpStatus.BAD_REQUEST, "User consent is missed");
                        return Future.succeededFuture();
                    }

                    return proxy.getTaskExecutor().submit(() -> {
                        proxy.getConsentService().acceptConsent(context, deploymentId, request.getConsent());
                        return null;
                    });
                })
                .onSuccess(ignored -> context.respond(HttpStatus.OK))
                .onFailure(error -> handleRequestError(deploymentId, error));
        return Future.succeededFuture();
    }

    private void handleRequestError(String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            log.warn("Forbidden deployment {}", deploymentId);
            context.respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            log.warn("Deployment not found {}", deploymentId, error);
            context.respond(HttpStatus.NOT_FOUND, error.getMessage());
        } else {
            log.error("Failed to process user consent", error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to process user consent for deployment=%s".formatted(deploymentId));
        }
    }

}