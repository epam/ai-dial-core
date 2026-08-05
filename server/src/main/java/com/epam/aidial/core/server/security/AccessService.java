package com.epam.aidial.core.server.security;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.PublicationService;
import com.epam.aidial.core.server.service.RuleService;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.resource.ResourceUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;


@Slf4j
public class AccessService {

    private final EncryptionService encryptionService;
    private final ShareService shareService;
    private final RuleService ruleService;
    private final List<Rule> adminRules;
    private final List<Rule> globalReaderRules;
    private final boolean adminRulesConfigured;
    private final boolean globalReaderRulesConfigured;

    private final List<String> createCodeAppRoles;

    private final ApplicationSchemaService applicationSchemaService;

    private static final Set<ResourceType> DEPLOYMENT_TYPES = Set.of(ResourceTypes.APPLICATION, ResourceTypes.TOOL_SET);

    private final List<PermissionRule> permissionRules = List.of(
            AccessService::getOwnResourcesAccess,
            this::getAdminAccess,
            this::getGlobalReaderAccess,
            AccessService::getAutoSharedAccess,
            AccessService::getPerRequestPermissions,
            AccessService::getAppResourceAccess,
            this::getReviewAccess,
            this::getPublicAccess,
            this::getSharedAccess,
            AccessService::getAppSelfAccess,
            this::getOwnResourcesAccessForChainedSchemaRichApplication);

    public AccessService(EncryptionService encryptionService,
                         ShareService shareService,
                         RuleService ruleService,
                         ApplicationSchemaService applicationSchemaService,
                         JsonObject settings) {
        this.encryptionService = encryptionService;
        this.shareService = shareService;
        this.ruleService = ruleService;
        this.applicationSchemaService = applicationSchemaService;
        this.adminRules = adminRules(settings);
        this.globalReaderRules = globalReaderRules(settings);
        this.adminRulesConfigured = !this.adminRules.isEmpty();
        this.globalReaderRulesConfigured = !this.globalReaderRules.isEmpty();
        this.createCodeAppRoles = getCreateCodeAppRoles(settings);
    }

    private List<String> getCreateCodeAppRoles(JsonObject settings) {
        JsonArray roles = settings.getJsonArray("createCodeAppRoles");
        if (roles == null) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < roles.size(); i++) {
            result.add(roles.getString(i));
        }
        return result;
    }

    public boolean hasReadAccess(ResourceDescriptor resource, ProxyContext context) {
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions =
                lookupPermissions(Set.of(resource), context, Set.of(ResourceAccessType.READ));
        return permissions.get(resource).contains(ResourceAccessType.READ);
    }

    public boolean hasWriteAccess(ResourceDescriptor resource, ProxyContext context) {
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions =
                lookupPermissions(Set.of(resource), context, Set.of(ResourceAccessType.WRITE));
        return permissions.get(resource).contains(ResourceAccessType.WRITE);
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

    public boolean canCreateCodeApps(ProxyContext context) {
        if (context.getApiKeyData().getPerRequestKey() != null || createCodeAppRoles == null) {
            return true;
        }
        List<String> actualUserRoles = context.getUserRoles();
        return !createCodeAppRoles.isEmpty()
                && actualUserRoles.stream().anyMatch(createCodeAppRoles::contains);
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

            if (remainingResources.isEmpty()) {
                break;
            }
        }
        for (ResourceDescriptor resource : resources) {
            result.computeIfAbsent(resource, r -> Set.of());
        }

        return result;
    }

    @VisibleForTesting
    Map<ResourceDescriptor, Set<ResourceAccessType>> getAdminAccess(
            Set<ResourceDescriptor> resources, ProxyContext context) {
        if (hasAdminAccess(context)) {
            return resources.stream()
                    .filter(resource -> resource.isPublic()
                            || PublicationService.isReviewBucket(resource)
                            || ApplicationService.isPublicApplicationSourceDirectory(resource))
                    .collect(Collectors.toUnmodifiableMap(Function.identity(), resource -> ResourceAccessType.ALL));
        }

        return Map.of();
    }

    @VisibleForTesting
    Map<ResourceDescriptor, Set<ResourceAccessType>> getGlobalReaderAccess(
            Set<ResourceDescriptor> resources, ProxyContext context) {
        if (hasGlobalReaderAccess(context)) {
            return resources.stream()
                    .collect(Collectors.toUnmodifiableMap(Function.identity(), resource -> ResourceAccessType.READ_ONLY));
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
        ApiKeyData apiKeyData = context.getApiKeyData();
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceDescriptor resource : resources) {
            String resourceUrl = resource.getUrl();
            AutoSharedData autoSharedData = apiKeyData.getAttachedFiles().get(resourceUrl);
            if (autoSharedData != null) {
                result.put(resource, autoSharedData.accessTypes());
                continue;
            }
            autoSharedData = apiKeyData.getAttachedPrompts().get(resourceUrl);
            if (autoSharedData != null) {
                result.put(resource, autoSharedData.accessTypes());
                continue;
            }
            autoSharedData = apiKeyData.getAttachedDeployments().get(resourceUrl);
            if (autoSharedData != null) {
                result.put(resource, autoSharedData.accessTypes());
                continue;
            }
            autoSharedData = apiKeyData.getAttachedResourceCredentials().get(resourceUrl);
            if (autoSharedData != null) {
                result.put(resource, autoSharedData.accessTypes());
                continue;
            }
            Set<Map.Entry<String, AutoSharedData>> attachedFolders = apiKeyData
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

    private static Map<ResourceDescriptor, Set<ResourceAccessType>> getPerRequestPermissions(Set<ResourceDescriptor> resources,
                                                                                             ProxyContext context) {
        Map<String, PerRequestSharedData> perRequestSharedResources = context.getApiKeyData().getPerRequestSharedResources();
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceDescriptor resource : resources) {
            String resourceUrl = resource.getUrl();
            PerRequestSharedData data = perRequestSharedResources.get(resourceUrl);
            if (data != null) {
                result.put(resource, data.permissions());
                continue;
            }
            Set<ResourceAccessType> folderPermissions = findFolderPermissions(perRequestSharedResources, resourceUrl);
            if (folderPermissions != null) {
                result.put(resource, folderPermissions);
            }
        }
        return result;
    }

    @Nullable
    private static Set<ResourceAccessType> findFolderPermissions(Map<String, PerRequestSharedData> perRequestSharedResources,
                                                                 String resourceUrl) {
        for (Map.Entry<String, PerRequestSharedData> entry : perRequestSharedResources.entrySet()) {
            String permittedUrl = entry.getKey();
            if (ResourceUtil.isFolder(permittedUrl) && resourceUrl.startsWith(permittedUrl)) {
                return entry.getValue().permissions();
            }
        }
        return null;
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

    public Map<ResourceDescriptor, Set<ResourceAccessType>> getOwnResourcesAccessForChainedSchemaRichApplication(
            Set<ResourceDescriptor> resources, ProxyContext context) {
        if (!(context.getDeployment() instanceof Application application) || !application.hasApplicationTypeSchemaId()) {
            return Map.of();
        }
        String location = BucketBuilder.buildInitiatorBucket(context);
        Set<ResourceDescriptor> ownResources = resources.stream()
                .filter(resource -> resource.getBucketLocation().equals(location))
                .collect(Collectors.toSet());
        if (ownResources.isEmpty()) {
            return Map.of();
        }

        // Collecting declared resources re-validates the application against its schema, so ask only for the kinds actually requested.
        Set<ResourceDescriptor> declaredResources = new HashSet<>();
        if (ownResources.stream().anyMatch(resource -> resource.getType() == ResourceTypes.FILE)) {
            declaredResources.addAll(applicationSchemaService.getFiles(application));
        }
        if (ownResources.stream().anyMatch(resource -> DEPLOYMENT_TYPES.contains(resource.getType()))) {
            declaredResources.addAll(applicationSchemaService.getDeployments(application));
        }

        Map<ResourceDescriptor, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceDescriptor resource : ownResources) {
            if (declaredResources.contains(resource)) {
                result.put(resource, ResourceAccessType.READ_ONLY);
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

        String appPath = BucketBuilder.APPDATA_PATTERN.formatted(deployment);
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = new HashMap<>();
        String location = BucketBuilder.buildAppDataBucket(context);
        for (ResourceDescriptor resource : resources) {
            if (!resource.getBucketLocation().equals(location)) {
                continue;
            }

            String parentPath = resource.getParentPath();
            String filePath = parentPath == null
                    ? resource.getName()
                    : parentPath + ResourceDescriptor.PATH_SEPARATOR + resource.getName();

            if (filePath != null && filePath.startsWith(appPath)) {
                result.put(resource, ResourceAccessType.ALL);
            }
        }

        return result;
    }

    public static Map<ResourceDescriptor, Set<ResourceAccessType>> getAppSelfAccess(
            Set<ResourceDescriptor> resources, ProxyContext context) {
        if (context.getDecodedSourceDeployment() == null) {
            return Map.of();
        }
        return getAppSelfAccess(resources, context.getDecodedSourceDeployment());
    }

    /**
     * Application makes a call to read own configuration on behalf of user.
     */
    public static Map<ResourceDescriptor, Set<ResourceAccessType>> getAppSelfAccess(Set<ResourceDescriptor> resources, String sourceApp) {
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceDescriptor resource : resources) {
            if (resource.getType() == ResourceTypes.APPLICATION
                    && !resource.isFolder()
                    &&  resource.getDecodedUrl().equals(sourceApp)) {
                result.put(resource, ResourceAccessType.READ_ONLY);
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

    public boolean hasAdminAccess(ProxyContext context) {
        return context.getApiKeyData().getPerRequestKey() == null // not application
                && RuleMatcher.match(context, adminRules);
    }

    public boolean hasGlobalReaderAccess(ProxyContext context) {
        return globalReaderRulesConfigured
                && context.getApiKeyData().getPerRequestKey() == null // not application
                && RuleMatcher.match(context, globalReaderRules);
    }

    /**
     * Fail-closed admin check for the Configuration/Admin API surface. Unlike {@link #hasAdminAccess}
     * — whose empty rule set is treated by {@link RuleMatcher} as allow-all — this returns
     * {@code false} when {@code access.admin.rules} is unconfigured or empty, so an operator who
     * never configured admin rules denies all config/admin access rather than granting it to everyone.
     */
    public boolean hasExplicitAdminAccess(ProxyContext context) {
        return adminRulesConfigured && hasAdminAccess(context);
    }

    /**
     * Returns {@code true} when {@code bucket} equals the caller's encrypted initiator bucket.
     * Returns {@code false} (does not throw) when no initiator can be resolved — that case is
     * treated as "not the owner" so the surrounding authz dispatch produces 403 rather than 500.
     */
    public boolean isOwnerOf(ProxyContext context, String bucket) {
        try {
            return encryptionService.encrypt(BucketBuilder.buildInitiatorBucket(context)).equals(bucket);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public void filterForbidden(ProxyContext context, ResourceDescriptor descriptor, MetadataBase metadata) {
        if (descriptor.isPublic() && descriptor.isFolder()
                && !hasAdminAccess(context) && !hasGlobalReaderAccess(context)) {
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
        try {
            ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(metadata.getUrl(), encryptionService);
            result.put(resource, metadata);
            if (metadata instanceof ResourceFolderMetadata folderMetadata && folderMetadata.getItems() != null) {
                for (MetadataBase item : folderMetadata.getItems()) {
                    expandMetadata(item, result);
                }
            }
        } catch (Exception error) {
            log.warn("Unable to expand resource metadata {} due to the error: ", metadata.getUrl(), error);
        }
    }

    private static List<Rule> adminRules(JsonObject settings) {
        JsonObject admin = settings.getJsonObject("admin");
        if (admin == null) {
            return List.of();
        }
        JsonArray rules = admin.getJsonArray("rules");
        if (rules == null) {
            return List.of();
        }
        List<Rule> list = ProxyUtil.convertToObject(rules.toString(), Rule.LIST_TYPE);
        return (list == null) ? List.of() : list;
    }

    private static List<Rule> globalReaderRules(JsonObject settings) {
        JsonObject globalReader = settings.getJsonObject("globalReader");
        if (globalReader == null) {
            return List.of();
        }
        JsonArray rules = globalReader.getJsonArray("rules");
        if (rules == null) {
            return List.of();
        }
        List<Rule> list = ProxyUtil.convertToObject(rules.toString(), Rule.LIST_TYPE);
        return (list == null) ? List.of() : list;
    }

    private interface PermissionRule extends BiFunction
            <Set<ResourceDescriptor>, ProxyContext, Map<ResourceDescriptor, Set<ResourceAccessType>>> {
    }
}
