package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

public class CollectResponseChatCompletionAttachmentsFn extends CollectResponseAttachmentsFn {
    public CollectResponseChatCompletionAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected Set<String> collectAttachments(ObjectNode tree) {
        Set<String> result = new HashSet<>();
        ProxyUtil.collectAttachmentsFromResponse(tree, context.isStreamingRequest(), result::add);
        return result;
    }
}
