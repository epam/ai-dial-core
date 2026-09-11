package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

/**
 * Collects skill resources referenced from {@code messages[*].custom_content.skills[*]} and auto-shares
 * them to the invoked deployment's per-request API key, the same way {@link CollectRequestAttachmentsFn}
 * auto-shares attachments.
 */
public class CollectRequestSkillsFn extends BaseRequestFunction<RequestObject> {
    public CollectRequestSkillsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(RequestObject request) {
        for (String url : request.collectSkills()) {
            tryToAutoShareAttachedSkill(url);
        }
        return false;
    }

    private void tryToAutoShareAttachedSkill(String url) {
        ResourceDescriptor resource = fromAnyUrl(url, proxy.getEncryptionService());
        if (resource == null || resource.getType() != ResourceTypes.SKILL) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Url must reference a skill resource: %s".formatted(url));
        }
        if (resource.isPublic()) {
            return;
        }
        AccessService accessService = proxy.getAccessService();
        if (accessService.hasReadAccess(resource, context)) {
            context.getProxyApiKeyData().getAttachedSkills()
                    .put(resource.getUrl(), new AutoSharedData(ResourceAccessType.READ_ONLY));
        } else {
            throw new HttpException(HttpStatus.FORBIDDEN, "Access denied to the skill %s".formatted(url));
        }
    }
}
