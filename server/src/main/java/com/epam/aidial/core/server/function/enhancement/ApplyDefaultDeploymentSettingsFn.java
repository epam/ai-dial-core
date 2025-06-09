package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class ApplyDefaultDeploymentSettingsFn extends BaseRequestFunction<ObjectNode> {

    /**
     * The flag is used to check interceptor index if it's defined (not equal to -1).
     */
    private final boolean checkInterceptorIndex;

    public ApplyDefaultDeploymentSettingsFn(Proxy proxy, ProxyContext context, boolean checkInterceptorIndex) {
        super(proxy, context);
        this.checkInterceptorIndex = checkInterceptorIndex;
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        ApiKeyData apiKeyData = context.getApiKeyData();
        int interceptorIndex = apiKeyData.getInterceptorIndex();
        boolean applied = false;
        // we want to apply the function only once in the interceptor's call chain.
        // the model has no interceptors OR
        // apply the function at the first interceptor
        if (interceptorIndex == -1 || (checkInterceptorIndex && interceptorIndex == 0)) {

            Deployment deployment = context.getDeployment();
            for (Map.Entry<String, Object> e : deployment.getDefaults().entrySet()) {
                String key = e.getKey();
                Object value = e.getValue();
                if (!tree.has(key)) {
                    tree.set(key, ProxyUtil.MAPPER.convertValue(value, JsonNode.class));
                    applied = true;
                }
            }
        }

        return applied;
    }
}
