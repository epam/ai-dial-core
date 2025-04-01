package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.consent.AcceptConsentRequest;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class ConsentController {

    private ProxyContext context;
    private Proxy proxy;

    public Future<?> requestConsent(String deploymentId) {
        proxy.getVertx().executeBlocking(() -> proxy.getConsentService().buildConsent(context, deploymentId), false).onSuccess(consent -> {
            context.respond(HttpStatus.OK, consent);
        }).onFailure(error -> handleRequestError(deploymentId, error));
        return Future.succeededFuture();
    }

    public Future<?> acceptConsent(String deploymentId) {
        context.getRequest()
                .body()
                .compose(buffer -> {
                    AcceptConsentRequest request;
                    try {
                        request = ProxyUtil.convertToObject(buffer, AcceptConsentRequest.class);
                    } catch (Exception e) {
                        log.error("Invalid request body provided", e);
                        context.respond(HttpStatus.BAD_REQUEST, "Invalid request body provided");
                        return Future.succeededFuture();
                    }

                    if (request == null || request.getConsent() == null) {
                        context.respond(HttpStatus.BAD_REQUEST, "User consent is missed");
                        return Future.succeededFuture();
                    }

                    return proxy.getVertx().executeBlocking(() -> {
                        proxy.getConsentService().acceptConsent(context, deploymentId, request.getConsent());
                        return null;
                    }, false);
                })
                .onSuccess(ignored -> context.respond(HttpStatus.OK))
                .onFailure(error -> handleRequestError(deploymentId, error));
        return Future.succeededFuture();
    }

    private void handleRequestError(String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            log.error("Forbidden deployment {}. Project: {}. User sub: {}", deploymentId, context.getProject(), context.getUserSub());
            context.respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else if (error instanceof ResourceNotFoundException) {
            log.error("Deployment not found {}", deploymentId, error);
            context.respond(HttpStatus.NOT_FOUND, error.getMessage());
        } else {
            log.error("Failed to process user consent", error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to process user consent for deployment=%s".formatted(deploymentId));
        }
    }

}
