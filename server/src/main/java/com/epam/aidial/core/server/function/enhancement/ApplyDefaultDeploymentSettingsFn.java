package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.request.RequestObject;
import io.vertx.core.MultiMap;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class ApplyDefaultDeploymentSettingsFn extends BaseRequestFunction<RequestObject> {

    private final InterfaceType interfaceType;

    public ApplyDefaultDeploymentSettingsFn(Proxy proxy, ProxyContext context, InterfaceType interfaceType) {
        super(proxy, context);
        this.interfaceType = interfaceType;
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

    /**
     * Default body params and default headers land together, and neither replaces a value the request
     * already carries. At the first interceptor that means the interceptor's own settings, applied first,
     * take precedence over the ones of the deployment it fronts.
     */
    private void applyDefaults(RequestObject request, Deployment deployment) {
        request.applyDefaults(deployment);
        applyDefaultHeaders(deployment);
    }

    /**
     * Adds the deployment's default headers to the inbound request under every name it does not already
     * carry. They land on the inbound request, not on the outgoing one, so that they behave exactly as if
     * the client had sent them: the core's own header reads see them, and they reach the deployment
     * through the same copy - and the same exclusions - as client headers.
     */
    private void applyDefaultHeaders(Deployment deployment) {
        Map<String, String> defaultHeaders = deployment.resolveDefaultHeaders(interfaceType);
        if (defaultHeaders.isEmpty()) {
            return;
        }
        MultiMap headers = context.getRequest().headers();
        defaultHeaders.forEach((name, value) -> {
            if (!headers.contains(name)) {
                headers.set(name, value);
            }
        });
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
