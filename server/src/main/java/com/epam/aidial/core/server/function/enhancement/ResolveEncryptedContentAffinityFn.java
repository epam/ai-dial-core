package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.function.request.ResponsesApiRequest;
import com.epam.aidial.core.server.util.EncryptedContentAffinityUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the upstream config id encoded into an incoming Responses API request's echoed encrypted content
 * items (see {@link EncryptedContentAffinityUtil}), unwraps those items back to their provider-native shape,
 * and stores the resolved id via {@link RequestObject#setEncryptedUpstreamId(String)} so the controller can
 * force routing back to the originating upstream.
 */
@Slf4j
public class ResolveEncryptedContentAffinityFn extends BaseRequestFunction<RequestObject> {

    public ResolveEncryptedContentAffinityFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(RequestObject request) {
        if (!EncryptedContentAffinityUtil.hasConfiguredUpstreams(context.getDeployment())) {
            return false;
        }
        if (!(request instanceof ResponsesApiRequest responsesRequest)) {
            return false;
        }
        Model model = (Model) context.getDeployment();
        Set<String> configuredUpstreamIds = model.getUpstreams().stream()
                .map(Upstream::getId)
                .collect(Collectors.toSet());
        EncryptedContentAffinityUtil.Resolution resolution = responsesRequest.resolveAndUnwrapEncryptedContentAffinity(configuredUpstreamIds);
        if (resolution.upstreamId() == null) {
            return false;
        }
        for (String deploymentName : resolution.deploymentNames()) {
            if (!deploymentName.equals(model.getName())) {
                log.warn("Deployment mismatch: encrypted content was produced by '{}' but current deployment is '{}'",
                        deploymentName, model.getName());
            }
        }
        request.setEncryptedUpstreamId(resolution.upstreamId());
        return false;
    }
}
