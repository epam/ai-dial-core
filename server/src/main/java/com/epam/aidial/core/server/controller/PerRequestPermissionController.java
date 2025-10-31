package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.permission.ListPermissionRequest;
import com.epam.aidial.core.server.data.permission.PerRequestReceiver;
import com.epam.aidial.core.server.service.PerRequestPermissionService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;

@Slf4j
public class PerRequestPermissionController {

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final PerRequestPermissionService service;

    public PerRequestPermissionController(ProxyContext context) {
        this.context = context;
        Proxy proxy = context.getProxy();
        this.taskExecutor = proxy.getTaskExecutor();
        this.service = proxy.getPerRequestPermissionService();
    }

    public Future<Void> handle(String operation) {
        Future<Void> result = Future.succeededFuture();
        if (context.getApiKeyData().getPerRequestKey() == null) {
            context.respond(HttpStatus.UNAUTHORIZED, "Operation is only permitted by per request API key");
            return result;
        }
        Function<Buffer, Future<Object>> bodyHandler;
        switch (operation) {
            case "grant":
                bodyHandler = this::grant;
                break;
            case "revoke":
                bodyHandler = this::revoke;
                break;
            case "list":
                bodyHandler = this::list;
                break;
            default: {
                context.respond(HttpStatus.BAD_REQUEST, "Unsupported operation: " + operation);
                return result;
            }
        }
        context.getRequest()
                .body()
                .compose(bodyHandler)
                .onSuccess(this::respondJson)
                .onFailure(this::respondError);
        return Future.succeededFuture();
    }

    Future<Object> grant(Buffer body) {
        PerRequestReceiver data = convertJson(body, PerRequestReceiver.class);
        return taskExecutor.submit(() -> {
            service.grant(context, data);
            return null;
        });
    }

    Future<Object> revoke(Buffer body) {
        PerRequestReceiver data = convertJson(body, PerRequestReceiver.class);
        return taskExecutor.submit(() -> {
            service.revoke(context, data);
            return null;
        });
    }

    Future<Object> list(Buffer body) {
        ListPermissionRequest data = convertJson(body, ListPermissionRequest.class);
        return taskExecutor.submit(() -> {
            if (data.getWith() == ListPermissionRequest.ShareWith.ME) {
                return service.listResourcePermissions(context);
            } else {
                return service.listReceivers(context);
            }
        });
    }

    private static <T> T convertJson(Buffer body, Class<T> clazz) {
        try {
            T result = ProxyUtil.convertToObject(body, clazz);

            if (result == null) {
                throw new IllegalArgumentException("No JSON body");
            }

            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("Not valid JSON body");
        }
    }

    private void respondJson(Object data) {
        if (data == null) {
            context.respond(HttpStatus.OK);
        } else {
            context.respond(HttpStatus.OK, data);
        }
    }

    private void respondError(Throwable error) {
        switch (error) {
            case IllegalArgumentException ignored ->
                    context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
            case PermissionDeniedException ignored ->
                    context.respond(HttpStatus.FORBIDDEN, error.getMessage());
            case ResourceNotFoundException ignored ->
                    context.respond(HttpStatus.NOT_FOUND, error.getMessage());
            case HttpException e -> context.respond(e.getStatus(), e.getMessage());
            case null, default -> {
                log.error("Failed to handle request", error);
                context.respond(error, "Internal error");
            }
        }
    }
}
