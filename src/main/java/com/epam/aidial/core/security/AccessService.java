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
import com.google.common.collect.Sets;
import io.vertx.core.json.JsonObject;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;


public class AccessService {

    private final EncryptionService encryptionService;
    private final ShareService shareService;
    private final RuleService ruleService;
    private final List<Rule> adminRules;
    private final List<PermissionRule> permissionRules = List.of(
            AccessService::getAutoSharedAccess,
            this::getPublicAccess,
            this::getReviewAccess,
            AccessService::getOwnResourcesAccess,
            AccessService::getAppResourceAccess,
            this::getSharedAccess);

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
        return hasAccess(resource, context, ResourceAccessType.READ);
    }

    public boolean hasPublicReadAccess(ResourceDescription resource, ProxyContext context) {
        return getPublicAccess(Set.of(resource), context).getOrDefault(resource, Set.of())
                .contains(ResourceAccessType.READ);
    }

    public boolean hasAccess(
            ResourceDescription resource, ProxyContext context, ResourceAccessType resourceAccessType) {
        Map<ResourceDescription, Set<ResourceAccessType>> permissions =
                lookupPermissions(Set.of(resource), context, Set.of(resourceAccessType));
        return permissions.get(resource).contains(resourceAccessType);
    }

    public Map<ResourceDescription, Set<ResourceAccessType>> lookupPermissions(
            Set<ResourceDescription> resources, ProxyContext context) {
        return lookupPermissions(resources, context, ResourceAccessType.ALL);
    }

    private Map<ResourceDescription, Set<ResourceAccessType>> lookupPermissions(
            Set<ResourceDescription> resources, ProxyContext context, Set<ResourceAccessType> toLookup) {
        Map<ResourceDescription, Set<ResourceAccessType>> result = new HashMap<>();
        Set<ResourceDescription> remainingResources = new HashSet<>(resources);
        for (PermissionRule permissionRule : permissionRules) {
            Map<ResourceDescription, Set<ResourceAccessType>> rulePermissions =
                    permissionRule.apply(remainingResources, context);

            rulePermissions.forEach((resource, permissions) -> {
                Set<ResourceAccessType> mergedPermissions = result.merge(resource, permissions, Sets::union);
                if (toLookup.equals(mergedPermissions)) {
                    remainingResources.remove(resource);
                }
            });
        }
        for (ResourceDescription resource : resources) {
            result.computeIfAbsent(resource, r -> Set.of());
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
    private Map<ResourceDescription, Set<ResourceAccessType>> getPublicAccess(
            Set<ResourceDescription> resources, ProxyContext context) {
        Map<ResourceDescription, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceDescription resource : resources) {
            if (!resource.isPublic()) {
                continue;
            }

            if (hasAdminAccess(context)) {
                boolean isNotApplication = (context.getApiKeyData().getPerRequestKey() == null);
                result.put(resource, isNotApplication
                        ? ResourceAccessType.ALL
                        : EnumSet.of(ResourceAccessType.READ));
            } else if (ruleService.hasPublicAccess(context, resource)) {
                result.put(resource, EnumSet.of(ResourceAccessType.READ));
            }
        }

        return result;
    }

    private static Map<ResourceDescription, Set<ResourceAccessType>> getAutoSharedAccess(
            Set<ResourceDescription> resources, ProxyContext context) {
        Map<ResourceDescription, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceDescription resource : resources) {
            String resourceUrl = resource.getUrl();
            boolean isAutoShared = context.getApiKeyData().getAttachedFiles().contains(resourceUrl);
            if (isAutoShared) {
                result.put(resource, EnumSet.of(ResourceAccessType.READ));
                continue;
            }
            List<String> attachedFolders = context.getApiKeyData().getAttachedFolders();
            for (String folder : attachedFolders) {
                if (resourceUrl.startsWith(folder)) {
                    result.put(resource, EnumSet.of(ResourceAccessType.READ));
                    break;
                }
            }
        }

        return result;
    }

    private static Map<ResourceDescription, Set<ResourceAccessType>> getOwnResourcesAccess(
            Set<ResourceDescription> resources, ProxyContext context) {
        String location = BlobStorageUtil.buildUserBucket(context);
        Map<ResourceDescription, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceDescription resource : resources) {
            if (resource.getBucketLocation().equals(location)) {
                result.put(resource, ResourceAccessType.ALL);
            }
        }

        return result;
    }

    private static Map<ResourceDescription, Set<ResourceAccessType>> getAppResourceAccess(
            Set<ResourceDescription> resources, ProxyContext context) {
        String deployment = context.getSourceDeployment();
        if (deployment == null) {
            return Map.of();
        }

        Map<ResourceDescription, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceDescription resource : resources) {
            String location = BlobStorageUtil.buildAppDataBucket(context);
            if (!resource.getBucketLocation().equals(location)) {
                continue;
            }

            String parentPath = resource.getParentPath();
            String filePath = (parentPath == null)
                    ? resource.getName()
                    : resource.getParentPath() + BlobStorageUtil.PATH_SEPARATOR + resource.getName();

            if (filePath.startsWith(BlobStorageUtil.APPDATA_PATTERN.formatted(UrlUtil.encodePath(deployment)))) {
                result.put(resource, ResourceAccessType.ALL);
            }
        }

        return result;
    }

    private Map<ResourceDescription, Set<ResourceAccessType>> getSharedAccess(
            Set<ResourceDescription> resources, ProxyContext context) {
        String actualUserLocation = BlobStorageUtil.buildInitiatorBucket(context);
        String actualUserBucket = encryptionService.encrypt(actualUserLocation);
        return shareService.getPermissions(actualUserBucket, actualUserLocation, resources);
    }

    private Map<ResourceDescription, Set<ResourceAccessType>> getReviewAccess(
            Set<ResourceDescription> resources, ProxyContext context) {
        Map<ResourceDescription, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceDescription resource : resources) {
            if (!PublicationService.isReviewResource(resource)) {
                continue;
            }

            if (hasAdminAccess(context)) {
                result.put(resource, ResourceAccessType.ALL);
            } else if (PublicationService.hasReviewAccess(context, resource)) {
                result.put(resource, EnumSet.of(ResourceAccessType.READ));
            }
        }

        return result;
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

    private interface PermissionRule extends BiFunction
            <Set<ResourceDescription>, ProxyContext, Map<ResourceDescription, Set<ResourceAccessType>>> {
    }
}
