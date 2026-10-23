package com.epam.aidial.core.server.data.orgunit;

/**
 * @param name        the new unit's folder name
 * @param displayName optional; the name when absent
 */
public record CreateOrgUnitRequest(String name, String displayName) {
}
