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

    private final OrgUnitRoles roles;
    private final Function<String, Set<String>> lookup;
    private final Supplier<Set<String>> lookupUnitsAbove;
    private final Map<String, Set<String>> byUnit = new HashMap<>();
    private Set<String> unitsAbove;

    CallerUnitRoles(OrgUnitRoles roles, Function<String, Set<String>> lookup, Supplier<Set<String>> lookupUnitsAbove) {
        this.roles = roles;
        this.lookup = lookup;
        this.lookupUnitsAbove = lookupUnitsAbove;
    }

    OrgUnitRoles roles() {
        return roles;
    }

    /**
     * The role names assigned to the caller at the unit, known to core or not.
     */
    Set<String> at(String unit) {
        return byUnit.computeIfAbsent(unit, lookup);
    }

    boolean holdsRoleAt(String unit) {
        return at(unit).stream().anyMatch(roles::exists);
    }

    boolean seesChildrenOf(String unit) {
        return roles.allows(at(unit), OrgUnitRoles.READ, OrgUnitRoles.ORG_UNITS);
    }

    boolean isAboveHeldRole(String unit) {
        if (unitsAbove == null) {
            unitsAbove = lookupUnitsAbove.get();
        }
        return unitsAbove.contains(unit);
    }

    boolean shows(String unit) {
        return holdsRoleAt(unit) || seesChildrenOf(OrgUnitService.parentKey(unit)) || isAboveHeldRole(unit);
    }

    /**
     * Every unit above one where a role core knows is held, from the role names held at each unit.
     */
    static Set<String> unitsAbove(OrgUnitRoles roles, Map<String, Set<String>> heldByUnit) {
        Set<String> above = new HashSet<>();
        heldByUnit.forEach((unit, held) -> {
            if (held.stream().noneMatch(roles::exists)) {
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
