package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public abstract class BaseFunction<T, R> implements Function<T, R> {
    protected final Proxy proxy;
    protected final ProxyContext context;

    public BaseFunction(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
    }

    @SneakyThrows
    public static ResourceDescriptor fromAnyUrl(String url, EncryptionService encryption) {
        if (url == null || UrlUtil.isAbsoluteUrl(url) || UrlUtil.isDataUrl(url)) {
            // skipping public resources and Data URLs
            return null;
        }

        return ResourceDescriptorFactory.fromAnyUrl(url, encryption);
    }

    public static Set<String> collectAttachmentsFromJson(ObjectNode tree, List<String> jsonPointers) {
        if (jsonPointers.isEmpty()) {
            return Set.of();
        }
        Set<String> attachments = new HashSet<>();
        for (String pointer : jsonPointers) {
            JsonNode node = tree.at(pointer);
            if (node == null) {
                continue;
            }
            if (node.isTextual()) {
                attachments.add(node.textValue());
            } else if (node.isArray()) {
                ArrayNode arrayNode = (ArrayNode) node;
                for (JsonNode item : arrayNode) {
                    if (item.isTextual()) {
                        attachments.add(item.textValue());
                    }
                }
            }
        }
        return attachments;
    }
}
