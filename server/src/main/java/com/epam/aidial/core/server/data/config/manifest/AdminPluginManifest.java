package com.epam.aidial.core.server.data.config.manifest;

import com.epam.aidial.core.config.Plugin;

public record AdminPluginManifest(String kind, String name, Plugin spec) implements AdminTypedManifest {
}
