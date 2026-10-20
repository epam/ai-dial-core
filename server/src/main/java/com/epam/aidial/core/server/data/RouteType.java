package com.epam.aidial.core.server.data;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Where a selected route is defined. The value is what telemetry and analytics publish.
 */
@Getter
@RequiredArgsConstructor
public enum RouteType {
    GLOBAL("global"),
    APPLICATION("application");

    private final String value;
}
