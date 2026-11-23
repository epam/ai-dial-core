package com.epam.aidial.core.server.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The caller's roles at each unit for one decision, and the units the caller sees: those where they hold a role, those
 * directly below one where they hold {@code read} on {@code org-units}, and every unit above one where they hold a role.
 * Each unit's roles are looked up at most once, and the units above only when a decision needs them, as that reads
 * every unit's assignments. Not shared between requests.
 */
final class CallerUnitRoles {

    private final OrgUnitRoles roleDefinitions;
    private final Function<String, Set<String>> loadRolesAt;
    private final Supplier<Set<String>> loadUnitsAboveHeldRoles;
    private final Map<String, Set<String>> rolesByUnit = new HashMap<>();
    private Set<String> unitsAboveHeldRoles;

    CallerUnitRoles(OrgUnitRoles roleDefinitions, Function<String, Set<String>> loadRolesAt, Supplier<Set<String>> loadUnitsAboveHeldRoles) {
        this.roleDefinitions = roleDefinitions;
        this.loadRolesAt = loadRolesAt;
        this.loadUnitsAboveHeldRoles = loadUnitsAboveHeldRoles;
    }

    OrgUnitRoles roleDefinitions() {
        return roleDefinitions;
    }

    /**
     * The role names assigned to the caller at the unit, known to core or not.
     */
    Set<String> rolesAt(String unit) {
        return rolesByUnit.computeIfAbsent(unit, loadRolesAt);
    }

    boolean holdsRoleAt(String unit) {
        return rolesAt(unit).stream().anyMatch(roleDefinitions::exists);
    }

    boolean seesChildUnitsOf(String unit) {
        return roleDefinitions.allows(rolesAt(unit), OrgUnitRoles.READ, OrgUnitRoles.ORG_UNITS);
    }

    /**
     * Whether the caller lists the unit's folder, at least for the child units in it.
     */
    boolean listsFolderOf(String unit) {
        return seesChildUnitsOf(unit) || holdsRoleBelow(unit);
    }

    /**
     * Always false for applications.
     */
    boolean holdsRoleBelow(String unit) {
        if (unitsAboveHeldRoles == null) {
            unitsAboveHeldRoles = loadUnitsAboveHeldRoles.get();
        }
        return unitsAboveHeldRoles.contains(unit);
    }

    boolean sees(String unit) {
        return holdsRoleAt(unit) || seesChildUnitsOf(OrgUnitService.parentKey(unit)) || holdsRoleBelow(unit);
    }

    /**
     * Every unit above one where a role core knows is held, from the role names held at each unit.
     */
    static Set<String> unitsAboveHeldRoles(OrgUnitRoles roleDefinitions, Map<String, Set<String>> heldRolesByUnit) {
        Set<String> above = new HashSet<>();
        heldRolesByUnit.forEach((unit, heldRoles) -> {
            if (heldRoles.stream().noneMatch(roleDefinitions::exists)) {
                return;
            }
            for (String parent = OrgUnitService.parentKey(unit); !parent.equals(OrgUnitService.SHARED_SPACE_KEY);
                    parent = OrgUnitService.parentKey(parent)) {
                if (!above.add(parent)) {
                    break; // the units further up are in already
                }
            }
        });
        return above;
    }
}
