package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.request.RequestObject;

import java.util.Set;

public class CollectRequestChatCompletionAttachmentsFn extends CollectRequestAttachmentsFn {

    public CollectRequestChatCompletionAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected Set<String> collectAttachments(RequestObject request) {
        return request.collectAttachments();
    }
}
