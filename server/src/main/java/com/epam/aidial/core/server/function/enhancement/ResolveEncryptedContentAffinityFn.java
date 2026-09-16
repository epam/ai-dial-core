package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.function.request.ResponsesApiRequest;
import com.epam.aidial.core.server.util.EncryptedContentAffinityUtil;
import lombok.Getter;

/**
 * Resolves the upstream config id encoded into an incoming Responses API request's echoed encrypted content
 * items (see {@link EncryptedContentAffinityUtil}), unwraps those items back to their provider-native shape,
 * and exposes the resolved id via {@link #getUpstreamConfigId()} so the controller can force routing back to
 * the originating upstream.
 */
public class ResolveEncryptedContentAffinityFn extends BaseRequestFunction<RequestObject> {

    @Getter
    private String upstreamConfigId;

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
        String resolvedUpstreamId = responsesRequest.resolveAndUnwrapEncryptedContentAffinity();
        if (resolvedUpstreamId == null) {
            return false;
        }
        Model model = (Model) context.getDeployment();
        boolean exists = model.getUpstreams().stream()
                .anyMatch(upstream -> resolvedUpstreamId.equals(upstream.getId()));
        if (!exists) {
            throw EncryptedContentAffinityUtil.upstreamUnavailableException(resolvedUpstreamId);
        }
        this.upstreamConfigId = resolvedUpstreamId;
        return false;
    }
}
