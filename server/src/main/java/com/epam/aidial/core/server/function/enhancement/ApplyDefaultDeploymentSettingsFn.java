package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class ApplyDefaultDeploymentSettingsFn extends BaseRequestFunction<ObjectNode> {

    public ApplyDefaultDeploymentSettingsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @SneakyThrows
    @Override
    public Boolean apply(ObjectNode tree) {
        boolean applied = false;
        if (shouldApply(context)) {

            Deployment deployment = context.getDeployment();
            if (deployment instanceof Interceptor) {
                String deploymentId = context.getInitialDeployment();
                deployment = proxy.getDeploymentService().findDeployment(context, deploymentId);
            }
            if (!deployment.getDefaults().isEmpty()) {
                applied = true;
            }
            for (Map.Entry<String, Object> e : deployment.getDefaults().entrySet()) {
                String key = e.getKey();
                Object value = e.getValue();
                JsonNode nodeToBeUpdated = tree.get(key);
                JsonNode update = ProxyUtil.MAPPER.convertValue(value, JsonNode.class);
                tree.set(key, merge(nodeToBeUpdated, update));
            }
        }

        return applied;
    }

    private JsonNode merge(JsonNode target, JsonNode source) {
        if (target == null || target.isNull()) {
            return source;
        }
        if (source == null || source.isNull()) {
            return target;
        }
        if (target.getNodeType() != source.getNodeType()) {
            return source;
        }
        if (source.isObject()) {
            return mergeObjects((ObjectNode) target, (ObjectNode) source);
        }
        return target;
    }

    private ObjectNode mergeObjects(ObjectNode target, ObjectNode source) {
        for (Map.Entry<String, JsonNode> entry : source.properties()) {
            String name = entry.getKey();
            target.set(name, merge(target.get(name), entry.getValue()));
        }
        return target;
    }

    /**
     * The function determines if the call is made to:
     *
     * <ul>
     *     <li>the first interceptor or</li>
     *     <li>the deployment without interceptors or</li>
     *     <li>the deployment from the interceptor</li>
     * </ul>
     */
    private static boolean shouldApply(ProxyContext context) {
        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        int interceptorIndex = proxyApiKeyData.getInterceptorIndex();
        if (interceptorIndex == 0) {
            return true;
        }
        if (interceptorIndex == -1) {
            ApiKeyData apiKeyData = context.getApiKeyData();
            if (apiKeyData.isInterceptor()) {
                // interceptor may call another deployment
                return !context.getDeployment().getName().equals(context.getInitialDeployment());
            }
            return true;
        }
        return false;
    }
}
