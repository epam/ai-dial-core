package com.epam.aidial.core.server.security;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import com.epam.aidial.core.server.orgunit.OrgUnitApprovals;
import com.epam.aidial.core.server.orgunit.OrgUnitGrants;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;


@Slf4j
public class AccessService {

    private final EncryptionService encryptionService;
    private final ShareService shareService;
    private final RuleService ruleService;
    private final OrgUnitApprovals orgUnitApprovals;
    private final OrgUnitGrants orgUnitGrants;
    private final List<Rule> adminRules;
    private final List<Rule> globalReaderRules;
    private final boolean adminRulesConfigured;
    private final boolean globalReaderRulesConfigured;

    private final List<String> createCodeAppRoles;

    private final ApplicationSchemaService applicationSchemaService;

    private static final Set<ResourceType> DEPLOYMENT_TYPES = Set.of(ResourceTypes.APPLICATION, ResourceTypes.TOOL_SET);

    private final List<PermissionRule> permissionRulesBesidesUnitRoles = List.of(
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

    // last, so a check the rules or anything else already satisfies never reads the unit tree
    private final List<PermissionRule> permissionRules = Stream.concat(permissionRulesBesidesUnitRoles.stream(),
            Stream.<PermissionRule>of(this::getUnitRoleAccess)).toList();

    public AccessService(EncryptionService encryptionService,
                         ShareService shareService,
                         RuleService ruleService,
                         OrgUnitApprovals orgUnitApprovals,
                         OrgUnitGrants orgUnitGrants,
                         ApplicationSchemaService applicationSchemaService,
                         JsonObject settings) {
        this.encryptionService = encryptionService;
        this.shareService = shareService;
        this.ruleService = ruleService;
        this.orgUnitApprovals = orgUnitApprovals;
        this.orgUnitGrants = orgUnitGrants;
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
     * Read access from anything but roles in an org unit: what sharing a whole folder with an application needs, as
     * that shares everything below it, child units included.
     */
    public boolean hasReadAccessBesidesUnitRoles(ResourceDescriptor resource, ProxyContext context) {
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = lookupPermissions(Set.of(resource), context,
                Set.of(ResourceAccessType.READ), permissionRulesBesidesUnitRoles);
        return permissions.get(resource).contains(ResourceAccessType.READ);
    }

    /**
     * Read on a folder and everything below it, as attaching a folder hands it all to an application. A folder the
     * caller already received attached was checked when it was attached; otherwise a public folder needs its part under
     * no unit read through the rules and its part in units read through roles at each of them.
     */
    public boolean hasReadAccessToEverythingUnder(ResourceDescriptor folder, ProxyContext context) {
        if (!orgUnitGrants.isEnabled() || !folder.isPublic() || hasAdminAccess(context) || hasGlobalReaderAccess(context)) {
            return hasReadAccessBesidesUnitRoles(folder, context);
        }

        Set<ResourceDescriptor> resources = Set.of(folder);
        if (readable(getAutoSharedAccess(resources, context), folder) || readable(getPerRequestPermissions(resources, context), folder)) {
            return true;
        }
        boolean folderReadable = orgUnitGrants.outsideUnits(resources).isEmpty() || !getPublicAccess(resources, context).isEmpty();
        return folderReadable && orgUnitGrants.readsEveryUnitUnder(context, folder);
    }

    private static boolean readable(Map<ResourceDescriptor, Set<ResourceAccessType>> access, ResourceDescriptor resource) {
        return access.getOrDefault(resource, Set.of()).contains(ResourceAccessType.READ);
    }

    /**
     * Write access from anything but roles in an org unit: what operating a code app needs.
     */
    public boolean hasWriteAccessBesidesUnitRoles(ResourceDescriptor resource, ProxyContext context) {
        Map<ResourceDescriptor, Set<ResourceAccessType>> permissions = lookupPermissions(Set.of(resource), context,
                Set.of(ResourceAccessType.WRITE), permissionRulesBesidesUnitRoles);
        return permissions.get(resource).contains(ResourceAccessType.WRITE);
    }

    /**
     * Checks if USER has public access to the provided resources: through the read rules outside every org unit, and
     * through roles inside one. This method also checks admin privileges.
     *
     * @param resources - public resources
     * @param context - context
     * @return true - if all provided resources are public and user has permissions to all of them, otherwise - false
     */
    public boolean hasPublicAccess(Set<ResourceDescriptor> resources, ProxyContext context) {
        if (resources.stream().allMatch(ResourceDescriptor::isPublic) && hasAdminAccess(context)) {
            return true;
        }
        Set<ResourceDescriptor> allowed = new HashSet<>(getPublicAccess(resources, context).keySet());
        getUnitRoleAccess(resources, context).forEach((resource, access) -> {
            if (access.contains(ResourceAccessType.READ)) {
                allowed.add(resource);
            }
        });
        return allowed.containsAll(resources);
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
        return lookupPermissions(resources, context, toLookup, permissionRules);
    }

    private Map<ResourceDescriptor, Set<ResourceAccessType>> lookupPermissions(Set<ResourceDescriptor> resources,
            ProxyContext context, Set<ResourceAccessType> toLookup, List<PermissionRule> rules) {
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = new HashMap<>();
        Set<ResourceDescriptor> remainingResources = new HashSet<>(resources);
        for (PermissionRule permissionRule : rules) {
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
     * What the caller's roles grant on the shared-space resources units own; admins already hold everything.
     */
    private Map<ResourceDescriptor, Set<ResourceAccessType>> getUnitRoleAccess(
            Set<ResourceDescriptor> resources, ProxyContext context) {
        if (!orgUnitGrants.isEnabled() || hasAdminAccess(context)) {
            return Map.of();
        }
        return orgUnitGrants.access(context, resources);
    }

    /**
     * Read through the rules, which decide only what lies under no org unit.
     */
    private Map<ResourceDescriptor, Set<ResourceAccessType>> getPublicAccess(
            Set<ResourceDescriptor> resources, ProxyContext context) {
        return ruleService.getAllowedPublicResources(context, orgUnitGrants.outsideUnits(resources)).stream()
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
            autoSharedData = apiKeyData.getAttachedSkills().get(resourceUrl);
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
        if (ownResources.stream().anyMatch(resource -> resource.getType() == ResourceTypes.PROMPT)) {
            declaredResources.addAll(applicationSchemaService.getPrompts(application));
        }
        if (ownResources.stream().anyMatch(resource -> resource.getType() == ResourceTypes.SKILL)) {
            declaredResources.addAll(applicationSchemaService.getSkills(application));
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

        // admins already hold every review copy, so asking the unit approvers would only cost storage reads
        boolean admin = hasAdminAccess(context);
        return resources.stream()
                .filter(resource -> PublicationService.hasReviewAccess(context, resource)
                        || (!admin && orgUnitApprovals.mayReadReviewCopy(context, resource)))
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

    /**
     * Leaves out of a shared-space listing what the caller may not see: the read rules decide items under no org unit,
     * roles decide items a unit owns. The rules take the listed folder as allowed when they let the caller read it;
     * otherwise every item is checked against the rules of its whole path. A unit's folder read through anything but
     * roles, such as an attachment, lists in full, as that access covers everything below it.
     */
    public void filterForbidden(ProxyContext context, ResourceDescriptor descriptor, MetadataBase metadata) {
        if (descriptor.isPublic() && descriptor.isFolder() && metadata instanceof ResourceFolderMetadata folder
                && !hasAdminAccess(context) && !hasGlobalReaderAccess(context)) {
            if (!orgUnitGrants.isEnabled() || folder.getItems() == null) {
                ruleService.filterForbidden(context, descriptor, folder);
                return;
            }

            List<? extends MetadataBase> items = folder.getItems();
            Map<MetadataBase, ResourceDescriptor> resources = new IdentityHashMap<>();
            for (MetadataBase item : items) {
                resources.put(item, ResourceDescriptorFactory.fromPublicUrl(item.getUrl()));
            }
            if (orgUnitGrants.outsideUnits(Set.of(descriptor)).isEmpty() && hasReadAccessBesidesUnitRoles(descriptor, context)) {
                return;
            }
            Set<ResourceDescriptor> all = Set.copyOf(resources.values());
            Set<ResourceDescriptor> outside = orgUnitGrants.outsideUnits(all);
            Set<ResourceDescriptor> allowed = new HashSet<>();
            if (!outside.isEmpty()) {
                if (outside.size() == all.size() && hasReadAccessBesidesUnitRoles(descriptor, context)) {
                    ruleService.filterForbidden(context, descriptor, folder);
                    folder.getItems().forEach(item -> allowed.add(resources.get(item)));
                } else {
                    allowed.addAll(ruleService.getAllowedPublicResources(context, outside));
                }
            }
            Set<ResourceDescriptor> inside = new HashSet<>(all);
            inside.removeAll(outside);
            allowed.addAll(orgUnitGrants.visibleInListing(context, inside));
            folder.setItems(items.stream().filter(item -> allowed.contains(resources.get(item))).toList());
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
