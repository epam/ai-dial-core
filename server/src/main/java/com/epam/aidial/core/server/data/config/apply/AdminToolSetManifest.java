package com.epam.aidial.core.server.data.config.apply;

import com.epam.aidial.core.config.ToolSet;

public record AdminToolSetManifest(String kind, String name, ToolSet spec) implements AdminTypedManifest {
}
