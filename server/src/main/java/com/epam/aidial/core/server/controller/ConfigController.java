package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class ConfigController implements Controller {

    private final ProxyContext context;

    @Override
    public Future<?> handle() throws Exception {
        Proxy proxy = context.getProxy();
        proxy.getVertx().executeBlocking(() -> {
            if (proxy.getAccessService().hasAdminAccess(context)) {
                return context.getProxy().getConfigStore().reload();
            }
            throw new PermissionDeniedException("User must be admin");
        }, false)
                .onSuccess(config -> context.respond(HttpStatus.OK, config))
                .onFailure(this::handleError);
        return Future.succeededFuture();
    }

    private void handleError(Throwable error) {
        if (error instanceof PermissionDeniedException) {
            context.respond(HttpStatus.FORBIDDEN, error.getMessage());
        } else {
            log.error("Failed to reload config", error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
        }
    }
}
