package com.epam.aidial.core.server.orgunit;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the caller's roles at a unit give on the shared-space content that unit owns: {@code read} on a type's
 * namespace reads it, {@code write} changes, creates and deletes it, and {@code read} on {@code org-units} lists the
 * unit's folder with only its child units in it. A resource belongs to the nearest unit above it, so a child unit's
 * content needs roles at the child unit. Inside a unit roles are all there is: the read rules decide only what lies
 * under no unit. Callers other than a person read through their roles and get nothing else.
 */
@RequiredArgsConstructor
public class OrgUnitGrants {

    private final OrgUnitService orgUnits;

    public boolean isEnabled() {
        return orgUnits.isEnabled();
    }

    /**
     * The resources no unit owns, which the read rules decide; all of them while org units are off.
     */
    public Set<ResourceDescriptor> outsideUnits(Set<ResourceDescriptor> resources) {
        if (!isEnabled() || resources.stream().noneMatch(ResourceDescriptor::isPublic)) {
            return resources;
        }
        Map<String, OrgUnit> units = orgUnits.getUnitList().units();
        Set<ResourceDescriptor> outside = new HashSet<>();
        for (ResourceDescriptor resource : resources) {
            if (OrgUnitService.ownerOf(units, resource).equals(OrgUnitService.SHARED_SPACE_KEY)) {
                outside.add(resource);
            }
        }
        return outside;
    }

    /**
     * The access per resource. Reads the unit tree at most once and each unit's policy at most once, and nothing at
     * all when no resource is shared-space content.
     */
    public Map<ResourceDescriptor, Set<ResourceAccessType>> access(ProxyContext context, Set<ResourceDescriptor> resources) {
        List<ResourceDescriptor> candidates = candidates(context, resources);
        if (candidates.isEmpty()) {
            return Map.of();
        }

        Map<String, OrgUnit> units = orgUnits.getUnitList().units();
        OrgUnitRoles roles = OrgUnitRoles.of(context.getConfig());
        Map<String, Set<String>> heldByUnit = new HashMap<>();
        boolean readOnly = !OrgUnitSubjects.isPerson(context);
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = new HashMap<>();
        for (ResourceDescriptor resource : candidates) {
            String key = OrgUnitService.ownerOf(units, resource);
            if (key.equals(OrgUnitService.SHARED_SPACE_KEY)) {
                continue;
            }
            Set<String> held = held(context, units, heldByUnit, key);
            Set<ResourceAccessType> access = new HashSet<>(granted(roles, held, resource));
            if (isUnitFolder(key, resource) && roles.allows(held, OrgUnitRoles.READ, OrgUnitRoles.ORG_UNITS)) {
                access.add(ResourceAccessType.READ);
            }
            if (readOnly) {
                access.retainAll(ResourceAccessType.READ_ONLY);
            }
            if (!access.isEmpty()) {
                result.put(resource, Set.copyOf(access));
            }
        }
        return result;
    }

    /**
     * Whether roles read the folder's content in every unit it spans: the unit owning it and each unit below it. An
     * attached folder hands an application everything below it, and a unit's roles don't reach the units below. True
     * when no unit owns anything there, which leaves the folder to the rules.
     */
    public boolean readsEveryUnitUnder(ProxyContext context, ResourceDescriptor folder) {
        if (!isEnabled() || !folder.isPublic()) {
            return true;
        }

        Map<String, OrgUnit> units = orgUnits.getUnitList().units();
        String owner = OrgUnitService.ownerOf(units, folder);
        String folderKey = OrgUnitService.keyOf(folder);
        List<String> spanned = units.keySet().stream()
                .filter(key -> !key.equals(OrgUnitService.SHARED_SPACE_KEY) && (key.equals(owner) || key.startsWith(folderKey)))
                .toList();
        if (spanned.isEmpty()) {
            return true;
        }

        OrgUnitRoles roles = OrgUnitRoles.of(context.getConfig());
        Map<String, Set<String>> heldByUnit = new HashMap<>();
        return spanned.stream().allMatch(key -> granted(roles, held(context, units, heldByUnit, key), folder).contains(ResourceAccessType.READ));
    }

    /**
     * Of a listed folder's items that units own, those the listing keeps. A child unit's folder stays when the caller
     * holds a role there, or {@code read} on {@code org-units} at the unit above it; anything else stays when a role
     * reads it.
     */
    public Set<ResourceDescriptor> visibleInListing(ProxyContext context, Set<ResourceDescriptor> items) {
        List<ResourceDescriptor> candidates = candidates(context, items);
        if (candidates.isEmpty()) {
            return Set.of();
        }

        Map<String, OrgUnit> units = orgUnits.getUnitList().units();
        OrgUnitRoles roles = OrgUnitRoles.of(context.getConfig());
        Map<String, Set<String>> heldByUnit = new HashMap<>();
        Set<ResourceDescriptor> visible = new HashSet<>();
        for (ResourceDescriptor resource : candidates) {
            String key = OrgUnitService.ownerOf(units, resource);
            if (key.equals(OrgUnitService.SHARED_SPACE_KEY)) {
                continue;
            }
            boolean shown;
            if (isUnitFolder(key, resource)) {
                shown = showsUnit(roles, held(context, units, heldByUnit, key),
                        held(context, units, heldByUnit, OrgUnitService.parentKey(key)));
            } else {
                shown = granted(roles, held(context, units, heldByUnit, key), resource).contains(ResourceAccessType.READ);
            }
            if (shown) {
                visible.add(resource);
            }
        }
        return visible;
    }

    /**
     * Whether the caller sees a unit, in the unit list and in its parent's listing: they hold a role there, or
     * {@code read} on {@code org-units} at the unit above it.
     */
    public static boolean showsUnit(OrgUnitRoles roles, Set<String> heldThere, Set<String> heldAbove) {
        return heldThere.stream().anyMatch(roles::exists) || roles.allows(heldAbove, OrgUnitRoles.READ, OrgUnitRoles.ORG_UNITS);
    }

    private Set<String> held(ProxyContext context, Map<String, OrgUnit> units, Map<String, Set<String>> heldByUnit, String key) {
        return heldByUnit.computeIfAbsent(key,
                unit -> OrgUnitSubjects.rolesToReadWith(context, orgUnits.roleAssignmentsOf(unit, units.get(unit))));
    }

    private static boolean isUnitFolder(String key, ResourceDescriptor resource) {
        return resource.isFolder() && OrgUnitService.keyOf(resource).equals(key);
    }

    private List<ResourceDescriptor> candidates(ProxyContext context, Set<ResourceDescriptor> resources) {
        if (!isEnabled()) {
            return List.of();
        }
        return resources.stream()
                .filter(resource -> resource.isPublic() && OrgUnitService.CONTENT_TYPES.contains(resource.getType()))
                .toList();
    }

    /**
     * WRITE comes with SHARE, so that a move and organisation credentials, which ask for every permission, work.
     * Sharing itself is decided by bucket ownership, so SHARE lets no one share shared-space resources.
     */
    private static Set<ResourceAccessType> granted(OrgUnitRoles roles, Set<String> held, ResourceDescriptor resource) {
        String namespace = resource.getType().group();
        Set<ResourceAccessType> access = new HashSet<>();
        if (roles.allows(held, OrgUnitRoles.READ, namespace)) {
            access.add(ResourceAccessType.READ);
        }
        if (roles.allows(held, OrgUnitRoles.WRITE, namespace)) {
            access.add(ResourceAccessType.WRITE);
            access.add(ResourceAccessType.SHARE);
        }
        return Set.copyOf(access);
    }
}
