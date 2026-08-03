package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;

public class RewriteMcpUiDomainFn extends BaseResponseFunction {

    public RewriteMcpUiDomainFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Future<JsonNode> apply(JsonNode jsonNode) {
        String domain = getMcpAppsDomain();
        if (domain == null) {
            return Future.succeededFuture(jsonNode);
        }
        JsonNode tools = jsonNode.path("result").path("tools");
        if (!tools.isArray()) {
            return Future.succeededFuture(jsonNode);
        }
        for (JsonNode tool : tools) {
            if (!(tool instanceof ObjectNode t)) {
                continue;
            }
            JsonNode metaNode = t.get("_meta");
            if (metaNode != null && !(metaNode instanceof ObjectNode)) {
                continue;
            }
            ObjectNode meta = metaNode != null ? (ObjectNode) metaNode : t.putObject("_meta");
            JsonNode uiNode = meta.get("ui");
            if (uiNode != null && !(uiNode instanceof ObjectNode)) {
                continue;
            }
            ObjectNode ui = uiNode != null ? (ObjectNode) uiNode : meta.putObject("ui");
            ui.put("domain", domain);
        }
        return Future.succeededFuture(jsonNode);
    }

    private String getMcpAppsDomain() {
        if (context.getDeployment() instanceof Application app
                && app.getMcp() != null
                && app.getMcp().getMcpApps() != null) {
            return app.getMcp().getMcpApps().getDomainOverride();
        }
        return null;
    }
}
