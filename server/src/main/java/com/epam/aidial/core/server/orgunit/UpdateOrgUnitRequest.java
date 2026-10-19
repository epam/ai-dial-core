package com.epam.aidial.core.server.orgunit;

/**
 * @param displayName what clients show for the unit; blank falls back to the unit's name
 */
public record UpdateOrgUnitRequest(String displayName) {
}
