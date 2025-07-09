package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

public class CollectRequestChatCompletionAttachmentsFn extends CollectRequestAttachmentsFn {

    public CollectRequestChatCompletionAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected Set<String> collectAttachments(ObjectNode tree) {
        Set<String> attachments = new HashSet<>();
        ProxyUtil.collectAttachedFilesFromRequest(tree, attachments::add);
        return attachments;
    }
}
