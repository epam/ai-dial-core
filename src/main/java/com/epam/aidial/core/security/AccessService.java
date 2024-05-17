package com.epam.aidial.core.security;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.ResourceFolderMetadata;
import com.epam.aidial.core.data.Rule;
import com.epam.aidial.core.service.PublicationService;
import com.epam.aidial.core.service.RuleService;
import com.epam.aidial.core.service.ShareService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ProxyUtil;
import com.epam.aidial.core.util.UrlUtil;
import io.vertx.core.json.JsonObject;

import java.util.List;

public class AccessService {

    private final EncryptionService encryptionService;
    private final ShareService shareService;
    private final RuleService ruleService;
    private final List<Rule> adminRules;

    public AccessService(EncryptionService encryptionService,
                         ShareService shareService,
                         RuleService ruleService,
                         JsonObject settings) {
        this.encryptionService = encryptionService;
        this.shareService = shareService;
        this.ruleService = ruleService;
        this.adminRules = adminRules(settings);
    }

    public boolean hasReadAccess(ResourceDescription resource, ProxyContext context) {
        if (isAutoShared(resource, context)) {
            return true;
        }

        if (resource.isPublic()) {
            return hasPublicAccess(List.of(resource), context, true);
        }

        return isMyResource(resource, context) || isAppResource(resource, context)
                || hasReviewAccess(resource, context, true) || isSharedResource(resource, context);
    }

    public boolean hasWriteAccess(ResourceDescription resource, ProxyContext context) {
        if (resource.isPublic()) {
            return hasPublicAccess(List.of(resource), context, false);
        }

        return isMyResource(resource, context) || isAppResource(resource, context) || hasReviewAccess(resource, context, false);
    }

    /**
     * Checks if USER has public access to the provided resources.
     * This method also checks admin privileges.
     *
     * @param resources - public resources
     * @param context - context
     * @return true - if all provided resources are public and user has permissions to all of them, otherwise - false
     */
    public boolean hasPublicAccess(List<ResourceDescription> resources, ProxyContext context) {
        return hasPublicAccess(resources, context, true);
    }

    /**
     * Checks if USER has public access to the provided resources.
     * This method also checks admin privileges.
     *
     * @param resources - public resources
     * @param context - context
     * @param readOnly - true to check read only access, false to check write access as well
     * @return true - if all provided resources are public and user has permissions to all of them, otherwise - false
     */
    private boolean hasPublicAccess(List<ResourceDescription> resources, ProxyContext context, boolean readOnly) {
        boolean isAllPublic = resources.stream().allMatch(ResourceDescription::isPublic);
        if (!isAllPublic) {
            return false;
        }

        if (readOnly) {
            return hasAdminAccess(context) || ruleService.hasPublicAccess(context, resources);
        } else {
            boolean isNotApplication = (context.getApiKeyData().getPerRequestKey() == null);
            return isNotApplication && hasAdminAccess(context);
        }
    }

    private static boolean isAutoShared(ResourceDescription resource, ProxyContext context) {
        String resourceUrl = resource.getUrl();
        boolean isAutoShared = context.getApiKeyData().getAttachedFiles().contains(resourceUrl);
        if (isAutoShared) {
            return true;
        }
        List<String> attachedFolders = context.getApiKeyData().getAttachedFolders();
        for (String folder : attachedFolders) {
            if (resourceUrl.startsWith(folder)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMyResource(ResourceDescription resource, ProxyContext context) {
        String location = BlobStorageUtil.buildUserBucket(context);
        return resource.getBucketLocation().equals(location);
    }

    private static boolean isAppResource(ResourceDescription resource, ProxyContext context) {
        String deployment = context.getSourceDeployment();
        if (deployment == null) {
            return false;
        }

        String location = BlobStorageUtil.buildAppDataBucket(context);
        if (!resource.getBucketLocation().equals(location)) {
            return false;
        }

        String parentPath = resource.getParentPath();
        String filePath = (parentPath == null)
                ? resource.getName()
                : resource.getParentPath() + BlobStorageUtil.PATH_SEPARATOR + resource.getName();

        return filePath.startsWith(BlobStorageUtil.APPDATA_PATTERN.formatted(UrlUtil.encodePath(deployment)));
    }

    private boolean isSharedResource(ResourceDescription resource, ProxyContext context) {
        String actualUserLocation = BlobStorageUtil.buildInitiatorBucket(context);
        String actualUserBucket = encryptionService.encrypt(actualUserLocation);
        return shareService.hasReadAccess(actualUserBucket, actualUserLocation, resource);
    }

    private boolean hasReviewAccess(ResourceDescription resource, ProxyContext context, boolean readOnly) {
        if (!PublicationService.isReviewResource(resource)) {
            return false;
        }

        if (hasAdminAccess(context)) {
            return true;
        }

        return readOnly && PublicationService.hasReviewAccess(context, resource);
    }

    public boolean hasAdminAccess(ProxyContext context) {
        return RuleMatcher.match(context, adminRules);
    }

    public void filterForbidden(ProxyContext context, ResourceDescription descriptor, MetadataBase metadata) {
        if (descriptor.isPublic() && descriptor.isFolder() && !hasAdminAccess(context)) {
            ResourceFolderMetadata folder = (ResourceFolderMetadata) metadata;
            ruleService.filterForbidden(context, descriptor, folder);
        }
    }

    private static List<Rule> adminRules(JsonObject settings) {
        String rules = settings.getJsonObject("admin").getJsonArray("rules").toString();
        List<Rule> list = ProxyUtil.convertToObject(rules, Rule.LIST_TYPE);
        return (list == null) ? List.of() : list;
    }
}
