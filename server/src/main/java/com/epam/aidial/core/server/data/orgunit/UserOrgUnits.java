package com.epam.aidial.core.server.data.orgunit;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the caller may do in org units, for a client such as the admin panel to decide what to show.
 *
 * @param adminAccess {@code FULL} when the configured admin rules match the caller, else {@code NONE}
 * @param items       the units the caller sees or holds a role in, parents before children; a unit reached only by its
 *                    path comes without the units above it
 */
public record UserOrgUnits(AdminAccess adminAccess, List<Unit> items) {

    public enum AdminAccess {
        FULL,
        NONE
    }

    /**
     * @param roles       the configured roles the caller holds at the unit, sorted
     * @param permissions what the caller may do at the unit: the actions, sorted, by namespace, as roles name them
     */
    public record Unit(String id, String name, String displayName, String path, String folder,
                       List<String> roles, Map<String, Set<String>> permissions) {
    }
}
