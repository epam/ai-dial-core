package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.util.ApplicationTypeSchemaUtils;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class AppendApplicationPropertiesFn extends BaseRequestFunction<ObjectNode> {
    public AppendApplicationPropertiesFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        Deployment deployment = context.getDeployment();
        if (!(deployment instanceof Application application && application.getApplicationTypeSchemaId() != null)) {
            return false;
        }
        Map<String, Object> props = ApplicationTypeSchemaUtils.getCustomServerProperties(context.getConfig(), application);
        ObjectNode customFieldsNode = ProxyUtil.MAPPER.createObjectNode();
        customFieldsNode.set("application_properties", ProxyUtil.MAPPER.valueToTree(props));
        JsonNode currentCustomFields = tree.get("custom_fields");
        if (currentCustomFields != null) {
            ((ObjectNode) currentCustomFields).setAll(customFieldsNode);
        } else {
            tree.set("custom_fields", customFieldsNode);
        }
        return true;
    }
}
