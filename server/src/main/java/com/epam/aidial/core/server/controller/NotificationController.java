package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.DeleteNotificationRequest;
import com.epam.aidial.core.server.data.Notifications;
import com.epam.aidial.core.server.service.NotificationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NotificationController {

    private final ProxyContext context;
    private final NotificationService service;
    private final AsyncTaskExecutor taskExecutor;

    public NotificationController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.service = proxy.getNotificationService();
        this.taskExecutor = proxy.getTaskExecutor();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/notification/list",
            operationId = "getNotifications",
            tags = {"Notifications"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = Notifications.class)),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> listNotifications() {
        taskExecutor.submit(() -> service.listNotification(context))
                .onSuccess(notifications -> context.respond(HttpStatus.OK, new Notifications(notifications)))
                .onFailure(error -> respondError("Can't list notifications", error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/notification/delete",
            operationId = "deleteNotifications",
            requestBody = @ApiSchema(implementation = DeleteNotificationRequest.class),
            tags = {"Notifications"},
            responses = {
                    @ApiResponse(code = 200, description = "Success"),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> deleteNotification() {
        context.getRequest()
                .body()
                .compose(body -> {
                    DeleteNotificationRequest request = ProxyUtil.convertToObject(body, DeleteNotificationRequest.class);
                    return taskExecutor.submit(() -> {
                        service.deleteNotification(context, request);
                        return null;
                    });
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
        }

        context.respond(status, body);
        
        if (!(error instanceof HttpException) && !(error instanceof IllegalArgumentException)) {
            log.warn(message, error);
        }
    }
}