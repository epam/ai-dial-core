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
        if (deployment instanceof Application application
                && application.hasApplicationTypeSchemaId()
                && application.getMcp().getConfigDelivery() == Application.McpConfigDelivery.META) {
            Map<String, Object> props = application.getApplicationProperties();
            if (props == null || props.isEmpty()) {
                return false;
            }
            ObjectNode node = getApplicationPropsNode(tree);
            put(node, application.getApplicationProperties());
            return true;
        }
        return false;
    }

    private void put(ObjectNode node, Map<String, Object> appProps) {
        for (Map.Entry<String, Object> e : appProps.entrySet()) {
            JsonNode val = ProxyUtil.MAPPER.valueToTree(e.getValue());
            node.set(e.getKey(), val);
        }
    }

    private ObjectNode getApplicationPropsNode(ObjectNode tree) {
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
