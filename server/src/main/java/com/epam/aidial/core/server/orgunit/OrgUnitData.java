package com.epam.aidial.core.server.orgunit;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The shared space or a unit as the API returns it.
 *
 * @param id          stable id
 * @param name        the folder name; {@code public} for the shared space
 * @param path        {@code public}, then each unit's name: {@code public/Acme/Risk}
 * @param folder      the unit's folder, url-encoded, as publications take {@code targetFolder}: {@code public/Acme/Risk/US%20Risk/}.
 *                    A resource url is the type, then this, then the rest of the path.
 * @param author      the display name of who created the unit
 * @param createdAt   when the unit was created, in epoch milliseconds
 * @param items       the units directly below; every unit below, parents before children, when asked for
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgUnitData(String id, String name, String displayName, String path, String folder,
                          String author, Long createdAt, List<Summary> items) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(String id, String name, String displayName, String path, String folder,
                          String author, Long createdAt) {
    }
}
