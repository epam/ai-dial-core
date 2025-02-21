package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.ProxyContext;
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
        context.getProxy().getVertx().executeBlocking(() -> {
            context.getProxy().getConfigStore().reload();
            return null;
        }, false)
                .onSuccess(ignore -> context.respond(HttpStatus.OK))
                .onFailure(error -> log.error("Failed to reload config", error));
        return Future.succeededFuture();
    }
}
