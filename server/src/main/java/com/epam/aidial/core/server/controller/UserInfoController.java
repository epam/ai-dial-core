package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import lombok.AllArgsConstructor;

import java.util.Iterator;

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
            ObjectNode claims = normalize(context.getExtractedClaims().userClaims());
            response.put("userClaims", claims);
        }
        context.respond(HttpStatus.OK, response.encode());
        return Future.succeededFuture();
    }

    /**
     * Normalizes user claims to support backward compatibility.
     */
    private ObjectNode normalize(ObjectNode userClaims) {
        Iterator<String> fields = userClaims.fieldNames();
        ObjectNode result = ProxyUtil.MAPPER.createObjectNode();
        while (fields.hasNext()) {
            String field = fields.next();
            JsonNode property = userClaims.get(field);
            if (property.isTextual()) {
                ArrayNode array = ProxyUtil.MAPPER.createArrayNode();
                array.add(property.textValue());
                result.set(field, array);
            } else {
                result.set(field, property);
            }
        }
        return result;
    }
}
