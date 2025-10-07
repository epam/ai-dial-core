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
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class ApplyDefaultDeploymentSettingsFn extends BaseRequestFunction<ObjectNode> {

    public ApplyDefaultDeploymentSettingsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        applyInterceptorDefaults(tree);
        applyDeploymentDefaults(tree);
        return true;
    }

    private void applyInterceptorDefaults(ObjectNode tree) {
        ObjectNode customFields = (ObjectNode) tree.get("custom_fields");
        if (customFields != null) {
            customFields.remove("interceptor_configuration");
            if (customFields.isEmpty()) {
                tree.remove("custom_fields");
            }
        }
        Deployment deployment = context.getDeployment();
        if (deployment instanceof Interceptor) {
            applyDefaults(tree, deployment);
        }
    }

    private void applyDeploymentDefaults(ObjectNode tree) {
        if (shouldApply(context)) {
            Deployment deployment = context.getDeployment();
            if (deployment instanceof Interceptor) {
                String deploymentId = context.getInitialDeployment();
                deployment = proxy.getDeploymentService().findDeployment(context, deploymentId);
            }
            applyDefaults(tree, deployment);
        }
    }

    private void applyDefaults(ObjectNode tree, Deployment deployment) {
        for (Map.Entry<String, Object> e : deployment.getDefaults().entrySet()) {
            String key = e.getKey();
            JsonNode update = ProxyUtil.MAPPER.convertValue(e.getValue(), JsonNode.class);
            tree.set(key, copy(tree.get(key), update));
        }
    }

    /**
     * Copies default values to the target node from the source.
     * The default value is copied from the source to the target if it's missed in the target node.
     *
     * <p>
     *     Note. Arrays are not copied.
     * </p>
     */
    private static JsonNode copy(JsonNode target, JsonNode source) {
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
            return copyObjects((ObjectNode) target, (ObjectNode) source);
        }
        return target;
    }

    private static ObjectNode copyObjects(ObjectNode target, ObjectNode source) {
        for (Map.Entry<String, JsonNode> entry : source.properties()) {
            String name = entry.getKey();
            target.set(name, copy(target.get(name), entry.getValue()));
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
