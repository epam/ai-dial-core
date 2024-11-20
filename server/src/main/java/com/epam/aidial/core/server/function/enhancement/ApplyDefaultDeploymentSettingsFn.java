package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class ApplyDefaultDeploymentSettingsFn extends BaseRequestFunction<ObjectNode> {
    public ApplyDefaultDeploymentSettingsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        Deployment deployment = context.getDeployment();
        boolean applied = false;
        for (Map.Entry<String, Object> e : deployment.getDefaults().entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (!tree.has(key)) {
                tree.set(key, ProxyUtil.MAPPER.convertValue(value, JsonNode.class));
                applied = true;
            }
        }

        return applied;
    }
}
