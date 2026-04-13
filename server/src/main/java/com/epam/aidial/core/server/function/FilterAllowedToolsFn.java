package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

public class FilterAllowedToolsFn extends BaseResponseFunction {

    private static final ArrayNode EMPTY_JSON_ARRAY = ProxyUtil.MAPPER.createArrayNode();

    public FilterAllowedToolsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Future<JsonNode> apply(JsonNode jsonNode) {
        if (!jsonNode.isObject()) {
            return Future.succeededFuture(jsonNode);
        }
        ObjectNode body = (ObjectNode) jsonNode;
        ArrayNode tools = (ArrayNode) Optional.ofNullable(body.get("result")).map(result -> result.get("tools"))
                .filter(JsonNode::isArray).orElse(EMPTY_JSON_ARRAY);
        List<String> allowedTools = getAllowedTools(context.getDeployment());
        if (allowedTools.isEmpty()) {
            return Future.succeededFuture(jsonNode);
        }
        for (Iterator<JsonNode> iter = tools.iterator(); iter.hasNext();) {
            JsonNode tool = iter.next();
            JsonNode name = tool.get("name");
            if (name != null && !allowedTools.contains(name.asText())) {
                iter.remove();
            }
        }
        return Future.succeededFuture(jsonNode);
    }

    private List<String> getAllowedTools(Deployment deployment) {
        if (deployment instanceof ToolSet toolSet) {
            return toolSet.getAllowedTools();
        } else if (deployment instanceof Application application) {
            return application.getMcp().getAllowedTools();
        }
        throw new IllegalArgumentException("Unsupported deployment type: " + deployment.getName());
    }
}
