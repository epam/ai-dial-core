package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.request.RequestObject;
import lombok.extern.slf4j.Slf4j;

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
            request.applyDefaults(deployment);
        }
    }

    private void applyDeploymentDefaults(RequestObject request) {
        if (shouldApply(context)) {
            Deployment deployment = context.getDeployment();
            if (deployment instanceof Interceptor) {
                String deploymentId = context.getInitialDeployment();
                deployment = proxy.getDeploymentService().findDeployment(context, deploymentId);
            }
            request.applyDefaults(deployment);
        }
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
