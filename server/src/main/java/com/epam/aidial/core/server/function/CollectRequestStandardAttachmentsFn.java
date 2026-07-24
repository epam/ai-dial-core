package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.request.RequestObject;

import java.util.Set;

/**
 * Collects attachments (DIAL links) from Chat Completions and Responses API requests,
 * using the attachment locations described in their respective API specifications.
 */
public class CollectRequestStandardAttachmentsFn extends CollectRequestAttachmentsFn {

    public CollectRequestStandardAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected Set<String> collectAttachments(RequestObject request) {
        return request.collectAttachments();
    }
}
