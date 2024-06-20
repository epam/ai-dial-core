package com.epam.aidial.core.security;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.MetadataBase;
import com.epam.aidial.core.data.ResourceAccessType;
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

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

public class AccessService {

    private final EncryptionService encryptionService;
    private final ShareService shareService;
    private final RuleService ruleService;
    private final List<Rule> adminRules;
    private final List<PermissionRule> permissionRules = List.of(
            new PermissionRule(AccessService::isAutoShared, (resource, context) -> EnumSet.of(ResourceAccessType.READ)),
            new PermissionRule((resource, context) -> resource.isPublic(), this::getPublicAccess),
            new PermissionRule((resource, context) -> PublicationService.isReviewResource(resource), this::getReviewAccess),
            new PermissionRule(AccessService::isMyResource, (resource, context) -> EnumSet.allOf(ResourceAccessType.class)),
            new PermissionRule(AccessService::isAppResource, (resource, context) -> EnumSet.allOf(ResourceAccessType.class)),
            new PermissionRule((resource, context) -> true, this::getSharedAccess)
    );

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
        return !lookupPermissions(resource, context, Set.of(ResourceAccessType.READ)).isEmpty();
    }

    public Set<ResourceAccessType> lookupPermissions(ResourceDescription resource, ProxyContext context) {
        return lookupPermissions(resource, context, EnumSet.allOf(ResourceAccessType.class));
    }

    public Set<ResourceAccessType> lookupPermissions(
            ResourceDescription resource, ProxyContext context, Set<ResourceAccessType> toLookup) {
        Set<ResourceAccessType> remainingAccess = new HashSet<>(toLookup);
        Set<ResourceAccessType> result = new HashSet<>();
        for (PermissionRule permissionRule : permissionRules) {
            if (permissionRule.predicate.test(resource, context)) {
                Set<ResourceAccessType> permissions = permissionRule.function.apply(resource, context);
                for (ResourceAccessType permission : permissions) {
                    if (remainingAccess.remove(permission)) {
                        result.add(permission);
                        if (remainingAccess.isEmpty()) {
                            return result;
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * Returns USER permissions to the provided public resources.
     * This method also checks admin privileges.
     *
     * @param resources - public resources
     * @param context - context
     * @return USER permissions
     */
    private Set<ResourceAccessType> getPublicAccess(ResourceDescription resources, ProxyContext context) {
        if (hasAdminAccess(context)) {
            boolean isNotApplication = (context.getApiKeyData().getPerRequestKey() == null);
            return isNotApplication
                    ? EnumSet.allOf(ResourceAccessType.class)
                    : EnumSet.of(ResourceAccessType.READ);
        }

        return ruleService.hasPublicAccess(context, resources)
                ? EnumSet.of(ResourceAccessType.READ)
                : Set.of();
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

    private Set<ResourceAccessType> getSharedAccess(ResourceDescription resource, ProxyContext context) {
        String actualUserLocation = BlobStorageUtil.buildInitiatorBucket(context);
        String actualUserBucket = encryptionService.encrypt(actualUserLocation);
        return shareService.getPermissions(actualUserBucket, actualUserLocation, resource);
    }

    private Set<ResourceAccessType> getReviewAccess(ResourceDescription resource, ProxyContext context) {
        if (hasAdminAccess(context)) {
            return EnumSet.allOf(ResourceAccessType.class);
        }

        return PublicationService.hasReviewAccess(context, resource)
                ? EnumSet.of(ResourceAccessType.READ)
                : Set.of();
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

    private record PermissionRule(
            BiPredicate<ResourceDescription, ProxyContext> predicate,
            BiFunction<ResourceDescription, ProxyContext, Set<ResourceAccessType>> function) {
    }
}
