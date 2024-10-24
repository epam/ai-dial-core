package com.epam.aidial.core.server.security;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.data.MetadataBase;
import com.epam.aidial.core.server.data.ResourceAccessType;
import com.epam.aidial.core.server.data.ResourceFolderMetadata;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.resource.ResourceDescriptor;
import com.epam.aidial.core.server.resource.ResourceDescriptorFactory;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.PublicationService;
import com.epam.aidial.core.server.service.RuleService;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.server.util.BucketBuilder;
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

    public boolean hasReadAccess(ResourceDescriptor resource, ProxyContext context) {
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions =
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
    public boolean hasPublicAccess(Set<ResourceDescriptor> resources, ProxyContext context) {
        return resources.stream().allMatch(ResourceDescriptor::isPublic) && hasAdminAccess(context)
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
    public Map<ResourceDescriptor, Set<ResourceAccessType>> lookupPermissions(
            Set<ResourceDescriptor> resources, ProxyContext context) {
        return lookupPermissions(resources, context, ResourceAccessType.ALL);
    }

    private Map<ResourceDescriptor, Set<ResourceAccessType>> lookupPermissions(
            Set<ResourceDescriptor> resources, ProxyContext context, Set<ResourceAccessType> toLookup) {
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = new HashMap<>();
        Set<ResourceDescriptor> remainingResources = new HashSet<>(resources);
        for (PermissionRule permissionRule : permissionRules) {
            Map<ResourceDescriptor, Set<ResourceAccessType>> rulePermissions =
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
        for (ResourceDescriptor resource : resources) {
            result.computeIfAbsent(resource, r -> Set.of());
        }

        return result;
    }

    private Map<ResourceDescriptor, Set<ResourceAccessType>> getAdminAccess(
            Set<ResourceDescriptor> resources, ProxyContext context) {
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
    private Map<ResourceDescriptor, Set<ResourceAccessType>> getPublicAccess(
            Set<ResourceDescriptor> resources, ProxyContext context) {
        return ruleService.getAllowedPublicResources(context, resources).stream()
                .collect(Collectors.toUnmodifiableMap(
                        Function.identity(),
                        resource -> ResourceAccessType.READ_ONLY));
    }

    private static Map<ResourceDescriptor, Set<ResourceAccessType>> getAutoSharedAccess(
            Set<ResourceDescriptor> resources, ProxyContext context) {
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceDescriptor resource : resources) {
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

    private static Map<ResourceDescriptor, Set<ResourceAccessType>> getOwnResourcesAccess(
            Set<ResourceDescriptor> resources, ProxyContext context) {
        String location = BucketBuilder.buildUserBucket(context);
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceDescriptor resource : resources) {
            if (resource.getBucketLocation().equals(location)) {
                result.put(resource, ResourceAccessType.ALL);
            }
        }

        return result;
    }

    public static Map<ResourceDescriptor, Set<ResourceAccessType>> getAppResourceAccess(
            Set<ResourceDescriptor> resources, ProxyContext context) {
        String deployment = context.getSourceDeployment();
        if (deployment == null) {
            return Map.of();
        }
        return getAppResourceAccess(resources, context, deployment);
    }

    public static Map<ResourceDescriptor, Set<ResourceAccessType>> getAppResourceAccess(
            Set<ResourceDescriptor> resources, ProxyContext context, String deployment) {

        Map<ResourceDescriptor, Set<ResourceAccessType>> result = new HashMap<>();
        String location = BucketBuilder.buildAppDataBucket(context);
        for (ResourceDescriptor resource : resources) {
            if (!resource.getBucketLocation().equals(location)) {
                continue;
            }

            String parentPath = resource.getParentPath();
            String filePath = (parentPath == null)
                    ? resource.getName()
                    : parentPath + ResourceDescriptor.PATH_SEPARATOR + resource.getName();

            if (filePath.startsWith(BucketBuilder.APPDATA_PATTERN.formatted(UrlUtil.encodePath(deployment)))) {
                result.put(resource, ResourceAccessType.ALL);
            }
        }

        return result;
    }

    private Map<ResourceDescriptor, Set<ResourceAccessType>> getSharedAccess(
            Set<ResourceDescriptor> resources, ProxyContext context) {
        String actualUserLocation = BucketBuilder.buildInitiatorBucket(context);
        String actualUserBucket = encryptionService.encrypt(actualUserLocation);
        return shareService.getPermissions(actualUserBucket, actualUserLocation, resources);
    }

    private Map<ResourceDescriptor, Set<ResourceAccessType>> getReviewAccess(
            Set<ResourceDescriptor> resources, ProxyContext context) {

        return resources.stream()
                .filter(resource -> PublicationService.hasReviewAccess(context, resource))
                .collect(Collectors.toUnmodifiableMap(
                        Function.identity(), resource -> ResourceAccessType.READ_ONLY));
    }

    private Map<ResourceDescriptor, Set<ResourceAccessType>> getDeploymentAccess(
            Set<ResourceDescriptor> resources, ProxyContext context) {

        return resources.stream()
                .filter(resource -> ApplicationService.hasDeploymentAccess(context, resource))
                .collect(Collectors.toUnmodifiableMap(
                        Function.identity(), resource -> ResourceAccessType.READ_ONLY));
    }

    public boolean hasAdminAccess(ProxyContext context) {
        return RuleMatcher.match(context, adminRules);
    }

    public void filterForbidden(ProxyContext context, ResourceDescriptor descriptor, MetadataBase metadata) {
        if (descriptor.isPublic() && descriptor.isFolder() && !hasAdminAccess(context)) {
            ResourceFolderMetadata folder = (ResourceFolderMetadata) metadata;
            ruleService.filterForbidden(context, descriptor, folder);
        }
    }

    public void populatePermissions(ProxyContext context, Collection<MetadataBase> metadata) {
        Map<ResourceDescriptor, MetadataBase> allMetadata = new HashMap<>();
        for (MetadataBase meta : metadata) {
            expandMetadata(meta, allMetadata);
        }

        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = lookupPermissions(allMetadata.keySet(), context);
        allMetadata.forEach((resource, meta) -> meta.setPermissions(permissions.get(resource)));
    }

    private void expandMetadata(MetadataBase metadata, Map<ResourceDescriptor, MetadataBase> result) {
        ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(metadata.getUrl(), encryptionService);
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
            <Set<ResourceDescriptor>, ProxyContext, Map<ResourceDescriptor, Set<ResourceAccessType>>> {
    }
}
