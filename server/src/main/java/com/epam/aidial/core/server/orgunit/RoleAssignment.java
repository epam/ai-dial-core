package com.epam.aidial.core.server.orgunit;

/**
 * A role given to a subject at one unit; what it grants there comes from the configured roles ({@link OrgUnitRoles}).
 */
public record RoleAssignment(String id, String roleName, Subject subject) {

    public RoleAssignment {
        if (id == null || id.isBlank() || roleName == null || roleName.isBlank() || subject == null) {
            throw new IllegalArgumentException("A role assignment needs an id, a role name and a subject");
        }
    }
}
