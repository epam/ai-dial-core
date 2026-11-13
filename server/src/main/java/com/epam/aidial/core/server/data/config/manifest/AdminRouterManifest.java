package com.epam.aidial.core.server.data.config.manifest;

import com.epam.aidial.core.config.Router;

public record AdminRouterManifest(String kind, String name, Router spec) implements AdminTypedManifest {
}
