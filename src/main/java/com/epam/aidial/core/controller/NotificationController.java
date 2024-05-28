package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.DeleteNotificationRequest;
import com.epam.aidial.core.data.Notifications;
import com.epam.aidial.core.service.NotificationService;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NotificationController {

    private final ProxyContext context;
    private final Vertx vertx;
    private final NotificationService service;

    public NotificationController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.vertx = proxy.getVertx();
        this.service = proxy.getNotificationService();
    }

    public Future<?> listNotifications() {
        vertx.executeBlocking(() -> service.listNotification(context), false)
                .onSuccess(notifications -> context.respond(HttpStatus.OK, new Notifications(notifications)))
                .onFailure(error -> respondError("Can't list notifications", error));

        return Future.succeededFuture();
    }

    public Future<?> deleteNotification() {
        context.getRequest()
                .body()
                .compose(body -> {
                    DeleteNotificationRequest request = ProxyUtil.convertToObject(body, DeleteNotificationRequest.class);
                    return vertx.executeBlocking(() -> {
                        service.deleteNotification(context, request);
                        return null;
                    }, false);
                })
                .onSuccess(ignore -> context.respond(HttpStatus.OK))
                .onFailure(error -> respondError("Can't delete notifications", error));

        return Future.succeededFuture();
    }

    private void respondError(String message, Throwable error) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String body = null;

        if (error instanceof HttpException e) {
            status = e.getStatus();
            body = e.getMessage();
        } else if (error instanceof IllegalArgumentException e) {
            status = HttpStatus.BAD_REQUEST;
            body = e.getMessage();
        } else {
            log.warn(message, error);
        }

        context.respond(status, body);
    }
}
