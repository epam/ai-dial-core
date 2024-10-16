package com.epam.aidial.core.controller;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class UserInfoController implements Controller {

    private final ProxyContext context;

    @Override
    public Future<?> handle() throws Exception {
        JsonObject response = new JsonObject();
        response.put("roles", context.getUserRoles());
        if (context.getKey() != null) {
            response.put("project", context.getKey().getProject());
        } else {
            response.put("userClaims", context.getExtractedClaims().userClaims());
        }
        context.respond(HttpStatus.OK, response.encode());
        return Future.succeededFuture();
    }
}
