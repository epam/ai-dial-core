package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
public abstract class RoleBasedEntity {

    private String name;

    @JsonAlias({"userRoles", "user_roles"})
    private Set<String> userRoles;

    /**
     * Checks if the actual user roles ({@code actualUserRoles} parameter) contain any of the expected user roles ({@code userRoles} field).
     * The method verifies if any of the passed role is allowed to operate with the deployment, route or any other instance that extends {@link RoleBasedEntity}.
     *
     * @return true if one of the {@code actualUserRoles} exists in the {@code userRoles} or if {@code userRoles} field is empty,
     *         meaning that the current descendant of {@link RoleBasedEntity} allows access to it for any role.
     */
    public boolean hasAccess(List<String> actualUserRoles) {
        Set<String> expectedUserRoles = getUserRoles();

        if (expectedUserRoles == null) {
            return true;
        }

        return !expectedUserRoles.isEmpty()
                && actualUserRoles.stream().anyMatch(expectedUserRoles::contains);
    }
}
