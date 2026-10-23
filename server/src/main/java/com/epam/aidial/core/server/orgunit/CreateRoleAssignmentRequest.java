package com.epam.aidial.core.server.orgunit;

/**
 * @param roleName one of the roles {@code GET …/roles} lists
 */
public record CreateRoleAssignmentRequest(String roleName, Subject subject) {
}
