package com.epam.aidial.core.server.data.config.apply;

import com.epam.aidial.core.config.Route;

public record AdminRouteManifest(String kind, String name, Route spec) implements AdminTypedManifest {
}
