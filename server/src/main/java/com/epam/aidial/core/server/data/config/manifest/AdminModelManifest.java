package com.epam.aidial.core.server.data.config.manifest;

import com.epam.aidial.core.config.Model;

public record AdminModelManifest(String kind, String name, Model spec) implements AdminTypedManifest {
}
