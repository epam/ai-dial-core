package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.data.ResourceAccessType;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.resource.ResourceDescription;
import com.epam.aidial.core.server.util.HttpException;
import com.epam.aidial.core.server.util.HttpStatus;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Collects attached files from the chat completion request and puts the result to API key data.
 * <p>
 *     Note. The function assigns a per-request key in the end of the processing.
 * </p>
 */
@Slf4j
public class CollectRequestAttachmentsFn extends BaseRequestFunction<ObjectNode> {
    public CollectRequestAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Throwable apply(ObjectNode tree) {
        try {
            ProxyUtil.collectAttachedFilesFromRequest(tree, this::processAttachedFile);
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
        ResourceDescription resource = fromAnyUrl(url, proxy.getEncryptionService());
        if (resource == null) {
            return;
        }
        String resourceUrl = resource.getUrl();
        ApiKeyData sourceApiKeyData = context.getApiKeyData();
        ApiKeyData destApiKeyData = context.getProxyApiKeyData();
        AccessService accessService = proxy.getAccessService();
        if (sourceApiKeyData.getAttachedFiles().containsKey(resourceUrl) || accessService.hasReadAccess(resource, context)) {
            if (resource.isFolder()) {
                destApiKeyData.getAttachedFolders().put(resourceUrl, new AutoSharedData(ResourceAccessType.READ_ONLY));
            } else {
                destApiKeyData.getAttachedFiles().put(resourceUrl, new AutoSharedData(ResourceAccessType.READ_ONLY));
            }
        } else {
            throw new HttpException(HttpStatus.FORBIDDEN, "Access denied to the file %s".formatted(url));
        }
    }

}
