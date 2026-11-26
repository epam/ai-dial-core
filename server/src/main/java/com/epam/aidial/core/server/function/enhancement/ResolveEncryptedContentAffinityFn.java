package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.function.request.ResponsesApiRequest;
import com.epam.aidial.core.server.util.EncryptedContentAffinityUtil;
import lombok.extern.slf4j.Slf4j;


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
        if (!(request instanceof ResponsesApiRequest responsesRequest) || !(context.getDeployment() instanceof Model model)) {
            return false;
        }
        String upstreamId = responsesRequest.resolveAndUnwrapEncryptedContentAffinity(model);
        if (upstreamId != null) {
            request.setEncryptedUpstreamId(upstreamId);
        }
        return false;
    }
}
