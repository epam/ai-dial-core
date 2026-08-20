package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

public class InjectApplicationPropsToMcpRequest extends BaseRequestFunction<ObjectNode> {

    private static final String[] APP_PROPS_PATH = new String[]{"params", "_meta", "ai_dial_config"};

    public InjectApplicationPropsToMcpRequest(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        Deployment deployment = context.getDeployment();
        if (deployment instanceof Application application) {
            return injectAiDialConfig(tree, application);
        }
        return false;
    }

    /**
     * Injects the application's custom properties (e.g. {@code openapi}) into the JSON-RPC request
     * body at {@code params._meta.ai_dial_config}, when the application uses {@code META} config
     * delivery (the default). Returns {@code true} if the body was modified.
     */
    public static boolean injectAiDialConfig(ObjectNode tree, Application application) {
        if (application.getMcp().getConfigDelivery() != Application.McpConfigDelivery.META) {
            return false;
        }
        Map<String, Object> props = application.getApplicationProperties();
        if (props == null || props.isEmpty()) {
            return false;
        }
        ObjectNode node = getApplicationPropsNode(tree);
        put(node, props);
        return true;
    }

    private static void put(ObjectNode node, Map<String, Object> appProps) {
        for (Map.Entry<String, Object> e : appProps.entrySet()) {
            JsonNode val = ProxyUtil.MAPPER.valueToTree(e.getValue());
            node.set(e.getKey(), val);
        }
    }

    private static ObjectNode getApplicationPropsNode(ObjectNode tree) {
        ObjectNode root = tree;
        for (String field : APP_PROPS_PATH) {
            JsonNode node = root.get(field);
            if (node == null || !node.isObject()) {
                node = ProxyUtil.MAPPER.createObjectNode();
                root.set(field, node);
            }
            root = (ObjectNode) node;
        }
        return root;
    }
}
