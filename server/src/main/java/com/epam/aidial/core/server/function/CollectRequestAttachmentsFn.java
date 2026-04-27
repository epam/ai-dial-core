package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * Collects attached files from the chat completion request and puts the result to API key data.
 * <p>
 *     Note. The function assigns a per-request key in the end of the processing.
 * </p>
 */
@Slf4j
public abstract class CollectRequestAttachmentsFn extends BaseRequestFunction<RequestObject> {
    public CollectRequestAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(RequestObject request) {
        Set<String> attachments = collectAttachments(request);
        for (String attachment : attachments) {
            tryToAutoShareAttachedFile(attachment);
        }
        return false;
    }

    protected abstract Set<String> collectAttachments(RequestObject tree);

    protected void tryToAutoShareAttachedFile(String url) {
        ResourceDescriptor resource = fromAnyUrl(url, proxy.getEncryptionService());
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
