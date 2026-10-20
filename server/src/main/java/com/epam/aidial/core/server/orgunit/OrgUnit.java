package com.epam.aidial.core.server.orgunit;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One entry of the unit tree. {@code id} never changes; {@code displayName} is what clients show.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgUnit(String id, String displayName, String author, Long createdAt) {
}
