package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class ApplyDefaultDeploymentSettingsFn extends BaseRequestFunction<RequestObject> {

    public ApplyDefaultDeploymentSettingsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(RequestObject request) {
        applyInterceptorDefaults(request);
        applyDeploymentDefaults(request);
        return true;
    }

    private void applyInterceptorDefaults(RequestObject request) {
        request.clearInterceptorSettings();
        Deployment deployment = context.getDeployment();
        if (deployment instanceof Interceptor) {
            applyDefaults(request, deployment);
        }
    }

    private void applyDeploymentDefaults(RequestObject request) {
        if (shouldApply(context)) {
            Deployment deployment = context.getDeployment();
            if (deployment instanceof Interceptor) {
                String deploymentId = context.getInitialDeployment();
                deployment = proxy.getDeploymentService().findDeployment(context, deploymentId);
            }
            applyDefaults(request, deployment);
        }
    }

    private void applyDefaults(RequestObject request, Deployment deployment) {
        for (Map.Entry<String, Object> e : deployment.getDefaults().entrySet()) {
            String key = e.getKey();
            JsonNode update = ProxyUtil.MAPPER.convertValue(e.getValue(), JsonNode.class);
            request.update(key, oldValue -> copy(oldValue, update));
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
