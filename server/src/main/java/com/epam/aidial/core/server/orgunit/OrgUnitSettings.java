package com.epam.aidial.core.server.orgunit;

import io.vertx.core.json.JsonObject;

/**
 * Static settings {@code orgUnits}: {@code enabled} switches org units on.
 */
public record OrgUnitSettings(boolean enabled) {

    public static OrgUnitSettings from(JsonObject settings) {
        return new OrgUnitSettings(settings.getBoolean("enabled", false));
    }
}
