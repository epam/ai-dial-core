package com.epam.aidial.core.server.data;

import java.util.Locale;

/**
 * Where a selected route is defined. The value is what telemetry and analytics publish.
 */
public enum RouteType {
    GLOBAL,
    APPLICATION;

    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
