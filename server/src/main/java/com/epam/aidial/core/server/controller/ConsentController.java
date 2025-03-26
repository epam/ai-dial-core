package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.consent.ConsentRequest;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

@AllArgsConstructor
@Slf4j
public class ConsentController {

    private ProxyContext context;
    private Proxy proxy;

    public Future<?> requestConsent(String deploymentId) {
        proxy.getVertx().executeBlocking(() -> proxy.getConsentService().buildConsent(context, deploymentId), false).onSuccess(consent -> {
            context.respond(HttpStatus.OK, consent);
        }).onFailure(error -> {
            log.error("Error occurred on requesting user consent: ", error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Requesting user consent is failed");
        });
        return Future.succeededFuture();
    }

    public Future<?> acceptConsent(String deploymentId) {
        context.getRequest()
                .body()
                .compose(buffer -> {
                    ConsentRequest request;
                    try {
                        String body = buffer.toString(StandardCharsets.UTF_8);
                        request = ProxyUtil.convertToObject(body, ConsentRequest.class);
                    } catch (Exception e) {
                        log.error("Invalid request body provided", e);
                        throw new IllegalArgumentException("Can't accept user consent. Incorrect body");
                    }

                    if (request == null || request.getConsent() == null) {
                        log.error("User consent is missed");
                        context.respond(HttpStatus.BAD_REQUEST, "User consent is missed");
                        return Future.succeededFuture();
                    }

                    return proxy.getVertx().executeBlocking(() -> {
                        proxy.getConsentService().acceptConsent(context, deploymentId, request.getConsent());
                        return null;
                    }, false);
                })
                .onSuccess(ignored -> context.respond(HttpStatus.OK))
                .onFailure(error -> {
                    log.error("Error occurred on accepting user consent: ", error);
                    context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Accepting user consent is failed");
                });
        return Future.succeededFuture();
    }

}
