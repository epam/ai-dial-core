package com.epam.aidial.core.function;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

/**
 * Collects attached files from the chat completion request and puts the result to API key data.
 * <p>
 *     Note. The function assigns a per-request key in the end of the processing.
 * </p>
 */
@Slf4j
public class CollectAttachmentsFn extends BaseFunction<ObjectNode> {
    public CollectAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Throwable apply(ObjectNode tree) {
        try {
            ProxyUtil.collectAttachedFiles(tree, this::processAttachedFile);
            // assign api key data after processing attachments
            ApiKeyData destApiKeyData = context.getProxyApiKeyData();
            proxy.getApiKeyStore().assignPerRequestApiKey(destApiKeyData);
            return null;
        } catch (HttpException e) {
            context.respond(e.getStatus(), e.getMessage());
            log.warn("Can't collect attached files. Trace: {}. Span: {}. Error: {}",
                    context.getTraceId(), context.getSpanId(), e.getMessage());
            return e;
        } catch (Throwable e) {
            context.respond(HttpStatus.BAD_REQUEST);
            log.warn("Can't collect attached files. Trace: {}. Span: {}. Error: {}",
                    context.getTraceId(), context.getSpanId(), e.getMessage());
            return e;
        }
    }

    private void processAttachedFile(String url) {
        ResourceDescription resource = getResourceDescription(url);
        if (resource == null) {
            return;
        }
        String resourceUrl = resource.getUrl();
        ApiKeyData sourceApiKeyData = context.getApiKeyData();
        ApiKeyData destApiKeyData = context.getProxyApiKeyData();
        AccessService accessService = proxy.getAccessService();
        if (sourceApiKeyData.getAttachedFiles().contains(resourceUrl) || accessService.hasReadAccess(resource, context)) {
            if (resource.isFolder()) {
                destApiKeyData.getAttachedFolders().add(resourceUrl);
            } else {
                destApiKeyData.getAttachedFiles().add(resourceUrl);
            }
        } else {
            throw new HttpException(HttpStatus.FORBIDDEN, "Access denied to the file %s".formatted(url));
        }
    }

    @SneakyThrows
    private ResourceDescription getResourceDescription(String url) {
        if (url == null) {
            return null;
        }
        URI uri = new URI(url);
        if (uri.isAbsolute()) {
            // skip public resource
            return null;
        }
        return ResourceDescription.fromAnyUrl(url, proxy.getEncryptionService());
    }
}
