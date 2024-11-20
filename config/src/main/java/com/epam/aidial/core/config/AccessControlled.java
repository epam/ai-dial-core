package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.util.Collection;
import java.util.Set;

@Data
public abstract class AccessControlled {

    private String name;

    @JsonAlias({"userRoles", "user_roles"})
    private Set<String> userRoles;

    public boolean hasAccess(Collection<String> roles) {
        if (userRoles == null) {
            return true;
        }

        if (userRoles.isEmpty() || roles == null || roles.isEmpty()) {
            return false;
        }

        return roles.stream()
                .anyMatch(userRoles::contains);
    }
}
