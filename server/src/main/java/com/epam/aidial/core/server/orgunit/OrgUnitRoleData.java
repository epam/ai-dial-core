package com.epam.aidial.core.server.orgunit;

import com.epam.aidial.core.config.OrgUnitPermission;

import java.util.List;

/**
 * @param system true for a role the platform defines, which no unit can change
 */
public record OrgUnitRoleData(String name, String description, boolean system, List<OrgUnitPermission> permissions) {
}
