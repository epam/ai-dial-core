package com.epam.aidial.core.server.security;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.data.MetadataBase;
import com.epam.aidial.core.server.data.ResourceAccessType;
import com.epam.aidial.core.server.data.ResourceFolderMetadata;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.PublicationService;
import com.epam.aidial.core.server.service.RuleService;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.server.storage.BlobStorageUtil;
import com.epam.aidial.core.server.storage.ResourceDescription;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.UrlUtil;
import com.google.common.collect.Sets;
import io.vertx.core.json.JsonObject;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;


public class AccessService {

    private final EncryptionService encryptionService;
    private final ShareService shareService;
    private final RuleService ruleService;
    private final List<Rule> adminRules;
    private final List<PermissionRule> permissionRules = List.of(
            AccessService::getOwnResourcesAccess,
            this::getAdminAccess,
            AccessService::getAutoSharedAccess,
            AccessService::getAppResourceAccess,
            this::getSharedAccess,
            this::getPublicAccess,
            this::getReviewAccess,
            this::getDeploymentAccess);

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
        Map<ResourceDescription, Set<ResourceAccessType>> permissions =
                lookupPermissions(Set.of(resource), context, Set.of(ResourceAccessType.READ));
        return permissions.get(resource).contains(ResourceAccessType.READ);
    }

    /**
     * Checks if USER has public access to the provided resources.
     * This method also checks admin privileges.
     *
     * @param resources - public resources
     * @param context - context
     * @return true - if all provided resources are public and user has permissions to all of them, otherwise - false
     */
    public boolean hasPublicAccess(Set<ResourceDescription> resources, ProxyContext context) {
        return resources.stream().allMatch(ResourceDescription::isPublic) && hasAdminAccess(context)
                || resources.equals(ruleService.getAllowedPublicResources(context, resources));
    }

    /**
     * The method returns permissions associated with provided resources.
     * Check sequence:
     * <ul>
     *   <li>Own resources</li>
     *   <li>Admin access</li>
     *   <li>Auto shared</li>
     *   <li>App resource</li>
     *   <li>Shared access</li>
     *   <li>Public access</li>
     *   <li>Review resource</li>
     * </ul>
     *
     * @param resources - resources to retrieve permissions for
     * @param context - proxy context
     * @return User permissions to all requested resources
     */
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

            // Merge permissions returned by the rule with previously collected permissions
            rulePermissions.forEach((resource, permissions) -> {
                Set<ResourceAccessType> mergedPermissions = result.merge(resource, permissions, Sets::union);
                if (toLookup.equals(mergedPermissions)) {
                    // Remove from further lookup if all requested permissions are collected
                    remainingResources.remove(resource);
                }
            });
        }
        for (ResourceDescription resource : resources) {
            result.computeIfAbsent(resource, r -> Set.of());
        }

        return result;
    }

    private Map<ResourceDescription, Set<ResourceAccessType>> getAdminAccess(
            Set<ResourceDescription> resources, ProxyContext context) {
        if (hasAdminAccess(context)) {
            boolean isNotApplication = context.getApiKeyData().getPerRequestKey() == null;
            Set<ResourceAccessType> permissions = isNotApplication
                    ? ResourceAccessType.ALL
                    : ResourceAccessType.READ_ONLY;
            return resources.stream()
                    .collect(Collectors.toUnmodifiableMap(Function.identity(), resource -> permissions));
        }

        return Map.of();
    }

    /**
     * Returns USER permissions to the provided public resources.
     *
     * @param resources - public resources
     * @param context - context
     * @return USER permissions
     */
    private Map<ResourceDescription, Set<ResourceAccessType>> getPublicAccess(
            Set<ResourceDescription> resources, ProxyContext context) {
        return ruleService.getAllowedPublicResources(context, resources).stream()
                .collect(Collectors.toUnmodifiableMap(
                        Function.identity(),
                        resource -> ResourceAccessType.READ_ONLY));
    }

    private static Map<ResourceDescription, Set<ResourceAccessType>> getAutoSharedAccess(
            Set<ResourceDescription> resources, ProxyContext context) {
        Map<ResourceDescription, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceDescription resource : resources) {
            String resourceUrl = resource.getUrl();
            AutoSharedData autoSharedData = context.getApiKeyData().getAttachedFiles().get(resourceUrl);
            if (autoSharedData != null) {
                result.put(resource, autoSharedData.accessTypes());
                continue;
            }
            Set<Map.Entry<String, AutoSharedData>> attachedFolders = context.getApiKeyData()
                    .getAttachedFolders().entrySet();
            for (var entry : attachedFolders) {
                String folder = entry.getKey();
                if (resourceUrl.startsWith(folder)) {
                    result.put(resource, entry.getValue().accessTypes());
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

    public static Map<ResourceDescription, Set<ResourceAccessType>> getAppResourceAccess(
            Set<ResourceDescription> resources, ProxyContext context) {
        String deployment = context.getSourceDeployment();
        if (deployment == null) {
            return Map.of();
        }
        return getAppResourceAccess(resources, context, deployment);
    }

    public static Map<ResourceDescription, Set<ResourceAccessType>> getAppResourceAccess(
            Set<ResourceDescription> resources, ProxyContext context, String deployment) {

        Map<ResourceDescription, Set<ResourceAccessType>> result = new HashMap<>();
        String location = BlobStorageUtil.buildAppDataBucket(context);
        for (ResourceDescription resource : resources) {
            if (!resource.getBucketLocation().equals(location)) {
                continue;
            }

            String parentPath = resource.getParentPath();
            String filePath = (parentPath == null)
                    ? resource.getName()
                    : parentPath + BlobStorageUtil.PATH_SEPARATOR + resource.getName();

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

        return resources.stream()
                .filter(resource -> PublicationService.hasReviewAccess(context, resource))
                .collect(Collectors.toUnmodifiableMap(
                        Function.identity(), resource -> ResourceAccessType.READ_ONLY));
    }

    private Map<ResourceDescription, Set<ResourceAccessType>> getDeploymentAccess(
            Set<ResourceDescription> resources, ProxyContext context) {

        return resources.stream()
                .filter(resource -> ApplicationService.hasDeploymentAccess(context, resource))
                .collect(Collectors.toUnmodifiableMap(
                        Function.identity(), resource -> ResourceAccessType.READ_ONLY));
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

    public void populatePermissions(ProxyContext context, Collection<MetadataBase> metadata) {
        Map<ResourceDescription, MetadataBase> allMetadata = new HashMap<>();
        for (MetadataBase meta : metadata) {
            expandMetadata(meta, allMetadata);
        }

        Map<ResourceDescription, Set<ResourceAccessType>> permissions = lookupPermissions(allMetadata.keySet(), context);
        allMetadata.forEach((resource, meta) -> meta.setPermissions(permissions.get(resource)));
    }

    private void expandMetadata(MetadataBase metadata, Map<ResourceDescription, MetadataBase> result) {
        ResourceDescription resource = ResourceDescription.fromAnyUrl(metadata.getUrl(), encryptionService);
        result.put(resource, metadata);
        if (metadata instanceof ResourceFolderMetadata folderMetadata && folderMetadata.getItems() != null) {
            for (MetadataBase item : folderMetadata.getItems()) {
                expandMetadata(item, result);
            }
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
